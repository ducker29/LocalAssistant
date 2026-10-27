package com.example.localassistant.calendar

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON builders/parsers for the Google Calendar REST payloads. No networking, no tokens —
 * trivially unit-testable.
 */
object CalendarJson {

    fun eventPayload(payload: CreateEventPayload): JSONObject = buildStringPayload(
        title = payload.title,
        start = payload.start,
        end = payload.end,
        allDay = payload.allDay,
        description = payload.description,
        location = payload.location,
    )

    fun updatePayload(payload: UpdateEventPayload): JSONObject =
        buildStringPayload(
            title = payload.title,
            start = payload.start,
            end = payload.end,
            allDay = payload.allDay,
            description = payload.description,
            location = payload.location,
        )

    private fun buildStringPayload(
        title: String?,
        start: String?,
        end: String?,
        allDay: Boolean?,
        description: String?,
        location: String?,
    ): JSONObject {
        val root = JSONObject()
        title?.let { root.put("summary", it) }
        description?.let { root.put("description", it) }
        location?.let { root.put("location", it) }
        val isAllDay = allDay ?: false
        start?.let {
            val startObj = JSONObject()
            if (isAllDay) startObj.put("date", it) else startObj.put("dateTime", it)
            root.put("start", startObj)
        }
        end?.let {
            val endObj = JSONObject()
            if (isAllDay) endObj.put("date", it) else endObj.put("dateTime", it)
            root.put("end", endObj)
        }
        return root
    }

    fun parseEventList(json: String): List<CalendarEvent> {
        val items = JSONObject(json).optJSONArray("items") ?: JSONArray()
        return List(items.length()) { index ->
            CalendarEvent.fromItem(items.getJSONObject(index))
        }
    }

    fun parseEvent(json: String): CalendarEvent? {
        val obj = JSONObject(json)
        if (obj.isNull("id") && obj.isNull("summary") && obj.isNull("start")) {
            return null
        }
        return CalendarEvent.fromItem(obj)
    }
}
