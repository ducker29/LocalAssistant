package com.example.localassistant.calendar

import org.json.JSONObject

enum class CalendarOp { READ, CREATE, UPDATE, DELETE, SEARCH }
enum class CalendarContextAction { NEW, CONTINUE }

/** Resolved app command. Operation/context/scope are never supplied by the model. */
data class CalendarToolRequest(
    val op: CalendarOp,
    val date: String?, val time: String?, val duration: String?, val endTime: String?,
    val title: String?, val description: String?, val location: String?,
    val allDay: Boolean, val eventId: String?, val query: String?, val days: Int?,
    val sourceDate: String? = null, val sourceTime: String? = null,
    val allDaySpecified: Boolean = false,
    val contextAction: CalendarContextAction = CalendarContextAction.NEW,
    val allMatches: Boolean = false,
    val allMatchesSpecified: Boolean = false,
    val titleQuery: Boolean = false,
    val objectKind: CalendarObjectKind = CalendarObjectKind.EVENT,
    val taskListQuery: String? = null,
    val taskListId: String? = null,
    val taskStatus: GoogleTaskStatus? = null,
    val taskStatusFilter: GoogleTaskStatus? = null,
    val taskListsOnly: Boolean = false,
    val clearDate: Boolean = false,
) {
    companion object {
        fun empty(op: CalendarOp) = CalendarToolRequest(op, null, null, null, null, null, null, null, false, null, null, null)
        const val MAX_TITLE = 200
        const val MAX_TASK_TITLE = 1024
        const val MAX_DETAIL = 2000
        const val MIN_DAYS = 1
        const val MAX_DAYS = 90
        const val DEFAULT_SEARCH_DAYS = 30
    }
}

/** Strict field-patch protocol. Unknown operation/context keys cannot become commands. */
object CalendarToolParser {
    sealed interface ParseOutcome {
        data class Ok(val proposal: CalendarModelPatch) : ParseOutcome
        data class ToolParseError(val reason: String) : ParseOutcome
    }

    fun parse(raw: String): ParseOutcome {
        val root = try { JSONObject(raw) } catch (_: Exception) {
            return ParseOutcome.ToolParseError("not_json_object")
        }
        if (root.keys().asSequence().any { it !in setOf("task_id", "version", "set", "clear") }) {
            return ParseOutcome.ToolParseError("unknown_field")
        }
        fun number(key: String): Long? {
            val value = root.opt(key) as? Number ?: return null
            return value.toLong().takeIf { it >= 0 && value.toDouble() == it.toDouble() }
        }
        val id = number("task_id") ?: return ParseOutcome.ToolParseError("missing_task_id")
        val version = number("version") ?: return ParseOutcome.ToolParseError("missing_version")
        val fields = root.opt("set") as? JSONObject ?: return ParseOutcome.ToolParseError("invalid_set")
        val values = mutableMapOf<CalendarField, CalendarFieldValue>()
        for (key in fields.keys()) {
            val field = CalendarField.fromWire(key) ?: return ParseOutcome.ToolParseError("unknown_field")
            val rawValue = fields.opt(key)
            values[field] = when (field) {
                CalendarField.ALL_DAY -> {
                    if (rawValue !is Boolean) return ParseOutcome.ToolParseError("invalid_boolean")
                    CalendarFieldValue.Flag(rawValue)
                }
                CalendarField.DAYS -> {
                    if (rawValue !is Number || rawValue.toDouble() != rawValue.toInt().toDouble()) return ParseOutcome.ToolParseError("invalid_days")
                    if (rawValue.toInt() !in 1..90) return ParseOutcome.ToolParseError("days_out_of_range")
                    CalendarFieldValue.Number(rawValue.toInt())
                }
                else -> {
                    if (rawValue !is String || rawValue.isBlank()) return ParseOutcome.ToolParseError("invalid_string")
                    if (field == CalendarField.STATUS && GoogleTaskStatus.fromWire(rawValue) == null) return ParseOutcome.ToolParseError("invalid_status")
                    val limit = if (field == CalendarField.TITLE) CalendarToolRequest.MAX_TASK_TITLE else CalendarToolRequest.MAX_DETAIL
                    if (rawValue.length > limit) return ParseOutcome.ToolParseError("field_too_long")
                    CalendarFieldValue.Text(rawValue)
                }
            }
        }
        val clear = mutableSetOf<CalendarField>()
        if (root.has("clear")) {
            val array = root.optJSONArray("clear") ?: return ParseOutcome.ToolParseError("invalid_clear")
            for (i in 0 until array.length()) {
                val key = array.opt(i) as? String ?: return ParseOutcome.ToolParseError("invalid_clear")
                clear += CalendarField.fromWire(key) ?: return ParseOutcome.ToolParseError("unknown_field")
            }
        }
        if (clear.any { it in values }) return ParseOutcome.ToolParseError("conflicting_patch")
        return ParseOutcome.Ok(CalendarModelPatch(id, version, CalendarFieldPatch(values, clear)))
    }
}
