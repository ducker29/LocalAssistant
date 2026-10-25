package com.example.localassistant.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrTranscriptTest {

    @Test
    fun `a lone non speech tag is not a transcript`() {
        assertEquals("", AsrTranscript.clean("[музыка]"))
        assertEquals("", AsrTranscript.clean("[BLANK_AUDIO]"))
        assertEquals("", AsrTranscript.clean("(тишина)"))
    }

    @Test
    fun `tags are stripped from real speech`() {
        assertEquals("Привет, мир.", AsrTranscript.clean("Привет, [музыка] мир."))
    }

    @Test
    fun `punctuation only is not a transcript`() {
        assertEquals("", AsrTranscript.clean("..."))
    }

    @Test
    fun `plain text is preserved`() {
        assertEquals("Открой календарь на завтра.", AsrTranscript.clean(" Открой  календарь на завтра. "))
    }

    @Test
    fun `null input is empty`() {
        assertEquals("", AsrTranscript.clean(null))
    }
}
