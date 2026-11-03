package com.example.localassistant.calendar

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarDateReadRegressionTest {
    private val now = ZonedDateTime.parse("2026-11-02T23:30:00+03:00[Europe/Moscow]")

    @Test fun `reported past Saturday listing does not search for byli`() {
        val clock = ZonedDateTime.parse("2026-11-03T12:00:00+03:00[Europe/Moscow]")
        listOf("какие события были в прошлую субботу?", "Какие события были у меня в прошлую субботу?",
            "Какие события у меня были в календаре в прошлую субботу?").forEach { question ->
            val session = CalendarSession()
            val plan = session.prepare(question, clock)
            val request = session.accept(CalendarTurnCommand(plan, plan.seed), clock)
            assertEquals("$question must not search for tense words", null, request.query)
            assertEquals(CalendarOp.READ, request.op)
            val range = CalendarTime.queryRange(request.date, request.days, 7, clock)
            assertEquals(LocalDate.of(2026, 10, 31).atStartOfDay(clock.zone), range.start)
            assertEquals(LocalDate.of(2026, 11, 1).atStartOfDay(clock.zone), range.endExclusive)
        }
    }

    @Test fun `past Saturday returned events are not discarded by tense filtering`() = runTest {
        val clock = ZonedDateTime.parse("2026-11-03T12:00:00+03:00[Europe/Moscow]")
        val gateway = FakeCalendar()
        val executor = CalendarToolExecutor(gateway, { true }, { clock })
        val plan = executor.prepareTurn("какие события были в прошлую субботу?")
        val result = executor.executeSuspend(executor.commandFor(plan, null))
        assertTrue(result is CalendarToolResult.Ok)
        val text = (result as CalendarToolResult.Ok).statusText
        assertTrue(text.startsWith("События "))
        assertTrue(text.contains("Проверка оборудования"))
        assertFalse(text.contains("совпадений нет"))
        assertEquals(1, gateway.reads)
        assertEquals(0, gateway.searchCalls)
        assertEquals(LocalDate.of(2026, 10, 31), gateway.min?.toLocalDate())
    }

    @Test fun `quoted tense words remain genuine title queries`() {
        listOf("Были", "Будут", "Есть").forEach { title ->
            val session = CalendarSession()
            val plan = session.prepare("Найди «$title» завтра?", now)
            val request = session.accept(CalendarTurnCommand(plan, plan.seed), now)
            assertEquals(CalendarOp.SEARCH, request.op)
            assertEquals(title, request.query)
        }
    }

    @Test fun `reported possessive today question has no event title filter`() {
        val clock = ZonedDateTime.parse("2026-11-03T12:00:00+03:00[Europe/Moscow]")
        listOf(
            "какие события у меня сегодня?",
            "Какие события сегодня у меня?",
            "Какие у меня события сегодня?",
            "Какие события у меня в календаре сегодня?",
            "Какие события в календаре у меня сегодня?",
            "Какие события у   меня сегодня?!",
        ).forEach { question ->
            val session = CalendarSession()
            val plan = session.prepare(question, clock)
            val request = session.accept(CalendarTurnCommand(plan, plan.seed), clock)
            assertEquals("$question must not search for service words", null, request.query)
            assertEquals(question, CalendarOp.READ, request.op)
            val range = CalendarTime.queryRange(request.date, request.days, 7, clock)
            assertEquals(clock.toLocalDate().atStartOfDay(clock.zone), range.start)
            assertEquals(clock.toLocalDate().plusDays(1).atStartOfDay(clock.zone), range.endExclusive)
        }
    }

    @Test fun `reported today query retains events returned by calendar without local word filtering`() = runTest {
        val clock = ZonedDateTime.parse("2026-11-03T12:00:00+03:00[Europe/Moscow]")
        val gateway = FakeCalendar()
        val executor = CalendarToolExecutor(gateway, { true }, { clock })
        val plan = executor.prepareTurn("какие события у меня сегодня?")
        val result = executor.executeSuspend(executor.commandFor(plan, null))
        assertTrue(result is CalendarToolResult.Ok)
        val text = (result as CalendarToolResult.Ok).statusText
        assertTrue(text.contains("Проверка оборудования"))
        assertTrue(text.startsWith("События "))
        assertFalse(text.contains("совпадений нет"))
        assertEquals(1, gateway.reads)
        assertEquals(clock.toLocalDate(), gateway.min?.toLocalDate())
        assertEquals(clock.toLocalDate().plusDays(1), gateway.max?.toLocalDate())
    }

    @Test fun `literal possessive phrase in a quoted event name remains a real search filter`() {
        val session = CalendarSession()
        val plan = session.prepare("Найди «У меня» сегодня?", now)
        val request = session.accept(CalendarTurnCommand(plan, plan.seed), now)
        assertEquals(CalendarOp.SEARCH, request.op)
        assertEquals("У меня", request.query)
    }

    @Test fun `named and relative day questions do not become title searches`() {
        listOf(
            "какие события в календаре 30 октября?" to LocalDate.of(2026, 10, 30),
            "какие события в календаре завтра?" to LocalDate.of(2026, 11, 3),
            "Какие события в календаре на завтра?" to LocalDate.of(2026, 11, 3),
            "Какие события в календаре завтра?!" to LocalDate.of(2026, 11, 3),
        ).forEach { (question, date) ->
            val session = CalendarSession()
            val plan = session.prepare(question, now)
            assertEquals(question, CalendarOp.READ, plan.operation)
            assertFalse(question, CalendarField.QUERY in plan.seed.values)
            val request = session.accept(CalendarTurnCommand(plan, plan.seed), now)
            assertEquals(null, request.query)
            val range = CalendarTime.queryRange(request.date, request.days, 7, now)
            assertEquals(date.atStartOfDay(now.zone), range.start)
            assertEquals(date.plusDays(1).atStartOfDay(now.zone), range.endExclusive)
        }
    }

    @Test fun `executor returns all day events instead of filtering by phrase in calendar`() = runTest {
        val gateway = FakeCalendar()
        val executor = CalendarToolExecutor(gateway, { true }, { now })
        for (question in listOf("какие события в календаре 30 октября?", "какие события в календаре завтра?")) {
            val plan = executor.prepareTurn(question)
            val result = executor.executeSuspend(executor.commandFor(plan, null))
            assertTrue(result is CalendarToolResult.Ok)
            val text = (result as CalendarToolResult.Ok).statusText
            assertTrue(text.contains("Проверка оборудования"))
            assertFalse(text.contains("совпадений нет"))
            assertEquals(gateway.min?.toLocalDate()?.plusDays(1), gateway.max?.toLocalDate())
        }
        assertEquals(2, gateway.reads)
        assertEquals(0, gateway.searchCalls)
    }

    @Test fun `actual event title search and quoted calendar words remain intact`() {
        val session = CalendarSession()
        val plan = session.prepare("Найди «В календаре» завтра?", now)
        assertEquals(CalendarOp.SEARCH, plan.operation)
        val request = session.accept(CalendarTurnCommand(plan, plan.seed), now)
        assertEquals("В календаре", request.query)
    }

    @Test fun `whole day range follows local timezone across daylight saving boundary`() {
        val local = ZonedDateTime.of(2026, 10, 24, 22, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        val session = CalendarSession()
        val plan = session.prepare("какие события в календаре завтра?", local)
        val request = session.accept(CalendarTurnCommand(plan, plan.seed), local)
        val range = CalendarTime.queryRange(request.date, request.days, 7, local)
        assertEquals(LocalDate.of(2026, 10, 25), range.start.toLocalDate())
        assertEquals(25, java.time.Duration.between(range.start, range.endExclusive).toHours())
    }

    private class FakeCalendar : CalendarGateway {
        var min: ZonedDateTime? = null
        var max: ZonedDateTime? = null
        var reads = 0
        var searchCalls = 0
        override suspend fun listEvents(timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int): Result<List<CalendarEvent>> {
            reads++; min = timeMin; max = timeMax
            val start = requireNotNull(timeMin).plusHours(10)
            return Result.success(listOf(CalendarEvent("synthetic", "Проверка оборудования", start.toString(), start.plusHours(1).toString(), false, null, null)))
        }
        override suspend fun searchEvents(query: String, timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int): Result<List<CalendarEvent>> {
            searchCalls++; return Result.success(emptyList())
        }
        override suspend fun getEvent(id: String): Result<CalendarEvent?> = Result.success(null)
        override suspend fun createEvent(payload: CreateEventPayload): Result<CalendarEvent> = error("No writes in date read test")
        override suspend fun updateEvent(id: String, payload: UpdateEventPayload): Result<CalendarEvent> = error("No writes in date read test")
        override suspend fun deleteEvent(id: String): Result<Unit> = error("No writes in date read test")
        override suspend fun primaryCalendarName() = "primary"
    }
}
