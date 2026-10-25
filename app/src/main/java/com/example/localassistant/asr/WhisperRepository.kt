package com.example.localassistant.asr

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface WhisperModelStatus {
    data object Absent : WhisperModelStatus
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long,
    ) : WhisperModelStatus

    data object Verifying : WhisperModelStatus
    data object Loading : WhisperModelStatus
    data class Ready(
        val fileName: String,
        val sizeBytes: Long,
        val engineDescription: String?,
        val loaded: Boolean,
    ) : WhisperModelStatus

    data class Error(val message: String) : WhisperModelStatus
}

/**
 * Owns Whisper model acquisition and the lazy lifecycle of the native recogniser.
 *
 * The model is loaded on the first transcription rather than on app start: it is not needed
 * for chat, and deferring it keeps the process small until the user actually records.
 */
class WhisperRepository(
    private val store: WhisperModelStore,
    private val engine: AsrEngine,
    private val contentResolver: ContentResolver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<WhisperModelStatus>(WhisperModelStatus.Absent)
    val status: StateFlow<WhisperModelStatus> = _status.asStateFlow()

    private var acquisitionJob: Job? = null

    fun initialize() {
        scope.launch {
            val model = store.activeModel()
            if (model == null) {
                if (store.modelFile.exists()) {
                    Log.w(TAG, "discarding invalid whisper artifact")
                    store.modelFile.delete()
                }
                _status.value = WhisperModelStatus.Absent
                return@launch
            }
            _status.value = readyStatus(model, loaded = engine.isLoaded)
        }
    }

    fun startDownload() {
        if (acquisitionJob?.isActive == true) return
        acquisitionJob = scope.launch {
            _status.value = WhisperModelStatus.Downloading(0L, WhisperModelSpec.EXPECTED_SIZE, 0L)
            var lastBytes = 0L
            var lastAt = System.currentTimeMillis()
            val result = WhisperDownloader(store).download { downloaded, total ->
                val now = System.currentTimeMillis()
                if (now - lastAt >= 500L || downloaded == total) {
                    val elapsed = (now - lastAt).coerceAtLeast(1L)
                    val speed = ((downloaded - lastBytes) * 1000L) / elapsed
                    lastBytes = downloaded
                    lastAt = now
                    _status.value = WhisperModelStatus.Downloading(downloaded, total, speed)
                }
            }
            result.fold(
                onSuccess = {
                    _status.value = WhisperModelStatus.Verifying
                    val model = store.activeModel()
                    if (model == null) {
                        _status.value = WhisperModelStatus.Absent
                    } else {
                        _status.value = readyStatus(model, loaded = engine.isLoaded)
                    }
                },
                onFailure = { error ->
                    Log.w(TAG, "download failed: ${error.message}")
                    val model = store.activeModel()
                    _status.value = if (model != null) {
                        readyStatus(model, loaded = engine.isLoaded)
                    } else {
                        WhisperModelStatus.Error(error.message ?: "Не удалось скачать модель")
                    }
                },
            )
        }
    }

    fun cancelDownload() {
        acquisitionJob?.cancel()
        acquisitionJob = null
    }

    fun importModel(uri: Uri) {
        if (acquisitionJob?.isActive == true) return
        acquisitionJob = scope.launch {
            _status.value = WhisperModelStatus.Verifying
            try {
                store.ensureDirectory()
                store.discardPart()
                val total = querySize(uri)
                if (total != null && total != WhisperModelSpec.EXPECTED_SIZE) {
                    throw IOException(
                        "Ожидается ${WhisperModelSpec.FILE_NAME} (${WhisperModelSpec.EXPECTED_SIZE} байт), " +
                            "а выбран файл размером $total байт"
                    )
                }
                var copied = 0L
                contentResolver.openInputStream(uri)?.use { input ->
                    store.importPartFile.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (copied > WhisperModelSpec.EXPECTED_SIZE) {
                                throw IOException("Выбранный файл больше, чем модель Whisper")
                            }
                            _status.value = WhisperModelStatus.Downloading(copied, total, 0L)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                } ?: throw IOException("Не удалось открыть выбранный файл")

                if (store.importPartFile.length() != WhisperModelSpec.EXPECTED_SIZE) {
                    throw IOException("Размер выбранного файла не совпадает с моделью Whisper")
                }
                if (!sha256(store.importPartFile).equals(WhisperModelSpec.SHA256, ignoreCase = true)) {
                    throw IOException("Контрольная сумма файла не совпала с опубликованной")
                }
                if (!WhisperModelStore.hasGgmlMagic(store.importPartFile)) {
                    throw IOException("Выбранный файл не является моделью whisper.cpp")
                }
                if (!store.promoteImport()) {
                    throw IOException("Не удалось сохранить импортированную модель")
                }
                withContext(Dispatchers.IO) { engine.unload() }
                _status.value = readyStatus(store.modelFile, loaded = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "import failed: ${t.message}")
                store.discardPart()
                _status.value = WhisperModelStatus.Error(t.message ?: "Ошибка импорта модели")
            }
        }
    }

    fun deleteModel() {
        acquisitionJob?.cancel()
        acquisitionJob = null
        scope.launch {
            withContext(Dispatchers.IO) {
                engine.unload()
                store.discardAll()
            }
            _status.value = WhisperModelStatus.Absent
        }
    }

    fun retryLoad() {
        scope.launch { ensureLoaded() }
    }

    /** Loads the model if needed. Safe to call before every transcription. */
    suspend fun ensureLoaded(): Result<Unit> {
        if (engine.isLoaded) return Result.success(Unit)
        val model = store.activeModel()
            ?: return Result.failure(IOException("Модель распознавания не найдена"))
        _status.value = WhisperModelStatus.Loading
        val result = engine.load(model.absolutePath)
        result.fold(
            onSuccess = {
                _status.value = readyStatus(model, loaded = true)
            },
            onFailure = { error ->
                _status.value = WhisperModelStatus.Error(
                    error.message ?: "Не удалось загрузить модель распознавания"
                )
            },
        )
        return result.map { }
    }

    suspend fun transcribe(samples: FloatArray, language: String? = "ru"): String? {
        val loaded = ensureLoaded()
        loaded.exceptionOrNull()?.let { error ->
            throw IllegalStateException(
                error.message ?: "Модель распознавания не загружена",
                error,
            )
        }
        return engine.transcribe(samples, language)
    }

    fun cancelTranscription() {
        engine.cancel()
    }

    /** Frees the recogniser (for example when the system reports memory pressure). */
    fun releaseEngine() {
        if (!engine.isLoaded) return
        scope.launch {
            withContext(Dispatchers.IO) { engine.unload() }
            val model = store.activeModel()
            if (model != null) {
                _status.value = readyStatus(model, loaded = false)
            }
        }
    }

    fun close() {
        acquisitionJob?.cancel()
        engine.close()
    }

    private fun readyStatus(model: File, loaded: Boolean): WhisperModelStatus.Ready =
        WhisperModelStatus.Ready(
            fileName = model.name,
            sizeBytes = model.length(),
            engineDescription = (engine.state.value as? AsrEngineState.Ready)?.description,
            loaded = loaded,
        )

    private fun querySize(uri: Uri): Long? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "WhisperRepository"
        private const val BUFFER_SIZE = 256 * 1024
    }
}
