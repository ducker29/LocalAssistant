package com.example.localassistant.asr

/**
 * Cleans raw whisper output before it reaches the chat draft.
 *
 * Whisper annotates non-speech audio with bracketed tags ("[музыка]", "[BLANK_AUDIO]",
 * "(смех)"). On silence it can emit nothing but a tag, which must not become text the
 * user is invited to send. Tags are removed; a result without a single letter or digit
 * is treated as "no speech".
 */
internal object AsrTranscript {

    private val annotation = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*""")
    private val whitespace = Regex("""\s+""")
    private val spaceBeforePunctuation = Regex("""\s+([,.;:!?…])""")
    private val letterOrDigit = Regex("""[\p{L}\p{N}]""")

    fun clean(raw: String?): String {
        if (raw == null) return ""
        var text = annotation.replace(raw, " ")
        text = whitespace.replace(text, " ")
        text = spaceBeforePunctuation.replace(text, "$1").trim()
        return if (letterOrDigit.containsMatchIn(text)) text else ""
    }
}
