package com.example.localassistant.llm

/**
 * Streaming safety net for models that emit a reasoning block even though the chat was
 * started in non-thinking mode. The block is dropped, ordinary text is forwarded unchanged
 * and markers split across token boundaries are handled.
 */
class ReasoningFilter {

    private val buffer = StringBuilder()
    private var insideReasoning = false

    fun append(text: String): String {
        if (text.isEmpty()) return ""
        buffer.append(text)
        val out = StringBuilder()
        while (true) {
            if (!insideReasoning) {
                val hit = firstMarker(buffer, START_MARKERS)
                if (hit != null) {
                    out.append(buffer, 0, hit.index)
                    buffer.delete(0, hit.index + hit.marker.length)
                    insideReasoning = true
                    continue
                }
                val keep = longestPartialSuffix(buffer, START_MARKERS)
                out.append(buffer, 0, buffer.length - keep)
                buffer.delete(0, buffer.length - keep)
                break
            } else {
                val hit = firstMarker(buffer, END_MARKERS)
                if (hit != null) {
                    buffer.delete(0, hit.index + hit.marker.length)
                    insideReasoning = false
                    continue
                }
                val keep = longestPartialSuffix(buffer, END_MARKERS)
                buffer.delete(0, (buffer.length - keep).coerceAtLeast(0))
                break
            }
        }
        return out.toString()
    }

    fun finish(): String {
        val tail = if (insideReasoning) "" else buffer.toString()
        buffer.setLength(0)
        insideReasoning = false
        return tail
    }

    private data class Hit(val index: Int, val marker: String)

    private fun firstMarker(text: StringBuilder, markers: List<String>): Hit? {
        var best: Hit? = null
        for (marker in markers) {
            val index = text.indexOf(marker)
            if (index >= 0 && (best == null || index < best.index)) {
                best = Hit(index, marker)
            }
        }
        return best
    }

    private fun longestPartialSuffix(text: StringBuilder, markers: List<String>): Int {
        var longest = 0
        for (marker in markers) {
            val max = minOf(marker.length - 1, text.length)
            for (length in max downTo 1) {
                if (length <= longest) break
                if (text.regionMatches(text.length - length, marker, 0, length)) {
                    longest = length
                    break
                }
            }
        }
        return longest
    }

    private fun StringBuilder.indexOf(marker: String): Int {
        val limit = length - marker.length
        for (i in 0..limit) {
            if (regionMatches(i, marker, 0, marker.length)) return i
        }
        return -1
    }

    companion object {
        private const val BAR = '\uFF5C'

        private val START_MARKERS = listOf(
            "<think>",
            "\u601D\u8003\u548C",
            "<|channel|>thought",
        )

        private val END_MARKERS = listOf(
            "</think>",
            "$BAR" + "response" + "$BAR",
            "<|channel|>final",
        )
    }
}
