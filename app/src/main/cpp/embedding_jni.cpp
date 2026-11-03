#include <jni.h>
#include "embedding_runtime.h"
#include <type_traits>
#include <unordered_map>

// Shares the process-wide backend initialization with Qwen; never frees its backend.
void localassistant_backend_init_once();

namespace {
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<EmbeddingContext>> registry;
std::atomic<jlong> next_id{1};

std::shared_ptr<EmbeddingContext> context_for(jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto it = registry.find(id);
    if (it == registry.end()) throw std::runtime_error("Embedding context is closed");
    return it->second;
}

void java_error(JNIEnv * env, const char * type, const char * message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(type);
    if (cls) { env->ThrowNew(cls, message); env->DeleteLocalRef(cls); }
}

template<typename F> auto guarded(JNIEnv * env, F action) -> decltype(action()) {
    try { return action(); }
    catch (const EmbeddingCancelled &) { java_error(env, "java/util/concurrent/CancellationException", "Embedding operation cancelled"); }
    catch (const std::invalid_argument &) { java_error(env, "java/lang/IllegalArgumentException", "Invalid embedding model or input (maximum 512 tokens)"); }
    catch (const std::bad_alloc &) { java_error(env, "java/lang/OutOfMemoryError", "Not enough memory for embedding model"); }
    catch (...) { java_error(env, "java/lang/IllegalStateException", "Embedding runtime operation failed"); }
    if constexpr (!std::is_void_v<decltype(action())>) return {};
}

std::string utf8_bytes(JNIEnv * env, jbyteArray bytes, size_t maximum) {
    if (!bytes) throw std::invalid_argument("Missing UTF-8 input");
    const jsize size = env->GetArrayLength(bytes);
    if (size <= 0 || static_cast<size_t>(size) > maximum) throw std::invalid_argument("Invalid UTF-8 length");
    std::string text(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte *>(text.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot copy UTF-8 input");
    return text;
}
}

extern "C" {
JNIEXPORT jlong JNICALL Java_com_example_localassistant_embedding_EmbeddingNative_create(JNIEnv * env, jobject) {
    return guarded(env, [] {
        localassistant_backend_init_once();
        auto context = std::make_shared<EmbeddingContext>();
        const jlong id = next_id.fetch_add(1);
        std::lock_guard<std::mutex> lock(registry_mutex);
        registry.emplace(id, std::move(context));
        return id;
    });
}

JNIEXPORT void JNICALL Java_com_example_localassistant_embedding_EmbeddingNative_load(JNIEnv * env, jobject, jlong id, jbyteArray path, jint threads) {
    guarded(env, [&] { context_for(id)->load(utf8_bytes(env, path, 16 * 1024), threads); });
}

JNIEXPORT jint JNICALL Java_com_example_localassistant_embedding_EmbeddingNative_countTokens(JNIEnv * env, jobject, jlong id, jbyteArray text) {
    return guarded(env, [&] { return static_cast<jint>(context_for(id)->count_tokens(utf8_bytes(env, text, 256 * 1024))); });
}

JNIEXPORT jfloatArray JNICALL Java_com_example_localassistant_embedding_EmbeddingNative_embed(JNIEnv * env, jobject, jlong id, jbyteArray text) {
    return guarded(env, [&] {
        const auto output = context_for(id)->embed(utf8_bytes(env, text, 256 * 1024));
        auto result = env->NewFloatArray(static_cast<jsize>(output.size()));
        if (result) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
        return result;
    });
}

JNIEXPORT void JNICALL Java_com_example_localassistant_embedding_EmbeddingNative_cancel(JNIEnv *, jobject, jlong id) {
    // Cancellation may race with destroy; the shared_ptr lookup prevents dangling handles.
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto it = registry.find(id);
    if (it != registry.end()) it->second->cancel();
}

JNIEXPORT void JNICALL Java_com_example_localassistant_embedding_EmbeddingNative_resetCancellation(JNIEnv * env, jobject, jlong id) {
    guarded(env, [&] { context_for(id)->reset_cancellation(); });
}

JNIEXPORT void JNICALL Java_com_example_localassistant_embedding_EmbeddingNative_unload(JNIEnv * env, jobject, jlong id) {
    guarded(env, [&] { context_for(id)->unload(); });
}

JNIEXPORT void JNICALL Java_com_example_localassistant_embedding_EmbeddingNative_destroy(JNIEnv * env, jobject, jlong id) {
    guarded(env, [&] {
        std::shared_ptr<EmbeddingContext> context;
        {
            std::lock_guard<std::mutex> lock(registry_mutex);
            auto it = registry.find(id);
            if (it == registry.end()) return;
            context = std::move(it->second);
            registry.erase(it);
        }
        context->cancel();
        context->unload(); // waits for a native operation to leave its mutex
    });
}
}
