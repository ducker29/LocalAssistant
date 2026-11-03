package com.example.localassistant.documents

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.Locale
import org.json.JSONObject

data class SpreadsheetTable(
    val id: String,
    val document: StoredDocument,
    val sheet: String,
    val headerRow: Int,
    val headers: Map<String, String>,
    val rows: List<DocumentBlock>,
    val date1904: Boolean,
)

data class SpreadsheetFilter(val column: String, val operation: String, val value: String)
data class SpreadsheetPlan(
    val operation: String,
    val table: String,
    val column: String?,
    val filters: List<SpreadsheetFilter>,
    val groupBy: String?,
    val startRow: Int?,
    val endRow: Int?,
    val compareTable: String?,
    val compareColumn: String?,
    val key: String?,
    val compareKey: String?,
    val compareFilters: List<SpreadsheetFilter>,
)
data class SpreadsheetResult(val text: String, val documents: List<StoredDocument>)

/** Arithmetic and filtering are deterministic app code; never evaluate Excel formulas or SQL. */
object SpreadsheetCalculator {
    private val math = MathContext.DECIMAL128
    private val columnPattern = Regex("[A-Z]{1,3}")
    private val totalLabel = Regex("^(итого|всего|общий итог|total|subtotal)[ :]*$", RegexOption.IGNORE_CASE)

    fun tables(documents: List<Pair<StoredDocument, ExtractedDocument>>): List<SpreadsheetTable> {
        val result = mutableListOf<SpreadsheetTable>()
        for ((document, content) in documents) for (sheet in content.sheets) {
            val rows = content.blocks.filter { it.sheet == sheet && it.cells.isNotEmpty() }
            val header = rows.take(20).filter { it.cells.count { c -> c.type == "TEXT" && c.rawValue.isNotBlank() } >= 2 }
                .maxByOrNull { it.cells.count { c -> c.type == "TEXT" } } ?: continue
            result.add(SpreadsheetTable("T${result.size + 1}", document, sheet, rowNumber(header),
                header.cells.associate { column(it.address) to it.rawValue }, rows, content.date1904))
        }
        return result
    }

