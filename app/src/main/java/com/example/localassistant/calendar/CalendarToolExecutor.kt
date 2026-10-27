package com.example.localassistant.calendar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class CalendarConfirmationGate {
    private val deferred = CompletableDeferred<Boolean>()
    fun confirm() { deferred.complete(true) }
    fun cancel() { deferred.complete(false) }
    suspend fun await(): Boolean = deferred.await()
}

data class CalendarTargetSelection(val operation: CalendarOp, val candidates: List<CalendarEvent>)
data class CalendarTaskListSelection(val candidates: List<GoogleTaskList>)

sealed interface CalendarToolResult {
    data class Ok(val statusText: String) : CalendarToolResult
    data class Clarification(val message: String) : CalendarToolResult
    data class Error(val message: String) : CalendarToolResult
    data class NotConnected(val message: String) : CalendarToolResult
}

/** One task/session and one resolve → prepare → confirm → execute path for all Calendar writes. */
class CalendarToolExecutor(
    private val gateway: CalendarGateway,
    private val is_connected: () -> Boolean,
    private val nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val tasksGateway: GoogleTasksGateway? = null,
) {
    private val session = CalendarSession()
    private var generation = 0L
    private var gate: CalendarConfirmationGate? = null
    private var targetChoice: CompletableDeferred<String?>? = null
    private val _pendingConfirmation = MutableStateFlow<CalendarToolRequest?>(null)
    val pendingConfirmation: StateFlow<CalendarToolRequest?> = _pendingConfirmation
    private val _confirmationSummary = MutableStateFlow<String?>(null)
    val confirmationSummary: StateFlow<String?> = _confirmationSummary
    private val _targetSelection = MutableStateFlow<CalendarTargetSelection?>(null)
    val targetSelection: StateFlow<CalendarTargetSelection?> = _targetSelection
    private var listChoice: CompletableDeferred<String?>? = null
    private val _taskListSelection = MutableStateFlow<CalendarTaskListSelection?>(null)
    val taskListSelection: StateFlow<CalendarTaskListSelection?> = _taskListSelection

    fun selectTaskList(id: String?) {
        if (id == null || _taskListSelection.value?.candidates?.any { it.id == id } == true) listChoice?.complete(id)
    }

    fun plannerContext(): String = session.plannerContext()
    fun contextVersion(): Long = generation
    fun prepareTurn(text: String): CalendarTurnPlan = session.prepare(text, nowProvider())
    fun commandFor(plan: CalendarTurnPlan, proposal: CalendarModelPatch?) =
        CalendarTurnCommand(plan, CalendarTurnPolicy.ground(plan, proposal))
    fun stageSubject(plan: CalendarTurnPlan): String = session.stageSubject(plan, nowProvider())

    fun clearContext() {
        generation++
        session.clear()
        targetChoice?.complete(null)
        listChoice?.complete(null)
        gate?.cancel()
        _targetSelection.value = null
        _taskListSelection.value = null
        _pendingConfirmation.value = null
        _confirmationSummary.value = null
    }

    fun selectTarget(id: String?) {
        if (id == null || _targetSelection.value?.candidates?.any { it.id == id } == true) {
            targetChoice?.complete(id)
        }
    }

    fun resolvePending(confirmed: Boolean) {
        if (confirmed) gate?.confirm() else gate?.cancel()
    }

    suspend fun executeSuspend(command: CalendarTurnCommand): CalendarToolResult {
        val token = generation
        val beforeVersion = session.version
        if (!is_connected()) return CalendarToolResult.NotConnected("Календарь не подключён. Откройте настройки.")
        val now = nowProvider()
        return try {
            var request = session.accept(command, now)
            if (request.objectKind == CalendarObjectKind.TASK) {
                GoogleTaskUpdate.validate(request)
                require(tasksGateway != null) { "Google Tasks не настроен." }
                if (request.op != CalendarOp.CREATE && request.taskListQuery != null) {
                    val listResult = resolveTaskList(request, token)
                    if (listResult.isFailure) return mapFailure(listResult.exceptionOrNull(), false)
                    val chosen = listResult.getOrNull() ?: run {
                        session.finish()
                        return CalendarToolResult.Error("Выбор списка отменён")
                    }
                    request = request.copy(taskListId = chosen.id, taskListQuery = chosen.title)
                    session.rememberDraft(request)
                }
            } else require(request.taskStatus == null) { "Статус выполнения доступен только для Google Tasks, не для событий." }
            CalendarAssistantDebug.log("session: transition=${command.plan.transition}, active_op=${request.op}, all=${request.allMatches}, phase=${session.phase}")
            when (request.op) {
                CalendarOp.READ, CalendarOp.SEARCH -> read(request, now, token)
                CalendarOp.CREATE -> {
                    if (request.objectKind == CalendarObjectKind.TASK) createTask(request, now, token) else create(request, now, token)
                }
                CalendarOp.UPDATE, CalendarOp.DELETE -> {
                    change(request, now, token)
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (_: java.time.DateTimeException) {
            // Preserve valid slots even if the supplied date needs a month/year clarification.
            val dateField = if (session.draft?.op == CalendarOp.DELETE || session.draft?.sourceDate != null) CalendarField.SOURCE_DATE else CalendarField.DATE
            val task = session.objectKind == CalendarObjectKind.TASK
            if (token == generation && session.version != beforeVersion) session.await(
                if (task) setOf(dateField) else setOf(dateField, CalendarField.TIME))
            CalendarAssistantDebug.log("session: clarification=date_time")
            CalendarToolResult.Clarification(if (task) "Уточните полную дату: день, месяц и при необходимости год."
                else "Уточните полную дату и время: день, месяц и при необходимости год.")
        } catch (t: IllegalArgumentException) {
            if (token == generation && session.version != beforeVersion) session.await(expectedFields(), any = session.draft?.op == CalendarOp.UPDATE)
            CalendarAssistantDebug.log("session: clarification=validation")
            CalendarToolResult.Clarification(t.message ?: "Уточните параметры Calendar-задачи.")
        }
    }

    private fun checkSession(token: Long) {
        require(token == generation && is_connected()) { "Подключение или задача изменились. Повторите запрос." }
    }

    private fun provider(kind: CalendarObjectKind): CalendarGateway =
        if (kind == CalendarObjectKind.TASK) requireNotNull(tasksGateway) else gateway

    private suspend fun getItem(item: CalendarEvent): Result<CalendarEvent?> = provider(item.kind).getEvent(item.id).map { result ->
        result?.also { require(it.kind == item.kind && it.taskListId == item.taskListId) { "Источник объекта изменился." } }
            ?.copy(taskListTitle = item.taskListTitle)
    }

    private suspend fun resolveTaskList(request: CalendarToolRequest, token: Long): Result<GoogleTaskList?> {
        val catalog = requireNotNull(tasksGateway).taskLists()
        if (catalog.isFailure) return Result.failure(requireNotNull(catalog.exceptionOrNull()))
        checkSession(token)
        val lists = catalog.getOrNull().orEmpty()
        session.rememberTaskLists(lists)
        val query = request.taskListQuery?.trim()?.lowercase()?.replace('ё', 'е')
        val candidates = lists.filter {
            if (request.taskListId != null) it.id == request.taskListId
            else query == null || it.title.trim().lowercase().replace('ё', 'е') == query
        }
        if (candidates.isEmpty()) {
            session.await(setOf(CalendarField.LIST))
            return Result.failure(CalendarApiException(CalendarApiError.InvalidRequest(
                if (lists.isEmpty()) "Нет списков Google Tasks. Создайте список в Google Tasks." else "Список задач не найден. Уточните его название.")))
        }
        if (candidates.size == 1) return Result.success(candidates.single())
        val choice = CompletableDeferred<String?>()
        listChoice = choice
        session.setPhase(CalendarTaskPhase.CHOOSING)
        _taskListSelection.value = CalendarTaskListSelection(candidates)
        return try {
            val selected = choice.await()
            checkSession(token)
            Result.success(candidates.firstOrNull { it.id == selected })
        } finally {
            if (listChoice === choice) listChoice = null
            _taskListSelection.value = null
        }
    }

    private suspend fun createTask(request: CalendarToolRequest, now: ZonedDateTime, token: Long): CalendarToolResult {
        require(!request.allMatches) { "Создавайте разные задачи по одной." }
        if (request.title.isNullOrBlank()) {
            session.await(setOf(CalendarField.TITLE))
            return CalendarToolResult.Clarification("Как назвать задачу Google Tasks?")
        }
        val listResult = resolveTaskList(request, token)
        if (listResult.isFailure) return mapFailure(listResult.exceptionOrNull(), false)
        val list = listResult.getOrNull() ?: run { session.finish(); return CalendarToolResult.Error("Создание задачи отменено") }
        val resolved = request.copy(taskListId = list.id, taskListQuery = list.title)
        session.rememberDraft(resolved)
        val date = request.date?.let { CalendarTime.resolveDate(it, now).toString() }
        val preview = CalendarEvent("", request.title, date, null, false, null, request.description,
            CalendarObjectKind.TASK, list.id, list.title, request.taskStatus ?: GoogleTaskStatus.NEEDS_ACTION)
        checkSession(token)
        if (!confirm(resolved, "Создать задачу Google Tasks:\n${preview.summary(now)}\nВремя Tasks API не назначает." +
                (request.description?.let { "\nОписание: $it" } ?: ""))) {
            session.finish()
            return CalendarToolResult.Error("Создание задачи отменено")
        }
        checkSession(token)
        session.setPhase(CalendarTaskPhase.EXECUTING)
        val result = requireNotNull(tasksGateway).createEvent(CreateEventPayload(list.id, request.title, date.orEmpty(), null, false,
            description = request.description, taskStatus = request.taskStatus))
        checkGenerationAfterWrite(token)
        if (result.isFailure) { session.finish(uncertain = uncertain(result.exceptionOrNull())); return mapFailure(result.exceptionOrNull(), true) }
        val task = requireNotNull(result.getOrNull()).copy(taskListTitle = list.title)
        require(task.kind == CalendarObjectKind.TASK && task.taskListId == list.id)
        session.finish(listOf(task))
        return CalendarToolResult.Ok("Задача создана:\n${task.summary(now)}")
    }

    private suspend fun read(request: CalendarToolRequest, now: ZonedDateTime, token: Long): CalendarToolResult {
        if (request.taskListsOnly && request.objectKind == CalendarObjectKind.TASK) {
            val result = requireNotNull(tasksGateway).taskLists()
            if (result.isFailure) return mapFailure(result.exceptionOrNull(), false)
            checkSession(token)
            session.rememberTaskLists(result.getOrNull().orEmpty())
            session.finish(emptyList())
            return CalendarToolResult.Ok("Списки Google Tasks:\n" + result.getOrNull().orEmpty().joinToString("\n") { "• ${it.title}" })
        }
        // An unsuccessful new/narrowed search must not leave an older set eligible for "delete them".
        session.rememberResults(emptyList(), request)
        session.rememberDraft(request)
        if (request.op == CalendarOp.SEARCH && request.query.isNullOrBlank()) {
            session.await(setOf(CalendarField.QUERY))
            return CalendarToolResult.Clarification("По какому названию или словам найти ${if (request.objectKind == CalendarObjectKind.TASK) "задачи" else "события"}?")
        }
        val range = if (request.objectKind == CalendarObjectKind.TASK && request.date == null && request.days == null) null else
            CalendarTime.queryRange(request.date, request.days, if (request.op == CalendarOp.SEARCH) 30 else 7, now)
        CalendarAssistantDebug.log("executor ${request.op}: object_kind=${request.objectKind}, range_category=${if (range == null) "ALL_DATES" else CalendarTime.referenceCategory(request.date)}, days=${range?.let(::rangeDays)}")
        session.setPhase(CalendarTaskPhase.RESOLVING)
        val result = load(request, request.query.takeIf { request.op == CalendarOp.SEARCH }, range)
        if (result.isFailure) return mapFailure(result.exceptionOrNull(), false)
        checkSession(token)
        val sourceTime = request.sourceTime?.let(CalendarEventUpdate::parseTime)
        val events = result.getOrNull().orEmpty().filter { event ->
            sourceTime == null || (event.startInZone(now.zone)?.let { !event.allDay && it.hour == sourceTime.hour && it.minute == sourceTime.minute } == true)
        }
        val selector = request.copy(date = range?.start?.toLocalDate()?.toString(), days = range?.let(::rangeDays))
        session.finish(events)
        session.rememberResults(events, selector)
        val header = (if (request.objectKind == CalendarObjectKind.TASK) "Задачи Google Tasks " else if (request.op == CalendarOp.SEARCH) "Результаты поиска " else "События ") +
            (range?.let(::period) ?: "(все даты, включая задачи без даты)")
        CalendarAssistantDebug.log("executor ${request.op}: result=Ok, complete_result_set=true, days=${range?.let(::rangeDays)}")
        return CalendarToolResult.Ok(formatList(events, header, now))
    }

    private suspend fun load(request: CalendarToolRequest, query: String?, range: CalendarTime.QueryRange?, titleOnly: Boolean = false): Result<List<CalendarEvent>> {
        val result = if (request.objectKind == CalendarObjectKind.TASK) requireNotNull(tasksGateway).listTasks(
            request.taskListId, range?.start, range?.endExclusive, CalendarGateway.MAX_RESULTS)
        else gateway.listEvents(requireNotNull(range).start, range.endExclusive, CalendarGateway.MAX_RESULTS)
        if (result.isFailure) return result
        val source = result.getOrNull().orEmpty().filter { it.kind == request.objectKind &&
            (request.taskStatusFilter == null || it.taskStatus == request.taskStatusFilter) }
        val matched = if (query.isNullOrBlank()) source else source.filter { CalendarEventSearch.matches(it, query, titleOnly) }
        CalendarAssistantDebug.log("read_set=${if (source.isEmpty()) "empty" else "nonempty"}, matched_set=${if (matched.isEmpty()) "empty" else "nonempty"}, title_only=$titleOnly")
        return Result.success(matched)
    }

    private suspend fun create(request: CalendarToolRequest, now: ZonedDateTime, token: Long): CalendarToolResult {
        require(!request.allMatches) { "Для создания нескольких разных событий уточните каждое отдельно." }
        val missing = buildList {
            if (request.title.isNullOrBlank()) add("название")
            if (request.date == null) add("дату")
            if (!request.allDay && request.time == null) add("время (либо укажите «весь день»)")
        }
        if (missing.isNotEmpty()) {
            session.await(expectedFields())
            return CalendarToolResult.Clarification(
            "Продолжаем создание${request.title?.let { " «$it»" } ?: " события"}. " +
                "Уточните ${missing.joinToString(", ")}.",
            )
        }
        val date = CalendarTime.resolveDate(requireNotNull(request.date), now)
        val start = if (request.allDay) date.atStartOfDay(now.zone)
            else date.atTime(CalendarEventUpdate.parseTime(requireNotNull(request.time))).atZone(now.zone)
        if (!request.allDay) require(start.toLocalTime() == CalendarEventUpdate.parseTime(requireNotNull(request.time))) {
            "Такого местного времени нет из-за перевода часов. Уточните время."
        }
        val end = if (request.allDay) date.plusDays(1).atStartOfDay(now.zone)
            else CalendarTime.resolveEnd(start, request.duration, request.endTime)
        require(end.isAfter(start)) { "Конец события должен быть позже начала." }
        val payload = CreateEventPayload("primary", requireNotNull(request.title),
            if (request.allDay) date.toString() else CalendarTime.rfc3339(start),
            if (request.allDay) date.plusDays(1).toString() else CalendarTime.rfc3339(end),
            request.allDay, request.description, request.location)
        val preview = CalendarEvent("", payload.title, payload.start, payload.end,
            payload.allDay, payload.location, payload.description)
        val summary = "Создать событие:\n${preview.summary(now)}" +
            (request.location?.let { "\nМесто: $it" } ?: "") +
            (request.description?.let { "\nОписание: $it" } ?: "")
        checkSession(token)
        if (!confirm(request, summary)) {
            session.finish()
            return CalendarToolResult.Error("Создание отменено пользователем")
        }
        checkSession(token)
        session.setPhase(CalendarTaskPhase.EXECUTING)
        val result = gateway.createEvent(payload)
        checkGenerationAfterWrite(token)
        if (result.isFailure) {
            session.finish(uncertain = uncertain(result.exceptionOrNull()))
            return mapFailure(result.exceptionOrNull(), true)
        }
        val event = requireNotNull(result.getOrNull())
        session.finish(listOf(event))
        return CalendarToolResult.Ok("Событие создано:\n${event.summary(now)}")
    }

    private data class Plan(val before: CalendarEvent, val update: PreparedCalendarUpdate?)

    private suspend fun change(request: CalendarToolRequest, now: ZonedDateTime, token: Long): CalendarToolResult {
        val sourceDate = request.sourceDate ?: request.date.takeIf { request.op == CalendarOp.DELETE }
        val priorSelector = session.selector.takeIf { request.contextAction == CalendarContextAction.CONTINUE && sourceDate == null }
        val range = if (priorSelector != null) CalendarTime.queryRange(priorSelector.date, priorSelector.days, 30, now)
            else CalendarTime.queryRange(sourceDate, request.days, 30, now)
        val sourceTime = request.sourceTime?.let(CalendarEventUpdate::parseTime)
        fun matches(event: CalendarEvent): Boolean {
            val start = event.startInZone(now.zone)
            val end = event.endInZone(now.zone)
            // Same overlap semantics as Google events.list, including multi-day/all-day events.
            return (sourceDate == null || (start != null && start.isBefore(range.endExclusive) &&
                (end?.isAfter(range.start) ?: !start.isBefore(range.start)))) &&
                (sourceTime == null || (!event.allDay && start != null && start.hour == sourceTime.hour && start.minute == sourceTime.minute))
        }
        val fromContext = request.contextAction == CalendarContextAction.CONTINUE && session.targets.isNotEmpty()
        val unboundedTasks = request.objectKind == CalendarObjectKind.TASK && sourceDate == null && request.days == null && priorSelector?.date == null
        if (request.objectKind == CalendarObjectKind.EVENT && request.allMatches && !fromContext && sourceDate == null && priorSelector == null) {
            session.await(setOf(CalendarField.SOURCE_DATE))
            return CalendarToolResult.Clarification("За какой период выполнить действие над всеми совпадениями? Например, «на этой неделе».")
        }
        val candidates = if (fromContext) {
            CalendarAssistantDebug.log("executor ${request.op}: target_resolution=session")
            if (request.query != null && request.query != session.selector?.query) {
                val foundSelector = session.selector
                val starts = session.targets.mapNotNull { it.startInZone(now.zone)?.toLocalDate() }
                val narrowedRange = if (foundSelector != null) CalendarTime.queryRange(foundSelector.date, foundSelector.days, 30, now)
                    else if (starts.isNotEmpty()) CalendarTime.QueryRange(
                        starts.min().atStartOfDay(now.zone), starts.max().plusDays(1).atStartOfDay(now.zone)) else range
                val narrowed = load(request, request.query, if (unboundedTasks) null else narrowedRange, request.titleQuery)
                if (narrowed.isFailure) return mapFailure(narrowed.exceptionOrNull(), false)
                val ids = narrowed.getOrNull().orEmpty().mapTo(mutableSetOf()) { it.id }
                session.targets.filter { it.id in ids }
            } else session.targets
        } else {
            if (request.query.isNullOrBlank() && sourceDate == null && !(request.objectKind == CalendarObjectKind.TASK && request.allMatches)) {
                session.await(setOf(CalendarField.QUERY, CalendarField.SOURCE_DATE), any = true)
                return CalendarToolResult.Clarification(
                "Какую запись нужно ${if (request.op == CalendarOp.DELETE) "удалить" else "изменить"}? Укажите название или день.",
                )
            }
            session.setPhase(CalendarTaskPhase.RESOLVING)
            CalendarAssistantDebug.log("executor ${request.op}: target_resolution=search, all=${request.allMatches}")
            val loaded = load(request, request.query, if (unboundedTasks) null else range, request.titleQuery)
            if (loaded.isFailure) return mapFailure(loaded.exceptionOrNull(), false)
            loaded.getOrNull().orEmpty()
        }.filter(::matches).filter { it.id.isNotBlank() }.distinctBy { it.id }
        checkSession(token)
        CalendarAssistantDebug.log("executor ${request.op}: candidates=${when (candidates.size) { 0 -> "none"; 1 -> "single"; else -> "multiple" }}")
        val selector = if (fromContext) session.selector?.let { it.copy(query = request.query ?: it.query) } else request.copy(
            date = if (unboundedTasks) null else range.start.toLocalDate().toString(), days = if (unboundedTasks) null else rangeDays(range),
        )
        session.rememberResults(candidates, selector)
        if (candidates.isEmpty()) {
            session.await(setOf(CalendarField.QUERY, CalendarField.SOURCE_DATE, CalendarField.SOURCE_TIME), any = true)
            return CalendarToolResult.Clarification("Подходящих записей нет. Уточните исходное название или дату.")
        }
        require(!request.allMatches || candidates.size <= MAX_BATCH) {
            "Найдено больше $MAX_BATCH событий. Сузьте выборку перед групповым действием."
        }
        val selected = if (request.allMatches || candidates.size == 1) candidates else {
            val chosen = choose(request.op, candidates)
            if (chosen == null) {
                session.finish()
                return CalendarToolResult.Error("Выбор события отменён пользователем")
            }
            listOf(chosen)
        }
        checkSession(token)
        // Freeze the resolved target set while asking for missing update slots.
        session.rememberResults(selected, selector)
        session.rememberDraft(request.copy(contextAction = CalendarContextAction.CONTINUE))
        if (request.op == CalendarOp.UPDATE && request.date == null && request.time == null &&
            request.title == null && request.duration == null && request.endTime == null &&
            request.location == null && request.description == null && request.taskStatus == null && !request.clearDate) {
            if (request.objectKind == CalendarObjectKind.TASK) {
                session.await(setOf(CalendarField.DATE, CalendarField.TITLE, CalendarField.DESCRIPTION, CalendarField.STATUS), any = true)
                return CalendarToolResult.Clarification("Что изменить у выбранных задач: дату, название, описание или статус выполнения?")
            }
            session.await(setOf(CalendarField.DATE, CalendarField.TIME, CalendarField.TITLE, CalendarField.LOCATION, CalendarField.DESCRIPTION), any = true)
            return CalendarToolResult.Clarification("Что изменить у выбранных событий: дату, время, название, место или описание?")
        }
        val plans = ArrayList<Plan>()
        for (event in selected) {
            val fetched = getItem(event)
            if (fetched.isFailure) return mapFailure(fetched.exceptionOrNull(), false)
            val current = fetched.getOrNull() ?: return CalendarToolResult.Error("Запись больше не найдена. Повторите поиск.")
            require(matches(current)) { "Исходные параметры записи изменились. Повторите поиск." }
            require(!request.titleQuery || request.query.isNullOrBlank() || CalendarEventSearch.matches(current, request.query, titleOnly = true)) {
                "Название найденного события изменилось. Повторите поиск перед переименованием."
            }
            plans += Plan(current, if (request.op == CalendarOp.UPDATE) {
                if (current.kind == CalendarObjectKind.TASK) GoogleTaskUpdate.prepare(current, request, now) else CalendarEventUpdate.prepare(current, request, now)
            } else null)
        }
        checkSession(token)
        if (plans.all { it.update?.changed == false }) {
            session.finish(plans.map { it.before })
            return CalendarToolResult.Ok(formatList(plans.map { it.before }, "События уже соответствуют запросу", now))
        }
        val summary = buildString {
            append(if (request.op == CalendarOp.DELETE) "Удалить" else "Изменить")
                .append(if (request.objectKind == CalendarObjectKind.TASK) " задач Google Tasks: " else " событий: ").append(plans.size)
            plans.forEachIndexed { i, plan ->
                append("\n\n").append(i + 1).append(". ").append(plan.before.summary(now))
                if (plan.before.assigned && request.op == CalendarOp.DELETE) append("\nНазначенная задача: удаление затронет и исходную задачу в сервисе назначения.")
                plan.update?.let {
                    append("\nСтанет: ").append(it.after.summary(now))
                    if (request.taskStatus != null) append("\nСтатус: ")
                        .append(if (it.after.taskStatus == GoogleTaskStatus.COMPLETED) "выполнена" else "не выполнена")
                    if (!it.changed) append("\nУже соответствует запросу; запись не требуется.")
                    if (request.location != null) append("\nМесто: ").append(plan.before.location.orEmpty()).append(" → ").append(it.after.location.orEmpty())
                    if (request.description != null) append("\nОписание: ").append(plan.before.description.orEmpty()).append(" → ").append(it.after.description.orEmpty())
                }
            }
        }
        if (!confirm(request, summary)) {
            session.finish()
            return CalendarToolResult.Error("Действие отменено пользователем")
        }
        // Validate the whole confirmed set before the first write; never silently reselect targets.
        for (plan in plans) {
            checkSession(token)
            val checked = getItem(plan.before)
            if (checked.isFailure) return mapFailure(checked.exceptionOrNull(), false)
            require(checked.getOrNull() == plan.before) { "Запись изменилась после подтверждения. Повторите запрос." }
        }
        return applyPlans(request, plans, now, token)
    }

    private suspend fun applyPlans(request: CalendarToolRequest, plans: List<Plan>, now: ZonedDateTime, token: Long): CalendarToolResult {
        session.setPhase(CalendarTaskPhase.EXECUTING)
        val remaining = session.targets.toMutableList()
        val successful = mutableListOf<CalendarEvent>()
        val unchanged = mutableListOf<CalendarEvent>()
        val notRun = mutableListOf<CalendarEvent>()
        var failure: Pair<CalendarEvent, String>? = null
        var completed = 0
        var halted = false
        var ambiguousWrite = false
        for (plan in plans) {
            if (plan.update?.changed == false) {
                completed++
                unchanged += plan.before
                continue
            }
            if (halted || token != generation || !is_connected()) {
                notRun += plan.before
                halted = true
                continue
            }
            CalendarAssistantDebug.log("executor ${request.op}: target_validated=true, confirmed_plan=true")
            val provider = provider(plan.before.kind)
            val result: Result<CalendarEvent?> = if (plan.update == null) provider.deleteEvent(plan.before.id).map { null }
                else provider.updateEvent(plan.before.id, plan.update.payload).map { it.copy(taskListTitle = plan.before.taskListTitle) }
            if (result.isFailure) {
                ambiguousWrite = uncertain(result.exceptionOrNull())
                failure = plan.before to failureMessage(mapFailure(result.exceptionOrNull(), true))
                halted = true
            } else {
                completed++
                remaining.removeAll { it.id == plan.before.id }
                val actual = result.getOrNull() ?: plan.before
                if (plan.update != null) remaining += actual
                successful += actual
            }
        }
        if (token == generation) session.finish(
            if (completed == plans.size) remaining else remaining.filterNot { event -> (successful + unchanged).any { it.id == event.id } },
            uncertain = ambiguousWrite,
        )
        CalendarAssistantDebug.log("executor ${request.op}: batch_result=${if (completed == plans.size) "complete" else "partial_or_stopped"}")
        val text = buildString {
            append("Обработано $completed из ${plans.size}.")
            if (successful.isNotEmpty()) append("\n\n").append(formatList(successful,
                if (request.op == CalendarOp.DELETE) "Удалено" else "Обновлено", now))
            if (unchanged.isNotEmpty()) append("\n\n").append(formatList(unchanged, "Уже соответствовали запросу; запись не выполнялась", now))
            failure?.let { (event, reason) -> append("\n\n").append(formatList(listOf(event), "Ошибка или неподтверждённый результат", now))
                .append("\n").append(reason) }
            if (notRun.isNotEmpty()) append("\n\n").append(formatList(notRun, "Не выполнялось", now))
        }
        return if (completed == plans.size) CalendarToolResult.Ok(text) else CalendarToolResult.Error(text)
    }

    private fun checkGenerationAfterWrite(token: Long) {
        // Do not mutate a new account/session with results from an old in-flight write.
        require(token == generation) { "Подключение изменилось. Проверьте календарь перед повтором записи." }
    }

    private suspend fun choose(op: CalendarOp, candidates: List<CalendarEvent>): CalendarEvent? {
        session.setPhase(CalendarTaskPhase.CHOOSING)
        val choice = CompletableDeferred<String?>()
        targetChoice = choice
        _targetSelection.value = CalendarTargetSelection(op, candidates)
        CalendarAssistantDebug.log("executor $op: outcome=awaiting_target_selection")
        return try {
            val id = choice.await()
            candidates.firstOrNull { it.id == id }
        } finally {
            if (targetChoice === choice) targetChoice = null
            _targetSelection.value = null
        }
    }

    private suspend fun confirm(request: CalendarToolRequest, summary: String): Boolean {
        session.setPhase(CalendarTaskPhase.CONFIRMING)
        val current = CalendarConfirmationGate()
        check(gate == null) { "Calendar confirmation already active" }
        gate = current
        _confirmationSummary.value = summary
        _pendingConfirmation.value = request
        CalendarAssistantDebug.log("executor: outcome=awaiting_confirmation, all=${request.allMatches}")
        return try {
            current.await().also { CalendarAssistantDebug.log("executor: confirmation=$it") }
        } finally {
            if (gate === current) gate = null
            _pendingConfirmation.value = null
            _confirmationSummary.value = null
        }
    }

    private fun rangeDays(range: CalendarTime.QueryRange) = java.time.temporal.ChronoUnit.DAYS
        .between(range.start.toLocalDate(), range.endExclusive.toLocalDate()).toInt()
    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))
    private fun period(range: CalendarTime.QueryRange): String {
        val first = range.start.toLocalDate()
        val last = range.endExclusive.toLocalDate().minusDays(1)
        return if (first == last) "на ${first.format(dateFormat)}" else "с ${first.format(dateFormat)} по ${last.format(dateFormat)}"
    }

    private fun formatList(events: List<CalendarEvent>, header: String, now: ZonedDateTime): String {
        if (events.isEmpty()) return "$header — совпадений нет."
        val dated = events.map { it to it.startInZone(now.zone) }
            .sortedWith(compareBy(nullsLast()) { (_, start) -> start?.toInstant() })
        return buildString {
            append(header).append(":\n\n")
            dated.groupBy { it.second?.toLocalDate() }.entries.forEachIndexed { index, (date, items) ->
                if (index > 0) append("\n\n")
                append("**").append(date?.format(dateFormat) ?: "Без даты").append("**")
                items.forEach { (event, _) ->
                    if (event.kind == CalendarObjectKind.TASK) {
                        append("\n").append(event.taskStatus?.symbol ?: "?").append(" ")
                            .append(event.title.ifBlank { "(без названия)" })
                        event.taskListTitle?.let { append(" (список: ").append(it).append(")") }
                    } else append("\n• ").append(event.timeLabel(now.zone)).append(" — ").append(event.title.ifBlank { "(без названия)" })
                }
            }
            if (events.any { it.kind == CalendarObjectKind.TASK }) append("\n\nВремя задач недоступно через Google Tasks API.")
        }
    }

    private fun uncertain(t: Throwable?): Boolean = t is CalendarApiException &&
        (t.error is CalendarApiError.Network || t.error is CalendarApiError.Server)

    private fun failureMessage(result: CalendarToolResult): String = when (result) {
        is CalendarToolResult.Error -> result.message
        is CalendarToolResult.NotConnected -> result.message
        else -> "Не удалось выполнить действие."
    }

    private fun mapFailure(t: Throwable?, write: Boolean): CalendarToolResult {
        val cause = t?.cause ?: t
        if (cause is CalendarResultLimitException) return CalendarToolResult.Error(requireNotNull(cause.message))
        if (cause is CalendarAuthorizationRequiredException) return CalendarToolResult.NotConnected(
            cause.message ?: "Требуется повторное подключение к календарю.")
        val error = (cause as? CalendarApiException)?.error
        return when (error) {
            CalendarApiError.Unauthorized -> CalendarToolResult.NotConnected("Сессия Google истекла. Подключите аккаунт заново.")
            CalendarApiError.Forbidden -> CalendarToolResult.Error("Недостаточно прав. Подключите аккаунт заново и разрешите Calendar и Google Tasks.")
            is CalendarApiError.Configuration -> CalendarToolResult.Error(error.message)
            is CalendarApiError.InvalidRequest -> CalendarToolResult.Error(error.message)
            CalendarApiError.Conflict -> CalendarToolResult.Error(error.message)
            CalendarApiError.NotFound -> CalendarToolResult.Error("Запись не найдена.")
            CalendarApiError.RateLimited -> CalendarToolResult.Error("Слишком много запросов. Попробуйте позже.")
            is CalendarApiError.Network, is CalendarApiError.Server -> CalendarToolResult.Error(
                if (write) "Результат записи не подтверждён. Проверьте календарь перед повтором."
                else "Не удалось получить данные календаря. Проверьте подключение и повторите запрос.")
            null -> CalendarToolResult.Error("Не удалось выполнить действие. Попробуйте позже.")
        }
    }

    private fun expectedFields(): Set<CalendarField> {
        val d = session.draft ?: return emptySet()
        if (session.awaiting.isNotEmpty()) return session.awaiting
        if (d.objectKind == CalendarObjectKind.TASK) {
            if (d.time != null || d.sourceTime != null || d.duration != null || d.endTime != null) return setOf(CalendarField.TIME)
            if (d.op == CalendarOp.CREATE) return setOf(CalendarField.TITLE)
            if (d.op == CalendarOp.UPDATE) return setOf(CalendarField.TITLE, CalendarField.DATE, CalendarField.DESCRIPTION, CalendarField.STATUS)
            return setOf(CalendarField.QUERY, CalendarField.SOURCE_DATE, CalendarField.LIST)
        }
        return if (d.op == CalendarOp.CREATE) buildSet {
            if (d.title.isNullOrBlank() || d.title.length > CalendarToolRequest.MAX_TITLE) add(CalendarField.TITLE)
            if (d.date == null) add(CalendarField.DATE)
            if (!d.allDay && d.time == null) add(CalendarField.TIME)
        }.ifEmpty { setOf(CalendarField.DATE, CalendarField.TIME, CalendarField.DURATION) }
        else if (d.op == CalendarOp.UPDATE) setOf(CalendarField.DATE, CalendarField.TIME, CalendarField.TITLE)
        else if (d.op == CalendarOp.DELETE) setOf(CalendarField.QUERY, CalendarField.SOURCE_DATE)
        else setOf(CalendarField.QUERY, CalendarField.DATE)
    }

    companion object { const val MAX_BATCH = 100 }
}
