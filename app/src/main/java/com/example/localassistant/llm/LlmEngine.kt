package com.example.localassistant.llm

import kotlinx.coroutines.Job
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

/** Why a generation stopped. Lets the UI distinguish a natural end from a truncation. */
enum class GenerationTermination {
    /** The model emitted its end-of-generation token. */
    EOG,

    /** The output token budget was exhausted before the model finished. */
    BUDGET,

    /** The context window could not hold the prompt plus at least a minimal answer. */
    CONTEXT,

    /** The user (or a host policy) requested cancellation. */
    CANCELLED,

    /** A native or transport error interrupted generation. [message] holds details. */
    ERROR,
}

data class GenerationResult(
    val text: String,
    val termination: GenerationTermination,
    val errorMessage: String? = null,
)

/**
 * Local inference engine abstraction. One instance owns at most one loaded model and
 * at most one active generation.
 */
interface LlmEngine {
    val state: StateFlow<EngineState>

    suspend fun load(modelPath: String): Result<Unit>

    suspend fun unload()

    /**
     * Streams decoded UTF-8 text chunks through [onChunk]; on completion or failure invokes
     * [onDone] exactly once with the full text and the reason generation stopped. Cancelling
     * the returned [Job] stops the native work and reports a [GenerationTermination.CANCELLED].
     */
    fun generate(
        messages: List<PromptMessage>,
        params: GenerationParams,
        onChunk: (String) -> Unit,
        onDone: (GenerationResult) -> Unit,
    ): Job

    fun cancelGeneration()

    /** Tokens of the fully formatted prompt, or -1 when the model is not loaded. */
    suspend fun countPromptTokens(messages: List<PromptMessage>): Int

    suspend fun close()
}
