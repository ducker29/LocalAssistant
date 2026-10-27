package com.example.localassistant.calendar

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** A single event as returned by the Calendar API (only the fields we surface). */
data class CalendarEvent(
    val id: String,
    val title: String,
    val start: String?,
    val end: String?,
    val allDay: Boolean,
    val location: String?,
    val description: String?,
) {
    /** Stable human line, e.g. "Созвон (2026-10-27 10:00)". Never contains tokens. */
    fun summary(now: ZonedDateTime): String {
        val whenText = startInZone(now.zone)?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            ?.let { "$it, ${timeLabel(now.zone)}" } ?: "без даты"
        val name = title.ifBlank { "(без названия)" }
        return "$name — $whenText"
    }

    fun startInZone(zone: ZoneId): ZonedDateTime? = parseMoment(start, zone)

    fun timeLabel(zone: ZoneId): String {
        if (allDay) return "Весь день"
        val from = startInZone(zone) ?: return "Время не указано"
        val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
        val until = parseMoment(end, zone) ?: return from.format(timeFormat)
        val endFormat = if (until.toLocalDate() == from.toLocalDate()) timeFormat
            else DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.forLanguageTag("ru"))
        return "${from.format(timeFormat)}–${until.format(endFormat)}"
    }

    private fun parseMoment(value: String?, zone: ZoneId): ZonedDateTime? {
        if (value.isNullOrBlank()) return null
        return try {
            if (allDay) LocalDate.parse(value).atStartOfDay(zone)
            else Instant.parse(value).atZone(zone)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    companion object {
        fun fromItem(item: JSONObject): CalendarEvent {
            val start = item.optJSONObject("start")?.let {
                if (it.has("date")) it.optString("date") else it.optString("dateTime")
            }
            val end = item.optJSONObject("end")?.let {
                if (it.has("date")) it.optString("date") else it.optString("dateTime")
            }
            val allDay = item.optJSONObject("start")?.has("date") ?: false
            return CalendarEvent(
                id = item.optString("id"),
                title = item.optString("summary", "(без названия)"),
                start = start,
                end = end,
                allDay = allDay,
                location = item.optString("location", "").takeIf { it.isNotBlank() },
                description = item.optString("description", "").takeIf { it.isNotBlank() },
            )
        }
    }
}
