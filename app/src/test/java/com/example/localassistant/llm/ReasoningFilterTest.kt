package com.example.localassistant.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningFilterTest {

    @Test
    fun `plain text passes through unchanged`() {
        val filter = ReasoningFilter()
        val out = StringBuilder()
        out.append(filter.append("Привет, "))
        out.append(filter.append("мир!"))
        out.append(filter.finish())
        assertEquals("Привет, мир!", out.toString())
    }

    @Test
    fun `complete reasoning block is removed`() {
        val filter = ReasoningFilter()
        val out = filter.append("<think>секрет</think>Ответ: 42")
        assertEquals("Ответ: 42", out + filter.finish())
    }

    @Test
    fun `reasoning markers split across chunks are handled`() {
        val filter = ReasoningFilter()
        val out = StringBuilder()
        out.append(filter.append("Начало <thi"))
        out.append(filter.append("nk>размышление</thi"))
        out.append(filter.append("nk> итог"))
        out.append(filter.finish())
        assertEquals("Начало  итог", out.toString())
    }

    @Test
    fun `unterminated reasoning block is dropped on finish`() {
        val filter = ReasoningFilter()
        val out = filter.append("Ответ <think>обрыв")
        assertEquals("Ответ ", out)
        assertEquals("", filter.finish())
    }

    @Test
    fun `chatml style markers are stripped`() {
        val filter = ReasoningFilter()
        val start = "\u601D\u8003\u548C"
        val end = "\uFF5C" + "response" + "\uFF5C"
        val out = filter.append("$start\nдлинно\n$end\n\nКоротко.")
        assertEquals("\n\nКоротко.", out + filter.finish())
    }

    @Test
    fun `lone angle bracket is not swallowed`() {
        val filter = ReasoningFilter()
        val out = StringBuilder()
        out.append(filter.append("a < b"))
        out.append(filter.finish())
        assertEquals("a < b", out.toString())
    }
}
