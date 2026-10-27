package com.example.localassistant.calendar

import java.time.LocalDate

enum class CalendarPeriodMode { CURRENT, PREVIOUS, NEXT, LAST, UPCOMING }
enum class CalendarPeriodUnit { WEEK, MONTH }

data class CalendarWeekdayReference(val day: Int, val week: String, val explicitDate: String? = null) {
    fun encode() = "weekday:$day:$week" + (explicitDate?.let { ":$it" } ?: "")
    companion object {
        fun parse(value: String): CalendarWeekdayReference? {
            if (!value.startsWith("weekday:")) return null
            val parts = value.split(':', limit = 4)
            val day = parts.getOrNull(1)?.toIntOrNull()
            require(parts.size >= 3 && day != null && day in 1..7 && parts[2] in setOf("-1", "0", "1", "last", "upcoming", "any", "?")) { "Уточните день недели." }
            require(parts.getOrNull(3)?.startsWith("weekday:") != true) { "Уточните дату." }
            return CalendarWeekdayReference(day, parts[2], parts.getOrNull(3))
        }
    }
}

data class CalendarDateOffset(val unit: String, val amount: Int?, val backwards: Boolean = false, val anchor: LocalDate? = null) {
    fun encode() = "offset:$unit:${amount?.toString() ?: if (backwards) "-?" else "?"}" + (anchor?.let { ":$it" } ?: "")
    companion object {
        fun parse(value: String): CalendarDateOffset? {
            if (!value.startsWith("offset:")) return null
            val parts = value.split(':')
            require(parts.size in 3..4 && parts[1] in setOf("day", "week", "month")) { "Уточните относительную дату." }
            return CalendarDateOffset(parts[1], if (parts[2] in setOf("?", "-?")) null else parts[2].toIntOrNull() ?: Int.MAX_VALUE,
                backwards = parts[2].startsWith('-'), anchor = parts.getOrNull(3)?.let(LocalDate::parse))
        }
    }
}

data class CalendarPeriodReference(val mode: CalendarPeriodMode, val unit: CalendarPeriodUnit, val count: Int?, val anchor: LocalDate? = null) {
    fun encode(): String = "period:${mode.name.lowercase()}:${unit.name.lowercase()}:${count ?: "?"}" + (anchor?.let { ":$it" } ?: "")

    companion object {
        private val aliases = mapOf(
                "this_week" to (CalendarPeriodMode.CURRENT to CalendarPeriodUnit.WEEK),
                "previous_week" to (CalendarPeriodMode.PREVIOUS to CalendarPeriodUnit.WEEK),
                "next_week" to (CalendarPeriodMode.NEXT to CalendarPeriodUnit.WEEK),
                "this_month" to (CalendarPeriodMode.CURRENT to CalendarPeriodUnit.MONTH),
                "previous_month" to (CalendarPeriodMode.PREVIOUS to CalendarPeriodUnit.MONTH),
                "next_month" to (CalendarPeriodMode.NEXT to CalendarPeriodUnit.MONTH),
        )
        fun isPeriod(value: String?): Boolean = value != null && (value.startsWith("period:") || value in aliases)
        fun parse(value: String?): CalendarPeriodReference? {
            aliases[value]?.let { return CalendarPeriodReference(it.first, it.second, 1) }
            if (value == null || !value.startsWith("period:")) return null
            val parts = value.split(':')
            require(parts.size in 4..5) { "Уточните период." }
            val mode = CalendarPeriodMode.entries.firstOrNull { it.name.equals(parts[1], true) }
            val unit = CalendarPeriodUnit.entries.firstOrNull { it.name.equals(parts[2], true) }
            require(mode != null && unit != null) { "Уточните период." }
            val count = if (parts[3] == "?") null else parts[3].toIntOrNull() ?: -1
            return CalendarPeriodReference(mode, unit, count, parts.getOrNull(4)?.let(LocalDate::parse))
        }
    }
}

