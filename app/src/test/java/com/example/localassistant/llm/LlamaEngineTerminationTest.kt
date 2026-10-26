package com.example.localassistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlamaEngineTerminationTest {

    @Test
    fun `plain codes keep their own termination`() {
        assertEquals(GenerationTermination.EOG, LlamaEngine.parseTermination("EOG").termination)
        assertEquals(GenerationTermination.BUDGET, LlamaEngine.parseTermination("BUDGET").termination)
        assertEquals(GenerationTermination.CANCELLED, LlamaEngine.parseTermination("CANCELLED").termination)
    }

    @Test
    fun `context exhaustion carries its message`() {
        val result = LlamaEngine.parseTermination("CONTEXTEXHAUSTED|История не помещается")

        assertEquals(GenerationTermination.CONTEXT, result.termination)
        assertEquals("История не помещается", result.errorMessage)
    }

    @Test
    fun `error carries its message`() {
        val result = LlamaEngine.parseTermination("ERROR|сбой инференса")

        assertEquals(GenerationTermination.ERROR, result.termination)
        assertEquals("сбой инференса", result.errorMessage)
    }

    @Test
    fun `null or blank is treated as eog`() {
        assertEquals(GenerationTermination.EOG, LlamaEngine.parseTermination(null).termination)
        assertEquals(GenerationTermination.EOG, LlamaEngine.parseTermination("  ").termination)
        assertNull(LlamaEngine.parseTermination("EOG").errorMessage)
    }

    @Test
    fun `unknown code becomes an error`() {
        val result = LlamaEngine.parseTermination("WHAT")

        assertEquals(GenerationTermination.ERROR, result.termination)
        assertEquals("Неизвестный код завершения: WHAT", result.errorMessage)
    }
}
