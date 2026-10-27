package com.example.localassistant.calendar

import org.json.JSONObject

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
    fun summary(now: java.time.ZonedDateTime): String {
        val whenText = start?.let { formatInstant(it, now) } ?: "без даты"
        val name = title.ifBlank { "(без названия)" }
        return "$name — $whenText"
    }

    private fun formatInstant(rfc3339: String, now: java.time.ZonedDateTime): String {
        val zdt = java.time.Instant.parse(rfc3339).atZone(now.zone)
        return zdt.format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        )
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
