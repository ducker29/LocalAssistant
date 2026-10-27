package com.example.localassistant.calendar

enum class CalendarField(val wire: String) {
    TITLE("title"), DATE("date"), TIME("time"), DURATION("duration"), END_TIME("end_time"),
    DESCRIPTION("description"), LOCATION("location"), ALL_DAY("all_day"), QUERY("query"),
    DAYS("days"), SOURCE_DATE("source_date"), SOURCE_TIME("source_time"), LIST("list"), STATUS("status");

    companion object { fun fromWire(value: String) = entries.firstOrNull { it.wire == value } }
}

sealed interface CalendarFieldValue {
    data class Text(val value: String) : CalendarFieldValue
    data class Number(val value: Int) : CalendarFieldValue
    data class Flag(val value: Boolean) : CalendarFieldValue
}

/** Missing fields mean keep. Clear is separate and must be supported by the user's turn. */
data class CalendarFieldPatch(
    val values: Map<CalendarField, CalendarFieldValue> = emptyMap(),
    val clear: Set<CalendarField> = emptySet(),
) {
    fun applyTo(base: CalendarToolRequest): CalendarToolRequest {
        fun text(field: CalendarField, old: String?): String? = if (field in clear) {
            if (field in setOf(CalendarField.TITLE, CalendarField.DESCRIPTION, CalendarField.LOCATION)) "" else null
        } else (values[field] as? CalendarFieldValue.Text)?.value ?: old
        val allDay = (values[CalendarField.ALL_DAY] as? CalendarFieldValue.Flag)?.value
        val days = (values[CalendarField.DAYS] as? CalendarFieldValue.Number)?.value
        return base.copy(
            title = text(CalendarField.TITLE, base.title), date = text(CalendarField.DATE, base.date),
            time = text(CalendarField.TIME, base.time), duration = text(CalendarField.DURATION, base.duration),
            endTime = text(CalendarField.END_TIME, base.endTime),
            description = text(CalendarField.DESCRIPTION, base.description), location = text(CalendarField.LOCATION, base.location),
            query = text(CalendarField.QUERY, base.query),
            taskListQuery = text(CalendarField.LIST, base.taskListQuery),
            taskListId = if (CalendarField.LIST in values || CalendarField.LIST in clear) null else base.taskListId,
            taskStatus = if (CalendarField.STATUS in clear) null else
                (values[CalendarField.STATUS] as? CalendarFieldValue.Text)?.value?.let(GoogleTaskStatus::fromWire) ?: base.taskStatus,
            clearDate = if (CalendarField.DATE in values) false else CalendarField.DATE in clear || base.clearDate,
            sourceDate = text(CalendarField.SOURCE_DATE, base.sourceDate), sourceTime = text(CalendarField.SOURCE_TIME, base.sourceTime),
            days = if (CalendarField.DAYS in clear) null else days ?: base.days,
            allDay = if (CalendarField.ALL_DAY in clear) false else allDay ?: base.allDay,
            allDaySpecified = CalendarField.ALL_DAY in clear || allDay != null || base.allDaySpecified,
        )
    }
}

enum class CalendarTaskPhase { EMPTY, COLLECTING, RESOLVING, CHOOSING, CONFIRMING, EXECUTING, RESULTS, UNCERTAIN }
enum class CalendarTurnTransition { NEW, NEW_OBJECT, CONTINUE, TARGETS, GENERAL, CANCEL, CLARIFY }

/** Immutable per-turn proposal, not another store of Calendar state. */
data class CalendarTurnPlan(
    val taskId: Long,
    val version: Long,
    val snapshot: String,
    val text: String,
    val transition: CalendarTurnTransition,
    val operation: CalendarOp?,
    val seed: CalendarFieldPatch = CalendarFieldPatch(),
    val allMatches: Boolean? = null,
    val message: String? = null,
    val usesPendingSubject: Boolean = false,
    val titleQuery: Boolean = false,
    val resetOnClarify: Boolean = false,
    val objectKind: CalendarObjectKind = CalendarObjectKind.EVENT,
    val taskListsOnly: Boolean = false,
    val taskStatusFilter: GoogleTaskStatus? = null,
)

data class CalendarModelPatch(val taskId: Long, val version: Long, val patch: CalendarFieldPatch)
data class CalendarTurnCommand(val plan: CalendarTurnPlan, val patch: CalendarFieldPatch)