/** Text recognition only. CalendarTime alone owns arithmetic and exclusive bounds. */
internal object CalendarTemporalExpression {
    data class Match(val reference: String, val range: IntRange, val destination: Boolean, val period: Boolean,
                     val extraRanges: List<IntRange> = emptyList())

    private val quantities = mapOf(
        "один" to 1, "одна" to 1, "одну" to 1, "одного" to 1, "одной" to 1,
        "два" to 2, "две" to 2, "двух" to 2, "пару" to 2, "пара" to 2,
        "три" to 3, "трех" to 3, "четыре" to 4, "четырех" to 4,
        "пять" to 5, "пяти" to 5, "шесть" to 6, "шести" to 6,
        "семь" to 7, "семи" to 7, "восемь" to 8, "восьми" to 8,
        "девять" to 9, "девяти" to 9, "десять" to 10, "десяти" to 10,
        "одиннадцать" to 11, "двенадцать" to 12,
    )
    private val quantity = "\\d+|${quantities.keys.joinToString("|")}|несколько"
    private val prefix = "(?:(?:в\\s+течение|за|на|в)\\s+)?"
    private val periods = Regex(
        "\\b$prefix(?:(?:всю|весь|все)\\s+)?(эт(?:ой|у|а|от|ом|ого|и|их)|текущ[а-я]*|следующ[а-я]*|будущ[а-я]*|" +
            "предыдущ[а-я]*|прошл[а-я]*|прошедш[а-я]*|последн[а-я]*|ближайш[а-я]*)\\s+" +
            "(?:($quantity)\\s+)?(недел[а-я]*|месяц[а-я]*)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val barePeriods = Regex("\\b(на|за)\\s+(?:($quantity)\\s+)?(недел[а-я]*|месяц[а-я]*)\\b", RegexOption.IGNORE_CASE)
    private val futureOffset = Regex("\\bчерез\\s+(?:($quantity)\\s+)?(день|дн[а-я]*|недел[а-я]*|месяц[а-я]*)\\b", RegexOption.IGNORE_CASE)
    private val pastOffset = Regex("\\b(?:($quantity)\\s+)?(день|дн[а-я]*|недел[а-я]*|месяц[а-я]*)\\s+назад\\b", RegexOption.IGNORE_CASE)
    private val weekdays = Regex(
        "\\b(?:(?:на|в|во|за)\\s+)?(понедельник(?:а|у|ом|е)?|вторник(?:а|у|ом|е)?|сред[ауыое]|средой|" +
            "четверг(?:а|у|ом|е)?|пятниц(?:а|у|ы|е|ей)|суббот(?:а|у|ы|е|ой)|воскресень(?:е|я|ю|ем|и))\\b", RegexOption.IGNORE_CASE)
    private val isoDate = Regex("\\b(?:(?:на|за|в)\\s+)?(\\d{4}-\\d{2}-\\d{2})\\b", RegexOption.IGNORE_CASE)
    private val monthDay = Regex("\\b(?:(?:на|за|в)\\s+)?(\\d{1,2})(?:-?(?:е|ое|го))?\\s*числ[оае]\\b", RegexOption.IGNORE_CASE)
    private val monthNames = listOf("январ", "феврал", "март", "апрел", "мая", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")
    private val namedDate = Regex("\\b(?:(?:на|за|в)\\s+)?(\\d{1,2})(?:-?го)?\\s+(${monthNames.joinToString("|")})[а-я]*(?:\\s+(\\d{4}))?\\b", RegexOption.IGNORE_CASE)
    // Long words first and word boundaries: послезавтра is never parsed as завтра.
    private val days = Regex(
        "\\b$prefix(позавчера[а-я]*|послезавтра[а-я]*|вчера[а-я]*|сегодня[а-я]*|завтра[а-я]*)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun find(text: String): Match? {
        val normalized = text.lowercase(java.util.Locale.forLanguageTag("ru")).replace('ё', 'е')
        val absolute = listOfNotNull(
            isoDate.find(normalized)?.let { Match(it.groupValues[1], it.range, it.value.startsWith("на "), false) },
            monthDay.find(normalized)?.let { Match("day:${it.groupValues[1]}", it.range, it.value.startsWith("на "), false) },
            namedDate.find(normalized)?.let {
                val month = monthNames.indexOf(it.groupValues[2]) + 1
                val year = it.groupValues[3]
                val ref = if (year.isEmpty()) "month_day:$month:${it.groupValues[1]}"
                    else "$year-${month.toString().padStart(2, '0')}-${it.groupValues[1].padStart(2, '0')}"
                Match(ref, it.range, it.value.startsWith("на "), false)
            },
        ).minByOrNull { it.range.first }
        val period = periods.find(normalized)?.let {
            val adjective = it.groupValues[1]
            val quantity = it.groupValues[2]
            val unit = if (it.groupValues[3].startsWith("недел")) CalendarPeriodUnit.WEEK else CalendarPeriodUnit.MONTH
            val mode = when {
                adjective.startsWith("следующ") || adjective.startsWith("будущ") -> CalendarPeriodMode.NEXT
                adjective.startsWith("предыдущ") || adjective.startsWith("прошл") || adjective.startsWith("прошедш") -> CalendarPeriodMode.PREVIOUS
                adjective.startsWith("последн") -> CalendarPeriodMode.LAST
                adjective.startsWith("ближайш") -> CalendarPeriodMode.UPCOMING
                else -> CalendarPeriodMode.CURRENT
            }
            val pluralWithoutCount = quantity.isEmpty() &&
                (listOf("ие", "ые", "их", "ых", "ими", "ыми").any { adjective.endsWith(it) } || adjective in setOf("эти", "этих"))
            val count = count(quantity, pluralWithoutCount)
            Match(CalendarPeriodReference(mode, unit, count).encode(), it.range,
                it.value.startsWith("на "), period = true)
        }
        val bare = barePeriods.find(normalized)?.let {
            val unit = if (it.groupValues[3].startsWith("недел")) CalendarPeriodUnit.WEEK else CalendarPeriodUnit.MONTH
            Match(CalendarPeriodReference(
                if (it.groupValues[1] == "за") CalendarPeriodMode.LAST else CalendarPeriodMode.UPCOMING,
                unit, count(it.groupValues[2], pluralUnit(it.groupValues[3])),
            ).encode(), it.range, destination = false, period = true)
        }
        fun offset(match: MatchResult, negative: Boolean): Match {
            val unitWord = match.groupValues[2]
            val unit = when { unitWord.startsWith("недел") -> "week"; unitWord.startsWith("месяц") -> "month"; else -> "day" }
            val amount = count(match.groupValues[1], pluralUnit(unitWord))?.let { if (negative) -it else it }
            return Match(CalendarDateOffset(unit, amount, backwards = negative).encode(), match.range, destination = !negative, period = false)
        }
        val future = futureOffset.find(normalized)?.let { offset(it, false) }
        val past = pastOffset.find(normalized)?.let { offset(it, true) }
        val day = days.find(normalized)?.let {
            val word = it.groupValues[1]
            val ref = when {
                word.startsWith("позавчера") -> "day_before_yesterday"
                word.startsWith("послезавтра") -> "day_after_tomorrow"
                word.startsWith("вчера") -> "yesterday"
                word.startsWith("сегодня") -> "today"
                else -> "tomorrow"
            }
            Match(ref, it.range, it.value.startsWith("на "), period = false)
        }
        val weekday = weekdays.findAll(normalized).firstOrNull { candidate ->
            if (!candidate.groupValues[1].startsWith("сред")) true else {
                val prefix = Regex("^(?:на|в|во|за)\\s+").containsMatchIn(candidate.value)
                val alone = normalized.trimEnd('?', '.', '!') == candidate.value
                val adjacent = absolute?.let { date ->
                    val between = when {
                        date.range.last < candidate.range.first -> normalized.substring(date.range.last + 1, candidate.range.first)
                        candidate.range.last < date.range.first -> normalized.substring(candidate.range.last + 1, date.range.first)
                        else -> ""
                    }
                    between.all { it.isWhitespace() || it in ",()—-" }
                } ?: false
                prefix || alone || adjacent
            }
        }?.let {
            val word = it.groupValues[1]
            val number = when {
                word.startsWith("понедельник") -> 1; word.startsWith("вторник") -> 2
                word.startsWith("сред") -> 3; word.startsWith("четверг") -> 4
                word.startsWith("пятниц") -> 5; word.startsWith("суббот") -> 6
                else -> 7
            }
            val qualified = period?.let { match -> CalendarPeriodReference.parse(match.reference) }
            val week = when {
                qualified == null -> if (absolute != null) "any" else "0"
                qualified.unit != CalendarPeriodUnit.WEEK || qualified.count != 1 -> "?"
                qualified.mode == CalendarPeriodMode.CURRENT -> "0"
                qualified.mode == CalendarPeriodMode.PREVIOUS -> "-1"
                qualified.mode == CalendarPeriodMode.NEXT -> "1"
                qualified.mode == CalendarPeriodMode.LAST -> "last"
                else -> "upcoming"
            }
            Match(CalendarWeekdayReference(number, week, absolute?.reference).encode(), it.range,
                it.value.startsWith("на "), false, listOfNotNull(period?.range, absolute?.range))
        }
        // Weekday and its week/date are one date, even when event words lie between them.
        if (weekday != null) return weekday
        return listOfNotNull(absolute, period, bare, future, past, day).minByOrNull { it.range.first }
    }

    private fun pluralUnit(value: String) = value in setOf("недели", "недель", "неделях", "месяца", "месяцев", "месяцы", "дня", "дней")
    private fun count(value: String, plural: Boolean): Int? = when {
        value == "несколько" -> null
        value.isNotEmpty() -> quantities[value] ?: value.toIntOrNull() ?: Int.MAX_VALUE
        plural -> null
        else -> 1
    }

    /** A count reply changes only the unresolved quantity, retaining the stored direction/unit. */
    fun quantityReply(text: String, reference: String?): Match? {
        if (reference == null) return null
        val period = CalendarPeriodReference.parse(reference)
        val offset = CalendarDateOffset.parse(reference)
        if ((period == null || period.count != null) && (offset == null || offset.amount != null)) return null
        val normalized = text.trim().lowercase(java.util.Locale.forLanguageTag("ru")).replace('ё', 'е')
        val reply = Regex("(?:количество\\s*[:—-]?\\s*)?($quantity)(?:\\s+(день|дн[а-я]*|недел[а-я]*|месяц[а-я]*))?[.!?]?")
            .matchEntire(normalized) ?: return null
        val amount = count(reply.groupValues[1], false) ?: return null
        val unitWord = reply.groupValues[2]
        val unit = when {
            unitWord.startsWith("недел") -> "week"
            unitWord.startsWith("месяц") -> "month"
            unitWord.isNotEmpty() -> "day"
            period != null -> period.unit.name.lowercase()
            else -> requireNotNull(offset).unit
        }
        val value = if (period != null) {
            if (unit == "day") return null
            period.copy(unit = if (unit == "week") CalendarPeriodUnit.WEEK else CalendarPeriodUnit.MONTH, count = amount).encode()
        } else {
            val old = requireNotNull(offset)
            CalendarDateOffset(unit, if (old.backwards) -amount else amount, old.backwards, old.anchor).encode()
        }
        return Match(value, text.indices, destination = offset?.backwards == false, period = period != null)
    }

    fun isOnlyExpression(text: String): Boolean {
        val stripped = text.trim().replace(Regex("^а\\s+", RegexOption.IGNORE_CASE), "").trimEnd('?', '.', '!')
        val match = find(stripped) ?: return false
        val ranges = listOf(match.range) + match.extraRanges
        return stripped.indices.all { i -> stripped[i].isWhitespace() || ranges.any { i in it } }
    }
}
