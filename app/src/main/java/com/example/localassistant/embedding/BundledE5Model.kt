package com.example.localassistant.embedding

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Caller serializes access against import/load. Never exposes a half-written GGUF. */
internal class BundledE5Model(
    private val target: File,
    private val openAsset: () -> InputStream,
    private val verify: (File, () -> Unit) -> Unit = VerifiedE5Model::verify,
    private val expectedSize: Long = VerifiedE5Model.SIZE,
) {
    suspend fun prepare(): Unit = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val checkActive = { context.ensureActive() }
        if (target.isFile) {
            val valid = try { verify(target, checkActive); true }
                catch (_: IllegalArgumentException) { false } // Wrong hash/size: restore from the packaged copy.
                catch (_: IOException) { false }
            if (valid) return@withContext
        }
        val directory = target.parentFile ?: error("Не определена папка embedding-модели")
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать папку embedding-модели" }
        val temporary = File(directory, "bundled-${UUID.randomUUID()}.part")
        try {
            openAsset().use { input ->
                temporary.outputStream().use { output ->
                    var total = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= expectedSize) { "Встроенная embedding-модель имеет неверный размер" }
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            verify(temporary, checkActive)
            checkActive()
            // Both files live in the same private directory. Fail instead of a non-atomic fallback.
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        } finally { temporary.delete() }
    }

    companion object {
        const val ASSET = "embedding/multilingual-e5-small-q8_0-posfix.gguf"
    }
}
