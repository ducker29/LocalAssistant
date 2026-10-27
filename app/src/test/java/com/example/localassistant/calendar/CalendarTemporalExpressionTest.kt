package com.example.localassistant.calendar

import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CalendarTemporalExpressionTest {
    private val now = ZonedDateTime.parse("2026-10-27T12:00:00+03:00[Europe/Moscow]")

    private fun expression(text: String) = requireNotNull(CalendarTemporalExpression.find(text))
    private fun range(text: String, at: ZonedDateTime = now): CalendarTime.QueryRange =
        CalendarTime.queryRange(expression(text).reference, null, 30, at)
    private fun assertDates(text: String, first: String, end: String, at: ZonedDateTime = now) {
        val result = range(text, at)
        assertEquals(text, LocalDate.parse(first), result.start.toLocalDate())
        assertEquals(text, LocalDate.parse(end), result.endExclusive.toLocalDate())
        assertEquals(at.zone, result.start.zone)
        assertEquals(at.zone, result.endExclusive.zone)
    }

    @Test fun `all five relative days are single complete days`() {
        val cases = listOf(
            Triple("позавчера", "2026-10-25", "2026-10-26"),
            Triple("вчера", "2026-10-26", "2026-10-27"),
            Triple("сегодня", "2026-10-27", "2026-10-28"),
            Triple("завтра", "2026-10-28", "2026-10-29"),
            Triple("послезавтра", "2026-10-29", "2026-10-30"),
        )
        cases.forEach { (text, first, end) -> assertDates(text, first, end) }
        assertEquals("day_after_tomorrow", expression("послезавтра").reference)
        assertEquals("day_before_yesterday", expression("позавчерашний созвон").reference)
    }

    @Test fun `reported search phrases extract week and leave only event words`() {
        val cases = listOf(
            "найди мне все созвоны на этой неделе" to "созвоны",
            "найди мне все конференции на этой неделе" to "конференции",
            "есть ли на этой неделе планерка?" to "планерка",
        )
        cases.forEach { (text, query) ->
            val turn = CalendarSession().prepare(text, now)
            assertEquals(CalendarOp.SEARCH, turn.operation)
            assertEquals(CalendarFieldValue.Text(query), turn.seed.values[CalendarField.QUERY])
            val ref = (turn.seed.values[CalendarField.DATE] as CalendarFieldValue.Text).value
            val result = CalendarTime.queryRange(ref, null, 30, now)
            assertEquals(LocalDate.parse("2026-10-26"), result.start.toLocalDate())
            assertEquals(LocalDate.parse("2026-11-02"), result.endExclusive.toLocalDate())
        }
    }

    @Test fun `week grammatical cases consume the entire expression`() {
        listOf("на этой неделе", "за эту неделю", "этой недели", "эта неделя",
            "в течение текущей недели", "на всю текущую неделю").forEach { text ->
            assertDates(text, "2026-10-26", "2026-11-02")
            val match = expression(text)
            assertEquals(text, text.substring(match.range))
        }
    }

    @Test fun `previous and next weeks use calendar monday boundaries`() {
        assertDates("за прошлую неделю", "2026-10-19", "2026-10-26")
        assertDates("на предыдущей неделе", "2026-10-19", "2026-10-26")
        assertDates("на следующей неделе", "2026-11-02", "2026-11-09")
        assertDates("в будущую неделю", "2026-11-02", "2026-11-09")
    }

    @Test fun `calendar months cross years and include actual february length`() {
        val january = ZonedDateTime.parse("2026-01-15T12:00:00Z")
        assertDates("в прошлом месяце", "2025-12-01", "2026-01-01", january)
        assertDates("за текущий месяц", "2026-01-01", "2026-02-01", january)
        assertDates("в следующем месяце", "2026-02-01", "2026-03-01", january)
        val leap = ZonedDateTime.parse("2028-01-31T12:00:00Z")
        assertDates("на следующий месяц", "2028-02-01", "2028-03-01", leap)
    }

    @Test fun `plural quantities are full calendar periods`() {
        assertDates("за предыдущие две недели", "2026-10-12", "2026-10-26")
        assertDates("на следующие 2 недели", "2026-11-02", "2026-11-16")
        assertDates("за прошлые два месяца", "2026-08-01", "2026-10-01")
        assertDates("на следующие два месяца", "2026-11-01", "2027-01-01")
    }

    @Test fun `rolling periods differ from previous full calendar periods`() {
        assertDates("за последнюю неделю", "2026-10-21", "2026-10-28")
        assertDates("на ближайшую неделю", "2026-10-27", "2026-11-03")
        assertDates("за последний месяц", "2026-09-28", "2026-10-28")
        assertDates("на месяц", "2026-10-27", "2026-11-27")
        assertDates("на две недели", "2026-10-27", "2026-11-10")
    }

    @Test fun `ago and through references denote a date not an entire period`() {
        assertDates("неделю назад", "2026-10-20", "2026-10-21")
        assertDates("через две недели", "2026-11-10", "2026-11-11")
        val endOfMarch = ZonedDateTime.parse("2026-03-31T12:00:00Z")
        assertDates("месяц назад", "2026-02-28", "2026-03-01", endOfMarch)
        assertDates("через месяц", "2026-04-30", "2026-05-01", endOfMarch)
    }

    @Test fun `ambiguous plural quantity asks instead of selecting one month`() {
        listOf("предыдущие месяцы", "на следующие недели", "за последние несколько месяцев").forEach {
            try {
                range(it)
                fail("An unspecified quantity must not fall back to a single/default period")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("количество"))
            }
        }
    }

    @Test fun `large period fails instead of silently truncating`() {
        try {
            range("на следующие четыре месяца")
            fail("Must respect the existing ninety-day limit")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("90"))
        }
    }

    @Test fun `local week across daylight saving does not assume 168 hours`() {
        val berlin = ZonedDateTime.parse("2026-03-25T12:00:00+01:00[Europe/Berlin]")
        val result = range("на этой неделе", berlin)
        assertDates("на этой неделе", "2026-03-23", "2026-03-30", berlin)
        assertEquals(167L, Duration.between(result.start, result.endExclusive).toHours())
    }

    @Test fun `temporal follow-up uses same recognizer for past days and months`() {
        assertTrue(CalendarTemporalExpression.isOnlyExpression("А позавчера?"))
        assertTrue(CalendarTemporalExpression.isOnlyExpression("В предыдущем месяце?"))
        assertFalse(CalendarTemporalExpression.isOnlyExpression("Найди созвоны вчера"))
    }

    @Test fun `creation date is frozen before the next-day time clarification`() {
        val session = CalendarSession()
        val initial = session.prepare("Создай событие Проверка послезавтра", now)
        val draft = session.accept(CalendarTurnCommand(initial, initial.seed), now)
        assertEquals("2026-10-29", draft.date)
        assertEquals("Проверка", draft.title)
        session.await(setOf(CalendarField.TIME))
        val later = now.plusDays(1)
        val followup = session.prepare("Время 16 часов", later)
        val completed = session.accept(CalendarTurnCommand(followup, followup.seed), later)
        assertEquals(CalendarOp.CREATE, completed.op)
        assertEquals("2026-10-29", completed.date)
        assertEquals("Проверка", completed.title)
        assertEquals("16:00", completed.time)
    }

    @Test fun `month follow-up keeps query but discards previous week day count`() {
        val session = CalendarSession()
        val initial = session.prepare("Найди созвоны на этой неделе", now)
        val request = session.accept(CalendarTurnCommand(initial, initial.seed), now)
        val events = listOf(CalendarEvent("synthetic", "Созвон", "2026-10-28T07:00:00Z", "2026-10-28T08:00:00Z", false, null, null))
        session.finish(events)
        session.rememberResults(events, request.copy(date = "2026-10-26", days = 7))
        val followup = session.prepare("А в следующем месяце?", now)
        assertEquals(CalendarTurnTransition.TARGETS, followup.transition)
        val next = session.accept(CalendarTurnCommand(followup, followup.seed), now)
        assertEquals(CalendarOp.SEARCH, next.op)
        assertEquals("созвоны", next.query)
        assertEquals(null, next.days)
        val result = CalendarTime.queryRange(next.date, next.days, 30, now)
        assertEquals(LocalDate.parse("2026-11-01"), result.start.toLocalDate())
        assertEquals(LocalDate.parse("2026-12-01"), result.endExclusive.toLocalDate())
    }

    @Test fun `quantity clarification retains search words and previous period direction`() {
        val session = CalendarSession()
        val initial = session.prepare("Найди созвоны за предыдущие недели", now)
        session.accept(CalendarTurnCommand(initial, initial.seed), now)
        session.await(setOf(CalendarField.DATE))
        val clarification = session.prepare("Две недели", now)
        assertEquals(CalendarTurnTransition.CONTINUE, clarification.transition)
        assertFalse(clarification.seed.values.containsKey(CalendarField.QUERY))
        val request = session.accept(CalendarTurnCommand(clarification, clarification.seed), now)
        assertEquals("созвоны", request.query)
        val result = CalendarTime.queryRange(request.date, request.days, 30, now)
        assertEquals(LocalDate.parse("2026-10-12"), result.start.toLocalDate())
        assertEquals(LocalDate.parse("2026-10-26"), result.endExclusive.toLocalDate())
    }

    @Test fun `unknown ago quantity stays in the past after clarification`() {
        val original = expression("несколько месяцев назад")
        val clarified = requireNotNull(CalendarTemporalExpression.quantityReply("два", original.reference))
        val result = CalendarTime.queryRange(clarified.reference, null, 30, now)
        assertEquals(LocalDate.parse("2026-08-27"), result.start.toLocalDate())
        assertEquals(LocalDate.parse("2026-08-28"), result.endExclusive.toLocalDate())
    }

    @Test fun `pending object retains name while an unknown time quantity is clarified`() {
        val session = CalendarSession()
        val initial = session.prepare("Другое событие Проверка через несколько недель", now)
        session.stageSubject(initial, now)
        val reply = session.prepare("две недели", now)
        session.stageSubject(reply, now)
        assertEquals(CalendarFieldValue.Text("Проверка"), session.pendingSubject?.values?.get(CalendarField.TITLE))
        assertEquals(CalendarFieldValue.Text("2026-11-10"), session.pendingSubject?.values?.get(CalendarField.DATE))
        assertTrue(org.json.JSONObject(session.plannerContext()).isNull("operation"))
    }

    @Test fun `unresolved calendar period keeps its original month across clarification`() {
        val session = CalendarSession()
        val initial = session.prepare("Найди созвоны за предыдущие месяцы", now)
        session.accept(CalendarTurnCommand(initial, initial.seed), now)
        session.await(setOf(CalendarField.DATE))
        val later = now.plusMonths(1)
        val reply = session.prepare("два месяца", later)
        val request = session.accept(CalendarTurnCommand(reply, reply.seed), later)
        val result = CalendarTime.queryRange(request.date, request.days, 30, later)
        assertEquals(LocalDate.parse("2026-08-01"), result.start.toLocalDate())
        assertEquals(LocalDate.parse("2026-10-01"), result.endExclusive.toLocalDate())
        assertEquals("созвоны", request.query)
    }
}
