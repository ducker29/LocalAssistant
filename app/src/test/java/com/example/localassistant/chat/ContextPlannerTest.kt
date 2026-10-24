package com.example.localassistant.chat

import com.example.localassistant.llm.PromptMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContextPlannerTest {

    private val counter: suspend (List<PromptMessage>) -> Int = { messages ->
        messages.sumOf { it.content.length }
    }

    @Test
    fun `keeps all turns when they fit`() = runTest {
        val planner = ContextPlanner(maxContextTokens = 2048, reservedOutputTokens = 256)
        val turns = listOf(
            listOf(PromptMessage("user", "раз"), PromptMessage("assistant", "два")),
            listOf(PromptMessage("user", "три"), PromptMessage("assistant", "четыре")),
        )

        val planned = planner.plan("система", turns, counter)

        assertNotNull(planned)
        assertEquals(0, planned!!.droppedTurns)
        assertEquals(5, planned.messages.size)
        assertEquals("system", planned.messages.first().role)
    }

    @Test
    fun `drops oldest turns first and keeps the system instruction`() = runTest {
        val planner = ContextPlanner(maxContextTokens = 40, reservedOutputTokens = 10, safetyMargin = 2)
        val turns = (1..6).map { index ->
            listOf(
                PromptMessage("user", "u$index-" + "x".repeat(4)),
                PromptMessage("assistant", "a$index-" + "y".repeat(4)),
            )
        }

        val planned = planner.plan("sys", turns, counter)

        assertNotNull(planned)
        assertEquals("system", planned!!.messages.first().role)
        assertEquals(planned.droppedTurns, turns.size - ((planned.messages.size - 1) / 2))
        assertEquals("u6-xxxx", planned.messages[1].content)
    }

    @Test
    fun `returns null when even the newest turn does not fit`() = runTest {
        val planner = ContextPlanner(maxContextTokens = 16, reservedOutputTokens = 8)
        val turns = listOf(listOf(PromptMessage("user", "x".repeat(100))))

        assertNull(planner.plan("sys", turns, counter))
    }

    @Test
    fun `empty history keeps only the system instruction`() = runTest {
        val planner = ContextPlanner(maxContextTokens = 64, reservedOutputTokens = 8)

        val planned = planner.plan("sys", emptyList(), counter)

        assertNotNull(planned)
        assertEquals(0, planned!!.messages.size)
    }
}
