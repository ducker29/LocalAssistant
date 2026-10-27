package com.example.localassistant.calendar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Confirms a write operation before executing it. The UI completes the deferred once the user
 * taps "Подтвердить" / "Отмена".
 */
class CalendarConfirmationGate {
    private val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
    val pending: Boolean get() = !deferred.isCompleted

    fun confirm() { if (!deferred.isCompleted) deferred.complete(true) }
    fun cancel() { if (!deferred.isCompleted) deferred.complete(false) }
    suspend fun await(): Boolean = deferred.await()
}

data class CalendarTargetSelection(val operation: CalendarOp, val candidates: List<CalendarEvent>)

sealed interface CalendarToolResult {
    /** Human-safe status for the chat. [modelContext] carries IDs/details for the next model turn. */
    data class Ok(val statusText: String, val modelContext: String = "") : CalendarToolResult
    data class Ambiguous(val candidates: List<CalendarEvent>, val hint: String) : CalendarToolResult
    data class NeedConfirmation(val request: CalendarToolRequest, val summary: String) : CalendarToolResult
    data class Error(val message: String) : CalendarToolResult
    data class NotConnected(val message: String) : CalendarToolResult
}

/**
 * Validates [CalendarToolRequest]s, enforces the safety rules, and talks to [CalendarGateway].
 * All write operations (CREATE/UPDATE/DELETE) are gated behind [CalendarConfirmationGate].
 */
