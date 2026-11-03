#include "embedding_runtime.h"
#include <cmath>
#include <cstdint>
#include <limits>

namespace {
constexpr int dimensions = 384;
constexpr int max_tokens = 512;
constexpr size_t max_input_bytes = 256 * 1024;
}

void EmbeddingContext::check_cancelled() const {
    if (cancelled_.load()) throw EmbeddingCancelled();
}

void EmbeddingContext::check_ready() const {
    if (!model_ || !context_ || !vocab_) throw std::runtime_error("Embedding model is not loaded");
}

void EmbeddingContext::release() {
    context_.reset();
    vocab_ = nullptr;
    model_.reset();
}

void EmbeddingContext::load(const std::string & path, int threads) {
    std::lock_guard<std::mutex> lock(mutex_);
    release();
    check_cancelled();
    if (path.empty() || path.find('\0') != std::string::npos || threads < 1 || threads > 6)
        throw std::invalid_argument("Invalid embedding model parameters");
    try {
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.progress_callback = [](float, void * user) {
            return !static_cast<EmbeddingContext *>(user)->cancelled_.load();
        };
        mp.progress_callback_user_data = this;
        model_.reset(llama_model_load_from_file(path.c_str(), mp));
        check_cancelled();
        if (!model_) throw std::runtime_error("Cannot load embedding model");
        if (llama_model_n_embd(model_.get()) != dimensions || llama_model_n_ctx_train(model_.get()) != max_tokens)
            throw std::invalid_argument("Expected audited e5-small model with 384 dimensions and 512 positions");
        vocab_ = llama_model_get_vocab(model_.get());
        if (!vocab_) throw std::runtime_error("Embedding vocabulary is missing");
        auto cp = llama_context_default_params();
        cp.n_ctx = max_tokens;
        cp.n_batch = max_tokens;
        cp.n_ubatch = max_tokens;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        cp.embeddings = true;
        cp.pooling_type = LLAMA_POOLING_TYPE_MEAN;
        cp.attention_type = LLAMA_ATTENTION_TYPE_NON_CAUSAL;
        cp.no_perf = true;
        cp.abort_callback = [](void * user) {
            return static_cast<EmbeddingContext *>(user)->cancelled_.load();
        };
        cp.abort_callback_data = this;
        context_.reset(llama_init_from_model(model_.get(), cp));
        check_cancelled();
        if (!context_ || llama_pooling_type(context_.get()) != LLAMA_POOLING_TYPE_MEAN)
            throw std::runtime_error("Cannot initialize mean-pooling embedding context");
    } catch (...) {
        release();
        throw;
    }
}

void EmbeddingContext::unload() {
    std::lock_guard<std::mutex> lock(mutex_);
    release();
}

int EmbeddingContext::token_count(const std::string & text) const {
    check_cancelled();
    if (text.empty() || text.size() > max_input_bytes) throw std::invalid_argument("Invalid embedding input length");
    const int result = llama_tokenize(vocab_, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, true, false);
    check_cancelled();
    if (result == std::numeric_limits<int32_t>::min() || result == 0)
        throw std::runtime_error("Embedding tokenization failed");
    return result < 0 ? -result : result;
}

int EmbeddingContext::count_tokens(const std::string & text) {
    std::lock_guard<std::mutex> lock(mutex_);
    check_ready();
    return token_count(text);
}

std::vector<float> EmbeddingContext::embed(const std::string & text) {
    std::lock_guard<std::mutex> lock(mutex_);
    check_ready();
    const int count = token_count(text);
    // Count includes query/passage prefix and BOS/EOS. Never truncate silently.
    if (count > max_tokens) throw std::invalid_argument("Embedding input exceeds 512 tokens");
    std::vector<llama_token> tokens(count);
    if (llama_tokenize(vocab_, text.data(), static_cast<int32_t>(text.size()), tokens.data(), count, true, false) != count)
        throw std::runtime_error("Embedding token count changed");
    check_cancelled();
    struct BatchOwner {
        llama_batch batch;
        explicit BatchOwner(int n) : batch(llama_batch_init(n, 0, 1)) {}
        ~BatchOwner() { llama_batch_free(batch); }
    } owner(count);
    auto & batch = owner.batch;
    if (!batch.token || !batch.pos || !batch.n_seq_id || !batch.seq_id || !batch.logits)
        throw std::bad_alloc();
    batch.n_tokens = count;
    for (int i = 0; i < count; ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = true;
    }
    const int result = llama_decode(context_.get(), batch);
    check_cancelled();
    if (result != 0) throw std::runtime_error("Embedding computation failed");
    const float * data = llama_get_embeddings_seq(context_.get(), 0);
    if (!data) throw std::runtime_error("Pooled embedding is missing");
    std::vector<float> output(data, data + dimensions);
    double norm = 0;
    for (float x : output) {
        if (!std::isfinite(x)) throw std::runtime_error("Non-finite embedding value");
        norm += static_cast<double>(x) * x;
    }
    if (!std::isfinite(norm) || norm < 1e-12) throw std::runtime_error("Invalid embedding norm");
    for (float & x : output) x = static_cast<float>(x / std::sqrt(norm));
    check_cancelled();
    return output;
}
