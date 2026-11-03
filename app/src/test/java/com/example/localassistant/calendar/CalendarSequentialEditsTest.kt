package com.example.localassistant.calendar

import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic analogue of a reported conversation; no screenshots/private event data. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarSequentialEditsTest {
    private val clock = ZonedDateTime.parse("2026-11-03T12:00:00+03:00[Europe/Moscow]")

    @Test fun `second named event from original list requires its own confirmation and real write`() = runTest {
        val gateway = FakeCalendar()
        val executor = CalendarToolExecutor(gateway, { true }, { clock })
        readFriday(executor)
        val firstPlan = executor.prepareTurn("Перенеси созвон с коллегой с 21 на 19")
        assertTrue(firstPlan.useResultSet)
        val first = async { executor.executeSuspend(executor.commandFor(firstPlan, null)) }
        runCurrent()
        assertNotNull(executor.pendingConfirmation.value)
        assertEquals(0, gateway.written.size)
        executor.resolvePending(true)
        assertTrue(first.await() is CalendarToolResult.Ok)
        assertEquals(19, gateway.hour("call"))

        val secondPlan = executor.prepareTurn("а проверку оборудования перенеси на 16")
        assertEquals(CalendarOp.UPDATE, secondPlan.operation)
        assertEquals(CalendarTurnTransition.TARGETS, secondPlan.transition)
        assertTrue(secondPlan.useResultSet)
        val second = async { executor.executeSuspend(executor.commandFor(secondPlan, null)) }
        runCurrent()
        assertNull(executor.targetSelection.value) // A same-named event on another day must not leak in.
        assertNotNull(executor.pendingConfirmation.value)
        assertEquals(1, gateway.written.size)
        assertEquals(13, gateway.hour("inspection"))
        executor.resolvePending(true)
        val result = second.await()
        assertTrue(result is CalendarToolResult.Ok)
        assertEquals(listOf("call", "inspection"), gateway.written)
        assertEquals(16, gateway.hour("inspection"))
        assertEquals(13, gateway.hour("next-week-inspection"))
        assertEquals(19, gateway.hour("call"))
        val text = (result as CalendarToolResult.Ok).statusText
        assertTrue(text.contains("Проверка оборудования"))
        assertFalse(text.contains("Созвон с коллегой"))

        // Repeating an already applied request is a real no-op, not a simulated update.
        val repeated = executor.prepareTurn("проверку оборудования перенеси на 16")
        val unchanged = executor.executeSuspend(executor.commandFor(repeated, null))
        assertTrue(unchanged is CalendarToolResult.Ok)
        assertTrue((unchanged as CalendarToolResult.Ok).statusText.contains("уже соответствуют"))
        assertNull(executor.pendingConfirmation.value)
        assertEquals(2, gateway.written.size)
    }

    @Test fun `cancelling second confirmation leaves API data unchanged and allows later retry`() = runTest {
        val gateway = FakeCalendar()
        val executor = CalendarToolExecutor(gateway, { true }, { clock })
        readFriday(executor)
        val first = executor.prepareTurn("Перенеси созвон с коллегой с 21 на 19")
        val pendingFirst = async { executor.executeSuspend(executor.commandFor(first, null)) }
        runCurrent(); executor.resolvePending(true); pendingFirst.await()
        val second = executor.prepareTurn("Проверку оборудования перенеси на 16")
        val pendingSecond = async { executor.executeSuspend(executor.commandFor(second, null)) }
        runCurrent()
        assertNotNull(executor.pendingConfirmation.value)
        executor.resolvePending(false)
        assertTrue(pendingSecond.await() is CalendarToolResult.Error)
        assertEquals(listOf("call"), gateway.written)
        assertEquals(13, gateway.hour("inspection"))
        val retry = executor.prepareTurn("Перенеси проверку оборудования на 16")
        val pendingRetry = async { executor.executeSuspend(executor.commandFor(retry, null)) }
        runCurrent()
        assertNotNull(executor.pendingConfirmation.value)
        executor.resolvePending(true)
        assertTrue(pendingRetry.await() is CalendarToolResult.Ok)
        assertEquals(listOf("call", "inspection"), gateway.written)
    }

    @Test fun `provider failure never produces an updated report`() = runTest {
        val gateway = FakeCalendar().apply { failWrites = true }
        val executor = CalendarToolExecutor(gateway, { true }, { clock })
        readFriday(executor)
        val plan = executor.prepareTurn("Проверку оборудования перенеси на 16")
        val pending = async { executor.executeSuspend(executor.commandFor(plan, null)) }
        runCurrent()
        assertNotNull(executor.pendingConfirmation.value)
        executor.resolvePending(true)
        val result = pending.await()
        assertTrue(result is CalendarToolResult.Error)
        assertFalse((result as CalendarToolResult.Error).message.contains("Обновлено"))
        assertEquals(13, gateway.hour("inspection"))
    }

    @Test fun `unresolved calendar actions require clarification instead of ordinary model generation`() = runTest {
        val executor = CalendarToolExecutor(FakeCalendar(), { true }, { clock })
        readFriday(executor)
        listOf("а другое на 16", "проверку оборудования передвинь к вечеру",
            "проверку оборудования перенеси когда-нибудь", "«Проверка оборудования» на 16",
            "другое событие перенеси на 16").forEach { text ->
            val plan = executor.prepareTurn(text)
            assertEquals(text, CalendarTurnTransition.CLARIFY, plan.transition)
            assertNotNull(plan.message)
            assertFalse(plan.resetOnClarify)
        }
    }

    @Test fun `quoted subject before time command is parsed but quoted command in a new title is data`() = runTest {
        val executor = CalendarToolExecutor(FakeCalendar(), { true }, { clock })
        readFriday(executor)
        val move = executor.prepareTurn("«Проверка оборудования» перенеси на 16")
        assertEquals(CalendarOp.UPDATE, move.operation)
        assertTrue(move.useResultSet)
        assertEquals(CalendarFieldValue.Text("Проверка оборудования"), move.seed.values[CalendarField.QUERY])
        val create = executor.prepareTurn("Создай событие «Перенеси на 16» завтра в 10 часов")
        assertEquals(CalendarOp.CREATE, create.operation)
        assertEquals(CalendarFieldValue.Text("Перенеси на 16"), create.seed.values[CalendarField.TITLE])
        assertEquals(CalendarFieldValue.Text("10:00"), create.seed.values[CalendarField.TIME])
    }

    private suspend fun readFriday(executor: CalendarToolExecutor) {
        val read = executor.prepareTurn("Какие события в пятницу?")
        val result = executor.executeSuspend(executor.commandFor(read, null))
        assertTrue(result is CalendarToolResult.Ok)
        val text = (result as CalendarToolResult.Ok).statusText
        assertTrue(text.contains("Созвон с коллегой"))
        assertTrue(text.contains("Проверка оборудования"))
    }

    private class FakeCalendar : CalendarGateway {
        val written = mutableListOf<String>()
        var failWrites = false
        private val events = linkedMapOf(
            "call" to event("call", "Созвон с коллегой", "2026-11-06T21:00:00+03:00"),
            "inspection" to event("inspection", "Проверка оборудования", "2026-11-06T13:00:00+03:00"),
            "next-week-inspection" to event("next-week-inspection", "Проверка оборудования", "2026-11-13T13:00:00+03:00"),
        )
        fun hour(id: String) = ZonedDateTime.parse(events.getValue(id).start)
            .withZoneSameInstant(java.time.ZoneId.of("Europe/Moscow")).hour
        override suspend fun listEvents(timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int) = Result.success(events.values.filter { event ->
            val start = ZonedDateTime.parse(event.start)
            val end = ZonedDateTime.parse(event.end)
            (timeMin == null || end.isAfter(timeMin)) && (timeMax == null || start.isBefore(timeMax))
        })
        override suspend fun searchEvents(query: String, timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int) = listEvents(timeMin, timeMax, limit)
        override suspend fun getEvent(id: String): Result<CalendarEvent?> = Result.success(events[id])
        override suspend fun createEvent(payload: CreateEventPayload): Result<CalendarEvent> = error("Not used")
        override suspend fun updateEvent(id: String, payload: UpdateEventPayload): Result<CalendarEvent> {
            if (failWrites) return Result.failure(CalendarApiException(CalendarApiError.Forbidden))
            val before = events.getValue(id)
            val actual = before.copy(title = payload.title ?: before.title, start = payload.start ?: before.start,
                end = payload.end ?: before.end, allDay = payload.allDay ?: before.allDay)
            written.add(id); events[id] = actual
            return Result.success(actual)
        }
        override suspend fun deleteEvent(id: String): Result<Unit> = error("Not used")
        override suspend fun primaryCalendarName() = "primary"
        companion object {
            private fun event(id: String, title: String, start: String) = CalendarEvent(id, title, start,
                ZonedDateTime.parse(start).plusHours(1).toInstant().toString(), false, null, null)
        }
    }
}
