package com.example.localassistant.calendar

import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stateful scenarios, not copies of private screenshot event data. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarMoveContinuationTest {
    private val now = ZonedDateTime.parse("2027-05-04T12:00:00+03:00[Europe/Moscow]")

    @Test fun `na twelve chasov is destination clock time and goes straight to confirmation`() = runTest {
        val api = Events()
        val executor = executor(api)
        readThursday(executor)
        val plan = executor.prepareTurn("Перенеси разбор отчёта на 12 часов.")
        assertTrue(plan.clarificationFields.isEmpty())
        assertEquals(CalendarFieldValue.Text("12:00"), plan.seed.values[CalendarField.TIME])
        assertFalse(CalendarField.DURATION in plan.seed.values)
        val pending = async { executor.executeSuspend(executor.commandFor(plan, null)) }
        runCurrent()
        assertNotNull(executor.pendingConfirmation.value)
        assertEquals(0, api.writes)
        executor.resolvePending(true)
        assertTrue(pending.await() is CalendarToolResult.Ok)
        assertLocal(api.item("review").start, "2027-05-06T12:00:00+03:00")
        assertLocal(api.item("review").end, "2027-05-06T13:00:00+03:00")
        assertEquals(1, api.writes)
    }

    @Test fun `relative shift asks clock but retains action target and bare reply resumes same task`() = runTest {
        val api = Events()
        val executor = executor(api)
        readThursday(executor)
        val ambiguous = executor.prepareTurn("Перенеси разбор отчёта на 2 часа позже")
        assertEquals(setOf(CalendarField.TIME), ambiguous.clarificationFields)
        val response = executor.executeSuspend(executor.commandFor(ambiguous, null))
        assertTrue(response is CalendarToolResult.Clarification)
        assertNull(executor.pendingConfirmation.value)
        assertEquals(0, api.writes)
        val task = JSONObject(executor.plannerContext()).getLong("task_id")
        assertEquals("UPDATE", JSONObject(executor.plannerContext()).getString("operation"))
        assertEquals("time", JSONObject(executor.plannerContext()).getJSONArray("awaiting").getString(0))
        // A non-calendar remark is not an explicit cancellation or replacement operation.
        assertEquals(CalendarTurnTransition.GENERAL, executor.prepareTurn("Спасибо").transition)
        assertEquals(task, JSONObject(executor.plannerContext()).getLong("task_id"))
        val reply = executor.prepareTurn("12")
        assertEquals(CalendarTurnTransition.CONTINUE, reply.transition)
        assertEquals(CalendarOp.UPDATE, reply.operation)
        assertEquals(CalendarFieldValue.Text("12:00"), reply.seed.values[CalendarField.TIME])
        val pending = async { executor.executeSuspend(executor.commandFor(reply, null)) }
        runCurrent()
        assertNotNull(executor.pendingConfirmation.value)
        assertEquals(task, JSONObject(executor.plannerContext()).getLong("task_id"))
        assertEquals(0, api.writes)
        executor.resolvePending(true)
        assertTrue(pending.await() is CalendarToolResult.Ok)
        assertEquals(listOf("review"), api.idsWritten)
        assertLocal(api.item("review").start, "2027-05-06T12:00:00+03:00")
        assertLocal(api.item("inspection").start, "2027-05-06T15:30:00+03:00")
    }

    @Test fun `invalid repeat keeps waiting for TIME instead of interpreting next bare number as a date`() = runTest {
        val api = Events()
        val executor = executor(api)
        readThursday(executor)
        val initial = executor.prepareTurn("Перенеси разбор отчёта на 25 часов")
        assertTrue(executor.executeSuspend(executor.commandFor(initial, null)) is CalendarToolResult.Clarification)
        val invalid = executor.prepareTurn("90")
        assertEquals(setOf(CalendarField.TIME), invalid.clarificationFields)
        assertTrue(executor.executeSuspend(executor.commandFor(invalid, null)) is CalendarToolResult.Clarification)
        val correct = executor.prepareTurn("12")
        assertFalse(CalendarField.DATE in correct.seed.values)
        assertEquals(CalendarFieldValue.Text("12:00"), correct.seed.values[CalendarField.TIME])
        val pending = async { executor.executeSuspend(executor.commandFor(correct, null)) }
        runCurrent(); assertNotNull(executor.pendingConfirmation.value)
        executor.resolvePending(true)
        assertTrue(pending.await() is CalendarToolResult.Ok)
        assertLocal(api.item("review").start, "2027-05-06T12:00:00+03:00")
    }

    @Test fun `weekday date-only move preserves real original time and duration`() = runTest {
        val api = Events()
        val executor = executor(api)
        readThursday(executor)
        val plan = executor.prepareTurn("Перенеси разбор отчёта на следующую пятницу")
        assertFalse(CalendarField.TIME in plan.seed.values)
        val pending = async { executor.executeSuspend(executor.commandFor(plan, null)) }
        runCurrent(); assertNotNull(executor.pendingConfirmation.value)
        assertEquals(0, api.writes)
        executor.resolvePending(true)
        assertTrue(pending.await() is CalendarToolResult.Ok)
        assertLocal(api.item("review").start, "2027-05-14T11:15:00+03:00")
        assertLocal(api.item("review").end, "2027-05-14T12:15:00+03:00")
    }

    @Test fun `explicit day and time moves across dates and original source-day window never loses moved ID`() = runTest {
        val api = Events()
        val executor = executor(api)
        readThursday(executor)
        suspend fun confirmed(text: String) {
            val plan = executor.prepareTurn(text)
            assertTrue(plan.useResultSet)
            val pending = async { executor.executeSuspend(executor.commandFor(plan, null)) }
            runCurrent(); assertNotNull(executor.pendingConfirmation.value)
            executor.resolvePending(true)
            assertTrue(pending.await() is CalendarToolResult.Ok)
        }
        confirmed("Перенеси разбор отчёта на 18 мая в 17:00")
        assertLocal(api.item("review").start, "2027-05-18T17:00:00+03:00")
        // A changed query still addresses the known API ID now outside the original Thursday.
        confirmed("Перенеси разбор на 20 число на 16 часов")
        assertLocal(api.item("review").start, "2027-05-20T16:00:00+03:00")
        assertLocal(api.item("review").end, "2027-05-20T17:00:00+03:00")
        assertEquals(listOf("review", "review"), api.idsWritten)
        assertLocal(api.item("inspection").start, "2027-05-06T15:30:00+03:00")
    }

    @Test fun `explicit new read replaces unfinished move and cancel cannot be resumed by a bare number`() = runTest {
        val api = Events()
        val executor = executor(api)
        readThursday(executor)
        val move = executor.prepareTurn("Перенеси разбор отчёта на 30 минут позже")
        assertTrue(executor.executeSuspend(executor.commandFor(move, null)) is CalendarToolResult.Clarification)
        val pendingTask = JSONObject(executor.plannerContext()).getLong("task_id")
        val read = executor.prepareTurn("Какие события в пятницу?")
        assertTrue(executor.executeSuspend(executor.commandFor(read, null)) is CalendarToolResult.Ok)
        val state = JSONObject(executor.plannerContext())
        assertNotEquals(pendingTask, state.getLong("task_id"))
        assertTrue(state.isNull("operation"))
        assertNotEquals(CalendarTurnTransition.CONTINUE, executor.prepareTurn("12").transition)
        executor.clearContext()
        assertNotEquals(CalendarOp.UPDATE, executor.prepareTurn("12").operation)
        assertEquals(0, api.writes)
    }

    @Test fun `explicit duration stays duration and is never silently interpreted as clock time`() = runTest {
        for (label in listOf("длительностью", "продолжительностью")) {
            val api = Events()
            val executor = executor(api)
            readThursday(executor)
            val plan = executor.prepareTurn("Измени разбор отчёта $label 2 часа")
            assertEquals(CalendarFieldValue.Text("2h"), plan.seed.values[CalendarField.DURATION])
            assertFalse(CalendarField.TIME in plan.seed.values)
            val pending = async { executor.executeSuspend(executor.commandFor(plan, null)) }
            runCurrent(); assertNotNull(executor.pendingConfirmation.value)
            assertEquals(0, api.writes)
            executor.resolvePending(false)
            assertTrue(pending.await() is CalendarToolResult.Error)
            assertEquals(0, api.writes)
        }
    }

    private fun executor(api: Events) = CalendarToolExecutor(api, { true }, { now })
    private suspend fun readThursday(executor: CalendarToolExecutor) {
        val plan = executor.prepareTurn("Какие события в четверг?")
        assertTrue(executor.executeSuspend(executor.commandFor(plan, null)) is CalendarToolResult.Ok)
    }
    private fun assertLocal(actual: String?, expected: String) {
        assertEquals(ZonedDateTime.parse(expected).toInstant(), ZonedDateTime.parse(requireNotNull(actual)).toInstant())
    }

    private class Events : CalendarGateway {
        var writes = 0
        val idsWritten = mutableListOf<String>()
        private val data = linkedMapOf(
            "review" to event("review", "Разбор отчёта", "2027-05-06T11:15:00+03:00"),
            "inspection" to event("inspection", "Проверка оборудования", "2027-05-06T15:30:00+03:00"),
        )
        fun item(id: String) = data.getValue(id)
        override suspend fun listEvents(timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int) = Result.success(data.values.filter {
            val start = ZonedDateTime.parse(it.start); val end = ZonedDateTime.parse(it.end)
            (timeMin == null || end.isAfter(timeMin)) && (timeMax == null || start.isBefore(timeMax))
        })
        override suspend fun searchEvents(query: String, timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int) = listEvents(timeMin, timeMax, limit)
        override suspend fun getEvent(id: String): Result<CalendarEvent?> = Result.success(data[id])
        override suspend fun createEvent(payload: CreateEventPayload): Result<CalendarEvent> = error("No creation in move test")
        override suspend fun deleteEvent(id: String): Result<Unit> = error("No deletion in move test")
        override suspend fun updateEvent(id: String, payload: UpdateEventPayload): Result<CalendarEvent> {
            val actual = item(id).copy(start = payload.start ?: item(id).start, end = payload.end ?: item(id).end, title = payload.title ?: item(id).title)
            writes++; idsWritten.add(id); data[id] = actual
            return Result.success(actual)
        }
        override suspend fun primaryCalendarName() = "primary"
        companion object {
            private fun event(id: String, title: String, time: String) = CalendarEvent(id, title, time,
                ZonedDateTime.parse(time).plusHours(1).toInstant().toString(), false, null, null)
        }
    }
}
