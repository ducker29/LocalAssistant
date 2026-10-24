package com.example.localassistant.model

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.example.localassistant.chat.ModelGateway
import com.example.localassistant.llm.EngineState
import com.example.localassistant.llm.LlmEngine
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

sealed interface ModelStatus {
    data object Absent : ModelStatus
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long,
    ) : ModelStatus

    data object Verifying : ModelStatus
    data object Loading : ModelStatus
    data class Ready(
        val fileName: String,
        val sizeBytes: Long,
        val engineDescription: String,
        val contextTokens: Int,
    ) : ModelStatus

    data class Error(val message: String) : ModelStatus
}

/**
 * Owns model acquisition and the lifecycle of the native engine. Only one download or
 * import can run at a time.
 */
class ModelRepository(
    private val store: ModelStore,
    private val engine: LlmEngine,
    private val contentResolver: ContentResolver,
) : ModelGateway {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Absent)
    override val status: StateFlow<ModelStatus> = _status.asStateFlow()

    private var downloadJob: Job? = null

    override fun initialize() {
        scope.launch {
            val model = store.activeModel()
            if (model == null) {
                _status.value = ModelStatus.Absent
                return@launch
            }
            loadEngine(model)
        }
    }

    override fun startDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _status.value = ModelStatus.Downloading(0L, ModelSpec.EXPECTED_SIZE, 0L)
            val downloader = ModelDownloader(store)
            var lastBytes = 0L
            var lastAt = System.currentTimeMillis()
            val result = downloader.download { downloaded, total ->
                val now = System.currentTimeMillis()
                if (now - lastAt >= 500L || downloaded == total) {
                    val elapsed = (now - lastAt).coerceAtLeast(1L)
                    val speed = ((downloaded - lastBytes) * 1000L) / elapsed
                    lastBytes = downloaded
                    lastAt = now
                    _status.value = ModelStatus.Downloading(downloaded, total, speed)
                }
            }
            result.fold(
                onSuccess = {
                    _status.value = ModelStatus.Verifying
                    val model = store.activeModel()
                    if (model == null) {
                        _status.value = ModelStatus.Absent
                    } else {
                        loadEngine(model)
                    }
                },
                onFailure = { error ->
                    Log.w(TAG, "download failed: ${error.message}")
                    if (store.activeModel() != null) {
                        loadEngine(store.activeModel()!!)
                    } else {
                        _status.value = ModelStatus.Error(
                            error.message ?: "Не удалось скачать модель"
                        )
                    }
                },
            )
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    override fun importModel(uri: Uri) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _status.value = ModelStatus.Verifying
            try {
                store.ensureDirectory()
                store.discardPart()
                val total = querySize(uri)
                var copied = 0L
                contentResolver.openInputStream(uri)?.use { input ->
                    File(store.directory, "import.part").let { target ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                _status.value = ModelStatus.Downloading(copied, total, 0L)
                            }
                        }
                        if (!GgufHeader.isValid(target)) {
                            target.delete()
                            throw IOException("Выбранный файл не является GGUF")
                        }
                        if (store.modelFile.exists()) store.modelFile.delete()
                        if (!target.renameTo(store.modelFile)) {
                            target.delete()
                            throw IOException("Не удалось сохранить импортированную модель")
                        }
                    }
                } ?: throw IOException("Не удалось открыть выбранный файл")
                loadEngine(store.modelFile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "import failed: ${t.message}")
                store.discardPart()
                File(store.directory, "import.part").delete()
                _status.value = ModelStatus.Error(t.message ?: "Ошибка импорта модели")
            }
        }
    }

    override fun deleteModel() {
        downloadJob?.cancel()
        downloadJob = null
        scope.launch {
            withContext(Dispatchers.IO) {
                engine.unload()
                store.discardAll()
                File(store.directory, "import.part").delete()
            }
            _status.value = ModelStatus.Absent
        }
    }

    override fun retryLoad() {
        val model = store.activeModel() ?: run {
            _status.value = ModelStatus.Absent
            return
        }
        scope.launch { loadEngine(model) }
    }

    private suspend fun loadEngine(model: File) {
        _status.value = ModelStatus.Loading
        val result = engine.load(model.absolutePath)
        result.fold(
            onSuccess = {
                val state = engine.state.value
                if (state is EngineState.Ready) {
                    _status.value = ModelStatus.Ready(
                        fileName = model.name,
                        sizeBytes = model.length(),
                        engineDescription = state.description,
                        contextTokens = state.contextTokens,
                    )
                } else {
                    _status.value = ModelStatus.Error("Модель загружена в неизвестном состоянии")
                }
            },
            onFailure = { error ->
                _status.value = ModelStatus.Error(error.message ?: "Не удалось загрузить модель")
            },
        )
    }

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

    companion object {
        private const val TAG = "ModelRepository"
    }
}
