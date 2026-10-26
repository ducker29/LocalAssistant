// JNI bridge between the Kotlin chat application and the pinned llama.cpp revision.
//
// All entry points are blocking; Kotlin is responsible for calling them off the main
// thread and for serialising access. A per-handle mutex is kept anyway so that a
// misbehaving caller cannot corrupt the native context.

#include <jni.h>

#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "chat.h"

#define LOG_TAG "LlmNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlmHandle {
    std::mutex              mutex;
    llama_model *           model     = nullptr;
    llama_context *         ctx       = nullptr;
    const llama_vocab *     vocab     = nullptr;
    common_chat_templates_ptr templates;
    int32_t                 n_ctx     = 0;
    bool                    loaded    = false;
};

std::once_flag g_backend_once;

void backend_init_once() {
    std::call_once(g_backend_once, [] { llama_backend_init(); });
}

// Resident set size of this process, in kB. Used only for diagnostics: it shows how much of
// the model actually becomes resident, which matters on phones with limited RAM.
long process_rss_kb() {
    std::ifstream status("/proc/self/status");
    std::string line;
    while (std::getline(status, line)) {
        if (line.rfind("VmRSS:", 0) == 0) {
            long kb = -1;
            std::sscanf(line.c_str(), "VmRSS: %ld kB", &kb);
            return kb;
        }
    }
    return -1;
}

long long elapsed_ms(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start).count();
}

