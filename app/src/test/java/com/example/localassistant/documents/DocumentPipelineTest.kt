package com.example.localassistant.documents

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentPipelineTest {
    @Test fun `chunking respects oracle budget preserves sources and Unicode`() = runTest {
        val original = "Договор 📄 и срок оплаты. ".repeat(180)
        val chunks = DocumentChunks.build(document(), ExtractedDocument(listOf(DocumentBlock("Абзац 1", original)))) {
            it.codePointCount(0, it.length) + 2
        }
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.embeddingText.codePointCount(0, it.embeddingText.length) + 2 <= 512 })
        assertTrue(chunks.all { it.blockIndex == 0 && it.documentId == document().id && it.source == "Абзац 1" })
        assertTrue(chunks.last().text.endsWith("срок оплаты. "))
        assertEquals(chunks.size, chunks.map { it.key }.distinct().size)
        chunks.forEach { chunk ->
            chunk.text.forEachIndexed { index, c ->
                if (Character.isHighSurrogate(c)) assertTrue(index + 1 < chunk.text.length && Character.isLowSurrogate(chunk.text[index + 1]))
                if (Character.isLowSurrogate(c)) assertTrue(index > 0 && Character.isHighSurrogate(chunk.text[index - 1]))
            }
        }
    }

    @Test fun `XLSX chunk contains sheet headers and exact cell coordinates`() = runTest {
        val content = ExtractedDocument(listOf(
            row(1, "Товар", "Цена"), row(2, "Насос", "18500", numericB = true),
        ), sheets = listOf("Прайс"))
        val chunks = DocumentChunks.build(document(), content) { it.length / 3 + 2 }
        val data = chunks.single { it.blockIndex == 1 }
        assertTrue(data.embeddingText.contains("Цена"))
        assertTrue(data.embeddingText.contains("B2: 18500"))
        assertEquals("Прайс", data.sheet)
    }

    @Test fun `hybrid retrieval retains an exact article even against semantic competitor`() {
        val exact = chunk("AB-1042: насос, цена 18500", 0)
        val other = chunk("Срок оплаты поставки", 1)
        val results = HybridDocumentSearch.search("AB-1042", vector(0), listOf(IndexedChunk(exact, vector(1)), IndexedChunk(other, vector(0))))
        assertEquals(exact.key, results.first().chunk.key)
        assertTrue(results.first().lexical)
    }

    @Test fun `RAG rejects invented source IDs and unsupported schema`() {
        val source = chunk("Гарантия 24 месяца", 0)
        expectFailure { DocumentLlm.parseAnswer(JSONObject("""{"notFound":false,"answer":"Ответ","sources":["S99"]}"""), listOf(source)) }
        expectFailure { DocumentLlm.parseAnswer(JSONObject("""{"notFound":false,"answer":"Ответ [S2]","sources":["S1"]}"""), listOf(source)) }
        val valid = DocumentLlm.parseAnswer(JSONObject("""{"notFound":false,"answer":"Гарантия 24 месяца","sources":["S1"]}"""), listOf(source))
        assertEquals(source, valid.sources.single())
        val missing = DocumentLlm.parseAnswer(JSONObject("""{"notFound":true,"answer":"","sources":[]}"""), listOf(source))
        assertTrue(missing.sources.isEmpty())
    }

    @Test fun `decimal sum filters and grouping use actual rows not retrieval top K`() {
        val tables = tables(listOf(row(1, "Товар", "Цена"), row(2, "A", "0.1", true), row(3, "A", "0.2", true), row(4, "B", "10", true)))
        val result = calculate("""{"operation":"sum","table":"T1","column":"B","groupBy":"A","filters":[{"column":"B","op":"lt","value":"1"}]}""", tables)
        assertTrue(result.text.contains("A: 0.3"))
        assertFalse(result.text.contains("B: 10"))
        assertTrue(result.text.contains("строк: 2"))
    }

    @Test fun `count supports nonnumeric column and formula use is explicit`() {
        val plain = tables(listOf(row(1, "Товар", "Цена"), row(2, "A", "10", true), row(3, "B", "20", true)))
        assertTrue(calculate("""{"operation":"count","table":"T1","column":"A"}""", plain).text.contains("Результат: 2"))
        val formula = row(2, "A", "30", true).copy(cells = listOf(DocumentCell("A2", "TEXT", "A"), DocumentCell("B2", "NUMBER", "30", "10+20")))
        val data = tables(listOf(row(1, "Товар", "Цена"), formula))
        val plan = SpreadsheetCalculator.parse(JSONObject("""{"operation":"sum","table":"T1","column":"B"}"""))
        expectFailure { SpreadsheetCalculator.execute(plan, data, false) }
        assertTrue(SpreadsheetCalculator.execute(plan, data, true).text.contains("Результат: 30"))
    }

    @Test fun `mixed currencies and duplicate compare keys are rejected`() {
        fun currency(row: DocumentBlock, value: String) = row.copy(cells = row.cells + DocumentCell("C${SpreadsheetCalculator.rowNumber(row)}", "TEXT", value))
        val mixed = tables(listOf(currency(row(1, "Товар", "Цена"), "Валюта"), currency(row(2, "A", "10", true), "RUB"), currency(row(3, "B", "20", true), "USD")))
        expectFailure { calculate("""{"operation":"sum","table":"T1","column":"B"}""", mixed) }
        val left = listOf(row(1, "Ключ", "Цена"), row(2, "A", "10", true), row(3, "A", "20", true))
        val right = listOf(row(1, "Ключ", "Цена"), row(2, "A", "12", true))
        val data = SpreadsheetCalculator.tables(listOf(document() to ExtractedDocument(left, listOf("Прайс")), document("b") to ExtractedDocument(right, listOf("Прайс"))))
        expectFailure { calculate(comparePlan, data) }
    }

    @Test fun `cross workbook comparison reports exact difference percent and missing keys`() {
        val left = listOf(row(1, "Ключ", "Цена"), row(2, "A", "10", true), row(3, "B", "0", true))
        val right = listOf(row(1, "Ключ", "Цена"), row(2, "A", "12", true), row(3, "C", "20", true), row(4, "B", "5", true))
        val data = SpreadsheetCalculator.tables(listOf(document() to ExtractedDocument(left, listOf("Прайс")), document("b") to ExtractedDocument(right, listOf("Прайс"))))
        val result = calculate(comparePlan, data)
        assertTrue(result.text.contains("разница 2, изменение 20%"))
        assertTrue(result.text.contains("не определён (база 0)"))
        assertTrue(result.text.contains("без соответствия 1"))
        assertEquals(2, result.documents.size)
    }

    @Test fun `dates use workbook 1904 system and plans cannot execute unknown operations`() {
        val dateRow = row(2, "A", "10", true).copy(cells = listOf(DocumentCell("A2", "DATE_SERIAL", "0"), DocumentCell("B2", "NUMBER", "10")))
        val data = SpreadsheetCalculator.tables(listOf(document() to ExtractedDocument(listOf(row(1, "Дата", "Цена"), dateRow), listOf("Прайс"), date1904 = true)))
        assertTrue(calculate("""{"operation":"sum","table":"T1","column":"B","filters":[{"column":"A","op":"eq","value":"1904-01-01"}]}""", data).text.contains("Результат: 10"))
        expectFailure { SpreadsheetCalculator.parse(JSONObject("""{"operation":"sql","table":"T1","code":"DELETE"}""")) }
        expectFailure { calculate("""{"operation":"sum","table":"T1","column":"Z"}""", data) }
    }

    @Test fun `subtotal rows are not silently counted twice`() {
        val data = tables(listOf(row(1, "Товар", "Цена"), row(2, "A", "10", true), row(3, "Итого", "10", true)))
        expectFailure { calculate("""{"operation":"sum","table":"T1","column":"B"}""", data) }
        assertTrue(calculate("""{"operation":"sum","table":"T1","column":"B","endRow":2}""", data).text.contains("Результат: 10"))
    }

    private fun document(letter: String = "a") = StoredDocument(letter.repeat(64), "Таблица.xlsx", DocumentFormat.XLSX, 100, 0, DocumentStatus.READY)
    private fun row(number: Int, a: String, b: String, numericB: Boolean = false) = DocumentBlock("Строка $number", "A$number: $a\nB$number: $b", "Прайс",
        listOf(DocumentCell("A$number", "TEXT", a), DocumentCell("B$number", if (numericB) "NUMBER" else "TEXT", b)))
    private fun tables(rows: List<DocumentBlock>) = SpreadsheetCalculator.tables(listOf(document() to ExtractedDocument(rows, listOf("Прайс"))))
    private fun calculate(json: String, tables: List<SpreadsheetTable>) = SpreadsheetCalculator.execute(SpreadsheetCalculator.parse(JSONObject(json)), tables, false)
    private fun vector(dimension: Int) = FloatArray(384).also { it[dimension] = 1f }
    private fun chunk(text: String, index: Int) = DocumentChunk("a".repeat(64), "Файл.docx", index, 0, "Абзац $index", null, text, text)
    private fun expectFailure(action: () -> Unit) { try { action(); throw AssertionError("Expected validation failure") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } }
    private val comparePlan = """{"operation":"compare","table":"T1","column":"B","key":"A","compareTable":"T2","compareColumn":"B","compareKey":"A"}"""
}
