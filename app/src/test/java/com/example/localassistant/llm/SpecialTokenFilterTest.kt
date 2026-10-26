package com.example.localassistant.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class SpecialTokenFilterTest {

    @Test
    fun `removes a complete im_end token`() {
        val filter = SpecialTokenFilter()

        assertEquals("Hello  World", filter.append("Hello <|im_end|> World"))
    }

    @Test
    fun `removes start and end tokens`() {
        val filter = SpecialTokenFilter()

        assertEquals("answer", filter.append("<|im_start|>answer<|im_end|>"))
    }

    @Test
    fun `removes a token split across chunks`() {
        val filter = SpecialTokenFilter()

        assertEquals("", filter.append("<|im_"))
        assertEquals("", filter.append("end|>"))
        assertEquals("done", filter.append("done"))
    }

    @Test
    fun `keeps ordinary text unchanged`() {
        val filter = SpecialTokenFilter()

        assertEquals("обычный ответ <b>", filter.append("обычный ответ <b>"))
    }

    @Test
    fun `releases a held prefix that never becomes a token`() {
        val filter = SpecialTokenFilter()

        assertEquals("a ", filter.append("a <"))
        assertEquals("<", filter.finish())
    }

    @Test
    fun `finish returns nothing when the stream is clean`() {
        val filter = SpecialTokenFilter()

        assertEquals("text", filter.append("text"))
        assertEquals("", filter.finish())
    }

    @Test
    fun `strip cleans an already stored message`() {
        assertEquals(
            "готовый ответ",
            SpecialTokenFilter.strip("<|im_start|>готовый ответ<|im_end|>"),
        )
    }
}
