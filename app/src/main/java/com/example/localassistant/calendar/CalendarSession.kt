package com.example.localassistant.calendar

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

/** App-owned task/object binding. The model can propose values, never transitions or IDs. */
internal class CalendarSession {
    var taskId = 0L
        private set
    var version = 0L
        private set
    private var nextTaskId = 0L
    var objectKind: CalendarObjectKind? = null
        private set
    var taskLists: List<GoogleTaskList> = emptyList()
        private set
    var phase = CalendarTaskPhase.EMPTY
        private set
    var draft: CalendarToolRequest? = null
        private set
    var awaiting: Set<CalendarField> = emptySet()
        private set
    private var awaitingAny = false
    var targets: List<CalendarEvent> = emptyList()
        private set
    var selector: CalendarToolRequest? = null
        private set
    var uncertainWrite = false
        private set
    var pendingSubject: CalendarFieldPatch? = null
        private set

    fun prepare(text: String, now: ZonedDateTime) = CalendarTurnPolicy.prepare(this, text, now)

    fun clear() {
        taskId = 0
        objectKind = null
        taskLists = emptyList()
        draft = null
        targets = emptyList()
        selector = null
        awaiting = emptySet()
        awaitingAny = false
        uncertainWrite = false
        pendingSubject = null
        phase = CalendarTaskPhase.EMPTY
        version++
    }

