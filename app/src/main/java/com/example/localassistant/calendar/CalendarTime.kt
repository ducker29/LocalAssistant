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
        val count = days ?: if (date == null) defaultDays else 1
        require(count in CalendarToolRequest.MIN_DAYS..CalendarToolRequest.MAX_DAYS)
        val firstDay = date?.let { resolveDate(it, now) } ?: now.toLocalDate()
        return QueryRange(
            firstDay.atStartOfDay(now.zone),
            firstDay.plusDays(count.toLong()).atStartOfDay(now.zone),
        )
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
        return when {
            ref == RELATIVE_TODAY -> now.toLocalDate()
            ref == RELATIVE_TOMORROW -> now.toLocalDate().plusDays(1)
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
    const val DEFAULT_START_TIME = "09:00"
    const val DEFAULT_DURATION = "1h"
}
