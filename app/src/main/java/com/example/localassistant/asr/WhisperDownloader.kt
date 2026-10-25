package com.example.localassistant.asr

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Downloads the pinned Whisper artifact into app-private storage.
 *
 * The transfer goes to a `.part` file and is promoted only after size, SHA-256 and the
 * ggml magic have been verified. Interrupted transfers resume with a Range request when
 * the server confirms the offset.
 */
class WhisperDownloader(private val store: WhisperModelStore) {

    suspend fun download(onProgress: (downloaded: Long, total: Long?) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            store.ensureDirectory()
            val part = store.partFile
            var resumeFrom = if (part.exists()) part.length() else 0L

            val remaining = WhisperModelSpec.EXPECTED_SIZE - resumeFrom
            if (remaining > 0 && store.availableBytes() < remaining + STORAGE_MARGIN) {
                return@withContext Result.failure(
                    IOException("Недостаточно свободного места для загрузки модели")
                )
            }

            var connection: HttpURLConnection? = null
            try {
                connection = openConnection(resumeFrom)
                val code = connection.responseCode

                var append = false
                when {
                    code == HttpURLConnection.HTTP_PARTIAL -> {
                        val start = parseRangeStart(connection.getHeaderField("Content-Range"))
                        if (start == resumeFrom && resumeFrom > 0) {
                            append = true
                        } else if (start == 0L) {
                            append = false
                            resumeFrom = 0
                        } else {
                            throw IOException("Сервер не подтвердил диапазон докачки")
                        }
                    }

                    code in 200..299 -> {
                        append = false
                        resumeFrom = 0
                    }

                    else -> throw IOException("Ошибка загрузки: HTTP $code")
                }

                if (!append && part.exists()) {
                    part.delete()
                }

                val contentLength = connection.contentLengthLong
                val total = if (contentLength > 0) resumeFrom + contentLength else null

                connection.inputStream.use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var written = resumeFrom
                        onProgress(written, total)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (written > WhisperModelSpec.EXPECTED_SIZE) {
                                throw IOException("Сервер прислал больше данных, чем ожидалось")
                            }
                            onProgress(written, total)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }

                val length = part.length()
                if (length != WhisperModelSpec.EXPECTED_SIZE) {
                    throw IOException(
                        "Неверный размер файла: $length из ${WhisperModelSpec.EXPECTED_SIZE} байт"
                    )
                }

                val digest = sha256(part)
                if (!digest.equals(WhisperModelSpec.SHA256, ignoreCase = true)) {
                    store.discardPart()
                    throw IOException("Контрольная сумма не совпала, файл удалён")
                }

                if (!WhisperModelStore.hasGgmlMagic(part)) {
                    store.discardPart()
                    throw IOException("Файл не является моделью whisper.cpp")
                }

                if (!store.promotePart()) {
                    throw IOException("Не удалось сохранить модель")
                }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                connection?.disconnect()
            }
        }

    private fun openConnection(resumeFrom: Long): HttpURLConnection {
        val connection = URL(WhisperModelSpec.DOWNLOAD_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "LocalAssistant/1.0 (Android)")
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (resumeFrom > 0) {
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        return connection
    }

    private fun parseRangeStart(contentRange: String?): Long? {
        if (contentRange == null) return null
        val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)").find(contentRange) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
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
        private const val BUFFER_SIZE = 128 * 1024
        private const val STORAGE_MARGIN = 64L * 1024 * 1024
    }
}
