package com.example.localassistant.calendar

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private var gate: CalendarConfirmationGate? = null

    /** Resolve the in-flight write confirmation from the UI (true = confirmed, false = cancelled). */
    fun resolvePending(confirmed: Boolean) {
        val g = gate ?: return
        if (confirmed) g.confirm() else g.cancel()
        gate = null
        _pendingConfirmation.value = null
    }

    fun execute(request: CalendarToolRequest): CalendarToolResult {
        if (!is_connected()) {
            return CalendarToolResult.NotConnected(
                "Календарь не подключён. Открой настройки, чтобы войти в Google Календарь."
            )
        }
        val now = nowProvider()
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
        Log.i(TAG, "executor: op=${request.op}, date=${request.date}, query=${request.query}, days=${request.days}, connected=${is_connected()}")
        if (!is_connected()) {
            Log.w(TAG, "executor: NOT CONNECTED — returning NotConnected")
            return CalendarToolResult.NotConnected(
                "Календарь не подключён. Открой настройки, чтобы войти в Google Календарь."
            )
        }
        val now = nowProvider()
        when (request.op) {
            CalendarOp.READ -> {
                val min = now
                val max = now.plusDays(7)
                Log.i(TAG, "executor READ: listing ${min} .. ${max} (date field=${request.date} ignored)")
                return when (val r = gateway.listEvents(min, max)) {
                    is Result<*> -> if (r.isSuccess) {
                        val events = r.getOrNull().orEmpty()
                        val text = formatList(events, "На этой неделе")
                        Log.i(TAG, "executor READ: success, ${events.size} events")
                        CalendarToolResult.Ok(text, events.toModelContext())
                    } else {
                        Log.w(TAG, "executor READ: gateway failure: ${r.exceptionOrNull()?.message}")
                        mapFailure(r.exceptionOrNull(), isWrite = false)
                    }
                }
            }
            CalendarOp.SEARCH -> {
                val days = request.days?.coerceIn(CalendarToolRequest.MIN_DAYS, CalendarToolRequest.MAX_DAYS)
                    ?: CalendarToolRequest.DEFAULT_SEARCH_DAYS
                val min = now
                val max = now.plusDays(days.toLong())
                val query = request.query ?: ""
                Log.i(TAG, "executor SEARCH: query='$query', ${min} .. ${max} (days=$days)")
                return when (val r = gateway.searchEvents(query, min, max)) {
                    is Result<*> -> if (r.isSuccess) {
                        val events = r.getOrNull().orEmpty()
                        val text = formatList(events, "Найдено: ${events.size}")
                        Log.i(TAG, "executor SEARCH: success, ${events.size} events")
                        CalendarToolResult.Ok(text, events.toModelContext())
                    } else {
                        Log.w(TAG, "executor SEARCH: gateway failure: ${r.exceptionOrNull()?.message}")
                        mapFailure(r.exceptionOrNull(), isWrite = false)
                    }
                }
            }
            CalendarOp.CREATE, CalendarOp.UPDATE, CalendarOp.DELETE -> {
                // Write ops: require confirmation via the gate.
                val g = CalendarConfirmationGate()
                gate = g
                _pendingConfirmation.value = request
                val confirmed = g.await()
                _pendingConfirmation.value = null
                if (!confirmed) {
                    return CalendarToolResult.Error("Отменено пользователем")
                }
                return runWrite(request, now)
            }
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
        val payload = UpdateEventPayload(
            title = request.title,
            start = request.date?.let {
                if (request.allDay) {
                    val ld = parseDateFlexible(it, now)
                    CalendarTime.rfc3339(CalendarTime.allDayStart(ld, now.zone))
                } else {
                    val ld = parseDateFlexible(it, now)
                    val s = CalendarTime.resolveStart(ld, request.time, now)
                    CalendarTime.rfc3339(s)
                }
            },
            end = request.date?.let {
                if (request.allDay) {
                    val ld = parseDateFlexible(it, now)
                    CalendarTime.rfc3339(CalendarTime.allDayEnd(ld, now.zone))
                } else {
                    val ld = parseDateFlexible(it, now)
                    val s = CalendarTime.resolveStart(ld, request.time, now)
                    val e = CalendarTime.resolveEnd(s, request.duration, request.endTime)
                    CalendarTime.rfc3339(e)
                }
            },
            allDay = request.allDay.takeIf { request.date != null },
            description = request.description,
            location = request.location,
        )
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
        val now = nowProvider()
        val lines = events.take(10).map { it.summary(now) }
        return "$header:\n" + lines.joinToString("\n") { "• " + it }
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

    companion object {
        private const val TAG = "CalToolExec"
    }
}