std::string jstring_to_std(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring std_to_jstring(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}

// Incremental UTF-8 decoder: token pieces may split a multi-byte code point.
class Utf8Stream {
public:
    std::string feed(const char * data, size_t size) {
        pending_.append(data, size);
        std::string out;
        size_t i = 0;
        while (i < pending_.size()) {
            const unsigned char c = static_cast<unsigned char>(pending_[i]);
            size_t need = 0;
            if (c < 0x80) {
                out.push_back(static_cast<char>(c));
                ++i;
                continue;
            } else if ((c & 0xE0) == 0xC0) {
                need = 2;
            } else if ((c & 0xF0) == 0xE0) {
                need = 3;
            } else if ((c & 0xF8) == 0xF0) {
                need = 4;
            } else {
                out += "\xEF\xBF\xBD"; // invalid lead byte
                ++i;
                continue;
            }

            if (pending_.size() - i < need) {
                break; // incomplete sequence, wait for the next piece
            }

            bool valid = true;
            for (size_t k = 1; k < need; ++k) {
                if ((static_cast<unsigned char>(pending_[i + k]) & 0xC0) != 0x80) {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                out.append(pending_, i, need);
                i += need;
            } else {
                out += "\xEF\xBF\xBD";
                ++i;
            }
        }
        pending_.erase(0, i);
        return out;
    }

    std::string flush() {
        std::string out;
        if (!pending_.empty()) {
            out = "\xEF\xBF\xBD";
            pending_.clear();
        }
        return out;
    }

private:
    std::string pending_;
};

std::vector<common_chat_msg> build_messages(JNIEnv * env, jobjectArray roles, jobjectArray contents) {
    std::vector<common_chat_msg> messages;
    if (roles == nullptr || contents == nullptr) {
        return messages;
    }
    const jsize n = env->GetArrayLength(roles);
    const jsize m = env->GetArrayLength(contents);
    const jsize count = n < m ? n : m;
    messages.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto role    = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        common_chat_msg msg;
        msg.role    = jstring_to_std(env, role);
        msg.content = jstring_to_std(env, content);
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
        messages.push_back(std::move(msg));
    }
    return messages;
}

// Renders the model's own chat template (Jinja) with thinking disabled.
bool render_prompt(LlmHandle & h, const std::vector<common_chat_msg> & messages,
                   std::string & out, bool & supports_thinking, std::string & error) {
    if (!h.templates) {
        error = "Чат-шаблон модели не инициализирован";
        return false;
    }
    try {
        common_chat_templates_inputs inputs;
        inputs.messages              = messages;
        inputs.add_generation_prompt = true;
        inputs.use_jinja             = true;
        inputs.enable_thinking       = false;

        common_chat_params params = common_chat_templates_apply(h.templates.get(), inputs);
        supports_thinking = params.supports_thinking;
        out = params.prompt.str();
        if (out.empty()) {
            error = "Модель отдала пустой промпт для этого диалога";
            return false;
        }
        return true;
    } catch (const std::exception & e) {
        error = std::string("Ошибка применения чат-шаблона: ") + e.what();
        return false;
    } catch (...) {
        error = "Неизвестная ошибка применения чат-шаблона";
        return false;
    }
}

bool tokenize_prompt(LlmHandle & h, const std::string & prompt, std::vector<llama_token> & tokens,
                     std::string & error) {
    // A too-small buffer reports the required size as a negative value, so the sizing pass
    // with a zero-length buffer is expected to come back negative.
    const int32_t sized = llama_tokenize(h.vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                         nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (sized == std::numeric_limits<int32_t>::min()) {
        error = "Промпт слишком длинный для токенизации";
        LOGE("tokenize overflow, prompt bytes=%zu", prompt.size());
        return false;
    }
    const size_t needed = static_cast<size_t>(sized < 0 ? -sized : sized);
    tokens.resize(needed + 8);
    int32_t written = llama_tokenize(h.vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                     tokens.data(), static_cast<int32_t>(tokens.size()),
                                     /*add_special=*/true, /*parse_special=*/true);
    if (written < 0 && -written <= static_cast<int32_t>(needed) + 8) {
        tokens.resize(static_cast<size_t>(-written) + 8);
        written = llama_tokenize(h.vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                 tokens.data(), static_cast<int32_t>(tokens.size()),
                                 /*add_special=*/true, /*parse_special=*/true);
    }
    if (written < 0) {
        error = "Не удалось токенизировать промпт";
        LOGE("tokenize failed, prompt bytes=%zu, result=%d", prompt.size(), written);
        return false;
    }
    tokens.resize(static_cast<size_t>(written));
    return true;
}

void free_context(LlmHandle & h) {
    if (h.ctx) {
        llama_free(h.ctx);
        h.ctx = nullptr;
    }
    h.vocab = nullptr;
    if (h.model) {
        llama_model_free(h.model);
        h.model = nullptr;
    }
    h.templates.reset();
    h.loaded = false;
    h.n_ctx  = 0;
}

bool is_cancelled(JNIEnv * env, jobject cancel, jmethodID get_id) {
    return env->CallBooleanMethod(cancel, get_id) == JNI_TRUE;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_localassistant_llm_LlmNative_nativeCreate(JNIEnv *, jclass) {
    backend_init_once();
    return reinterpret_cast<jlong>(new LlmHandle());
}

JNIEXPORT jstring JNICALL
Java_com_example_localassistant_llm_LlmNative_nativeLoadModel(JNIEnv * env, jclass, jlong handle,
                                                              jstring path, jint n_ctx, jint n_threads,
                                                              jint n_batch) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (h == nullptr) {
        return std_to_jstring(env, "Внутренняя ошибка: нулевой указатель движка");
    }
    backend_init_once();

    std::lock_guard<std::mutex> lock(h->mutex);
    free_context(*h);

    const auto load_start = std::chrono::steady_clock::now();
    const long rss_before = process_rss_kb();
    LOGI("RSS before model load: %ld kB", rss_before);

    const std::string model_path = jstring_to_std(env, path);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;       // CPU only, mmap stays at the library default

    h->model = llama_model_load_from_file(model_path.c_str(), mp);
    if (h->model == nullptr) {
        return std_to_jstring(env, "Не удалось загрузить модель: файл повреждён или несовместим");
    }
    h->vocab = llama_model_get_vocab(h->model);
    if (h->vocab == nullptr) {
        free_context(*h);
        return std_to_jstring(env, "Модель не содержит словаря");
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(n_ctx);
    cp.n_batch         = static_cast<uint32_t>(n_batch > 0 ? n_batch : 512);
    cp.n_ubatch        = static_cast<uint32_t>(n_batch > 0 ? n_batch : 512);
    cp.n_threads       = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf         = true;

    h->ctx = llama_init_from_model(h->model, cp);
    if (h->ctx == nullptr) {
        free_context(*h);
        return std_to_jstring(env, "Не удалось создать контекст инференса (недостаточно памяти?)");
    }
    h->n_ctx = static_cast<int32_t>(llama_n_ctx(h->ctx));

    try {
        h->templates = common_chat_templates_init(h->model, "", "", "");
    } catch (const std::exception & e) {
        LOGI("chat template init failed: %s", e.what());
        h->templates.reset();
    }

    char desc[256] = {0};
    llama_model_desc(h->model, desc, sizeof(desc));
    LOGI("model loaded: %s, n_ctx=%d, threads=%d", desc, h->n_ctx, n_threads);
    LOGI("model load took %lld ms, RSS after load: %ld kB",
         elapsed_ms(load_start), process_rss_kb());

    h->loaded = true;
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_example_localassistant_llm_LlmNative_nativeUnloadModel(JNIEnv *, jclass, jlong handle) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (h == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(h->mutex);
    free_context(*h);
    LOGI("model unloaded");
}

JNIEXPORT void JNICALL
Java_com_example_localassistant_llm_LlmNative_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (h == nullptr) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        free_context(*h);
    }
    delete h;
}

JNIEXPORT jint JNICALL
Java_com_example_localassistant_llm_LlmNative_nativeContextSize(JNIEnv *, jclass, jlong handle) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (h == nullptr) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(h->mutex);
    return h->loaded ? h->n_ctx : 0;
}

JNIEXPORT jstring JNICALL
Java_com_example_localassistant_llm_LlmNative_nativeModelDescription(JNIEnv * env, jclass, jlong handle) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (h == nullptr) {
        return std_to_jstring(env, "");
    }
    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->loaded || h->model == nullptr) {
        return std_to_jstring(env, "");
    }
    char desc[256] = {0};
    llama_model_desc(h->model, desc, sizeof(desc));
    return std_to_jstring(env, desc);
}

