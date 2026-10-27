package com.example.localassistant.calendar

import java.io.IOException
import java.time.ZonedDateTime

/** Why a calendar API call failed, mapped for the UI/model. */
sealed interface CalendarApiError {
    val message: String
    data object Unauthorized : CalendarApiError { override val message get() = "Не авторизован" }
    data object Forbidden : CalendarApiError { override val message get() = "Недостаточно прав" }
    data class Configuration(val detail: String?) : CalendarApiError {
        override val message get() = detail ?: "Календарь не настроен для приложения"
    }
    data object NotFound : CalendarApiError { override val message get() = "Не найдено" }
    data object RateLimited : CalendarApiError { override val message get() = "Слишком много запросов" }
    data class InvalidRequest(override val message: String = "Некорректные параметры запроса") : CalendarApiError
    data object Conflict : CalendarApiError { override val message get() = "Объект изменился. Повторите поиск." }
    data class Server(val detail: String?) : CalendarApiError { override val message get() = detail ?: "Ошибка сервера" }
    data class Network(val detail: String?) : CalendarApiError { override val message get() = detail ?: "Сетевая ошибка" }
}

/**
 * Carries a [CalendarApiError] through [Result] without losing its category. The gateway never
 * places tokens, headers or raw bodies into this type.
 */
class CalendarApiException(val error: CalendarApiError) : IOException(error.message)

/**
 * Raised when a calendar call cannot start because authorization must be renewed interactively.
 * This is not a network failure and must never be mistaken for a successful empty result.
 */
class CalendarAuthorizationRequiredException(message: String) : IOException(message)

class CalendarResultLimitException : IOException("Слишком много объектов. Уточните период, список или условия поиска.")

/**
 * The five calendar operations. Implementations must never leak tokens, cookies or raw HTTP
 * bodies into logs; only stable, user-safe strings cross this boundary.
 */
interface CalendarGateway {

    companion object {
        const val MAX_RESULTS = 500
    }

    suspend fun listEvents(
        timeMin: ZonedDateTime?,
        timeMax: ZonedDateTime?,
        limit: Int = MAX_RESULTS,
    ): Result<List<CalendarEvent>>

    suspend fun searchEvents(
        query: String,
        timeMin: ZonedDateTime?,
        timeMax: ZonedDateTime?,
        limit: Int = MAX_RESULTS,
    ): Result<List<CalendarEvent>>

    suspend fun getEvent(id: String): Result<CalendarEvent?>

    suspend fun createEvent(payload: CreateEventPayload): Result<CalendarEvent>

    suspend fun updateEvent(id: String, payload: UpdateEventPayload): Result<CalendarEvent>

    suspend fun deleteEvent(id: String): Result<Unit>

    suspend fun primaryCalendarName(): String
}

data class CreateEventPayload(
    val calendarId: String,
    val title: String,
    val start: String,
    val end: String?,
    val allDay: Boolean,
    val description: String? = null,
    val location: String? = null,
    val taskStatus: GoogleTaskStatus? = null,
)

data class UpdateEventPayload(
    val title: String?,
    val start: String?,
    val end: String?,
    val allDay: Boolean? = null,
    val description: String? = null,
    val location: String? = null,
    val taskStatus: GoogleTaskStatus? = null,
    val clearDate: Boolean = false,
)

internal data class PreparedCalendarUpdate(val payload: UpdateEventPayload, val after: CalendarEvent, val changed: Boolean)
