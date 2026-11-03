package com.example.localassistant.documents

import android.util.AtomicFile
import com.example.localassistant.embedding.VerifiedE5Model
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One atomic snapshot per document. An interrupted rebuild keeps the preceding valid index. */
class DocumentIndexStore(filesDir: File) {
    private val directory = File(filesDir, "document_index")
    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return AtomicFile(File(directory, "$id.idx"))
    }

    suspend fun write(id: String, chunks: List<IndexedChunk>) = withContext(Dispatchers.IO) {
        require(chunks.size <= 20_000 && chunks.all { it.chunk.documentId == id })
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать индекс" }
        val atomic = file(id)
        val stream = atomic.startWrite()
        try {
            val output = DataOutputStream(stream)
            output.writeInt(0x4C414931)
            output.writeInt(DocumentChunks.VERSION)
            output.writeUTF(VerifiedE5Model.SHA256)
            output.writeUTF(id)
            output.writeInt(chunks.size)
            chunks.forEach { indexed ->
                currentCoroutineContext().ensureActive()
                val c = indexed.chunk
                output.writeUTF(c.documentName)
                output.writeInt(c.blockIndex)
                output.writeInt(c.part)
                output.writeUTF(c.source)
                output.writeUTF(c.sheet.orEmpty())
                output.writeUTF(c.text)
                output.writeUTF(c.embeddingText)
                require(indexed.vector.size == 384 && indexed.vector.all { it.isFinite() })
                indexed.vector.forEach(output::writeFloat)
            }
            output.flush()
            atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    suspend fun read(id: String): List<IndexedChunk>? = withContext(Dispatchers.IO) {
        val atomic = file(id)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return@withContext null
        require(atomic.baseFile.length() <= 128L * 1024 * 1024) { "Индекс превышает лимит размера" }
        DataInputStream(atomic.openRead().buffered()).use { input ->
            require(input.readInt() == 0x4C414931 && input.readInt() == DocumentChunks.VERSION) { "Индекс устарел: выполните индексацию" }
            require(input.readUTF() == VerifiedE5Model.SHA256 && input.readUTF() == id) { "Индекс создан другой моделью" }
            val count = input.readInt()
            require(count in 0..20_000)
            List(count) {
                currentCoroutineContext().ensureActive()
                val name = input.readUTF()
                val block = input.readInt()
                val part = input.readInt()
                val source = input.readUTF()
                val sheet = input.readUTF().ifEmpty { null }
                val text = input.readUTF()
                val embedded = input.readUTF()
                require(block >= 0 && part >= 0 && text.length <= 4096)
                val vector = FloatArray(384) { input.readFloat() }
                val norm = vector.sumOf { it.toDouble() * it }
                require(vector.all { it.isFinite() } && kotlin.math.abs(norm - 1) < 1e-3) { "Индекс повреждён: выполните индексацию" }
                IndexedChunk(DocumentChunk(id, name, block, part, source, sheet, text, embedded), vector)
            }.also { require(input.read() == -1) { "Некорректный индекс" } }
        }
    }
}