JNIEXPORT jint JNICALL
Java_com_example_localassistant_llm_LlmNative_nativeCountPromptTokens(JNIEnv * env, jclass, jlong handle,
                                                                    jobjectArray roles,
                                                                    jobjectArray contents) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (h == nullptr) {
        return -1;
    }
    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->loaded) {
        return -1;
    }
    auto messages = build_messages(env, roles, contents);
    std::string prompt;
    std::string error;
    bool supports_thinking = false;
    if (!render_prompt(*h, messages, prompt, supports_thinking, error)) {
        return -1;
    }
    std::vector<llama_token> tokens;
    if (!tokenize_prompt(*h, prompt, tokens, error)) {
        return -1;
    }
    return static_cast<jint>(tokens.size());
}

JNIEXPORT jstring JNICALL
Java_com_example_localassistant_llm_LlmNative_nativeFormatPrompt(JNIEnv * env, jclass, jlong handle,
                                                                 jobjectArray roles, jobjectArray contents) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (h == nullptr) {
        return std_to_jstring(env, "");
    }
    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->loaded) {
        return std_to_jstring(env, "");
    }
    auto messages = build_messages(env, roles, contents);
    std::string prompt;
    std::string error;
    bool supports_thinking = false;
    if (!render_prompt(*h, messages, prompt, supports_thinking, error)) {
        LOGE("render_prompt failed: %s", error.c_str());
        return std_to_jstring(env, "");
    }
    return std_to_jstring(env, prompt);
}

