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

/**
 * The five calendar operations. Implementations must never leak tokens, cookies or raw HTTP
 * bodies into logs; only stable, user-safe strings cross this boundary.
 */
interface CalendarGateway {

    suspend fun listEvents(
        timeMin: ZonedDateTime?,
        timeMax: ZonedDateTime?,
        limit: Int = 25,
    ): Result<List<CalendarEvent>>

    suspend fun searchEvents(
        query: String,
        timeMin: ZonedDateTime?,
        timeMax: ZonedDateTime?,
        limit: Int = 25,
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
)

data class UpdateEventPayload(
    val title: String?,
    val start: String?,
    val end: String?,
    val allDay: Boolean? = null,
    val description: String? = null,
    val location: String? = null,
)
