package com.example.localassistant.calendar

/** Separates target words and literal new title before date/time or command interpretation. */
internal object CalendarRenameExpression {
    data class Match(
        val source: String,
        val title: String?,
        val labelRange: IntRange?,
        val valueRange: IntRange,
        val ambiguous: Boolean = false,
    )

    fun find(text: String, quoted: List<IntRange>): Match? {
        val direct = Regex("^(?:новое\\s+)?название\\s*[:—-]\\s*", RegexOption.IGNORE_CASE).find(text)
        if (direct != null) return value(text, "", direct.range, direct.range.last + 1, direct.range.first)
        val command = calendarRegex(
            "^(?:(?:пожалуйста|можешь|можете|прошу|хочу|нужно|надо|давай)\\s*,?\\s*)*" +
                "(изменить|измени(?:те)?|поменяй(?:те)?|переименуй(?:те)?|назови)\\b\\s*", RegexOption.IGNORE_CASE,
        ).find(text) ?: return null
        val verb = command.groupValues[1].lowercase()
        var bodyStart = command.range.last + 1
        var label: IntRange? = null
        val field = calendarRegex("названи[ея]\\b\\s*", RegexOption.IGNORE_CASE).find(text, bodyStart)?.takeIf { it.range.first == bodyStart }
        if (field != null) {
            label = field.range
            bodyStart = field.range.last + 1
        } else if (verb.startsWith("измен") || verb.startsWith("помен")) return null
        val body = text.substring(bodyStart)
        val punctuation = Regex("^[:—-]\\s*").find(body)
        if (punctuation != null) return value(text, "", label, bodyStart + punctuation.range.last + 1, bodyStart)
        val maskedBody = body.mapIndexed { i, c -> if (quoted.any { bodyStart + i in it }) ' ' else c }.joinToString("")
        val separatorPattern = if (label != null) "\\bна\\b\\s*" else "\\b(?:на|в)\\b\\s*"
        val separators = calendarRegex(separatorPattern, RegexOption.IGNORE_CASE).findAll(body)
            .filter { match -> quoted.none { bodyStart + match.range.first in it } }.toList()
        val eligible = separators.filter { candidate ->
            val before = CalendarTemporalExpression.find(maskedBody.substring(0, candidate.range.first))
            val after = CalendarTemporalExpression.find(maskedBody.substring(candidate.range.first))
            val numericDate = after?.reference?.let { it.startsWith("day:") || it.startsWith("month_day:") || Regex("^\\d{4}-").containsMatchIn(it) } == true
            val dateQualifier = body.substring(0, candidate.range.first).isNotBlank() && before == null && after?.range?.first == 0 && numericDate &&
                separators.any { it.range.first > candidate.range.first + requireNotNull(after).range.last }
            !dateQualifier
        }
        if (eligible.size > 1) return Match(body, null, label, bodyStart..text.lastIndex, ambiguous = true)
        val delimiter = eligible.singleOrNull()
        if (delimiter != null) {
            return value(text, body.substring(0, delimiter.range.first).trim(), label,
                bodyStart + delimiter.range.last + 1, bodyStart + delimiter.range.first)
        }
        return null
    }

    private fun value(text: String, source: String, label: IntRange?, from: Int, rangeFrom: Int): Match {
        val raw = text.substring(from).trim().trimEnd('?', '!', '.').trim()
        val title = when {
            raw.startsWith('«') && raw.endsWith('»') -> raw.substring(1, raw.length - 1)
            raw.startsWith('"') && raw.endsWith('"') -> raw.substring(1, raw.length - 1)
            raw.startsWith('“') && raw.endsWith('”') -> raw.substring(1, raw.length - 1)
            else -> raw
        }.takeIf { it.isNotBlank() }
        return Match(source, title, label, rangeFrom..text.lastIndex)
    }
}
