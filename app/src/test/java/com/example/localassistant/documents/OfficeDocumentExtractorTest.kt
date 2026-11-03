package com.example.localassistant.documents

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class OfficeDocumentExtractorTest {
    private val temp = Files.createTempDirectory("office-reader-test").toFile()
    private val extractor = OfficeDocumentExtractor()

    @After fun cleanup() { temp.deleteRecursively() }

    @Test fun `DOCX keeps paragraph and table order but ignores drawing text`() = runTest {
        val file = archive("word/document.xml" to """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
              <w:p><w:r><w:t>До таблицы</w:t><w:drawing><w:t>НЕ ИЗВЛЕКАТЬ</w:t></w:drawing></w:r></w:p>
              <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Товар</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>42</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
              <w:p><w:r><w:t>После таблицы</w:t></w:r></w:p>
            </w:body></w:document>
        """.trimIndent())
        val result = extractor.extract(file, DocumentFormat.DOCX)
        assertEquals(listOf("До таблицы", "Товар | 42", "После таблицы"), result.blocks.map { it.text })
        assertTrue(result.blocks[1].source.contains("Таблица 1"))
        assertFalse(result.blocks.any { it.text.contains("НЕ ИЗВЛЕКАТЬ") })
    }

    @Test fun `XLSX preserves coordinates types cached formulas rich strings and dates`() = runTest {
        val file = archive(
            "xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to relationships,
            "xl/styles.xml" to "<styleSheet><cellXfs><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/></cellXfs></styleSheet>",
            "xl/sharedStrings.xml" to "<sst><si><r><t>Название </t></r><r><t>товара</t></r></si></sst>",
            "xl/worksheets/sheet1.xml" to """
                <worksheet><sheetData><row r="7">
                  <c r="A7" t="s"><v>0</v></c>
                  <c r="C7"><v>12.50</v></c>
                  <c r="D7"><f>C7*2</f><v>25</v></c>
                  <c r="E7" t="inlineStr"><is><t>Текст</t></is></c>
                  <c r="F7" t="b"><v>1</v></c>
                  <c r="G7" s="1"><v>61</v></c>
                  <c r="H7"><f>SUM(C7:D7)</f></c>
                </row></sheetData></worksheet>
            """.trimIndent(),
        )
        val result = extractor.extract(file, DocumentFormat.XLSX)
        assertEquals(listOf("Лист 1"), result.sheets)
        val row = result.blocks.single()
        assertEquals("Лист 1", row.sheet)
        assertEquals(listOf("A7", "C7", "D7", "E7", "F7", "G7", "H7"), row.cells.map { it.address })
        assertEquals("Название товара", row.cells[0].rawValue)
        assertEquals("12.50", row.cells[1].rawValue)
        assertEquals("NUMBER", row.cells[1].type)
        assertEquals("C7*2", row.cells[2].formula)
        assertEquals("25", row.cells[2].rawValue)
        assertEquals("TEXT", row.cells[3].type)
        assertEquals("BOOLEAN", row.cells[4].type)
        assertEquals("DATE_SERIAL", row.cells[5].type)
        assertTrue(row.text.contains("1900-03-01"))
        assertTrue(result.warnings.any { it.contains("отсутствуют у 1") })
    }

    @Test fun `DTD is rejected`() = runTest {
        val file = archive("word/document.xml" to "<!DOCTYPE document [<!ENTITY x 'value'>]><document>&x;</document>")
        expectFailure(file, DocumentFormat.DOCX)
    }

    @Test fun `wrong package does not produce a successful empty document`() = runTest {
        expectFailure(archive("other.xml" to "<root/>"), DocumentFormat.XLSX)
    }

    @Test fun `worksheet relationship cannot escape package worksheet directory`() = runTest {
        val file = archive("xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to relationships.replace("worksheets/sheet1.xml", "../../outside.xml"))
        expectFailure(file, DocumentFormat.XLSX)
    }

    @Test fun `local examples extract without logging private content`() = runTest {
        val directory = listOf(File("../doc_examples"), File("doc_examples")).firstOrNull { it.isDirectory }
        assumeTrue("Local fixtures are optional and excluded from Git", directory != null)
        val examples = directory?.listFiles().orEmpty().filter { it.extension.lowercase() in setOf("docx", "xlsx") }
        assertEquals(4, examples.size)
        val results = examples.map { file ->
            val format = DocumentFormat.valueOf(file.extension.uppercase())
            val content = try { extractor.extract(file, format) }
            catch (e: DocumentReadException) { throw AssertionError("$format: ${e.diagnosticCode ?: e.message}") }
            format to content
        }
        assertEquals(2, results.count { it.first == DocumentFormat.DOCX && it.second.blocks.isNotEmpty() })
        val workbooks = results.filter { it.first == DocumentFormat.XLSX }.map { it.second }
        assertEquals(listOf(3, 7), workbooks.map { it.sheets.size }.sorted())
        assertEquals(2184, workbooks.sumOf { book -> book.blocks.sumOf { row -> row.cells.count { it.formula != null } } })
        assertTrue(workbooks.all { it.blocks.size > 100 })
    }

    private suspend fun expectFailure(file: File, format: DocumentFormat) {
        try {
            extractor.extract(file, format)
            throw AssertionError("Expected a document error")
        } catch (_: DocumentReadException) { /* Expected; never print document content. */ }
    }

    private fun archive(vararg entries: Pair<String, String>): File {
        val file = File(temp, "fixture-${temp.listFiles().orEmpty().size}.zip")
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (path, content) ->
                output.putNextEntry(ZipEntry(path))
                output.write(content.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
        return file
    }

    private val workbook = """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Лист 1" r:id="rId1"/></sheets></workbook>"""
    private val relationships = """<Relationships><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
}
