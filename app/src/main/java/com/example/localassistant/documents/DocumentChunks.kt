package com.example.localassistant.documents

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class DocumentChunk(
    val documentId: String,
    val documentName: String,
    val blockIndex: Int,
    val part: Int,
    val source: String,
    val sheet: String?,
    val text: String,
    val embeddingText: String,
) {
    val key: String get() = "$documentId:$blockIndex:$part"
    val location: String get() = listOfNotNull(documentName, sheet, source).joinToString(" · ")
}

data class IndexedChunk(val chunk: DocumentChunk, val vector: FloatArray)
data class SearchHit(val chunk: DocumentChunk, val score: Double, val lexical: Boolean)

object DocumentChunks {
    const val VERSION = 1

    /** Exact model token counts include passage prefix and specials. Source text is untouched. */
    suspend fun build(
        document: StoredDocument,
        content: ExtractedDocument,
        countTokens: suspend (String) -> Int,
    ): List<DocumentChunk> {
        val headers = content.sheets.associateWith { sheet ->
            content.blocks.filter { it.sheet == sheet }.take(20)
                .filter { row -> row.cells.count { it.type == "TEXT" && it.rawValue.isNotBlank() } >= 2 }
                .maxByOrNull { it.cells.count { cell -> cell.type == "TEXT" } }?.text.orEmpty()
        }
        val result = mutableListOf<DocumentChunk>()
        var heading = ""
        content.blocks.forEachIndexed { blockIndex, block ->
            currentCoroutineContext().ensureActive()
            if (block.source.startsWith("Заголовок")) heading = block.text
            var prefix = listOfNotNull(
                "Документ: ${document.name}", block.sheet?.let { "Лист: $it" },
                if (block.sheet == null) heading.takeIf { it.isNotBlank() } else
                    headers[block.sheet]?.takeIf { it.isNotBlank() }?.let { "Предполагаемые заголовки: $it" },
            ).joinToString("\n").safePrefix(320)
            while (prefix.isNotEmpty() && countTokens("$prefix\nx") > 480) prefix = prefix.safePrefix(prefix.length / 2)
            var part = 0
            suspend fun split(text: String) {
                currentCoroutineContext().ensureActive()
                if (text.isBlank()) return
                val embedded = listOf(prefix, text).filter { it.isNotBlank() }.joinToString("\n")
                if (text.length <= 4096 && countTokens(embedded) <= 512) {
                    result.add(DocumentChunk(document.id, document.name, blockIndex, part++, block.source, block.sheet, text, embedded))
                    require(result.size <= 20_000) { "В документе более 20 000 поисковых фрагментов" }
                    return
                }
                require(text.length > 1) { "Не удалось уложить фрагмент в контекст embedding-модели" }
                var middle = text.length / 2
                val whitespace = text.lastIndexOf(' ', middle)
                if (whitespace > middle / 2) middle = whitespace + 1
                if (middle > 0 && Character.isHighSurrogate(text[middle - 1])) middle--
                if (middle == 0) middle = 2.coerceAtMost(text.length - 1)
                require(middle in 1 until text.length) { "Некорректный Unicode-фрагмент" }
                split(text.substring(0, middle))
                // Small overlap only when both halves still make progress.
                var overlap = if (middle > 200) 40 else 0
                if (overlap > 0 && Character.isLowSurrogate(text[middle - overlap])) overlap--
                split(text.substring(middle - overlap))
            }
            split(block.text)
        }
        return result
    }

    internal fun String.safePrefix(maximum: Int): String {
        var end = minOf(length, maximum)
        if (end > 0 && Character.isHighSurrogate(this[end - 1])) end--
        return substring(0, end)
    }
}
