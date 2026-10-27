package com.example.localassistant.calendar

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.ZonedDateTime

/** Calendar Events adapter over the shared Google HTTP transport. */
class HttpCalendarGateway(tokenProvider: () -> String?, baseUrl: String = DEFAULT_BASE_URL) : CalendarGateway {
    private val http = GoogleApiHttp(tokenProvider, baseUrl, "Google Calendar")
    override suspend fun listEvents(timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int) = readPages(timeMin, timeMax, limit, null)
    override suspend fun searchEvents(query: String, timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int) = readPages(timeMin, timeMax, limit, enc(query))
    private data class Page(val events: List<CalendarEvent>, val next: String?)

    private suspend fun readPages(min: ZonedDateTime?, max: ZonedDateTime?, limit: Int, search: String?): Result<List<CalendarEvent>> {
        if (limit !in 1..CalendarGateway.MAX_RESULTS) return Result.failure(CalendarResultLimitException())
        val events = linkedMapOf<String, CalendarEvent>()
        val seenTokens = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val parts = mutableListOf("singleEvents=true", "orderBy=startTime", "maxResults=${minOf(limit, 250)}")
            min?.let { parts += "timeMin=${enc(CalendarTime.rfc3339(it))}" }
            max?.let { parts += "timeMax=${enc(CalendarTime.rfc3339(it))}" }
            search?.let { parts += "q=$it" }
            pageToken?.let { parts += "pageToken=${enc(it)}" }
            val result = request("GET", "/calendars/primary/events?${parts.joinToString("&")}", null) {
                Page(CalendarJson.parseEventList(it), JSONObject(it).optString("nextPageToken", "").takeIf(String::isNotBlank))
            }
            if (result.isFailure) return Result.failure(requireNotNull(result.exceptionOrNull()))
            val page = requireNotNull(result.getOrNull())
            page.events.forEach { events[it.id] = it }
            pageToken = page.next
            if (events.size > limit || (pageToken != null && (events.size >= limit || !seenTokens.add(pageToken) || seenTokens.size > 20))) {
                return Result.failure(CalendarResultLimitException())
            }
        } while (pageToken != null)
        return Result.success(events.values.toList())
    }

    override suspend fun getEvent(id: String): Result<CalendarEvent?> = request("GET", "/calendars/primary/events/${enc(id)}", null, CalendarJson::parseEvent)
    override suspend fun createEvent(payload: CreateEventPayload): Result<CalendarEvent> =
        request("POST", "/calendars/${enc(payload.calendarId)}/events", CalendarJson.eventPayload(payload).toString()) {
            CalendarJson.parseEvent(it) ?: throw IOException("Empty event response")
        }
    override suspend fun updateEvent(id: String, payload: UpdateEventPayload): Result<CalendarEvent> =
        request("PATCH", "/calendars/primary/events/${enc(id)}", CalendarJson.updatePayload(payload).toString()) {
            CalendarJson.parseEvent(it) ?: throw IOException("Empty event response")
        }
    override suspend fun deleteEvent(id: String): Result<Unit> = request("DELETE", "/calendars/primary/events/${enc(id)}", null) { Unit }
    override suspend fun primaryCalendarName() = "primary"

    private suspend fun <T> request(method: String, path: String, body: String?, parse: (String) -> T): Result<T> {
        val route = when {
            path.startsWith("/calendars/primary/events/") -> "/calendars/primary/events/{event_id}"
            path.startsWith("/calendars/primary/events") -> "/calendars/primary/events"
            else -> "/calendars/{calendar_id}/events"
        }
        return http.request(method, path, body, route, parse)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    companion object {
        const val DEFAULT_BASE_URL = "https://www.googleapis.com/calendar/v3"
        const val TIMEOUT_MS = 20_000
    }
}