class CalendarToolExecutor(
    private val gateway: CalendarGateway,
    private val is_connected: () -> Boolean,
    private val nowProvider: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private val _pendingConfirmation = MutableStateFlow<CalendarToolRequest?>(null)
    val pendingConfirmation: StateFlow<CalendarToolRequest?> = _pendingConfirmation

    private val _confirmationSummary = MutableStateFlow<String?>(null)
    val confirmationSummary: StateFlow<String?> = _confirmationSummary
    private val _targetSelection = MutableStateFlow<CalendarTargetSelection?>(null)
    val targetSelection: StateFlow<CalendarTargetSelection?> = _targetSelection
    private var targetChoice: CompletableDeferred<String?>? = null
    private var recentEvents: List<CalendarEvent> = emptyList()
    private var contextGeneration = 0L

    private var gate: CalendarConfirmationGate? = null

    fun selectTarget(id: String?) {
        val selection = _targetSelection.value ?: return
        if (id != null && selection.candidates.none { it.id == id }) return
        targetChoice?.complete(id)
    }

    fun clearContext() {
        contextGeneration++
        recentEvents = emptyList()
        targetChoice?.complete(null)
        gate?.cancel()
        _targetSelection.value = null
        _pendingConfirmation.value = null
        _confirmationSummary.value = null
    }

    /** Resolve the in-flight write confirmation from the UI (true = confirmed, false = cancelled). */
    fun resolvePending(confirmed: Boolean) {
        val g = gate ?: return
        if (confirmed) g.confirm() else g.cancel()
        gate = null
        _pendingConfirmation.value = null
        _confirmationSummary.value = null
    }

    fun execute(request: CalendarToolRequest): CalendarToolResult {
        if (!is_connected()) {
            return CalendarToolResult.NotConnected(
                "Календарь не подключён. Открой настройки, чтобы войти в Google Календарь."
            )
        }
        val now = nowProvider()
        if (request.op == CalendarOp.UPDATE || request.op == CalendarOp.DELETE) {
            return CalendarToolResult.Error("Для изменения или удаления требуется поиск события через executeSuspend.")
        }
        // Validation
        when (request.op) {
            CalendarOp.CREATE -> {
                val title = request.title?.takeIf { it.isNotBlank() }
                    ?: return CalendarToolResult.Error("Не указано название события")
                if (request.date == null && request.time == null) {
                    return CalendarToolResult.Error("Не указана дата события")
                }
            }
            CalendarOp.UPDATE, CalendarOp.DELETE -> {
                if (request.eventId.isNullOrBlank()) {
                    return CalendarToolResult.Error("Не указан идентификатор события (event_id)")
                }
            }
            CalendarOp.SEARCH -> {
                if (request.query.isNullOrBlank()) {
                    return CalendarToolResult.Error("Не указан поисковый запрос")
                }
            }
            CalendarOp.READ -> {}
        }
        // Write ops require explicit confirmation
        if (request.op == CalendarOp.CREATE || request.op == CalendarOp.UPDATE || request.op == CalendarOp.DELETE) {
            val summary = describe(request, now)
            val g = CalendarConfirmationGate()
            gate = g
            _pendingConfirmation.value = request
            // The coroutine that called execute() must resume the gate; for synchronous callers
            // we surface the NeedConfirmation result and let the VM drive the gate.
            return CalendarToolResult.NeedConfirmation(request, summary)
        }
        // READ / SEARCH: run synchronously is not possible (gateway is suspend); caller uses runBlocking
        // or the VM. Here we throw to force async path.
        error("READ/SEARCH must go through executeSuspend")
    }

    suspend fun executeSuspend(request: CalendarToolRequest): CalendarToolResult {
        val generation = contextGeneration
        CalendarAssistantDebug.log("executor: op=${request.op}, connected=${is_connected()}")
        if (!is_connected()) {
            CalendarAssistantDebug.log("executor result=NotConnected")
            return CalendarToolResult.NotConnected(
                "Календарь не подключён. Открой настройки, чтобы войти в Google Календарь."
            )
        }
        val now = nowProvider()
        when (request.op) {
            CalendarOp.READ, CalendarOp.SEARCH -> {
                val searching = request.op == CalendarOp.SEARCH
                if (searching && request.query.isNullOrBlank()) {
                    return CalendarToolResult.Error("Не указан поисковый запрос")
                }
                val range = try {
                    CalendarTime.queryRange(
                        request.date, request.days,
                        if (searching) CalendarToolRequest.DEFAULT_SEARCH_DAYS else 7,
                        now,
                    )
                } catch (_: java.time.DateTimeException) {
                    return CalendarToolResult.Error("Не удалось определить дату. Уточните день или начало периода.")
                } catch (_: IllegalArgumentException) {
                    return CalendarToolResult.Error("Период должен составлять от 1 до 90 дней.")
                }
                val dateCategory = when (request.date?.lowercase()?.trim()) {
                    null -> "default"
                    "today" -> "today"
                    "tomorrow" -> "tomorrow"
                    else -> "explicit"
                }
                CalendarAssistantDebug.log(
                    "executor ${request.op}: date_parameter_applied=${request.date != null}, " +
                        "date_category=$dateCategory, days=${java.time.temporal.ChronoUnit.DAYS.between(range.start.toLocalDate(), range.endExclusive.toLocalDate())}, " +
                        "query_present=${searching}, boundaries=local_midnight_exclusive_end",
                )
                val result = if (searching) {
                    gateway.searchEvents(request.query.orEmpty(), range.start, range.endExclusive)
                } else gateway.listEvents(range.start, range.endExclusive)
                if (result.isFailure) {
                    CalendarAssistantDebug.log("executor ${request.op}: result=gateway_failure")
                    return mapFailure(result.exceptionOrNull(), isWrite = false)
                }
                val events = result.getOrNull().orEmpty()
                if (generation != contextGeneration) return CalendarToolResult.Error("Подключение календаря изменилось. Повторите запрос.")
                recentEvents = events
                val dateFormat = java.time.format.DateTimeFormatter.ofPattern(
                    "d MMMM yyyy", java.util.Locale.forLanguageTag("ru"),
                )
                val first = range.start.toLocalDate()
                val last = range.endExclusive.toLocalDate().minusDays(1)
                val period = if (first == last) "на ${first.format(dateFormat)}"
                    else "с ${first.format(dateFormat)} по ${last.format(dateFormat)}"
                val header = if (searching) "Результаты поиска $period" else "События $period"
                CalendarAssistantDebug.log("executor ${request.op}: result=Ok")
                return CalendarToolResult.Ok(formatList(events, header), events.toModelContext())
            }
            CalendarOp.UPDATE, CalendarOp.DELETE -> {
                return resolveAndWrite(request, now, generation)
            }
            CalendarOp.CREATE -> {
                if (request.title.isNullOrBlank() || request.date == null || (!request.allDay && request.time == null)) {
                    return CalendarToolResult.Error("Для создания укажите название, дату и время (либо «весь день»).")
                }
                if (!awaitConfirmation(request, describe(request, now))) {
                    return CalendarToolResult.Error("Отменено пользователем")
                }
                if (generation != contextGeneration || !is_connected()) return CalendarToolResult.Error("Подключение календаря изменилось. Повторите запрос.")
                return try {
                    runWrite(request, now)
                } catch (t: java.time.DateTimeException) {
                    CalendarToolResult.Error("Не удалось определить дату или время события.")
                } catch (t: IllegalArgumentException) {
                    CalendarToolResult.Error("Некорректные параметры события. Уточните дату и время.")
                }
            }
        }
    }

    private suspend fun awaitConfirmation(request: CalendarToolRequest, summary: String): Boolean {
        val g = CalendarConfirmationGate()
        check(gate == null) { "Calendar confirmation already active" }
        gate = g
        _confirmationSummary.value = summary
        _pendingConfirmation.value = request
        CalendarAssistantDebug.log("executor: outcome=awaiting_confirmation, target_resolved=${request.op != CalendarOp.CREATE}")
        return try {
            g.await().also { CalendarAssistantDebug.log("executor: confirmation=$it") }
        } finally {
            if (gate === g) gate = null
            _pendingConfirmation.value = null
            _confirmationSummary.value = null
        }
    }

    private suspend fun resolveAndWrite(
        request: CalendarToolRequest,
        now: ZonedDateTime,
        generation: Long,
    ): CalendarToolResult {
        try {
            val sourceDate = request.sourceDate ?: request.date.takeIf { request.op == CalendarOp.DELETE }
            val range = CalendarTime.queryRange(sourceDate, request.days, 30, now)
            val sourceTime = request.sourceTime?.let(CalendarEventUpdate::parseTime)
            fun matchesSource(event: CalendarEvent): Boolean {
                val start = event.startInZone(now.zone)
                val dateMatches = sourceDate == null || (start != null &&
                    start.isBefore(range.endExclusive) && !start.isBefore(range.start))
                val timeMatches = sourceTime == null || (!event.allDay && start != null &&
                    start.hour == sourceTime.hour && start.minute == sourceTime.minute)
                return dateMatches && timeMatches
            }

            // Only IDs from actual API results are eligible. Unknown model IDs are never trusted.
            val known = recentEvents.firstOrNull { it.id == request.eventId }
            val candidates = when {
                !request.query.isNullOrBlank() -> {
                    CalendarAssistantDebug.log("executor ${request.op}: target_resolution=search, source_time_present=${sourceTime != null}")
                    val found = gateway.searchEvents(request.query, range.start, range.endExclusive)
                    if (found.isFailure) return mapFailure(found.exceptionOrNull(), isWrite = false)
                    found.getOrNull().orEmpty()
                }
                known != null -> listOf(known)
                sourceDate != null -> {
                    CalendarAssistantDebug.log("executor ${request.op}: target_resolution=list_by_day")
                    val found = gateway.listEvents(range.start, range.endExclusive)
                    if (found.isFailure) return mapFailure(found.exceptionOrNull(), isWrite = false)
                    found.getOrNull().orEmpty()
                }
                recentEvents.isNotEmpty() -> recentEvents
                else -> return CalendarToolResult.Error("Уточните название и день события или сначала найдите его в календаре.")
            }.filter(::matchesSource).filter { it.id.isNotBlank() }
            if (generation != contextGeneration || !is_connected()) {
                return CalendarToolResult.Error("Подключение календаря изменилось. Повторите запрос.")
            }
            CalendarAssistantDebug.log("executor ${request.op}: candidates=${when (candidates.size) { 0 -> "none"; 1 -> "single"; else -> "multiple" }}, source_date_present=${sourceDate != null}, source_time_present=${sourceTime != null}")
            if (candidates.isEmpty()) return CalendarToolResult.Error(
                "Подходящее событие не найдено. Уточните название, исходный день и время.",
            )
            recentEvents = candidates
            val selected = if (candidates.size == 1) candidates.single() else {
                val choice = CompletableDeferred<String?>()
                targetChoice = choice
                _targetSelection.value = CalendarTargetSelection(request.op, candidates)
                CalendarAssistantDebug.log("executor ${request.op}: outcome=awaiting_target_selection")
                val id = try {
                    choice.await()
                } finally {
                    if (targetChoice === choice) targetChoice = null
                    _targetSelection.value = null
                }
                candidates.firstOrNull { it.id == id }
                    ?: return CalendarToolResult.Error("Выбор события отменён пользователем")
            }
            val fetched = gateway.getEvent(selected.id)
            if (fetched.isFailure) return mapFailure(fetched.exceptionOrNull(), isWrite = false)
            val current = fetched.getOrNull()
                ?: return CalendarToolResult.Error("Событие больше не найдено. Повторите поиск.")
            if (!matchesSource(current)) return CalendarToolResult.Error("Время события изменилось. Повторите поиск.")
            val prepared = if (request.op == CalendarOp.UPDATE) CalendarEventUpdate.prepare(current, request, now) else null
            val summary = buildString {
                if (prepared == null) append("Удалить событие:\n").append(current.summary(now))
                else {
                    append("Изменить событие:\nБыло: ").append(current.summary(now))
                    append("\nСтанет: ").append(prepared.after.summary(now))
                    if (request.location != null) append("\nМесто: ").append(current.location.orEmpty())
                        .append(" → ").append(prepared.after.location.orEmpty())
                    if (request.description != null) append("\nОписание: ").append(current.description.orEmpty())
                        .append(" → ").append(prepared.after.description.orEmpty())
                }
            }
            if (generation != contextGeneration) return CalendarToolResult.Error("Подключение календаря изменилось. Повторите запрос.")
            if (!awaitConfirmation(request.copy(eventId = current.id, title = current.title), summary)) {
                return CalendarToolResult.Error("Отменено пользователем")
            }
            if (generation != contextGeneration || !is_connected()) return CalendarToolResult.Error("Подключение календаря изменилось. Повторите запрос.")
            // Recheck after the user has read the dialog; do not silently change the confirmed plan.
            val checked = gateway.getEvent(current.id)
            if (checked.isFailure) return mapFailure(checked.exceptionOrNull(), isWrite = false)
            if (checked.getOrNull() != current) return CalendarToolResult.Error("Событие изменилось после открытия подтверждения. Повторите запрос.")
            if (generation != contextGeneration || !is_connected()) return CalendarToolResult.Error("Подключение календаря изменилось. Повторите запрос.")
            CalendarAssistantDebug.log("executor ${request.op}: target_validated=true, confirmed_plan=true")
            return if (prepared != null) {
                val written = gateway.updateEvent(current.id, prepared.payload)
                if (written.isFailure) mapFailure(written.exceptionOrNull(), isWrite = true)
                else {
                    val event = written.getOrNull() ?: prepared.after
                    if (generation == contextGeneration) recentEvents = listOf(event)
                    CalendarToolResult.Ok("Событие обновлено:\n${event.summary(now)}", listOf(event).toModelContext())
                }
            } else {
                val deleted = gateway.deleteEvent(current.id)
                if (deleted.isFailure) mapFailure(deleted.exceptionOrNull(), isWrite = true)
                else {
                    if (generation == contextGeneration) recentEvents = recentEvents.filterNot { it.id == current.id }
                    CalendarToolResult.Ok("Событие «${current.title}» удалено")
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (_: java.time.DateTimeException) {
            CalendarAssistantDebug.log("executor ${request.op}: validation_rejected=date_time")
            return CalendarToolResult.Error("Не удалось определить исходную или новую дату/время. Уточните запрос.")
        } catch (t: IllegalArgumentException) {
            CalendarAssistantDebug.log("executor ${request.op}: validation_rejected=parameters")
            // Validation errors are app-defined; never send them to diagnostics with user values.
            return CalendarToolResult.Error(t.message ?: "Некорректные параметры изменения события.")
        }
    }

    suspend fun runWrite(request: CalendarToolRequest, now: ZonedDateTime): CalendarToolResult {
        return when (request.op) {
            CalendarOp.CREATE -> doCreate(request, now)
            CalendarOp.UPDATE -> doUpdate(request, now)
            CalendarOp.DELETE -> doDelete(request)
            else -> CalendarToolResult.Error("Неверная операция для записи")
        }
    }

    private suspend fun doCreate(request: CalendarToolRequest, now: ZonedDateTime): CalendarToolResult {
        val generation = contextGeneration
        val title = request.title ?: return CalendarToolResult.Error("Нет названия")
        val dateStr = request.date
        val zone = now.zone
        val allDay = request.allDay
        val start: ZonedDateTime
        val end: ZonedDateTime
        if (allDay) {
            val localDate = parseDateFlexible(dateStr, now)
            start = CalendarTime.allDayStart(localDate, zone)
            end = CalendarTime.allDayEnd(localDate, zone)
        } else {
            val localDate = parseDateFlexible(dateStr, now)
            start = CalendarTime.resolveStart(localDate, request.time, now)
            end = CalendarTime.resolveEnd(start, request.duration, request.endTime)
        }
        val payload = CreateEventPayload(
            calendarId = "primary",
            title = title,
            start = CalendarTime.rfc3339(start),
            end = CalendarTime.rfc3339(end),
            allDay = allDay,
            description = request.description,
            location = request.location,
        )
        return when (val r = gateway.createEvent(payload)) {
            is Result<*> -> if (r.isSuccess) {
                val event = r.getOrNull()
                if (event != null && generation == contextGeneration) recentEvents = listOf(event)
                val id = event?.id ?: "?"
                CalendarToolResult.Ok(
                    "Событие «$title» создано (${describeWhen(start, end, allDay)})",
                    modelContext = "Создано событие id=$id: $title, ${describeWhen(start, end, allDay)}"
                )
            } else mapFailure(r.exceptionOrNull(), isWrite = true)
        }
    }

    private suspend fun doUpdate(request: CalendarToolRequest, now: ZonedDateTime): CalendarToolResult {
        val id = request.eventId ?: return CalendarToolResult.Error("Нет event_id")
        // Fetch current to detect ambiguity / existence. A failed fetch must not be reported as
        // "not found": unauthorized/network failures have different recovery paths.
        val fetched = gateway.getEvent(id)
        if (fetched.isFailure) {
            return mapFailure(fetched.exceptionOrNull(), isWrite = true)
        }
        val existing = fetched.getOrNull()
        if (existing == null) {
            return CalendarToolResult.Error("Событие не найдено. Возможны совпадения — уточни, какое именно.")
        }
        val payload = CalendarEventUpdate.prepare(existing, request, now).payload
        return when (val r = gateway.updateEvent(id, payload)) {
            is Result<*> -> if (r.isSuccess) {
                val event = r.getOrNull()
                CalendarToolResult.Ok(
                    "Событие «${event?.title ?: request.title}» обновлено",
                    modelContext = "Обновлено событие id=$id"
                )
            } else mapFailure(r.exceptionOrNull(), isWrite = true)
        }
    }

    private suspend fun doDelete(request: CalendarToolRequest): CalendarToolResult {
        val id = request.eventId ?: return CalendarToolResult.Error("Нет event_id")
        val fetched = gateway.getEvent(id)
        if (fetched.isFailure) {
            return mapFailure(fetched.exceptionOrNull(), isWrite = true)
        }
        val existing = fetched.getOrNull()
        if (existing == null) {
            return CalendarToolResult.Error("Событие не найдено")
        }
        return when (val r = gateway.deleteEvent(id)) {
            is Result<*> -> if (r.isSuccess) {
                CalendarToolResult.Ok(
                    "Событие «${existing.title}» удалено",
                    modelContext = "Удалено событие id=$id"
                )
            } else mapFailure(r.exceptionOrNull(), isWrite = true)
        }
    }

    private fun describe(request: CalendarToolRequest, now: ZonedDateTime): String =
        when (request.op) {
            CalendarOp.CREATE -> "Создать: ${request.title ?: "?"} (${request.date ?: "?"} ${request.time ?: "?"})"
            CalendarOp.UPDATE -> "Изменить: ${request.title ?: request.eventId}"
            CalendarOp.DELETE -> "Удалить: ${request.title ?: request.eventId}"
            else -> ""
        }

    private fun describeWhen(start: ZonedDateTime, end: ZonedDateTime, allDay: Boolean): String {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        return if (allDay) {
            start.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        } else {
            "${start.format(fmt)}–${end.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
        }
    }

    private fun formatList(events: List<CalendarEvent>, header: String): String {
        if (events.isEmpty()) return "$header — событий нет."
        val zone = nowProvider().zone
        val dated = events.map { it to it.startInZone(zone) }
            .sortedWith(compareBy(nullsLast()) { (_, start) -> start?.toInstant() })
        val groups = dated.groupBy { (_, start) -> start?.toLocalDate() }
        val dateFormat = java.time.format.DateTimeFormatter.ofPattern(
            "d MMMM yyyy", java.util.Locale.forLanguageTag("ru"),
        )
        return buildString {
            append(header).append(":\n\n")
            groups.entries.forEachIndexed { index, (date, items) ->
                if (index > 0) append("\n\n")
                append("**").append(date?.format(dateFormat) ?: "Без даты").append("**")
                items.forEach { (event, _) ->
                    append("\n• ").append(event.timeLabel(zone)).append(" — ")
                        .append(event.title.ifBlank { "(без названия)" })
                }
            }
        }
    }

    private fun List<CalendarEvent>.toModelContext(): String =
        joinToString("\n") { "id=${it.id}: ${it.summary(nowProvider())}" }

    private fun mapFailure(t: Throwable?, isWrite: Boolean): CalendarToolResult {
        val cause = t?.cause ?: t
        if (cause is CalendarAuthorizationRequiredException) {
            return CalendarToolResult.NotConnected(
                cause.message ?: "Требуется повторное подключение к Google Календарю."
            )
        }
        val apiError: CalendarApiError? = when (cause) {
            is CalendarApiException -> cause.error
            is CalendarApiError -> cause
            else -> null
        }
        return when (apiError) {
            is CalendarApiError.Unauthorized -> CalendarToolResult.NotConnected(
                "Сессия календаря истекла. Подключите календарь заново в настройках."
            )
            is CalendarApiError.Forbidden -> CalendarToolResult.Error(
                "Недостаточно прав для календаря. Проверьте выданные разрешения."
            )
            is CalendarApiError.Configuration -> CalendarToolResult.Error(
                "Google Календарь не настроен для этого приложения."
            )
            is CalendarApiError.NotFound -> CalendarToolResult.Error("Событие не найдено.")
            is CalendarApiError.RateLimited -> CalendarToolResult.Error(
                "Слишком много запросов. Попробуйте позже."
            )
            is CalendarApiError.Server -> if (isWrite) {
                CalendarToolResult.Error(
                    "Сервер не подтвердил результат. Проверьте календарь перед повторной попыткой."
                )
            } else {
                CalendarToolResult.Error("Ошибка сервера календаря. Попробуйте позже.")
            }
            is CalendarApiError.Network -> if (isWrite) {
                CalendarToolResult.Error(
                    "Не удалось подтвердить, выполнено ли действие. Проверьте календарь перед повтором."
                )
            } else {
                CalendarToolResult.Error("Нет сети. Проверьте подключение.")
            }
            null -> CalendarToolResult.Error("Не удалось выполнить действие. Попробуйте позже.")
        }
    }

    private fun parseDateFlexible(value: String?, now: ZonedDateTime): java.time.LocalDate =
        if (value == null) now.toLocalDate()
        else CalendarTime.resolveDate(value, now)

}
