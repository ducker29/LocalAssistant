package com.example.localassistant.embedding

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One model/context and one worker, independent of Qwen. Own at application/service scope. */
class E5EmbeddingEngine internal constructor(
    private val bridge: EmbeddingBridge,
    private val verifyModel: (File, () -> Unit) -> Unit,
    private val threads: Int,
) : EmbeddingEngine {
    constructor(threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)) :
        this(JniEmbeddingBridge, VerifiedE5Model::verify, threads)

    init { require(threads in 1..6) }

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "e5-embedding") }
    private val mutex = Mutex()
    private val handle = AtomicLong(0L)
    private val activeCancellation = AtomicReference<AtomicBoolean?>(null)
    private var closed = false // guarded by mutex
    private val mutableState = MutableStateFlow<EmbeddingState>(EmbeddingState.Unloaded)
    override val state: StateFlow<EmbeddingState> = mutableState.asStateFlow()

    override suspend fun load(file: File) = mutex.withLock {
        checkOpen()
        mutableState.value = EmbeddingState.Loading
        try {
            nativeOperation { checkActive ->
                // An invalid replacement must not leave a previous model loaded invisibly.
                handle.get().takeIf { it != 0L }?.let(bridge::unload)
                verifyModel(file, checkActive)
                checkActive()
                var id = handle.get()
                if (id == 0L) {
                    id = bridge.create()
                    check(id != 0L) { "Не удалось создать embedding-контекст" }
                    handle.set(id)
                }
                checkActive()
                bridge.load(id, file.canonicalPath.toByteArray(Charsets.UTF_8), threads)
                checkActive()
            }
            mutableState.value = EmbeddingState.Ready
        } catch (e: CancellationException) {
            cleanupCancelled()
            throw e
        } catch (e: Exception) {
            mutableState.value = EmbeddingState.Failed("Не удалось загрузить проверенную embedding-модель")
            throw e
        } catch (e: LinkageError) {
            mutableState.value = EmbeddingState.Failed("Native embedding runtime недоступен для этой сборки")
            throw e
        }
    }

    override suspend fun embedQuery(text: String): FloatArray = embed(text, query = true)
    override suspend fun embedPassage(text: String): FloatArray = embed(text, query = false)
    override suspend fun countQueryTokens(text: String): Int = count(text, query = true)
    override suspend fun countPassageTokens(text: String): Int = count(text, query = false)

    private suspend fun embed(text: String, query: Boolean): FloatArray = readyOperation {
        val input = EmbeddingText.prepare(text, query)
        require(bridge.countTokens(handle.get(), input) <= MAX_TOKENS) { "Фрагмент превышает 512 токенов с учётом префикса" }
        val vector = bridge.embed(handle.get(), input)
        check(vector.size == DIMENSIONS && vector.all { it.isFinite() }) { "Некорректный embedding-вектор" }
        val norm = vector.sumOf { it.toDouble() * it }
        check(kotlin.math.abs(norm - 1.0) <= 1e-4) { "Embedding-вектор не нормализован" }
        vector
    }

    private suspend fun count(text: String, query: Boolean): Int = readyOperation {
        bridge.countTokens(handle.get(), EmbeddingText.prepare(text, query)).also {
            check(it > 0) { "Ошибка подсчёта embedding-токенов" }
        }
    }

    private suspend fun <T> readyOperation(block: () -> T): T = mutex.withLock {
        checkOpen()
        check(mutableState.value == EmbeddingState.Ready) { "Embedding-модель не готова" }
        mutableState.value = EmbeddingState.Running
        try {
            nativeOperation { checkActive ->
                checkActive()
                block().also { checkActive() }
            }.also { mutableState.value = EmbeddingState.Ready }
        } catch (e: CancellationException) {
            // Abort can interrupt a graph; discard its context before reuse.
            cleanupCancelled()
            throw e
        } catch (e: IllegalArgumentException) {
            mutableState.value = EmbeddingState.Ready
            throw e
        } catch (e: Exception) {
            mutableState.value = EmbeddingState.Failed("Ошибка вычисления embedding-вектора; перезагрузите модель")
            throw e
        }
    }

    override fun cancel() {
        activeCancellation.get()?.set(true)
        handle.get().takeIf { it != 0L }?.let(bridge::cancel)
    }

    override suspend fun unload() {
        cancel()
        withContext(NonCancellable) {
            mutex.withLock {
                if (closed) return@withLock
                nativeOperation { handle.get().takeIf { it != 0L }?.let(bridge::unload) }
                mutableState.value = EmbeddingState.Unloaded
            }
        }
    }

    override suspend fun close() {
        cancel()
        withContext(NonCancellable) {
            mutex.withLock {
                if (closed) return@withLock
                try {
                    nativeOperation { handle.getAndSet(0L).takeIf { it != 0L }?.let(bridge::destroy) }
                } finally {
                    closed = true
                    mutableState.value = EmbeddingState.Closed
                    executor.shutdown()
                }
            }
        }
    }

    private suspend fun cleanupCancelled() = withContext(NonCancellable) {
        nativeOperation { handle.get().takeIf { it != 0L }?.let(bridge::unload) }
        mutableState.value = EmbeddingState.Unloaded
    }

    /** Cancellation signals native immediately, but does not release the mutex until work exits. */
    private suspend fun <T> nativeOperation(block: (checkActive: () -> Unit) -> T): T {
        val finished = CompletableDeferred<Unit>()
        val requested = AtomicBoolean(false)
        activeCancellation.set(requested)
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancel() }
                try {
                    executor.execute {
                        try {
                            handle.get().takeIf { it != 0L }?.let(bridge::resetCancellation)
                            val checkActive = {
                                if (!continuation.isActive || requested.get()) throw CancellationException("Embedding operation cancelled")
                            }
                            checkActive()
                            continuation.resume(block(checkActive))
                        } catch (error: Throwable) {
                            // Propagate even cancellation/errors; never swallow them on the worker.
                            continuation.resumeWithException(error)
                        } finally {
                            activeCancellation.compareAndSet(requested, null)
                            finished.complete(Unit)
                        }
                    }
                } catch (error: Throwable) {
                    activeCancellation.compareAndSet(requested, null)
                    finished.complete(Unit)
                    continuation.resumeWithException(error)
                }
            }
        } finally {
            withContext(NonCancellable) { finished.await() }
        }
    }

    private fun checkOpen() { check(!closed) { "Embedding-движок уже закрыт" } }

    companion object {
        const val DIMENSIONS = 384
        const val MAX_TOKENS = 512
    }
}
