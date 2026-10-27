package com.example.localassistant.calendar

import java.time.LocalDate
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CalendarSelectionRegressionTest {
    private val now = ZonedDateTime.parse("2026-10-27T12:00:00+03:00[Europe/Moscow]")
    private fun event(title: String, description: String? = null) = CalendarEvent(
        "synthetic", title, "2026-10-28T07:00:00Z", "2026-10-28T08:00:00Z", false, null, description,
    )

    @Test fun `rename keeps target date separate from literal new title`() {
        val turn = CalendarSession().prepare(
            "можешь изменить название утренней встречи 28го числа на утреннюю встречу с коллегами?", now,
        )
        assertEquals(CalendarOp.UPDATE, turn.operation)
        assertEquals(CalendarFieldValue.Text("утренней встречи"), turn.seed.values[CalendarField.QUERY])
        assertEquals(CalendarFieldValue.Text("утреннюю встречу с коллегами"), turn.seed.values[CalendarField.TITLE])
        assertEquals(CalendarFieldValue.Text("day:28"), turn.seed.values[CalendarField.SOURCE_DATE])
        assertFalse(turn.seed.values.containsKey(CalendarField.DATE))
        assertTrue(turn.titleQuery)
    }

    @Test fun `new title words cannot become commands or dates`() {
        val turn = CalendarSession().prepare(
            "Измени название встречи 28 числа на «Найди событие в субботу на даче»", now,
        )
        assertEquals(CalendarOp.UPDATE, turn.operation)
        assertEquals(CalendarFieldValue.Text("встречи"), turn.seed.values[CalendarField.QUERY])
        assertEquals(CalendarFieldValue.Text("Найди событие в субботу на даче"), turn.seed.values[CalendarField.TITLE])
        assertEquals(CalendarFieldValue.Text("day:28"), turn.seed.values[CalendarField.SOURCE_DATE])
    }

    @Test fun `target date preposition does not split old and new titles`() {
        val turn = CalendarSession().prepare("Переименуй встречу на 28 число в «Встреча на природе»", now)
        assertEquals(CalendarFieldValue.Text("встречу"), turn.seed.values[CalendarField.QUERY])
        assertEquals(CalendarFieldValue.Text("Встреча на природе"), turn.seed.values[CalendarField.TITLE])
        assertEquals(CalendarFieldValue.Text("day:28"), turn.seed.values[CalendarField.SOURCE_DATE])
    }

    @Test fun `modal rename without an old name uses selected event without moving it`() {
        val session = CalendarSession()
        val first = session.prepare("Найди встречи 28 числа", now)
        val request = session.accept(CalendarTurnCommand(first, first.seed), now)
        val found = listOf(event("Встреча"))
        session.finish(found)
        session.rememberResults(found, request)
        val turn = session.prepare("можешь изменить название на «Встреча с коллегами»?", now)
        assertEquals(CalendarTurnTransition.TARGETS, turn.transition)
        val update = session.accept(CalendarTurnCommand(turn, turn.seed), now)
        assertEquals(CalendarOp.UPDATE, update.op)
        assertEquals("Встреча с коллегами", update.title)
        assertEquals(null, update.date)
        assertEquals(null, update.query)
        assertEquals(found, session.targets)
    }

    @Test fun `rename target search does not select another title through its description`() {
        assertTrue(CalendarEventSearch.matches(event("Утренняя встреча"), "утренней встречи", titleOnly = true))
        val other = event("Отчёт", "Обсудить итоги утренней встречи")
        assertTrue(CalendarEventSearch.matches(other, "утренней встречи"))
        assertFalse(CalendarEventSearch.matches(other, "утренней встречи", titleOnly = true))
    }

    @Test fun `person case endings match without broad fuzzy name matching`() {
        val meeting = event("Созвон Андрей Участников")
        assertTrue(CalendarEventSearch.matches(meeting, "созвон с Андреем"))
        assertTrue(CalendarEventSearch.matches(meeting, "созвон с Андреем Участниковым"))
        assertTrue(CalendarEventSearch.matches(meeting, "созвон Андрея"))
        assertFalse(CalendarEventSearch.matches(meeting, "созвон с Александром"))
    }

    @Test fun `absolute date makes a question calendar intent without special event words`() {
        val session = CalendarSession()
        val turn = session.prepare("есть ли 31го числа проверка системы?", now)
        assertEquals(CalendarOp.SEARCH, turn.operation)
        assertEquals(CalendarFieldValue.Text("проверка системы"), turn.seed.values[CalendarField.QUERY])
        val request = session.accept(CalendarTurnCommand(turn, turn.seed), now)
        assertEquals("2026-10-31", request.date)
        assertTrue(CalendarEventSearch.matches(event("Проверка системы"), requireNotNull(request.query)))
    }

    @Test fun `every weekday without a week means the current calendar week even if past`() {
        val cases = listOf(
            "в понедельник" to "2026-10-26", "во вторник" to "2026-10-27", "в среду" to "2026-10-28",
            "в четверг" to "2026-10-29", "в пятницу" to "2026-10-30", "в субботу" to "2026-10-31",
            "в воскресенье" to "2026-11-01",
        )
        cases.forEach { (words, date) ->
            val session = CalendarSession()
            val turn = session.prepare("Есть ли $words встреча?", now)
            assertEquals(CalendarFieldValue.Text("встреча"), turn.seed.values[CalendarField.QUERY])
            val request = session.accept(CalendarTurnCommand(turn, turn.seed), now)
            assertEquals(words, date, request.date)
            val range = CalendarTime.queryRange(request.date, request.days, 30, now)
            assertEquals(LocalDate.parse(date).plusDays(1), range.endExclusive.toLocalDate())
        }
    }

    @Test fun `weekday respects a preceding or following calendar week qualifier`() {
        listOf(
            "Есть ли в субботу на прошлой неделе встреча?" to "2026-10-24",
            "Есть ли на следующей неделе в субботу встреча?" to "2026-11-07",
        ).forEach { (text, date) ->
            val session = CalendarSession()
            val turn = session.prepare(text, now)
            assertEquals(CalendarFieldValue.Text("встреча"), turn.seed.values[CalendarField.QUERY])
            assertEquals(date, session.accept(CalendarTurnCommand(turn, turn.seed), now).date)
        }
        assertTrue(CalendarTemporalExpression.isOnlyExpression("А в субботу на следующей неделе?"))
    }

    @Test fun `contradictory weekday and number require clarification`() {
        val ref = requireNotNull(CalendarTemporalExpression.find("в субботу 30го числа")).reference
        try {
            CalendarTime.resolveDate(ref, now)
            fail("Friday must not be silently treated as Saturday")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("не совпадают"))
        }
    }

    @Test fun `environment in an event name is not mistaken for Wednesday`() {
        val session = CalendarSession()
        val turn = session.prepare("Есть ли 31 числа проверка среды разработки?", now)
        assertEquals(CalendarFieldValue.Text("проверка среды разработки"), turn.seed.values[CalendarField.QUERY])
        val request = session.accept(CalendarTurnCommand(turn, turn.seed), now)
        assertEquals("2026-10-31", request.date)
    }

    @Test fun `task and event sources are distinct and quoted task words are names`() {
        val tasks = CalendarSession().prepare("Покажи список именно задач на этой неделе", now)
        assertEquals(CalendarObjectKind.TASK, tasks.objectKind)
        assertEquals(CalendarOp.READ, tasks.operation)
        assertFalse(tasks.seed.values.containsKey(CalendarField.QUERY))
        assertEquals(CalendarObjectKind.TASK, CalendarSession().prepare("Создай задачу Проверка завтра", now).objectKind)
        val eventTurn = CalendarSession().prepare("Создай событие «Задачи дня» завтра в 16 часов", now)
        assertEquals(CalendarOp.CREATE, eventTurn.operation)
        assertEquals(CalendarObjectKind.EVENT, eventTurn.objectKind)
        assertEquals(CalendarFieldValue.Text("Задачи дня"), eventTurn.seed.values[CalendarField.TITLE])
    }
}
