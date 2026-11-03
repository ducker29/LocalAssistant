package com.example.localassistant.documents

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** All methods are main-safe. The ViewModel serializes imports and reads. */
class DocumentRepository(private val resolver: ContentResolver, filesDir: File) {
    private val root = File(filesDir, "documents")
    private val extractor = OfficeDocumentExtractor()

    suspend fun list(recoverInterrupted: Boolean = false): List<StoredDocument> = withContext(Dispatchers.IO) {
        if (!root.exists()) return@withContext emptyList()
        root.listFiles().orEmpty().filter { it.isDirectory }.map { dir ->
            val metadata = AtomicFile(File(dir, "metadata.json"))
            val item = try {
                decodeMetadata(JSONObject(metadata.openRead().bufferedReader().use { it.readText() }))
            } catch (_: Exception) {
                StoredDocument(dir.name, "Документ с недоступными метаданными", DocumentFormat.DOCX,
                    0, dir.lastModified(), DocumentStatus.ERROR, "Не удалось прочитать метаданные документа.")
            }
            if (recoverInterrupted && item.status == DocumentStatus.IMPORTING) {
                item.copy(status = DocumentStatus.ERROR, error = "Обработка была прервана. Добавьте исходный файл повторно.")
                    .also { writeMetadata(it) }
            } else item
        }.sortedByDescending { it.importedAt }
    }

    suspend fun importDocument(uri: Uri, onChanged: suspend () -> Unit) = withContext(Dispatchers.IO) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create document directory")
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.take(255) ?: throw DocumentReadException("Не удалось определить имя файла.")
        val format = when (name.substringAfterLast('.', "").lowercase()) {
            "docx" -> DocumentFormat.DOCX
            "xlsx" -> DocumentFormat.XLSX
            else -> throw DocumentReadException("Поддерживаются только DOCX и XLSX.")
        }
        // Copy to an app-owned temporary file; URI access is needed only during this import.
        val temporary = File(root, "${UUID.randomUUID()}.part")
        var item: StoredDocument? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val input = resolver.openInputStream(uri) ?: throw DocumentReadException("Не удалось открыть выбранный файл.")
            input.use { source ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > 50L * 1024 * 1024) throw DocumentReadException("Максимальный размер документа — 50 МБ.")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            if (size == 0L) throw DocumentReadException("Выбранный файл пуст.")
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            val existing = list().firstOrNull { it.id == id }
            if (existing?.status == DocumentStatus.READY) throw DocumentReadException("Этот документ уже добавлен.")
            val dir = File(root, id)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create document directory")
            val original = File(dir, "original.${format.name.lowercase()}")
            if (original.exists()) temporary.delete()
            else if (!temporary.renameTo(original)) throw IOException("Cannot store document")
            val importing = StoredDocument(id, name, format, size, System.currentTimeMillis(), DocumentStatus.IMPORTING)
            item = importing
            writeMetadata(importing)
            onChanged()
            val extracted = extractor.extract(original, format)
            writeJson(File(dir, "content.json"), encodeContent(extracted))
            writeMetadata(importing.copy(status = DocumentStatus.READY))
            onChanged()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = (e as? DocumentReadException)?.message ?: "Не удалось импортировать документ. Проверьте доступ к файлу и свободное место."
            item?.let { writeMetadata(it.copy(status = DocumentStatus.ERROR, error = message)); onChanged() }
            throw DocumentReadException(message)
        } finally {
            temporary.delete()
        }
    }

    suspend fun read(id: String): ExtractedDocument = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[a-f0-9]{64}")))
        val file = File(File(root, id), "content.json")
        if (file.length() > 48L * 1024 * 1024) throw DocumentReadException("Слишком большой файл извлечённых данных.")
        val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        val blocks = json.getJSONArray("blocks")
        ExtractedDocument(
            blocks = (0 until blocks.length()).map { index ->
                val block = blocks.getJSONObject(index)
                val cells = block.getJSONArray("cells")
                DocumentBlock(block.getString("source"), block.getString("text"),
                    if (block.isNull("sheet")) null else block.getString("sheet"),
                    (0 until cells.length()).map { cellIndex ->
                        val c = cells.getJSONObject(cellIndex)
                        DocumentCell(c.getString("address"), c.getString("type"), c.getString("value"),
                            if (c.isNull("formula")) null else c.getString("formula"))
                    })
            },
            sheets = json.getJSONArray("sheets").strings(),
            warnings = json.getJSONArray("warnings").strings(),
            date1904 = if (json.has("date1904")) json.getBoolean("date1904") else
                json.getJSONArray("warnings").strings().any { it.startsWith("Система дат: 1904") },
        )
    }

    private fun writeMetadata(item: StoredDocument) = writeJson(File(File(root, item.id), "metadata.json"),
        JSONObject().put("version", 1).put("id", item.id).put("name", item.name)
            .put("format", item.format.name).put("size", item.size).put("importedAt", item.importedAt)
            .put("status", item.status.name).put("error", item.error ?: JSONObject.NULL))

    private fun decodeMetadata(json: JSONObject) = StoredDocument(
        json.getString("id"), json.getString("name"), DocumentFormat.valueOf(json.getString("format")),
        json.getLong("size"), json.getLong("importedAt"), DocumentStatus.valueOf(json.getString("status")),
        if (json.isNull("error")) null else json.getString("error"),
    )

    private fun encodeContent(content: ExtractedDocument): JSONObject {
        val blocks = JSONArray()
        content.blocks.forEach { b ->
            val cells = JSONArray()
            b.cells.forEach { c -> cells.put(JSONObject().put("address", c.address).put("type", c.type)
                .put("value", c.rawValue).put("formula", c.formula ?: JSONObject.NULL)) }
            blocks.put(JSONObject().put("source", b.source).put("text", b.text)
                .put("sheet", b.sheet ?: JSONObject.NULL).put("cells", cells))
        }
        return JSONObject().put("version", 2).put("date1904", content.date1904).put("blocks", blocks)
            .put("sheets", JSONArray(content.sheets)).put("warnings", JSONArray(content.warnings))
    }

    private fun writeJson(file: File, json: JSONObject) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}