JNIEXPORT jstring JNICALL
Java_com_example_localassistant_llm_LlmNative_nativeGenerate(JNIEnv * env, jclass, jlong handle,
                                                             jobjectArray roles, jobjectArray contents,
                                                             jint max_tokens, jfloat temperature,
                                                             jfloat top_p, jint top_k, jint seed,
                                                             jobject cancel_flag,
                                                             jobject callback) {
    auto * h = reinterpret_cast<LlmHandle *>(handle);
    if (h == nullptr) {
        return std_to_jstring(env, "Внутренняя ошибка: нулевой указатель движка");
    }

    jmethodID get_id = nullptr;
    jmethodID on_token = nullptr;
    if (cancel_flag != nullptr) {
        jclass cls = env->GetObjectClass(cancel_flag);
        get_id = env->GetMethodID(cls, "get", "()Z");
        env->DeleteLocalRef(cls);
    }
    if (callback != nullptr) {
        jclass cls = env->GetObjectClass(callback);
        on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
    }
    if (get_id == nullptr || on_token == nullptr) {
        return std_to_jstring(env, "Внутренняя ошибка JNI: обратный вызов недоступен");
    }

    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->loaded) {
        return std_to_jstring(env, "Модель не загружена");
    }

    const auto t_request = std::chrono::steady_clock::now();

    auto messages = build_messages(env, roles, contents);
    std::string prompt;
    std::string error;
    bool supports_thinking = false;
    if (!render_prompt(*h, messages, prompt, supports_thinking, error)) {
        return std_to_jstring(env, error);
    }
    LOGI("prompt: %zu bytes, thinking_supported=%d", prompt.size(), supports_thinking ? 1 : 0);

    std::vector<llama_token> tokens;
    if (!tokenize_prompt(*h, prompt, tokens, error)) {
        return std_to_jstring(env, error);
    }
    if (static_cast<int32_t>(tokens.size()) + 8 >= h->n_ctx) {
        return std_to_jstring(env, "История не помещается в контекст модели");
    }

    int32_t budget = max_tokens;
    if (static_cast<int32_t>(tokens.size()) + budget > h->n_ctx) {
        budget = h->n_ctx - static_cast<int32_t>(tokens.size()) - 4;
    }
    if (budget < 8) {
        LOGI("context budget exhausted before generation: prompt=%zu tokens, n_ctx=%d",
             tokens.size(), h->n_ctx);
        return std_to_jstring(env, "CONTEXTEXHAUSTED|История не помещается в контекст модели");
    }
    if (budget < max_tokens) {
        LOGI("output budget clamped: requested=%d, clamped=%d (prompt=%zu tokens, n_ctx=%d)",
             max_tokens, budget, tokens.size(), h->n_ctx);
    }

    llama_memory_clear(llama_get_memory(h->ctx), true);

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("prompt decode failed: %zu tokens, n_ctx=%d", tokens.size(), h->n_ctx);
        return std_to_jstring(env, "Ошибка декодирования промпта");
    }
    const auto prompt_ready = std::chrono::steady_clock::now();
    LOGI("prompt ready: %zu tokens in %lld ms (render+tokenize+decode)",
         tokens.size(), elapsed_ms(t_request));

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            llama_vocab_n_tokens(h->vocab), 64, /*repeat=*/1.05f, 0.0f, 0.0f));
    if (top_k > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    }
    if (top_p > 0.0f && top_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    }
    if (temperature > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    }
    llama_sampler_chain_add(smpl,
            llama_sampler_init_dist(seed >= 0 ? static_cast<uint32_t>(seed) : LLAMA_DEFAULT_SEED));

    Utf8Stream stream;
    std::string result_error;
    int32_t produced = 0;
    bool first_token_logged = false;
    bool terminated_by_eog = false;
    bool terminated_by_cancel = false;
    char piece[256];

    auto emit_piece = [&](const char * data, int32_t len) {
        if (len <= 0) {
            return;
        }
        std::string text = stream.feed(data, static_cast<size_t>(len));
        if (text.empty()) {
            return;
        }
        if (!first_token_logged) {
            first_token_logged = true;
            LOGI("first token after %lld ms from request, RSS: %ld kB",
                 elapsed_ms(t_request), process_rss_kb());
        }
        jstring jtext = env->NewStringUTF(text.c_str());
        if (jtext != nullptr) {
            env->CallVoidMethod(callback, on_token, jtext);
            env->DeleteLocalRef(jtext);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            result_error = "Ошибка при передаче токена в Kotlin";
        }
    };

    for (int32_t i = 0; i < budget; ++i) {
        if (is_cancelled(env, cancel_flag, get_id)) {
            LOGI("generation cancelled after %d tokens in %lld ms",
                 produced, elapsed_ms(t_request));
            terminated_by_cancel = true;
            break;
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            result_error = "Ошибка в обратном вызове Kotlin";
            break;
        }

        const llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, id)) {
            // Emit the EOG piece (typically the newline that ends the turn) so the model's
            // output is not silently truncated before its final line break.
            const int32_t eog_len = llama_token_to_piece(
                    h->vocab, id, piece, sizeof(piece), 0, /*special=*/true);
            emit_piece(piece, eog_len);
            if (!result_error.empty()) {
                break;
            }
            terminated_by_eog = true;
            break;
        }

        const int32_t n = llama_token_to_piece(h->vocab, id, piece, sizeof(piece), 0, /*special=*/true);
        emit_piece(piece, n);
        if (!result_error.empty()) {
            break;
        }

        llama_sampler_accept(smpl, id);

        llama_batch single = llama_batch_get_one(const_cast<llama_token *>(&id), 1);
        if (llama_decode(h->ctx, single) != 0) {
            result_error = "Ошибка инференса при генерации";
            break;
        }
        ++produced;
    }

    const std::string tail = stream.flush();
    if (!tail.empty()) {
        jstring jtext = env->NewStringUTF(tail.c_str());
        if (jtext != nullptr) {
            env->CallVoidMethod(callback, on_token, jtext);
            env->DeleteLocalRef(jtext);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }

    llama_sampler_free(smpl);
    const long long total_ms = elapsed_ms(t_request);
    if (produced > 0) {
        const long long decode_ms = elapsed_ms(prompt_ready);
        const double tok_per_sec = decode_ms > 0
                ? produced * 1000.0 / static_cast<double>(decode_ms) : 0.0;
        LOGI("generation finished: %d tokens in %lld ms total; decode %lld ms (%.2f tok/s); RSS: %ld kB",
             produced, total_ms, decode_ms, tok_per_sec, process_rss_kb());
    } else {
        LOGI("generation finished: no tokens after %lld ms", total_ms);
    }

    std::string reason;
    if (!result_error.empty()) {
        reason = "ERROR";
    } else if (terminated_by_cancel) {
        reason = "CANCELLED";
    } else if (terminated_by_eog) {
        reason = "EOG";
    } else {
        reason = "BUDGET";
    }

    if (produced > 0) {
        LOGI("generation terminated: reason=%s, tokens=%d", reason.c_str(), produced);
    }

    if (reason == "ERROR") {
        return std_to_jstring(env, reason + "|" + result_error);
    }
    return std_to_jstring(env, reason);
}

} // extern "C"