    fun parse(json: JSONObject): SpreadsheetPlan {
        val allowed = setOf("operation", "table", "column", "filters", "groupBy", "startRow", "endRow",
            "compareTable", "compareColumn", "key", "compareKey", "compareFilters")
        require(json.keys().asSequence().all { it in allowed }) { "Неизвестные поля плана расчёта" }
        fun string(name: String): String? {
            if (!json.has(name) || json.isNull(name)) return null
            require(json.get(name) is String)
            return json.getString(name).also { require(it.length <= 200) }
        }
        fun integer(name: String): Int? {
            if (!json.has(name) || json.isNull(name)) return null
            val value = json.get(name)
            require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 1..1_048_576)
            return value.toInt()
        }
        fun filters(name: String): List<SpreadsheetFilter> {
            if (!json.has(name) || json.isNull(name)) return emptyList()
            val array = json.getJSONArray(name)
            require(array.length() <= 5)
            return (0 until array.length()).map {
                val f = array.getJSONObject(it)
                require(f.keys().asSequence().toSet() == setOf("column", "op", "value"))
                require(f.get("column") is String && f.get("op") is String && f.get("value") is String)
                val col = f.getString("column")
                val op = f.getString("op")
                val value = f.getString("value")
                require(columnPattern.matches(col) && op in setOf("eq", "contains", "gt", "gte", "lt", "lte") && value.length <= 200)
                SpreadsheetFilter(col, op, value)
            }
        }
        val operation = string("operation") ?: error("Не выбрана операция")
        require(operation in setOf("sum", "average", "min", "max", "count", "lookup", "compare")) { "Неизвестная операция расчёта" }
        return SpreadsheetPlan(operation, string("table") ?: error("Не выбрана таблица"), string("column"),
            filters("filters"), string("groupBy"), integer("startRow"), integer("endRow"), string("compareTable"),
            string("compareColumn"), string("key"), string("compareKey"), filters("compareFilters"))
    }

    fun execute(plan: SpreadsheetPlan, tables: List<SpreadsheetTable>, allowCached: Boolean): SpreadsheetResult {
        val table = tables.singleOrNull { it.id == plan.table } ?: error("Неизвестная таблица")
        validateColumns(table, listOfNotNull(plan.column, plan.groupBy, plan.key) + plan.filters.map { it.column })
        val rows = select(table, plan.filters, plan.startRow, plan.endRow, allowCached)
        require(rows.isNotEmpty()) { "Нет строк, соответствующих условиям расчёта" }
        val conditions = "${table.document.name} · ${table.sheet}; заголовки: строка ${table.headerRow}; " +
            "диапазон: ${plan.startRow ?: (table.headerRow + 1)}–${plan.endRow ?: table.rows.maxOf(::rowNumber)}; " +
            "фильтры: ${plan.filters.joinToString { "${it.column} ${it.operation} ${it.value}" }.ifEmpty { "нет" }}"
        if (plan.operation == "compare") return compare(plan, table, rows, tables, conditions, allowCached)
        require(plan.compareTable == null && plan.compareColumn == null && plan.compareKey == null && plan.compareFilters.isEmpty()) { "Лишние параметры сравнения" }
        if (plan.operation == "lookup") {
            val shown = rows.take(30).joinToString("\n") { row -> "${row.source}: ${row.text.take(800)}${if (row.text.length > 800) "… (фрагмент строки)" else ""}" }
            return SpreadsheetResult("$conditions\nНайдено строк: ${rows.size}. Показано: ${minOf(30, rows.size)}.\n$shown", listOf(table.document))
        }
        require(plan.operation == "count" || plan.column != null) { "Не выбран числовой столбец" }
        val groups = if (plan.groupBy == null) mapOf("Результат" to rows) else rows.groupBy { cell(it, plan.groupBy)?.rawValue?.trim().orEmpty().ifEmpty { "[пусто]" } }
        val lines = groups.map { (key, group) ->
            if (plan.operation != "count") ensureUnits(table, group)
            if (plan.operation != "count") require(group.none { row -> row.cells.any { it.type == "TEXT" && totalLabel.matches(it.rawValue.trim()) } }) {
                "В диапазоне есть итоговые строки. Уточните startRow/endRow или фильтры, чтобы не посчитать итог дважды."
            }
            val values = if (plan.column == null || plan.operation == "count") emptyList() else group.mapNotNull { numeric(it, plan.column, allowCached) }
            val value = when (plan.operation) {
                "count" -> BigDecimal(if (plan.column == null) group.size else group.count { !cell(it, plan.column)?.rawValue.isNullOrEmpty() })
                "sum" -> { require(values.isNotEmpty()) { "Нет числовых значений" }; values.fold(BigDecimal.ZERO, BigDecimal::add) }
                "average" -> { require(values.isNotEmpty()); values.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(values.size), math) }
                "min" -> values.minOrNull() ?: error("Нет числовых значений")
                "max" -> values.maxOrNull() ?: error("Нет числовых значений")
                else -> error("Неподдерживаемая операция")
            }
            "$key: ${format(value)} (строк: ${group.size}, непустых числовых значений: ${values.size})"
        }
        val cachedNote = if (allowCached) "Сохранённые результаты формул разрешены; они могли устареть. " else "Формулы не пересчитывались. "
        return SpreadsheetResult("$conditions\nОперация: ${plan.operation}; столбец: ${plan.column ?: "все строки"}; группировка: ${plan.groupBy ?: "нет"}.\n" +
            "${cachedNote}Пустые числовые ячейки пропущены.\n" + lines.take(100).joinToString("\n") +
            if (lines.size > 100) "\nПоказано 100 из ${lines.size} вычисленных групп." else "", listOf(table.document))
    }

    private fun compare(plan: SpreadsheetPlan, left: SpreadsheetTable, rows: List<DocumentBlock>, tables: List<SpreadsheetTable>, conditions: String, cached: Boolean): SpreadsheetResult {
        require(plan.column != null && plan.key != null && plan.compareColumn != null && plan.compareKey != null && plan.groupBy == null) { "Для сравнения нужны ключ и числовой столбец обеих таблиц" }
        val right = tables.singleOrNull { it.id == plan.compareTable } ?: error("Не выбрана вторая таблица")
        validateColumns(right, listOf(plan.compareColumn, plan.compareKey) + plan.compareFilters.map { it.column })
        val otherRows = select(right, plan.compareFilters, null, null, cached)
        ensureUnits(left, rows); ensureUnits(right, otherRows)
        require(unitValues(left, rows) == unitValues(right, otherRows)) { "Единицы/валюты таблиц различаются; сравнение без конвертации невозможно" }
        fun keyed(data: List<DocumentBlock>, key: String, value: String): Map<String, Pair<BigDecimal, String>> {
            val grouped = data.groupBy { cell(it, key)?.rawValue?.trim().orEmpty() }
            require(grouped.keys.none { it.isEmpty() } && grouped.values.all { it.size == 1 }) { "Ключи пусты или повторяются. Уточните фильтры/ключ сопоставления." }
            return grouped.mapValues { (_, entries) ->
                val row = entries.single()
                (numeric(row, value, cached) ?: error("Нет значения для сравнения")) to (cell(row, value)?.address ?: error("Нет ячейки"))
            }
        }
        val a = keyed(rows, plan.key, plan.column)
        val b = keyed(otherRows, plan.compareKey, plan.compareColumn)
        val keys = (a.keys + b.keys).sorted()
        val lines = keys.take(100).map { key ->
            val first = a[key]; val second = b[key]
            if (first == null || second == null) "$key: нет соответствия в ${if (first == null) "первой" else "второй"} таблице"
            else {
                val difference = second.first - first.first
                val percent = if (first.first.signum() == 0) "не определён (база 0)" else format(difference.multiply(BigDecimal(100)).divide(first.first, math)) + "%"
                "$key: ${format(first.first)} → ${format(second.first)}; разница ${format(difference)}, изменение $percent; ${left.sheet}!${first.second} → ${right.sheet}!${second.second}"
            }
        }
        return SpreadsheetResult("$conditions\nСравнение с ${right.document.name} · ${right.sheet}, заголовки строка ${right.headerRow}; " +
            "фильтры: ${plan.compareFilters}. Ключи сравниваются точно после удаления внешних пробелов.\n" +
            "Сопоставлено ${keys.count { it in a && it in b }}; без соответствия ${keys.count { it !in a || it !in b }}.\n" +
            (if (cached) "Используются сохранённые результаты формул: они могут быть устаревшими.\n" else "") +
            lines.joinToString("\n") + if (keys.size > 100) "\nПоказано 100 из ${keys.size} ключей." else "", listOf(left.document, right.document).distinctBy { it.id })
    }

    private fun select(table: SpreadsheetTable, filters: List<SpreadsheetFilter>, start: Int?, end: Int?, cached: Boolean): List<DocumentBlock> {
        val first = start ?: table.headerRow + 1
        val last = end ?: table.rows.maxOf(::rowNumber)
        require(first > table.headerRow && last >= first) { "Диапазон должен начинаться после строки заголовков" }
        return table.rows.filter { row -> rowNumber(row) in first..last && filters.all { f ->
            val c = cell(row, f.column)
            if (c?.formula != null) require(cached && c.rawValue.isNotEmpty()) { "Фильтр использует формулу без разрешённого сохранённого результата" }
            val raw = c?.rawValue.orEmpty()
            when (f.operation) {
                "contains" -> raw.contains(f.value, ignoreCase = true)
                else -> {
                    val order = when (c?.type) {
                        "NUMBER" -> if (raw.isEmpty()) null else decimal(raw).compareTo(decimal(f.value))
                        "DATE_SERIAL", "DATE_ISO" -> if (raw.isEmpty()) null else date(c, table.date1904).compareTo(LocalDate.parse(f.value))
                        else -> if (f.operation == "eq") raw.compareTo(f.value, ignoreCase = true) else error("Сравнение больше/меньше требует числового столбца или даты")
                    }
                    order != null && when (f.operation) { "eq" -> order == 0; "gt" -> order > 0; "gte" -> order >= 0; "lt" -> order < 0; "lte" -> order <= 0; else -> false }
                }
            }
        } }
    }

    private fun numeric(row: DocumentBlock, column: String, cached: Boolean): BigDecimal? {
        val c = cell(row, column) ?: return null
        if (c.formula != null) require(cached && c.rawValue.isNotEmpty()) { "Расчёт использует формулы. Разрешите сохранённые результаты или уточните исходный столбец. Пересчёт Excel не выполняется." }
        if (c.rawValue.isEmpty()) return null
        require(c.type == "NUMBER") { "${c.address}: значение не является числом; уточните диапазон/столбец" }
        return decimal(c.rawValue)
    }

    private fun decimal(raw: String): BigDecimal {
        require(raw.length <= 200) { "Число превышает поддерживаемую точность" }
        val value = raw.filterNot { it.isWhitespace() }.replace(',', '.').toBigDecimalOrNull()
            ?: error("Некорректное число в данных или условии")
        require(value.precision() <= 50 && value.scale() in -100..100) { "Число превышает поддерживаемую точность" }
        return value
    }
    private fun date(c: DocumentCell, date1904: Boolean): LocalDate {
        if (c.type == "DATE_ISO") return LocalDate.parse(c.rawValue.take(10))
        val days = decimal(c.rawValue).toBigInteger().longValueExact()
        require(date1904 || days != 60L) { "Excel-дата 1900-02-29 не существует" }
        return (if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 31)).plusDays(days - if (!date1904 && days > 60) 1 else 0)
    }
    private fun unitValues(table: SpreadsheetTable, rows: List<DocumentBlock>): Set<String> {
        val columns = table.headers.mapNotNull { (column, label) ->
            val name = label.lowercase(Locale.ROOT)
            when {
                name.contains("валют") || name == "currency" -> column to "currency"
                name.contains("ед. изм") || name.contains("единиц") || name == "unit" -> column to "unit"
                else -> null
            }
        }
        if (columns.isEmpty()) return emptySet()
        return rows.map { row -> columns.mapNotNull { (column, kind) -> cell(row, column)?.rawValue?.trim()?.takeIf(String::isNotBlank)?.let { "$kind=$it" } }.sorted().joinToString("|") }
            .filter { it.isNotEmpty() }.toSet()
    }
    private fun ensureUnits(table: SpreadsheetTable, rows: List<DocumentBlock>) { require(unitValues(table, rows).size <= 1) { "В выборке разные валюты/единицы. Уточните фильтр или группировку." } }
    private fun validateColumns(table: SpreadsheetTable, columns: List<String>) {
        val known = table.rows.flatMap { it.cells }.map { column(it.address) }.toSet()
        require(columns.all { columnPattern.matches(it) && it in known }) { "Неизвестный столбец таблицы" }
    }
    internal fun rowNumber(row: DocumentBlock): Int = row.cells.first().address.dropWhile { it.isLetter() }.toInt()
    private fun column(address: String) = address.takeWhile { it.isLetter() }
    private fun cell(row: DocumentBlock, columnName: String) = row.cells.firstOrNull { column(it.address) == columnName }
    private fun format(value: BigDecimal) = value.stripTrailingZeros().toPlainString()
}
