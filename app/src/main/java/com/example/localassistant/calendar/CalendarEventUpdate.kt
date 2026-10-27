package com.example.localassistant.calendar

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Builds a partial update from an API event, never from invented model defaults. */
internal object CalendarEventUpdate {
    data class Prepared(val payload: UpdateEventPayload, val after: CalendarEvent)

    fun prepare(event: CalendarEvent, request: CalendarToolRequest, now: ZonedDateTime): Prepared {
        val allDay = if (request.allDaySpecified || request.allDay) request.allDay else event.allDay
        require(allDay == event.allDay) {
            "Смена типа «весь день / со временем» требует отдельного уточнения."
        }
        val changesTime = request.date != null || request.time != null ||
            request.duration != null || request.endTime != null
        var start: String? = null
        var end: String? = null
        if (changesTime) {
            val before = requireNotNull(event.startInZone(now.zone)) {
                "У события не удалось определить исходную дату и время."
            }
            val date = request.date?.let { CalendarTime.resolveDate(it, now) } ?: before.toLocalDate()
            if (allDay) {
                require(request.time == null && request.duration == null && request.endTime == null) {
                    "Для события на весь день укажите только новую дату."
                }
                val originalEnd = LocalDate.parse(requireNotNull(event.end))
                val span = ChronoUnit.DAYS.between(before.toLocalDate(), originalEnd)
                require(span > 0) { "Не удалось определить длительность события на весь день." }
                start = date.toString()
                end = date.plusDays(span).toString()
            } else {
                val time = request.time?.let(::parseTime) ?: before.toLocalTime()
                val localStart = date.atTime(time)
                val proposed = localStart.atZone(now.zone)
                require(proposed.toLocalDateTime() == localStart) {
                    "Такого местного времени нет из-за перевода часов. Уточните время."
                }
                val until = if (request.duration != null || request.endTime != null) {
                    CalendarTime.resolveEnd(proposed, request.duration, request.endTime)
                } else {
                    val originalEnd = Instant.parse(requireNotNull(event.end))
                    val duration = Duration.between(before.toInstant(), originalEnd)
                    require(!duration.isNegative && !duration.isZero) {
                        "Не удалось определить исходную длительность события."
                    }
                    proposed.plus(duration)
                }
                require(until.isAfter(proposed)) { "Конец события должен быть позже начала." }
                start = CalendarTime.rfc3339(proposed)
                end = CalendarTime.rfc3339(until)
            }
        }
        val after = event.copy(
            title = request.title ?: event.title,
            start = start ?: event.start,
            end = end ?: event.end,
            location = request.location ?: event.location,
            description = request.description ?: event.description,
        )
        require(after != event) { "Не указаны изменения: событие уже имеет такие параметры." }
        return Prepared(
            UpdateEventPayload(
                title = request.title,
                start = start,
                end = end,
                allDay = allDay.takeIf { start != null },
                description = request.description,
                location = request.location,
            ),
            after,
        )
    }

    fun parseTime(value: String): LocalTime {
        require(Regex("\\d{2}:\\d{2}").matches(value)) { "Укажите время в формате ЧЧ:ММ." }
        return LocalTime.parse(value)
    }
}
