package com.example.localassistant.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Date/time helpers for the calendar tools. "Today" and "tomorrow" are always resolved in the
 * device-local zone; all external representations are RFC 3339.
 */
object CalendarTime {

    data class QueryRange(val start: ZonedDateTime, val endExclusive: ZonedDateTime)

    /** Whole local days, including across DST changes. A specific date defaults to one day. */
    fun queryRange(date: String?, days: Int?, defaultDays: Int, now: ZonedDateTime): QueryRange {
        val period = CalendarPeriodReference.parse(date)
        if (period != null) {
            val unitName = if (period.unit == CalendarPeriodUnit.WEEK) "недель" else "месяцев"
            val count = period.count ?: throw IllegalArgumentException("Уточните количество $unitName.")
            require(count in 1..90) { "Уточните количество $unitName: период не должен превышать 90 дней." }
            val today = period.anchor ?: now.toLocalDate()
            fun add(value: LocalDate, amount: Long): LocalDate = when (period.unit) {
                CalendarPeriodUnit.WEEK -> value.plusWeeks(amount)
                CalendarPeriodUnit.MONTH -> value.plusMonths(amount)
            }
            val firstCurrent = when (period.unit) {
                CalendarPeriodUnit.WEEK -> today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                CalendarPeriodUnit.MONTH -> today.withDayOfMonth(1)
            }
            val (first, end) = when (period.mode) {
                CalendarPeriodMode.CURRENT -> {
                    require(count == 1) { "Уточните начальный период: какие именно $unitName имеются в виду?" }
                    firstCurrent to add(firstCurrent, 1)
                }
                CalendarPeriodMode.PREVIOUS -> add(firstCurrent, -count.toLong()) to firstCurrent
                CalendarPeriodMode.NEXT -> add(firstCurrent, 1) to add(firstCurrent, count.toLong() + 1)
                CalendarPeriodMode.LAST -> {
                    val end = today.plusDays(1)
                    add(end, -count.toLong()) to end
                }
                CalendarPeriodMode.UPCOMING -> today to add(today, count.toLong())
            }
            return boundedRange(first, end, now.zone)
        }
        val count = days ?: if (date == null) defaultDays else 1
        require(count in CalendarToolRequest.MIN_DAYS..CalendarToolRequest.MAX_DAYS) { "Период должен составлять от 1 до 90 дней." }
        val firstDay = date?.let { resolveDate(it, now) } ?: now.toLocalDate()
        return boundedRange(firstDay, firstDay.plusDays(count.toLong()), now.zone)
    }

    private fun boundedRange(first: LocalDate, end: LocalDate, zone: ZoneId): QueryRange {
        val days = java.time.temporal.ChronoUnit.DAYS.between(first, end)
        require(days in CalendarToolRequest.MIN_DAYS.toLong()..CalendarToolRequest.MAX_DAYS.toLong()) {
            "Период должен составлять от 1 до 90 дней. Сузьте запрос."
        }
        return QueryRange(first.atStartOfDay(zone), end.atStartOfDay(zone))
    }

    /** Diagnostic vocabulary only; never exposes supplied dates or event text. */
    fun referenceCategory(reference: String?): String {
        if (reference == null) return "DEFAULT"
        CalendarPeriodReference.parse(reference)?.let { return "${it.mode.name}_${it.unit.name}" }
        CalendarDateOffset.parse(reference)?.let { return "OFFSET_${it.unit.uppercase(java.util.Locale.ROOT)}" }
        return when (reference) {
            RELATIVE_DAY_BEFORE_YESTERDAY -> "DAY_BEFORE_YESTERDAY"
            RELATIVE_YESTERDAY -> "YESTERDAY"
            RELATIVE_TODAY -> "TODAY"
            RELATIVE_TOMORROW -> "TOMORROW"
            RELATIVE_DAY_AFTER_TOMORROW -> "DAY_AFTER_TOMORROW"
            else -> "EXPLICIT_DAY"
        }
    }

