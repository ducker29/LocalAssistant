package com.example.localassistant.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One message exactly as it will be rendered into the model prompt. */
data class PromptMessage(val role: String, val content: String)

data class GenerationParams(
    val maxTokens: Int = 256,
    val temperature: Float = 0.7f,
    val topP: Float = 0.8f,
    val topK: Int = 20,
    val seed: Int = -1,
)

sealed interface EngineState {
    data object Unloaded : EngineState
    data object Loading : EngineState
    data class Ready(val description: String, val contextTokens: Int) : EngineState
    data class Failed(val message: String) : EngineState
}

class LlmException(message: String) : Exception(message)

/**
 * Local inference engine abstraction. One instance owns at most one loaded model and
 * at most one active generation.
 */
interface LlmEngine {
    val state: StateFlow<EngineState>

    suspend fun load(modelPath: String): Result<Unit>

    suspend fun unload()

    /** Streams decoded UTF-8 text chunks. Cancelling the collector stops native work. */
    fun generate(messages: List<PromptMessage>, params: GenerationParams): Flow<String>

    fun cancelGeneration()

    /** Tokens of the fully formatted prompt, or -1 when the model is not loaded. */
    suspend fun countPromptTokens(messages: List<PromptMessage>): Int

    suspend fun close()
}
