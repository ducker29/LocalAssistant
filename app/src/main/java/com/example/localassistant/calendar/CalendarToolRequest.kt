package com.example.localassistant.calendar

import org.json.JSONObject

/**
 * The five calendar operations the model can request. Each is untrusted structured data that must
 * be validated by [CalendarToolExecutor] before any API call.
 */
enum class CalendarOp {
    READ,
    CREATE,
    UPDATE,
    DELETE,
    SEARCH,
    ;
}

/**
 * A parsed and validated calendar tool call. Built only by [CalendarToolParser]; the fields are
 * exactly what the model is allowed to set.
 */
data class CalendarToolRequest(
    val op: CalendarOp,
    val date: String?,
    val time: String?,
    val duration: String?,
    val endTime: String?,
    val title: String?,
    val description: String?,
    val location: String?,
    val allDay: Boolean,
    val eventId: String?,
    val query: String?,
    val days: Int?,
) {
    companion object {
        const val MAX_TITLE = 200
        const val MAX_DETAIL = 2000
        const val MIN_DAYS = 1
        const val MAX_DAYS = 90
        const val DEFAULT_SEARCH_DAYS = 30
    }
}

/**
 * Strictly parses the model's planner JSON into a [CalendarToolRequest]. Unknown fields, wrong
 * types, or out-of-range values produce a [ToolParseError] with a human-readable reason so the
 * model can be told to correct the call. Never throws for malformed input.
 */
object CalendarToolParser {

    sealed interface ParseOutcome {
        data class Ok(val request: CalendarToolRequest) : ParseOutcome
        data class ToolParseError(val reason: String) : ParseOutcome
    }

    fun parse(raw: String): ParseOutcome {
        val json: JSONObject = try {
            JSONObject(raw)
        } catch (t: Exception) {
            return ParseOutcome.ToolParseError("Это не JSON-объект")
        }

        val opName = json.optString("tool", null)
        if (opName == null) {
            return ParseOutcome.ToolParseError("Не указано поле \"tool\"")
        }
        val op = CalendarOp.entries.firstOrNull { it.name.equals(opName.trim(), ignoreCase = true) }
            ?: return ParseOutcome.ToolParseError("Неизвестная операция: $opName")

        fun str(key: String): String? =
            if (json.has(key) && !json.isNull(key)) json.optString(key).takeIf { it.isNotBlank() } else null

        val allDay = json.optBoolean("all_day", false)
        val days: Int? = if (json.has("days")) {
            val raw = json.opt("days")
            if (raw != null && raw != JSONObject.NULL) {
                val value = json.optInt("days")
                if (value < CalendarToolRequest.MIN_DAYS || value > CalendarToolRequest.MAX_DAYS) {
                    return ParseOutcome.ToolParseError("\"days\" вне диапазона 1..${CalendarToolRequest.MAX_DAYS}")
                }
                value
            } else null
        } else null

        return ParseOutcome.Ok(
            CalendarToolRequest(
                op = op,
                date = str("date"),
                time = str("time"),
                duration = str("duration"),
                endTime = str("end_time"),
                title = str("title"),
                description = str("description"),
                location = str("location"),
                allDay = allDay,
                eventId = str("event_id"),
                query = str("query"),
                days = days,
            )
        )
    }
}
