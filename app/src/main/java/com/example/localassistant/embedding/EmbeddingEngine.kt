package com.example.localassistant.embedding

import java.io.File
import kotlinx.coroutines.flow.StateFlow

sealed interface EmbeddingState {
    data object Unloaded : EmbeddingState
    data object Loading : EmbeddingState
    data object Ready : EmbeddingState
    data object Running : EmbeddingState
    data class Failed(val message: String) : EmbeddingState
    data object Closed : EmbeddingState
}

interface EmbeddingEngine {
    val state: StateFlow<EmbeddingState>
    suspend fun load(file: File)
    suspend fun embedQuery(text: String): FloatArray
    suspend fun embedPassage(text: String): FloatArray
    /** Counts include prefix and BOS/EOS; may exceed 512 to support future chunking. */
    suspend fun countQueryTokens(text: String): Int
    suspend fun countPassageTokens(text: String): Int
    fun cancel()
    suspend fun unload()
    suspend fun close()
}

internal object EmbeddingText {
    fun prepare(text: String, query: Boolean): ByteArray {
        require(text.length <= 64 * 1024) { "Слишком большой фрагмент для поиска" }
        var content = text.trim()
        require(content.isNotEmpty()) { "Пустой текст для embedding-модели" }
        for (marker in listOf("<mask>", "<s>", "</s>", "<unk>", "<pad>")) {
            content = content.replace(marker, marker.replace("<", "< ").replace(">", " >"))
        }
        val prepared = (if (query) "query: $content" else "passage: $content").toByteArray(Charsets.UTF_8)
        require(prepared.size <= 256 * 1024) { "Слишком большой фрагмент для поиска" }
        return prepared
    }
}
