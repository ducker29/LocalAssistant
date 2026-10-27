package com.example.localassistant.calendar

import java.time.ZonedDateTime

internal object GoogleTaskUpdate {
    fun validate(request: CalendarToolRequest) {
        require(request.time == null && request.endTime == null && request.duration == null && request.sourceTime == null) {
            "Google Tasks API не читает и не назначает время/длительность. Укажите «без времени», чтобы сохранить только дату."
        }
        require(request.location.isNullOrBlank()) { "У Google Tasks нет отдельного поля места. Укажите его в описании задачи." }
    }

    fun prepare(item: CalendarEvent, request: CalendarToolRequest, now: ZonedDateTime): PreparedCalendarUpdate {
        require(item.kind == CalendarObjectKind.TASK && request.objectKind == CalendarObjectKind.TASK)
        validate(request)
        val date = request.date?.let { CalendarTime.resolveDate(it, now).toString() }
        val after = item.copy(
            title = request.title ?: item.title,
            start = if (request.clearDate) null else date ?: item.start,
            description = request.description ?: item.description,
            taskStatus = request.taskStatus ?: item.taskStatus,
        )
        return PreparedCalendarUpdate(UpdateEventPayload(request.title, date, null,
            description = request.description, taskStatus = request.taskStatus, clearDate = request.clearDate), after, after != item)
    }
}