    /** Freeze the origin while missing fields/quantities are clarified in later turns. */
    fun freezeReference(reference: String, now: ZonedDateTime): String {
        CalendarPeriodReference.parse(reference)?.let { return it.copy(anchor = it.anchor ?: now.toLocalDate()).encode() }
        CalendarDateOffset.parse(reference)?.let {
            val anchored = it.copy(anchor = it.anchor ?: now.toLocalDate())
            return if (anchored.amount == null) anchored.encode() else resolveDate(anchored.encode(), now).toString()
        }
        return resolveDate(reference, now).toString()
    }

    /** The RFC 3339 instant format used by the Calendar API. */
    fun rfc3339(zdt: ZonedDateTime): String =
        DateTimeFormatter.ISO_INSTANT.format(zdt.toInstant())

    /** Parse an RFC 3339 string into an [Instant]. Throws [IllegalArgumentException] on failure. */
    fun parseInstant(value: String): Instant {
        try {
            return Instant.parse(value)
        } catch (t: Exception) {
            throw IllegalArgumentException("Некорректная дата: $value", t)
        }
    }

    /** A day of the month within [year]. */
    fun date(year: Int, month: Int, day: Int): LocalDate =
        LocalDate.of(year, month, day)

    /** A day of the month; [year] omitted defaults to the current year. */
    fun date(year: Int?, month: Int, day: Int, now: ZonedDateTime): LocalDate {
        val y = year ?: now.year
        return LocalDate.of(y, month, day)
    }

