#include "embedding_runtime.h"
#include <cmath>
#include <cstdio>
#include <iostream>

// Checks the actual production native core, not a separate implementation of inference.
static double cosine(const std::vector<float> & a, const std::vector<float> & b) {
    double value = 0;
    for (size_t i = 0; i < a.size(); ++i) value += static_cast<double>(a[i]) * b[i];
    return value;
}

static void valid(const std::vector<float> & vector) {
    if (vector.size() != 384) throw std::runtime_error("Invalid dimensions");
    double squared = 0;
    for (float value : vector) {
        if (!std::isfinite(value)) throw std::runtime_error("Non-finite vector");
        squared += static_cast<double>(value) * value;
    }
    if (std::abs(squared - 1) > 1e-5) throw std::runtime_error("Invalid norm");
}

int main(int argc, char ** argv) {
    if (argc != 2) return 2;
    llama_log_set([](enum ggml_log_level level, const char * text, void *) {
        if (level == GGML_LOG_LEVEL_ERROR) std::fputs(text, stderr);
    }, nullptr);
    llama_backend_init();
    try {
        EmbeddingContext context;
        context.load(argv[1], 4);
        auto query = context.embed("query: Когда нужно перечислить деньги за поставку?");
        auto relevant = context.embed("passage: Условия оплаты: покупатель обязан оплатить поставку в течение десяти рабочих дней после получения счёта.");
        auto unrelated = context.embed("passage: Рецепт яблочного пирога: смешайте муку, сахар и яйца.");
        valid(query); valid(relevant); valid(unrelated);
        if (cosine(query, relevant) <= cosine(query, unrelated)) throw std::runtime_error("Russian ranking failed");
        auto repeat = context.embed("query: Когда нужно перечислить деньги за поставку?");
        if (cosine(query, repeat) < 0.999999) throw std::runtime_error("Input stability failed");
        std::cout << "PASS actual core: Russian ranking, finite384, normalization, repeated input\n";
        std::string input = "passage:";
        int checked = 0;
        for (int words = 0; words < 600; ++words) {
            int count = context.count_tokens(input);
            if (count >= 510 && count <= 512) { valid(context.embed(input)); ++checked; }
            if (count == 513) {
                bool rejected = false;
                try { context.embed(input); } catch (const std::invalid_argument &) { rejected = true; }
                if (!rejected) throw std::runtime_error("513 tokens were accepted");
                break;
            }
            input += " test";
        }
        if (checked != 3) throw std::runtime_error("Missing boundary checks");
        std::cout << "PASS actual core: 510/511/512 inference; 513 rejected\n";
        context.cancel();
        bool cancelled = false;
        try { context.embed("query: Отмена"); } catch (const EmbeddingCancelled &) { cancelled = true; }
        if (!cancelled) throw std::runtime_error("Cancel flag was ignored");
        context.unload(); context.unload();
        context.reset_cancellation();
        context.load(argv[1], 4);
        valid(context.embed("query: Повторная загрузка"));
        context.unload();
        bool unavailable = false;
        try { context.count_tokens("query: Нет модели"); } catch (const std::runtime_error &) { unavailable = true; }
        if (!unavailable) throw std::runtime_error("Unloaded model was used");
        std::cout << "PASS actual core: cancellation, unload, reload and unavailable guard\n";
    } catch (const std::exception & error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        llama_backend_free();
        return 1;
    }
    llama_backend_free();
    return 0;
}
