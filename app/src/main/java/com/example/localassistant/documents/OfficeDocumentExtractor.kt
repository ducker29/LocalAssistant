package com.example.localassistant.documents

import java.io.File
import java.io.FilterInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.ensureActive
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.ext.DefaultHandler2
import kotlin.coroutines.CoroutineContext

/** Bounded OOXML reader. Media, macros, external relationships and embedded objects are never opened. */
class OfficeDocumentExtractor {
    suspend fun extract(file: File, format: DocumentFormat): ExtractedDocument {
        val context = kotlin.coroutines.coroutineContext
        context.ensureActive()
        return try {
            ZipFile(file).use { zip ->
                val reader = PackageReader(zip, context)
                when (format) {
                    DocumentFormat.DOCX -> readWord(reader)
                    DocumentFormat.XLSX -> readWorkbook(reader)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: DocumentReadException) {
            throw e
        } catch (e: Exception) {
            val nested = (e as? org.xml.sax.SAXException)?.exception
            if (nested is DocumentReadException) throw nested
            val location = e.stackTrace.firstOrNull()
            throw DocumentReadException(
                "Не удалось прочитать документ. Возможно, он повреждён, зашифрован или имеет другой формат.",
                "${e.javaClass.simpleName}:${location?.className}:${location?.lineNumber}" +
                    (nested?.let { ":${it.javaClass.simpleName}:${it.stackTrace.firstOrNull()?.lineNumber}" } ?: ""),
            )
        }
    }

    private fun readWord(reader: PackageReader): ExtractedDocument {
        val blocks = mutableListOf<DocumentBlock>()
        val text = StringBuilder()
        val row = mutableListOf<String>()
        var paragraph = 0
        var table = 0
        var tableDepth = 0
        var rowNumber = 0
        var ignoredDepth = 0
        var inText = false
        var inCell = false
        var heading = false
        fun flushParagraph() {
            val value = text.toString().trim()
            text.setLength(0)
            if (inCell) {
                if (value.isNotEmpty()) row.add(value)
            } else if (value.isNotEmpty()) {
                paragraph++
                blocks.add(DocumentBlock("${if (heading) "Заголовок" else "Абзац"} $paragraph", value))
            }
            heading = false
        }
        reader.xml("word/document.xml", start = { name, attrs ->
            if (ignoredDepth > 0) ignoredDepth++
            else when (name) {
                "drawing", "pict", "object" -> ignoredDepth = 1
                "tbl" -> { if (tableDepth == 0) { table++; rowNumber = 0 }; tableDepth++ }
                "tr" -> if (tableDepth == 1) { row.clear(); rowNumber++ }
                "tc" -> if (tableDepth == 1) inCell = true
                "pStyle" -> heading = attrs.value("val").orEmpty().contains("heading", ignoreCase = true)
                "t" -> inText = true
                "tab" -> text.append('\t')
                "br", "cr" -> text.append('\n')
            }
        }, text = { value -> if (ignoredDepth == 0 && inText) text.append(value) }, end = { name ->
            if (ignoredDepth > 0) ignoredDepth--
            else when (name) {
                "t" -> inText = false
                "p" -> flushParagraph()
                "tc" -> if (tableDepth == 1) { row.add("\u0000"); inCell = false }
                "tr" -> if (tableDepth == 1) {
                    val cells = row.joinToString("\n").split("\u0000").map { it.trim() }.dropLastWhile { it.isEmpty() }
                    if (cells.any { it.isNotBlank() }) blocks.add(DocumentBlock(
                        "Таблица $table · строка $rowNumber", cells.joinToString(" | "),
                    ))
                }
                "tbl" -> tableDepth--
            }
        })
        return ExtractedDocument(blocks, warnings = listOf(
            "Изображения и встроенные объекты пропущены. Обработан основной текст документа; колонтитулы, сноски и комментарии не включены.",
        ))
    }

    private fun readWorkbook(reader: PackageReader): ExtractedDocument {
        val shared = mutableListOf<String>()
        if (reader.has("xl/sharedStrings.xml")) {
            val value = StringBuilder()
            var inText = false
            var phoneticDepth = 0
            reader.xml("xl/sharedStrings.xml", start = { name, _ ->
                when (name) {
                    "si" -> value.setLength(0)
                    "rPh" -> phoneticDepth++
                    "t" -> inText = true
                }
            }, text = { if (inText && phoneticDepth == 0) value.append(it) }, end = { name ->
                when (name) {
                    "t" -> inText = false
                    "rPh" -> phoneticDepth--
                    "si" -> { if (shared.size >= MAX_CELLS) limit(); shared.add(value.toString()) }
                }
            })
        }
        val customFormats = mutableMapOf<Int, String>()
        val styleFormats = mutableListOf<Int>()
        if (reader.has("xl/styles.xml")) {
            var inCellFormats = false
            reader.xml("xl/styles.xml", start = { name, a ->
                when (name) {
                    "numFmt" -> a.value("numFmtId")?.toIntOrNull()?.let { customFormats[it] = a.value("formatCode").orEmpty() }
                    "cellXfs" -> inCellFormats = true
                    "xf" -> if (inCellFormats) styleFormats.add(a.value("numFmtId")?.toIntOrNull() ?: 0)
                }
            }, end = { if (it == "cellXfs") inCellFormats = false })
        }
        val relationships = mutableMapOf<String, String>()
        reader.xml("xl/_rels/workbook.xml.rels", start = { name, a ->
            if (name == "Relationship" && a.value("TargetMode") != "External" &&
                a.value("Type").orEmpty().endsWith("/worksheet")) {
                val id = a.value("Id")
                val target = a.value("Target")
                if (id != null && target != null) relationships[id] = normalizePart(target)
            }
        })
        val sheets = mutableListOf<Pair<String, String>>()
        var date1904 = false
        reader.xml("xl/workbook.xml", start = { name, a ->
            if (name == "workbookPr") date1904 = a.value("date1904") in listOf("1", "true")
            if (name == "sheet") {
                val title = a.value("name") ?: throw DocumentReadException("Лист без имени.")
                val part = relationships[a.value("id")] ?: throw DocumentReadException("Не найдены данные одного из листов.")
                sheets.add(title to part)
                if (sheets.size > 100) limit()
            }
        })
        val blocks = mutableListOf<DocumentBlock>()
        var cellCount = 0
        var formulaCount = 0
        var missingResults = 0
        for ((sheet, path) in sheets) {
            val cells = mutableListOf<DocumentCell>()
            val value = StringBuilder()
            val formula = StringBuilder()
            var address = ""
            var rowNumber = ""
            var type = ""
            var style = 0
            var captureValue = false
            var captureFormula = false
            var hasFormula = false
            var inPhonetic = false
            reader.xml(path, start = { name, a ->
                when (name) {
                    "row" -> { cells.clear(); rowNumber = a.value("r").orEmpty() }
                    "c" -> {
                        address = a.value("r") ?: throw DocumentReadException("Ячейка без координат.")
                        type = a.value("t").orEmpty(); style = a.value("s")?.toIntOrNull() ?: 0
                        value.setLength(0); formula.setLength(0); hasFormula = false
                    }
                    "rPh" -> inPhonetic = true
                    "v", "t" -> captureValue = !inPhonetic
                    "f" -> { captureFormula = true; hasFormula = true }
                }
            }, text = {
                if (captureValue) value.append(it)
                if (captureFormula) formula.append(it)
            }, end = { name ->
                when (name) {
                    "rPh" -> inPhonetic = false
                    "v", "t" -> captureValue = false
                    "f" -> captureFormula = false
                    "c" -> {
                        val raw = value.toString()
                        val resolved = if (type == "s") {
                            shared.getOrNull(raw.toIntOrNull() ?: -1)
                                ?: throw DocumentReadException("Некорректная ссылка на строку XLSX.")
                        } else raw
                        val formatId = styleFormats.getOrNull(style) ?: 0
                        val cellType = when (type) {
                            "s", "inlineStr", "str" -> "TEXT"
                            "b" -> "BOOLEAN"
                            "e" -> "ERROR"
                            "d" -> "DATE_ISO"
                            else -> if (isDateFormat(formatId, customFormats[formatId])) "DATE_SERIAL" else "NUMBER"
                        }
                        if (hasFormula) { formulaCount++; if (raw.isEmpty()) missingResults++ }
                        if (resolved.isNotEmpty() || hasFormula) {
                            if (++cellCount > MAX_CELLS) limit()
                            cells.add(DocumentCell(
                                address, cellType, resolved,
                                if (hasFormula) formula.toString().ifBlank { "[общая формула — см. оригинал]" } else null,
                            ))
                        }
                    }
                    "row" -> if (cells.isNotEmpty()) {
                        val display = cells.joinToString("\n") { cell ->
                            val shown = when (cell.type) {
                                "BOOLEAN" -> if (cell.rawValue == "1") "Да" else "Нет"
                                "DATE_SERIAL" -> displayExcelDate(cell.rawValue, date1904)
                                else -> cell.rawValue
                            }
                            "${cell.address}: ${shown.ifEmpty { "нет сохранённого значения" }}" +
                                if (cell.formula != null) " (результат формулы)" else ""
                        }
                        blocks.add(DocumentBlock("Строка $rowNumber", display, sheet, cells.toList()))
                    }
                }
            })
        }
        val warnings = mutableListOf(
            "Скрытые листы и строки включены. Пустые ячейки не показаны; объединённые ячейки представлены исходной ячейкой. Числа показаны без оформления Excel.",
            "Система дат: ${if (date1904) "1904" else "1900"}. Исходные значения и координаты сохранены.",
        )
        if (formulaCount > 0) warnings.add("Формулы не пересчитывались: $formulaCount ячеек. Сохранённые результаты могут быть устаревшими; отсутствуют у $missingResults ячеек.")
        return ExtractedDocument(blocks, sheets.map { it.first }, warnings, date1904)
    }

    private fun normalizePart(target: String): String {
        val parts = mutableListOf<String>()
        val relative = if (target.startsWith('/')) target.drop(1) else "xl/$target"
        for (part in relative.split('/')) when (part) {
            "", "." -> Unit
            ".." -> { if (parts.isEmpty()) throw DocumentReadException("Некорректный путь листа."); parts.removeAt(parts.lastIndex) }
            else -> parts.add(part)
        }
        val result = parts.joinToString("/")
        if (!result.startsWith("xl/worksheets/")) throw DocumentReadException("Неподдерживаемый путь листа.")
        return result
    }

    private fun isDateFormat(id: Int, custom: String?): Boolean {
        if (id in 14..22 || id in 27..36 || id in 45..47 || id in 50..58) return true
        val code = custom?.lowercase()?.replace(Regex("\"[^\"]*\"|\\\\.|\\[[^]]*]"), "") ?: return false
        return code.any { it in "ymdhs" }
    }

    private fun displayExcelDate(raw: String, date1904: Boolean): String {
        val serial = raw.toBigDecimalOrNull() ?: return raw
        val days = try { serial.toLong() } catch (_: ArithmeticException) { return raw }
        if (!date1904 && days == 60L) return "1900-02-29 (особенность Excel; исходное: $raw)"
        return try {
            val base = if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 31)
            val date = base.plusDays(days - if (!date1904 && days > 60) 1 else 0)
            if (serial.remainder(BigDecimal.ONE).signum() == 0) date.toString()
            else "$date (доля суток/исходное: $raw)"
        } catch (_: Exception) { raw }
    }

    private class PackageReader(private val zip: ZipFile, private val context: CoroutineContext) {
        private var bytes = 0L
        private var chars = 0L
        fun has(path: String) = zip.getEntry(path) != null
        fun xml(
            path: String,
            start: (String, Attributes) -> Unit = { _, _ -> },
            text: (String) -> Unit = {},
            end: (String) -> Unit = {},
        ) {
            context.ensureActive()
            val entry = zip.getEntry(path) ?: throw DocumentReadException("В документе отсутствует обязательная часть.")
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            zip.getInputStream(entry).use { source ->
                val bounded = object : FilterInputStream(source) {
                    override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { if (it > 0) count(it) }
                    private fun count(n: Int) { context.ensureActive(); bytes += n; if (bytes > 64L * 1024 * 1024) limit() }
                }
                factory.newSAXParser().xmlReader.apply {
                    entityResolver = org.xml.sax.EntityResolver { _, _ -> throw DocumentReadException("Внешние XML-ссылки не поддерживаются.") }
                    val handler = object : DefaultHandler2() {
                        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                            throw DocumentReadException("DTD в документах не поддерживается.")
                        }
                        override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes) {
                            context.ensureActive(); start(localName, attributes)
                        }
                        override fun endElement(uri: String?, localName: String, qName: String?) { end(localName) }
                        override fun characters(ch: CharArray, offset: Int, length: Int) {
                            chars += length; if (chars > 4_000_000) limit()
                            text(String(ch, offset, length))
                        }
                    }
                    contentHandler = handler
                    errorHandler = handler
                    setProperty("http://xml.org/sax/properties/lexical-handler", handler)
                    parse(InputSource(bounded))
                }
            }
        }
    }

    companion object {
        private const val MAX_CELLS = 100_000
        private fun limit(): Nothing = throw DocumentReadException("Документ превышает лимит обработки: 100 000 ячеек, 4 млн символов XML-текста или 64 МБ распакованного XML.")
        private fun Attributes.value(name: String): String? = (0 until length).firstOrNull { getLocalName(it) == name }?.let { getValue(it) }
    }
}
