package com.example.localassistant.asr

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExecutorCoroutineDispatcher

/**
 * Owns exactly one whisper context on a dedicated thread. Load, transcribe and unload are
 * serialised; the context stays in memory between utterances instead of being reloaded per
 * call. Cancellation is cooperative and only takes effect inside whisper's decoder loop.
 */
class WhisperEngine(
    private val threads: Int = defaultThreadCount(),
) : AsrEngine {

    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "whisper-engine").apply { priority = Thread.NORM_PRIORITY }
        }.asCoroutineDispatcher()

    private val mutex = Mutex()
    private val _state = MutableStateFlow<AsrEngineState>(AsrEngineState.Unloaded)
    override val state: StateFlow<AsrEngineState> = _state.asStateFlow()

    private val cancelFlag = AtomicBoolean(false)
    private var handle: Long = WhisperNative.create()
    private var loadedPath: String? = null

    override val isLoaded: Boolean
        get() = _state.value is AsrEngineState.Ready

    override suspend fun load(modelPath: String): Result<Unit> = mutex.withLock {
        if (isLoaded && loadedPath == modelPath) return@withLock Result.success(Unit)
        withContext(dispatcher) {
            _state.value = AsrEngineState.Loading
            try {
                val error = WhisperNative.load(handle, modelPath, threads)
                if (error == null) {
                    loadedPath = modelPath
                    _state.value = AsrEngineState.Ready("whisper.cpp ${WhisperNative.version()}")
                    Result.success(Unit)
                } else {
                    loadedPath = null
                    _state.value = AsrEngineState.Failed(error)
                    Result.failure(IOException(error))
                }
            } catch (t: Throwable) {
                loadedPath = null
                _state.value = AsrEngineState.Failed(t.message ?: "Ошибка загрузки модели")
                Result.failure(t)
            }
        }
    }

    override suspend fun transcribe(samples: FloatArray, language: String?): String? {
        if (samples.isEmpty()) return ""
        return mutex.withLock {
            withContext(dispatcher) {
                if (!isLoaded || handle == 0L) {
                    throw IllegalStateException("Модель распознавания не загружена")
                }
                cancelFlag.set(false)
                val text = WhisperNative.transcribe(handle, samples, language, threads, cancelFlag)
                if (text == null) null else AsrTranscript.clean(text)
            }
        }
    }

    override fun cancel() {
        cancelFlag.set(true)
    }

    override suspend fun unload() = mutex.withLock {
        withContext(dispatcher) {
            if (handle != 0L) {
                WhisperNative.unload(handle)
            }
            loadedPath = null
            _state.value = AsrEngineState.Unloaded
        }
    }

    override fun close() {
        cancelFlag.set(true)
        if (handle != 0L) {
            WhisperNative.destroy(handle)
            handle = 0L
        }
        loadedPath = null
        _state.value = AsrEngineState.Unloaded
        dispatcher.close()
    }

    companion object {
        fun defaultThreadCount(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
