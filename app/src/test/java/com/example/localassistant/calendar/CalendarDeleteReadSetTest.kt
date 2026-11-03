package com.example.localassistant.calendar

import java.time.LocalDate
import java.time.ZoneId
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

/** Read→named delete uses actual returned IDs, independent of the literal event title. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarDeleteReadSetTest {
    private val now = ZonedDateTime.parse("2027-05-04T12:00:00+03:00[Europe/Moscow]")
    private val titles = listOf("Проверить оборудование", "Обсудить план", "Подготовить отчёт", "Согласовать поставку")

    @Test fun `named delete reuses original past day for different event names`() = runTest {
        for (title in titles) {
            val api = Events(title)
            val executor = executor(api)
            readPastDay(executor)
            val plan = executor.prepareTurn("Удали $title")
            assertTrue("Named deletion must keep the read result and its day", plan.useResultSet)
            assertEquals(CalendarTurnTransition.TARGETS, plan.transition)
            val pending = async { executor.executeSuspend(executor.commandFor(plan, null)) }
            runCurrent()
            assertNotNull(executor.pendingConfirmation.value)
            assertEquals(emptyList<String>(), api.deleted)
            assertTrue(api.fetched.contains("past"))
            executor.resolvePending(true)
            val result = pending.await()
            assertTrue(result is CalendarToolResult.Ok)
            assertTrue((result as CalendarToolResult.Ok).statusText.contains("Удалено"))
            assertEquals(listOf("past"), api.deleted)
            assertTrue(api.has("other"))
        }
    }

    @Test fun `same name on another day is never substituted for the displayed ID`() = runTest {
        val api = Events(titles.first(), duplicateFuture = true)
        val executor = executor(api)
        readPastDay(executor)
        val plan = executor.prepareTurn("Удали ${titles.first()}")
        val pending = async { executor.executeSuspend(executor.commandFor(plan, null)) }
        runCurrent()
        assertNull(executor.targetSelection.value)
        assertNotNull(executor.pendingConfirmation.value)
        assertEquals(1, api.listCalls)
        assertEquals(emptyList<String>(), api.deleted)
        executor.resolvePending(true)
        assertTrue(pending.await() is CalendarToolResult.Ok)
        assertEquals(listOf("past"), api.deleted)
        assertTrue(api.has("future"))
    }

    @Test fun `cancel preserves records and retry requires a new confirmation`() = runTest {
        val api = Events(titles.first())
        val executor = executor(api)
        readPastDay(executor)
        val first = executor.prepareTurn("Удали ${titles.first()}")
        val cancelled = async { executor.executeSuspend(executor.commandFor(first, null)) }
        runCurrent(); assertNotNull(executor.pendingConfirmation.value)
        executor.resolvePending(false)
        assertTrue(cancelled.await() is CalendarToolResult.Error)
        assertTrue(api.has("past")); assertTrue(api.deleted.isEmpty())
        val retry = executor.prepareTurn("Удали ${titles.first()}")
        val pending = async { executor.executeSuspend(executor.commandFor(retry, null)) }
        runCurrent(); assertNotNull(executor.pendingConfirmation.value)
        assertTrue(api.deleted.isEmpty())
        executor.resolvePending(true)
        assertTrue(pending.await() is CalendarToolResult.Ok)
        assertEquals(listOf("past"), api.deleted)
    }

    @Test fun `name outside read set asks clarification rather than broadening to another day`() = runTest {
        val api = Events(titles.first())
        api.add("future-only", "Только будущая запись", "2027-05-08")
        val executor = executor(api)
        readPastDay(executor)
        repeat(2) {
            val plan = executor.prepareTurn("Удали Только будущая запись")
            assertTrue(plan.useResultSet)
            val result = executor.executeSuspend(executor.commandFor(plan, null))
            assertTrue(result is CalendarToolResult.Clarification)
            assertNull(executor.pendingConfirmation.value)
            assertEquals(1, api.listCalls)
            assertTrue(api.deleted.isEmpty())
        }
    }

    @Test fun `explicit new source date overrides the old read day`() = runTest {
        val api = Events(titles.first(), duplicateFuture = true)
        val executor = executor(api)
        readPastDay(executor)
        val plan = executor.prepareTurn("Удали ${titles.first()} 8 мая")
        assertFalse(plan.useResultSet)
        val pending = async { executor.executeSuspend(executor.commandFor(plan, null)) }
        runCurrent(); assertNotNull(executor.pendingConfirmation.value)
        executor.resolvePending(true)
        assertTrue(pending.await() is CalendarToolResult.Ok)
        assertEquals(listOf("future"), api.deleted)
        assertTrue(api.has("past"))
    }

    @Test fun `repeated deletion after last displayed record does not target future namesake`() = runTest {
        val api = Events(titles.first(), duplicateFuture = true, includeOther = false)
        val executor = executor(api)
        readPastDay(executor)
        val plan = executor.prepareTurn("Удали ${titles.first()}")
        val pending = async { executor.executeSuspend(executor.commandFor(plan, null)) }
        runCurrent(); assertNotNull(executor.pendingConfirmation.value)
        executor.resolvePending(true); assertTrue(pending.await() is CalendarToolResult.Ok)
        val repeated = executor.prepareTurn("Удали ${titles.first()}")
        assertEquals(CalendarTurnTransition.CLARIFY, repeated.transition)
        assertFalse(repeated.resetOnClarify)
        assertTrue(api.has("future"))
        assertEquals(listOf("past"), api.deleted)
        assertEquals(1, api.listCalls)
    }

    @Test fun `ambiguous displayed names require choosing ID before deletion confirmation`() = runTest {
        val api = Events(titles.first(), includeOther = false)
        api.add("second-past", titles.first(), "2027-05-03")
        val executor = executor(api)
        readPastDay(executor)
        val plan = executor.prepareTurn("Удали ${titles.first()}")
        val pending = async { executor.executeSuspend(executor.commandFor(plan, null)) }
        runCurrent()
        assertNotNull(executor.targetSelection.value)
        assertNull(executor.pendingConfirmation.value)
        assertTrue(api.deleted.isEmpty())
        executor.selectTarget("second-past")
        runCurrent(); assertNotNull(executor.pendingConfirmation.value)
        assertTrue(api.deleted.isEmpty())
        executor.resolvePending(true)
        assertTrue(pending.await() is CalendarToolResult.Ok)
        assertEquals(listOf("second-past"), api.deleted)
        assertTrue(api.has("past"))
    }

    @Test fun `changed record after confirmation preview is never silently deleted`() = runTest {
        val api = Events(titles.first())
        val executor = executor(api)
        readPastDay(executor)
        val plan = executor.prepareTurn("Удали ${titles.first()}")
        val pending = async { executor.executeSuspend(executor.commandFor(plan, null)) }
        runCurrent(); assertNotNull(executor.pendingConfirmation.value)
        api.rename("past", "Изменённая синтетическая запись")
        executor.resolvePending(true)
        val result = pending.await()
        assertTrue(result is CalendarToolResult.Clarification)
        val message = (result as CalendarToolResult.Clarification).message
        assertTrue(message.contains("изменилась после подтверждения"))
        assertFalse(message.contains("Удалено"))
        assertTrue(api.has("past")); assertTrue(api.deleted.isEmpty())
    }

    private fun executor(api: Events) = CalendarToolExecutor(api, { true }, { now })
    private suspend fun readPastDay(executor: CalendarToolExecutor) {
        val plan = executor.prepareTurn("Какие события 3 мая?")
        assertTrue(executor.executeSuspend(executor.commandFor(plan, null)) is CalendarToolResult.Ok)
    }

    private class Events(title: String, duplicateFuture: Boolean = false, includeOther: Boolean = true) : CalendarGateway {
        val deleted = mutableListOf<String>()
        val fetched = mutableListOf<String>()
        var listCalls = 0
        private val records = linkedMapOf<String, CalendarEvent>()
        init {
            add("past", title, "2027-05-03")
            if (includeOther) add("other", "Другое синтетическое событие", "2027-05-03")
            if (duplicateFuture) add("future", title, "2027-05-08")
        }
        fun add(id: String, title: String, day: String) {
            val start = LocalDate.parse(day).atTime(10, 30).atZone(ZoneId.of("Europe/Moscow"))
            records[id] = CalendarEvent(id, title, start.toInstant().toString(), start.plusHours(1).toInstant().toString(), false, null, null)
        }
        fun has(id: String) = id in records
        fun rename(id: String, title: String) { records[id] = records.getValue(id).copy(title = title) }
        override suspend fun listEvents(timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int): Result<List<CalendarEvent>> {
            listCalls++
            return Result.success(records.values.filter {
                val start = ZonedDateTime.parse(it.start); val end = ZonedDateTime.parse(it.end)
                (timeMin == null || end.isAfter(timeMin)) && (timeMax == null || start.isBefore(timeMax))
            })
        }
        override suspend fun searchEvents(query: String, timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int) = listEvents(timeMin, timeMax, limit)
        override suspend fun getEvent(id: String): Result<CalendarEvent?> { fetched.add(id); return Result.success(records[id]) }
        override suspend fun deleteEvent(id: String): Result<Unit> { deleted.add(id); records.remove(id); return Result.success(Unit) }
        override suspend fun createEvent(payload: CreateEventPayload): Result<CalendarEvent> = error("No create in delete test")
        override suspend fun updateEvent(id: String, payload: UpdateEventPayload): Result<CalendarEvent> = error("No update in delete test")
        override suspend fun primaryCalendarName() = "primary"
    }
}
