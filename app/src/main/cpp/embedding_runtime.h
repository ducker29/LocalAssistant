#pragma once

#include "llama.h"
#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

class EmbeddingCancelled : public std::runtime_error {
public:
    EmbeddingCancelled() : std::runtime_error("Embedding operation cancelled") {}
};

// No JNI/Android dependencies: the same implementation can be checked on a host.
class EmbeddingContext {
public:
    void load(const std::string & path, int threads);
    void unload();
    int count_tokens(const std::string & prepared_text);
    std::vector<float> embed(const std::string & prepared_text);
    void cancel() noexcept { cancelled_.store(true); }
    void reset_cancellation() noexcept { cancelled_.store(false); }

private:
    std::mutex mutex_;
    std::atomic<bool> cancelled_{false};
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model_{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context_{nullptr, llama_free};
    const llama_vocab * vocab_ = nullptr;
    void check_cancelled() const;
    void check_ready() const;
    int token_count(const std::string & text) const;
    void release();
};
