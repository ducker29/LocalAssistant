package com.example.localassistant.chat

import com.example.localassistant.llm.PromptMessage

/** Context that will actually be sent to the model. */
data class PlannedContext(
    val messages: List<PromptMessage>,
    val droppedTurns: Int,
)

/**
 * Fits the conversation into the model context: the system instruction is always kept and
 * the oldest complete turns are dropped first. Counting is delegated so the policy stays
 * unit-testable without native code.
 */
class ContextPlanner(
    private val maxContextTokens: Int,
    private val reservedOutputTokens: Int,
    private val safetyMargin: Int = 8,
) {
    suspend fun plan(
        systemPrompt: String,
        turns: List<List<PromptMessage>>,
        counter: suspend (List<PromptMessage>) -> Int,
    ): PlannedContext? {
        if (turns.isEmpty()) {
            return PlannedContext(emptyList(), 0)
        }
        val budget = maxContextTokens - reservedOutputTokens - safetyMargin
        if (budget <= 0) {
            return null
        }
        for (keep in turns.size downTo 1) {
            val messages = ArrayList<PromptMessage>(keep * 2 + 1)
            messages.add(PromptMessage("system", systemPrompt))
            for (turn in turns.takeLast(keep)) {
                messages.addAll(turn)
            }
            val tokens = counter(messages)
            if (tokens < 0) {
                return null
            }
            if (tokens <= budget) {
                return PlannedContext(messages, turns.size - keep)
            }
        }
        return null
    }
}
