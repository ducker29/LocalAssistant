package com.example.localassistant.calendar

import android.util.Log
import java.time.ZonedDateTime

/**
 * Decorates a [CalendarGateway] with the shared token acquisition path.
 *
 * - Every call first obtains a token through [CalendarAuthManager.ensureToken]; if authorization
 *   must be renewed interactively the call fails with [CalendarAuthorizationRequiredException]
 *   instead of producing an empty or invented result.
 * - A 401 invalidates the rejected token and re-acquires once. Only idempotent operations
 *   (list/search/get) are replayed; writes are never replayed automatically.
 */
class AuthorizedCalendarGateway(
    private val delegate: CalendarGateway,
    private val auth: CalendarAuthorization,
) : CalendarGateway {

    override suspend fun listEvents(
        timeMin: ZonedDateTime?,
        timeMax: ZonedDateTime?,
        limit: Int,
    ): Result<List<CalendarEvent>> = authorized(idempotent = true) {
        delegate.listEvents(timeMin, timeMax, limit)
    }

    override suspend fun searchEvents(
        query: String,
        timeMin: ZonedDateTime?,
        timeMax: ZonedDateTime?,
        limit: Int,
    ): Result<List<CalendarEvent>> = authorized(idempotent = true) {
        delegate.searchEvents(query, timeMin, timeMax, limit)
    }

    override suspend fun getEvent(id: String): Result<CalendarEvent?> =
        authorized(idempotent = true) { delegate.getEvent(id) }

    override suspend fun createEvent(payload: CreateEventPayload): Result<CalendarEvent> =
        authorized(idempotent = false) { delegate.createEvent(payload) }

    override suspend fun updateEvent(id: String, payload: UpdateEventPayload): Result<CalendarEvent> =
        authorized(idempotent = false) { delegate.updateEvent(id, payload) }

    override suspend fun deleteEvent(id: String): Result<Unit> =
        authorized(idempotent = false) { delegate.deleteEvent(id) }

    override suspend fun primaryCalendarName(): String = delegate.primaryCalendarName()

    private suspend fun <T> authorized(
        idempotent: Boolean,
        call: suspend () -> Result<T>,
    ): Result<T> {
        val first = auth.ensureToken()
        if (first !is TokenResult.Ok) {
            return Result.failure(CalendarAuthorizationRequiredException(messageOf(first)))
        }

        var result = call()
        if (result.isUnauthorized()) {
            Log.i(TAG, "401 detected; invalidating token (idempotent=$idempotent)")
            auth.invalidateRejectedToken(first.token)
            if (!idempotent) {
                // Do not replay writes. The 401 means the request was rejected before execution.
                return result
            }
            val second = auth.ensureToken()
            if (second !is TokenResult.Ok || second.token == first.token) {
                return if (second is TokenResult.Ok) result else Result.failure(
                    CalendarAuthorizationRequiredException(messageOf(second))
                )
            }
            result = call()
        }
        return result
    }

    private fun messageOf(result: TokenResult): String = when (result) {
        is TokenResult.RequiresReconnect -> result.message
        is TokenResult.Unavailable -> result.message
        TokenResult.NotEnabled ->
            "Календарь не подключён. Откройте настройки, чтобы войти в Google Календарь."
        is TokenResult.Ok -> ""
    }

    private fun Result<*>.isUnauthorized(): Boolean {
        val error = exceptionOrNull()
        return error is CalendarApiException && error.error is CalendarApiError.Unauthorized
    }

    private companion object {
        const val TAG = "AuthorizedCalGateway"
    }
}