    /**
     * Resolve a natural-language date reference into a calendar date.
     * Supported: [RELATIVE_TODAY], [RELATIVE_TOMORROW], and an explicit `YYYY-MM-DD` (optionally
     * with a `THH:MM` time suffix, ignored for date-only purposes).
     */
    fun resolveDate(reference: String, now: ZonedDateTime): LocalDate {
        val ref = reference.trim().lowercase()
        CalendarWeekdayReference.parse(ref)?.let {
            val day = java.time.DayOfWeek.of(it.day)
            val monday = now.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            val selected = when (it.week) {
                "-1" -> monday.minusWeeks(1).plusDays(it.day - 1L)
                "0" -> monday.plusDays(it.day - 1L)
                "1" -> monday.plusWeeks(1).plusDays(it.day - 1L)
                "last" -> now.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(day))
                "upcoming" -> now.toLocalDate().with(java.time.temporal.TemporalAdjusters.nextOrSame(day))
                "any" -> null
                else -> throw IllegalArgumentException("Уточните одну неделю или точную дату для указанного дня недели.")
            }
            val explicit = it.explicitDate?.let { date -> resolveDate(date, now) }
            if (explicit != null) {
                require(explicit.dayOfWeek == day && (selected == null || explicit == selected)) {
                    "День недели и указанная дата/неделя не совпадают. Уточните запрос."
                }
                return explicit
            }
            return requireNotNull(selected) { "Уточните дату." }
        }
        CalendarDateOffset.parse(ref)?.let {
            val amount = it.amount ?: throw IllegalArgumentException("Уточните количество единиц времени для относительной даты.")
            require(kotlin.math.abs(amount.toLong()) in 1..90) { "Уточните относительную дату: количество должно быть от 1 до 90." }
            val origin = it.anchor ?: now.toLocalDate()
            return when (it.unit) {
                "day" -> origin.plusDays(amount.toLong())
                "week" -> origin.plusWeeks(amount.toLong())
                else -> origin.plusMonths(amount.toLong())
            }
        }
        return when {
            ref.startsWith("month_day:") -> {
                val parts = ref.split(':')
                require(parts.size == 3) { "Уточните дату." }
                LocalDate.of(now.year, parts[1].toInt(), parts[2].toInt())
            }
            ref == RELATIVE_DAY_BEFORE_YESTERDAY -> now.toLocalDate().minusDays(2)
            ref == RELATIVE_YESTERDAY -> now.toLocalDate().minusDays(1)
            ref == RELATIVE_TODAY -> now.toLocalDate()
            ref == RELATIVE_TOMORROW -> now.toLocalDate().plusDays(1)
            ref == RELATIVE_DAY_AFTER_TOMORROW -> now.toLocalDate().plusDays(2)
            CalendarPeriodReference.isPeriod(ref) -> throw java.time.DateTimeException("Нужна точная дата, а не период.")
            ref.matches(Regex("(?:day:)?\\d{1,2}")) -> {
                val day = ref.substringAfter(':').toInt()
                val resolved = now.toLocalDate().withDayOfMonth(day)
                if (resolved.isBefore(now.toLocalDate())) throw java.time.DateTimeException("Уточните месяц")
                resolved
            }
            else -> {
                val datePart = ref.substringBefore('t')
                LocalDate.parse(datePart)
            }
        }
    }

    /**
     * Resolve a natural-language start time into a [ZonedDateTime]. The hour/minute come from the
     * explicit [time] string ("HH:MM") or default to [DEFAULT_START_TIME] when the caller gave a
     * date only.
     */
    fun resolveStart(
        date: LocalDate,
        time: String?,
        now: ZonedDateTime,
    ): ZonedDateTime {
        val (hour, minute) = parseHhMm(time ?: DEFAULT_START_TIME)
        val zdt = date.atTime(hour, minute).atZone(now.zone)
        // Keep relative "today/tomorrow" usable: if the moment is already in the past for a
        // "today" reference, push it one day forward so the event is not created in the past.
        val shifted = if (zdt.isBefore(now) && date == now.toLocalDate()) {
            zdt.plusDays(1)
        } else {
            zdt
        }
        return shifted
    }

    /**
     * Resolve a duration reference ("1h", "90m", "1d") or an explicit end time into the end
     * instant of an event that starts at [start].
     */
    fun resolveEnd(
        start: ZonedDateTime,
        duration: String?,
        endTime: String?,
    ): ZonedDateTime {
        if (!endTime.isNullOrBlank()) {
            val (hour, minute) = parseHhMm(endTime)
            val sameDay = start.toLocalDate().atTime(hour, minute).atZone(start.zone)
            val end = if (sameDay.isAfter(start)) sameDay else sameDay.plusDays(1)
            return end
        }
        val d = duration?.trim()?.lowercase() ?: DEFAULT_DURATION
        val amount = d.dropLast(1).toIntOrNull() ?: throw IllegalArgumentException("Некорректная длительность: $duration")
        return when (d.last()) {
            'm' -> start.plusMinutes(amount.toLong())
            'h' -> start.plusHours(amount.toLong())
            'd' -> start.plusDays(amount.toLong())
            else -> throw IllegalArgumentException("Некорректная длительность: $duration")
        }
    }

    /** All-day event start: local midnight. */
    fun allDayStart(date: LocalDate, zone: ZoneId): ZonedDateTime =
        date.atStartOfDay(zone)

    /** All-day event end: local midnight of the following day. */
    fun allDayEnd(date: LocalDate, zone: ZoneId): ZonedDateTime =
        date.plusDays(1).atStartOfDay(zone)

    private fun parseHhMm(value: String): Pair<Int, Int> {
        val parts = value.split(':')
        if (parts.size != 2) throw IllegalArgumentException("Некорректное время: $value")
        val hour = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Некорректное время: $value")
        val minute = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Некорректное время: $value")
        if (hour !in 0..23 || minute !in 0..59) throw IllegalArgumentException("Время вне диапазона: $value")
        return hour to minute
    }

    const val RELATIVE_TODAY = "today"
    const val RELATIVE_TOMORROW = "tomorrow"
    const val RELATIVE_YESTERDAY = "yesterday"
    const val RELATIVE_DAY_BEFORE_YESTERDAY = "day_before_yesterday"
    const val RELATIVE_DAY_AFTER_TOMORROW = "day_after_tomorrow"
    const val DEFAULT_START_TIME = "09:00"
    const val DEFAULT_DURATION = "1h"
}