    fun accept(command: CalendarTurnCommand, now: ZonedDateTime): CalendarToolRequest {
        val plan = command.plan
        require(plan.taskId == taskId && plan.version == version) { "Задача изменилась. Повторите текущую реплику." }
        val operation = requireNotNull(plan.operation) { "Не выбрана операция календаря." }
        require(!uncertainWrite || operation in setOf(CalendarOp.READ, CalendarOp.SEARCH)) {
            "Результат предыдущей записи не подтверждён. Сначала проверьте календарь новым поиском."
        }
        val oldTargets = targets
        val oldSelector = selector
        val base = when (plan.transition) {
            CalendarTurnTransition.NEW -> {
                val uncertain = uncertainWrite
                val subjectId = taskId.takeIf { plan.usesPendingSubject && pendingSubject != null }
                clear()
                uncertainWrite = uncertain
                taskId = subjectId ?: ++nextTaskId
                CalendarToolRequest.empty(operation).copy(objectKind = plan.objectKind)
            }
            CalendarTurnTransition.TARGETS -> {
                require(oldTargets.isNotEmpty() || (operation in setOf(CalendarOp.READ, CalendarOp.SEARCH) && oldSelector != null)) {
                    "Набор найденных записей больше недоступен. Повторите поиск."
                }
                require(objectKind == plan.objectKind && oldTargets.all { it.kind == plan.objectKind }) { "Тип выбранных объектов изменился. Повторите поиск." }
                taskId = ++nextTaskId
                draft = null
                awaiting = emptySet()
                awaitingAny = false
                CalendarToolRequest.empty(operation).copy(
                    objectKind = plan.objectKind,
                    contextAction = CalendarContextAction.CONTINUE,
                    query = oldSelector?.query.takeIf { operation in setOf(CalendarOp.READ, CalendarOp.SEARCH) },
                    date = oldSelector?.date.takeIf { operation in setOf(CalendarOp.READ, CalendarOp.SEARCH) },
                    days = oldSelector?.days.takeIf { operation in setOf(CalendarOp.READ, CalendarOp.SEARCH) },
                    sourceTime = oldSelector?.sourceTime.takeIf { operation in setOf(CalendarOp.READ, CalendarOp.SEARCH) },
                    taskListId = oldSelector?.taskListId,
                    taskListQuery = oldSelector?.taskListQuery,
                    taskStatusFilter = oldSelector?.taskStatusFilter.takeIf { operation in setOf(CalendarOp.READ, CalendarOp.SEARCH) },
                )
            }
            CalendarTurnTransition.CONTINUE -> {
                val existing = requireNotNull(draft) { "Нет незавершённой задачи. Уточните новое действие." }
                require(existing.op == operation) { "Операция текущей задачи изменилась. Повторите запрос." }
                require(existing.objectKind == plan.objectKind) { "Тип текущего объекта изменился. Начните новую задачу." }
                existing.copy(contextAction = CalendarContextAction.CONTINUE)
            }
            else -> throw IllegalArgumentException("Требуется уточнение задачи.")
        }
        var resolved = command.patch.applyTo(base)
        objectKind = plan.objectKind
        if (objectKind == CalendarObjectKind.TASK) resolved = resolved.copy(allDay = false, allDaySpecified = false)
        resolved = resolved.copy(taskListsOnly = plan.taskListsOnly,
            taskStatusFilter = plan.taskStatusFilter ?: resolved.taskStatusFilter)
        if (plan.titleQuery) resolved = resolved.copy(titleQuery = true)
        if (operation == CalendarOp.READ && resolved.query != null) resolved = resolved.copy(op = CalendarOp.SEARCH)
        plan.allMatches?.let { resolved = resolved.copy(allMatches = it, allMatchesSpecified = true) }
        // A new date in a read follow-up means one day, not the previous week's length.
        if (operation in setOf(CalendarOp.READ, CalendarOp.SEARCH) && CalendarField.DATE in command.patch.values &&
            CalendarField.DAYS !in command.patch.values) resolved = resolved.copy(days = null)
        if (CalendarField.QUERY in command.patch.clear || (plan.transition == CalendarTurnTransition.CONTINUE &&
                CalendarField.QUERY in command.patch.values && resolved.query != base.query) ||
            (CalendarField.LIST in command.patch.values && resolved.taskListQuery != base.taskListQuery)) {
            targets = emptyList()
            selector = null
        }
        draft = resolved
        awaiting = emptySet()
        phase = CalendarTaskPhase.COLLECTING
        version++
        val titleLimit = if (resolved.objectKind == CalendarObjectKind.TASK) CalendarToolRequest.MAX_TASK_TITLE else CalendarToolRequest.MAX_TITLE
        require((resolved.title?.length ?: 0) <= titleLimit) { "Слишком длинное название. Сократите его." }
        require(listOf(resolved.query, resolved.description, resolved.location, resolved.taskListQuery, resolved.date, resolved.time,
            resolved.duration, resolved.endTime, resolved.sourceDate, resolved.sourceTime).all {
            (it?.length ?: 0) <= CalendarToolRequest.MAX_DETAIL
        }) { "Слишком длинное значение поля. Сократите его." }
        if (resolved.date != null) {
            resolved = resolved.copy(date = CalendarTime.freezeReference(requireNotNull(resolved.date), now))
            draft = resolved
            if (resolved.op in setOf(CalendarOp.CREATE, CalendarOp.UPDATE)) {
                resolved = resolved.copy(date = CalendarTime.resolveDate(requireNotNull(resolved.date), now).toString())
            }
        }
        val source = resolved.sourceDate ?: resolved.date.takeIf { resolved.op == CalendarOp.DELETE }
        if (source != null && resolved.op in setOf(CalendarOp.UPDATE, CalendarOp.DELETE)) {
            resolved = resolved.copy(sourceDate = CalendarTime.freezeReference(source, now))
            draft = resolved
            val range = CalendarTime.queryRange(resolved.sourceDate, resolved.days, 30, now)
            resolved = resolved.copy(sourceDate = range.start.toLocalDate().toString(),
                days = java.time.temporal.ChronoUnit.DAYS.between(range.start.toLocalDate(), range.endExclusive.toLocalDate()).toInt())
        }
        draft = resolved
        return resolved
    }

    fun stageSubject(plan: CalendarTurnPlan, now: ZonedDateTime): String {
        require(plan.taskId == taskId && plan.version == version) { "Задача изменилась. Повторите реплику." }
        val previous = pendingSubject.takeIf { plan.usesPendingSubject }
        if (previous == null) {
            clear()
            taskId = ++nextTaskId
        }
        objectKind = plan.objectKind
        val combined = (previous?.values.orEmpty() + plan.seed.values).filterKeys { it !in plan.seed.clear }.toMutableMap()
        val date = (combined[CalendarField.DATE] as? CalendarFieldValue.Text)?.value
        if (date != null) {
            try { combined[CalendarField.DATE] = CalendarFieldValue.Text(CalendarTime.freezeReference(date, now)) }
            catch (_: java.time.DateTimeException) { /* Keep the supplied invalid date for clarification. */ }
            catch (_: IllegalArgumentException) { /* Preserve an unresolved quantity until the user supplies it. */ }
        }
        pendingSubject = CalendarFieldPatch(combined)
        phase = CalendarTaskPhase.COLLECTING
        version++
        val title = (combined[CalendarField.TITLE] as? CalendarFieldValue.Text)?.value
        return "${title?.let { "Запомнил новую цель «$it»." } ?: "Предыдущая задача сброшена."} " +
            "Что с ней сделать: найти, создать, изменить или удалить?"
    }

