package com.example.localassistant.calendar

import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedCalendarGatewayTest {

    private class FakeAuth(vararg results: TokenResult) : CalendarAuthorization {
        private val queue = ArrayDeque(results.toList())
        var ensureCalls = 0
            private set
        val invalidated = mutableListOf<String?>()

        override suspend fun ensureToken(): TokenResult {
            ensureCalls++
            return queue.removeFirstOrNull() ?: TokenResult.Ok("token-$ensureCalls")
        }

        override suspend fun invalidateRejectedToken(rejected: String?) {
            invalidated += rejected
        }
    }

    private class FakeGateway : CalendarGateway {
        var listCalls = 0
            private set
        var createCalls = 0
            private set
        var listResults: MutableList<Result<List<CalendarEvent>>> =
            mutableListOf(Result.success(emptyList()))
        var createResult: () -> Result<CalendarEvent> = {
            Result.success(event("created"))
        }

        override suspend fun listEvents(
            timeMin: ZonedDateTime?,
            timeMax: ZonedDateTime?,
            limit: Int,
        ): Result<List<CalendarEvent>> {
            listCalls++
            return if (listResults.size > 1) listResults.removeAt(0) else listResults.first()
        }

        override suspend fun searchEvents(
            query: String,
            timeMin: ZonedDateTime?,
            timeMax: ZonedDateTime?,
            limit: Int,
        ): Result<List<CalendarEvent>> = Result.success(emptyList())

        override suspend fun getEvent(id: String): Result<CalendarEvent?> =
            Result.success(event(id))

        override suspend fun createEvent(payload: CreateEventPayload): Result<CalendarEvent> {
            createCalls++
            return createResult()
        }

        override suspend fun updateEvent(
            id: String,
            payload: UpdateEventPayload,
        ): Result<CalendarEvent> = Result.success(event(id))

        override suspend fun deleteEvent(id: String): Result<Unit> = Result.success(Unit)

        override suspend fun primaryCalendarName(): String = "primary"
    }

    @Test
    fun readRetriesOnceWithNewTokenAfter401() = runTest {
        val auth = FakeAuth(TokenResult.Ok("first"), TokenResult.Ok("second"))
        val delegate = FakeGateway().apply {
            listResults = mutableListOf(
                unauthorized(),
                Result.success(listOf(event("ok"))),
            )
        }
        val gateway = AuthorizedCalendarGateway(delegate, auth)

        val result = gateway.listEvents(null, null, 25)

        assertTrue(result.isSuccess)
        assertEquals(2, delegate.listCalls)
        assertEquals(2, auth.ensureCalls)
        assertEquals(listOf("first"), auth.invalidated)
    }

    @Test
    fun writeIsNeverReplayedAfter401() = runTest {
        val auth = FakeAuth(TokenResult.Ok("first"), TokenResult.Ok("second"))
        val delegate = FakeGateway().apply { createResult = { unauthorized() } }
        val gateway = AuthorizedCalendarGateway(delegate, auth)

        val result = gateway.createEvent(payload())

        assertTrue(result.isFailure)
        assertEquals(1, delegate.createCalls)
        assertEquals(1, auth.ensureCalls)
        assertEquals(listOf("first"), auth.invalidated)
    }

    @Test
    fun readFailsWithAuthorizationRequiredWhenReconnectNeeded() = runTest {
        val auth = FakeAuth(
            TokenResult.Ok("first"),
            TokenResult.RequiresReconnect("Нужно повторное подключение"),
        )
        val delegate = FakeGateway().apply { listResults = mutableListOf<Result<List<CalendarEvent>>>(unauthorized()) }
        val gateway = AuthorizedCalendarGateway(delegate, auth)

        val result = gateway.listEvents(null, null, 25)

        assertTrue(result.exceptionOrNull() is CalendarAuthorizationRequiredException)
        assertEquals(1, delegate.listCalls)
    }

    @Test
    fun delegateIsNotCalledWithoutToken() = runTest {
        val auth = FakeAuth(TokenResult.NotEnabled)
        val delegate = FakeGateway()
        val gateway = AuthorizedCalendarGateway(delegate, auth)

        val result = gateway.listEvents(null, null, 25)

        assertTrue(result.exceptionOrNull() is CalendarAuthorizationRequiredException)
        assertEquals(0, delegate.listCalls)
    }

    @Test
    fun readIsNotReplayedWhenTokenDidNotChange() = runTest {
        val auth = FakeAuth(TokenResult.Ok("same"), TokenResult.Ok("same"))
        val delegate = FakeGateway().apply { listResults = mutableListOf<Result<List<CalendarEvent>>>(unauthorized()) }
        val gateway = AuthorizedCalendarGateway(delegate, auth)

        val result = gateway.listEvents(null, null, 25)

        assertTrue(result.isFailure)
        assertEquals(1, delegate.listCalls)
        assertEquals(2, auth.ensureCalls)
    }

    private companion object {
        fun event(id: String) = CalendarEvent(
            id = id,
            title = "Event",
            start = null,
            end = null,
            allDay = false,
            location = null,
            description = null,
        )

        fun unauthorized(): Result<Nothing> =
            Result.failure(CalendarApiException(CalendarApiError.Unauthorized))

        fun payload() = CreateEventPayload(
            calendarId = "primary",
            title = "Event",
            start = "2026-10-28T10:00:00+03:00",
            end = null,
            allDay = false,
        )
    }
}
