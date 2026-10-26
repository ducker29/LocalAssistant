package com.example.localassistant.llm

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The single owner of the native llama.cpp model/context.
 *
 * All native calls run on one dedicated thread, so the model cannot be loaded twice and
 * cannot be freed while a generation is still running.
 */
class LlamaEngine(
    private val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    private val threads: Int = defaultThreadCount(),
) : LlmEngine {

    private val dispatcher = Executors
        .newSingleThreadExecutor { runnable -> Thread(runnable, "llama-engine") }
        .asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val handle = AtomicLong(0L)
    private val opMutex = Mutex()
    private val busy = AtomicBoolean(false)
    private val activeCancel = AtomicReference<AtomicBoolean?>(null)

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private suspend fun ensureHandle(): Long = withContext(dispatcher) {
        var value = handle.get()
        if (value == 0L) {
            value = LlmNative.nativeCreate()
            handle.set(value)
        }
        value
    }

    override suspend fun load(modelPath: String): Result<Unit> = withContext(dispatcher) {
        _state.value = EngineState.Loading
        val nativeHandle = ensureHandle()
        val error = LlmNative.nativeLoadModel(
            nativeHandle,
            modelPath,
            contextTokens,
            threads,
            BATCH_TOKENS,
        )
        if (error != null) {
            Log.e(TAG, "model load failed: $error")
            _state.value = EngineState.Failed(error)
            Result.failure(LlmException(error))
        } else {
            val description = LlmNative.nativeModelDescription(nativeHandle)
            val loadedContext = LlmNative.nativeContextSize(nativeHandle)
            Log.i(TAG, "model ready: $description, n_ctx=$loadedContext, threads=$threads")
            _state.value = EngineState.Ready(description, loadedContext)
            Result.success(Unit)
        }
    }

    override suspend fun unload() = withContext(dispatcher) {
        opMutex.withLock {
            activeCancel.get()?.set(true)
            val nativeHandle = handle.get()
            if (nativeHandle != 0L) {
                LlmNative.nativeUnloadModel(nativeHandle)
            }
            _state.value = EngineState.Unloaded
        }
    }

    override fun generate(
        messages: List<PromptMessage>,
        params: GenerationParams,
        onChunk: (String) -> Unit,
        onDone: (GenerationResult) -> Unit,
    ): Job {
        if (messages.isEmpty()) {
            throw LlmException("Пустой запрос к модели")
        }
        if (!busy.compareAndSet(false, true)) {
            throw LlmException("Модель уже генерирует ответ")
        }

        val cancelFlag = AtomicBoolean(false)
        activeCancel.set(cancelFlag)
        val text = StringBuilder()
        val specialFilter = SpecialTokenFilter()
        var reported = AtomicBoolean(false)
        var job: Job? = null

        fun finishIfPending(termination: GenerationTermination, message: String? = null) {
            if (reported.compareAndSet(false, true)) {
                busy.set(false)
                activeCancel.set(null)
                onDone(GenerationResult(text.toString(), termination, message))
            }
        }

        job = scope.launch {
            try {
                val nativeHandle = ensureHandle()
                val raw = LlmNative.nativeGenerate(
                    nativeHandle,
                    messages.map { it.role }.toTypedArray(),
                    messages.map { it.content }.toTypedArray(),
                    params.maxTokens,
                    params.temperature,
                    params.topP,
                    params.topK,
                    params.seed,
                    cancelFlag,
                    LlmNative.TokenCallback { chunk ->
                        val visible = specialFilter.append(chunk)
                        if (visible.isNotEmpty()) {
                            text.append(visible)
                            onChunk(visible)
                        }
                    },
                )
                val tail = specialFilter.finish()
                if (tail.isNotEmpty()) {
                    text.append(tail)
                    onChunk(tail)
                }
                val parsed = parseTermination(raw)
                if (parsed.termination == GenerationTermination.ERROR) {
                    Log.e(TAG, "generation failed: ${parsed.errorMessage}")
                }
                finishIfPending(parsed.termination, parsed.errorMessage)
            } catch (t: Throwable) {
                Log.e(TAG, "generation crashed", t)
                finishIfPending(GenerationTermination.ERROR, t.message ?: "Ошибка генерации")
            }
        }
        // The producer may already have completed synchronously; only cancel if we are still
        // alive. On cancellation the native loop observes the flag and finishes on its own,
        // then finishIfPending reports CANCELLED (or BUDGET/EOG if it already ended).
        job.invokeOnCompletion {
            if (cancelFlag.compareAndSet(false, true)) {
                finishIfPending(GenerationTermination.CANCELLED)
            }
        }
        return job
    }

    override fun cancelGeneration() {
        activeCancel.get()?.set(true)
    }

    override suspend fun countPromptTokens(messages: List<PromptMessage>): Int {
        val nativeHandle = handle.get()
        if (nativeHandle == 0L || _state.value !is EngineState.Ready) {
            return -1
        }
        return withContext(dispatcher) {
            LlmNative.nativeCountPromptTokens(
                nativeHandle,
                messages.map { it.role }.toTypedArray(),
                messages.map { it.content }.toTypedArray(),
            )
        }
    }

    suspend fun formatPrompt(messages: List<PromptMessage>): String {
        val nativeHandle = handle.get()
        if (nativeHandle == 0L) {
            return ""
        }
        return withContext(dispatcher) {
            LlmNative.nativeFormatPrompt(
                nativeHandle,
                messages.map { it.role }.toTypedArray(),
                messages.map { it.content }.toTypedArray(),
            )
        }
    }

    override suspend fun close() {
        unload()
        withContext(dispatcher) {
            val nativeHandle = handle.getAndSet(0L)
            if (nativeHandle != 0L) {
                LlmNative.nativeDestroy(nativeHandle)
            }
        }
        dispatcher.close()
    }

    companion object {
        private const val TAG = "LlamaEngine"
        const val DEFAULT_CONTEXT_TOKENS = 2048
        const val BATCH_TOKENS = 512
        const val DEFAULT_MAX_RESPONSE_TOKENS = 256

        /**
         * Maps the native termination string to a [GenerationTermination]. The native side
         * always returns a non-null code; a null/blank value is treated defensively as EOG.
         * Codes without a `|` payload ("EOG", "BUDGET", "CANCELLED") must keep their own code
         * instead of falling back to EOG.
         */
        internal fun parseTermination(raw: String?): GenerationResult {
            val code = raw?.substringBefore('|')?.trim()?.ifBlank { "EOG" } ?: "EOG"
            val message = raw?.substringAfter('|', "")?.takeIf { it.isNotBlank() }
            return when (code) {
                "EOG" -> GenerationResult("", GenerationTermination.EOG)
                "BUDGET" -> GenerationResult("", GenerationTermination.BUDGET)
                "CANCELLED" -> GenerationResult("", GenerationTermination.CANCELLED)
                "CONTEXTEXHAUSTED" ->
                    GenerationResult("", GenerationTermination.CONTEXT, message)
                "ERROR" -> GenerationResult("", GenerationTermination.ERROR, message)
                else -> GenerationResult(
                    "",
                    GenerationTermination.ERROR,
                    "Неизвестный код завершения: $code",
                )
            }
        }

        private fun defaultThreadCount(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return cores.coerceIn(2, 6)
        }
    }
}