    fun await(fields: Set<CalendarField>, any: Boolean = false) {
        awaiting = fields
        awaitingAny = any
        phase = CalendarTaskPhase.COLLECTING
        version++
    }

    fun setPhase(value: CalendarTaskPhase) {
        phase = value
        version++
    }

    fun rememberDraft(request: CalendarToolRequest) {
        require(draft == null || draft?.op == request.op) { "Нельзя заменить операцию без перехода задачи." }
        draft = request
        version++
    }

    fun rememberResults(events: List<CalendarEvent>, request: CalendarToolRequest?) {
        require(events.all { it.kind == objectKind }) { "Нельзя смешивать задачи и события в наборе целей." }
        targets = events
        selector = request
        version++
    }

    fun rememberTaskLists(lists: List<GoogleTaskList>) { taskLists = lists; version++ }

    fun finish(events: List<CalendarEvent> = targets, uncertain: Boolean = false) {
        val renamed = draft?.title?.takeIf { draft?.op == CalendarOp.UPDATE && it.isNotBlank() }
        if (renamed != null && events.isNotEmpty() && events.all { it.title == renamed }) {
            selector = selector?.copy(query = renamed)
        }
        draft = null
        targets = events
        awaiting = emptySet()
        awaitingAny = false
        uncertainWrite = uncertain
        phase = if (uncertain) CalendarTaskPhase.UNCERTAIN else CalendarTaskPhase.RESULTS
        version++
    }

    /** Explicit nulls/empty arrays distinguish empty state from omitted patch fields. */
    fun plannerContext(): String = JSONObject().apply {
        put("task_id", taskId)
        put("version", version)
        put("phase", phase.name)
        put("operation", draft?.op?.name ?: JSONObject.NULL)
        put("object_kind", objectKind?.name?.lowercase() ?: JSONObject.NULL)
        put("awaiting", JSONArray(awaiting.map { it.wire }))
        put("await_mode", if (awaitingAny) "any" else "all")
        put("uncertain_write", uncertainWrite)
        put("target_count", targets.size)
        put("targets", JSONArray(targets.take(3).mapIndexed { i, event -> JSONObject().apply {
            put("ref", i + 1); put("title", event.title.take(100)); put("start", event.start ?: JSONObject.NULL)
        } }))
        put("awaiting_operation", pendingSubject != null)
        val d = draft ?: pendingSubject?.applyTo(CalendarToolRequest.empty(CalendarOp.CREATE).copy(objectKind = objectKind ?: CalendarObjectKind.EVENT))
        put("values", JSONObject().apply {
            put("title", d?.title?.take(200) ?: JSONObject.NULL); put("date", d?.date ?: JSONObject.NULL)
            put("time", d?.time ?: JSONObject.NULL); put("duration", d?.duration ?: JSONObject.NULL)
            put("end_time", d?.endTime ?: JSONObject.NULL)
            put("query", d?.query?.take(160) ?: JSONObject.NULL)
            put("title_query", d?.titleQuery ?: JSONObject.NULL)
            put("list", d?.taskListQuery ?: JSONObject.NULL)
            put("status", d?.taskStatus?.wire ?: JSONObject.NULL)
            put("source_date", d?.sourceDate ?: JSONObject.NULL); put("source_time", d?.sourceTime ?: JSONObject.NULL)
            put("days", d?.days ?: JSONObject.NULL); put("all", d?.takeIf { it.allMatchesSpecified }?.allMatches ?: JSONObject.NULL)
            put("all_day", d?.takeIf { it.allDaySpecified }?.allDay ?: JSONObject.NULL)
            put("description", d?.description?.take(160) ?: JSONObject.NULL)
            put("location", d?.location?.take(160) ?: JSONObject.NULL)
        })
        put("preview_truncated", targets.size > 3 || (d?.title?.length ?: 0) > 200 || (d?.query?.length ?: 0) > 160 ||
            (d?.description?.length ?: 0) > 160 || (d?.location?.length ?: 0) > 160)
        put("selector", selector?.let { JSONObject().apply {
            put("query", it.query?.take(160) ?: JSONObject.NULL); put("date", it.date ?: JSONObject.NULL)
            put("days", it.days ?: JSONObject.NULL)
        } } ?: JSONObject.NULL)
    }.toString()
}
