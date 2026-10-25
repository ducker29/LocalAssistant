package com.example.localassistant.asr

import kotlinx.coroutines.flow.StateFlow

sealed interface AsrEngineState {
    data object Unloaded : AsrEngineState
    data object Loading : AsrEngineState
    data class Ready(val description: String) : AsrEngineState
    data class Failed(val message: String) : AsrEngineState
}

/** Speech-to-text surface, deliberately independent of whisper.cpp. */
interface AsrEngine {
    val state: StateFlow<AsrEngineState>
    val isLoaded: Boolean

    suspend fun load(modelPath: String): Result<Unit>

    /** Returns null when the call was cancelled; empty string when nothing was recognised. */
    suspend fun transcribe(samples: FloatArray, language: String?): String?

    /** Cooperative stop of the current transcription. Safe to call from any thread. */
    fun cancel()

    suspend fun unload()

    fun close()
}
