package com.example.localassistant.llm

/**
 * Removes chat special tokens (for example Qwen's `<|im_end|>` / `<|im_start|>`) from the
 * streamed model output before it reaches the UI or persistence.
 *
 * The native layer already asks llama.cpp not to render control tokens, but the filter is a
 * second line of defence at the streaming boundary. It also copes with a token that is split
 * across two chunks by holding back a suffix that could still turn into a special token.
 */
class SpecialTokenFilter(
    private val tokens: List<String> = DEFAULT_SPECIAL_TOKENS,
) {
    private val pending = StringBuilder()

    fun append(chunk: String): String {
        if (chunk.isEmpty()) return ""
        pending.append(chunk)

        val out = StringBuilder()
        var i = 0
        while (i < pending.length) {
            if (pending[i] == '<') {
                val match = tokens.firstOrNull { pending.startsWith(it, i) }
                if (match != null) {
                    i += match.length
                    continue
                }
                // Hold a trailing fragment that is still a prefix of a special token.
                val rest = pending.substring(i)
                if (tokens.any { it.startsWith(rest) }) {
                    break
                }
            }
            out.append(pending[i])
            ++i
        }

        if (i > 0) {
            pending.delete(0, i)
        }
        return out.toString()
    }

    /** Flushes any held text once the generation has finished. */
    fun finish(): String {
        if (pending.isEmpty()) return ""
        val out = pending.toString()
        pending.setLength(0)
        return out
    }

    companion object {
        /** Removes every known special token from an already stored or static string. */
        fun strip(text: String): String =
            DEFAULT_SPECIAL_TOKENS.fold(text) { acc, token -> acc.replace(token, "") }

        val DEFAULT_SPECIAL_TOKENS: List<String> = listOf(
            "<|im_end|>",
            "<|im_start|>",
            "<|endoftext|>",
            "<|end_of_text|>",
            "<|eot_id|>",
            "<|eom_id|>",
            "<|begin_of_text|>",
            "<|start_header_id|>",
            "<|end_header_id|>",
        )
    }
}
