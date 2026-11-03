package com.example.localassistant.embedding

/** All methods except cancel are blocking and confined to the owner's worker. */
internal interface EmbeddingBridge {
    fun create(): Long
    fun load(handle: Long, path: ByteArray, threads: Int)
    fun countTokens(handle: Long, text: ByteArray): Int
    fun embed(handle: Long, text: ByteArray): FloatArray
    fun cancel(handle: Long)
    fun resetCancellation(handle: Long)
    fun unload(handle: Long)
    fun destroy(handle: Long)
}

// Indirection keeps System.loadLibrary off the thread constructing the engine (usually UI).
internal object JniEmbeddingBridge : EmbeddingBridge {
    override fun create() = EmbeddingNative.create()
    override fun load(handle: Long, path: ByteArray, threads: Int) = EmbeddingNative.load(handle, path, threads)
    override fun countTokens(handle: Long, text: ByteArray) = EmbeddingNative.countTokens(handle, text)
    override fun embed(handle: Long, text: ByteArray) = EmbeddingNative.embed(handle, text)
    override fun cancel(handle: Long) = EmbeddingNative.cancel(handle)
    override fun resetCancellation(handle: Long) = EmbeddingNative.resetCancellation(handle)
    override fun unload(handle: Long) = EmbeddingNative.unload(handle)
    override fun destroy(handle: Long) = EmbeddingNative.destroy(handle)
}

internal object EmbeddingNative {
    init { System.loadLibrary("localassistant") }
    external fun create(): Long
    external fun load(handle: Long, path: ByteArray, threads: Int)
    external fun countTokens(handle: Long, text: ByteArray): Int
    external fun embed(handle: Long, text: ByteArray): FloatArray
    external fun cancel(handle: Long)
    external fun resetCancellation(handle: Long)
    external fun unload(handle: Long)
    external fun destroy(handle: Long)
}
