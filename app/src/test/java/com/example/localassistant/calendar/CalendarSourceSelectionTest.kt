package com.example.localassistant.calendar

import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSourceSelectionTest {
    private val now = ZonedDateTime.parse("2026-10-28T12:00:00+03:00[Europe/Moscow]")

    private fun afterTasks(withTargets: Boolean = true): CalendarSession {
        val session = CalendarSession()
        val plan = session.prepare("Найди невыполненные задачи Проверка в списке «Личные»", now)
        val request = session.accept(CalendarTurnCommand(plan, plan.seed), now)
        val targets = if (withTargets) listOf(CalendarEvent(
            GoogleTaskRef("list-a", "synthetic").key(), "Проверка", "2026-10-29", null, false, null, null,
            CalendarObjectKind.TASK, "list-a", "Личные", GoogleTaskStatus.NEEDS_ACTION,
        )) else emptyList()
        session.finish(targets)
        session.rememberResults(targets, request.copy(taskListId = "list-a", taskListQuery = "Личные"))
        return session
    }

    @Test fun `standalone named questions after tasks default to calendar events`() {
        listOf("Есть ли в субботу встреча с Андреем?", "Есть ли завтра встреча с коллегой?",
            "Есть ли 31 числа проверка системы?").forEach { text ->
            val session = afterTasks()
            val plan = session.prepare(text, now)
            assertEquals(text, CalendarObjectKind.EVENT, plan.objectKind)
            assertEquals(CalendarTurnTransition.NEW, plan.transition)
            assertEquals(CalendarOp.SEARCH, plan.operation)
            val request = session.accept(CalendarTurnCommand(plan, plan.seed), now)
            assertEquals(CalendarObjectKind.EVENT, request.objectKind)
            assertEquals(null, request.taskListId)
            assertEquals(null, request.taskListQuery)
            assertEquals(null, request.taskStatusFilter)
            assertTrue(session.targets.isEmpty())
        }
    }

    @Test fun `explicit task word selects native tasks for a new query`() {
        val session = afterTasks()
        val plan = session.prepare("Есть ли завтра задача Проверка?", now)
        assertEquals(CalendarObjectKind.TASK, plan.objectKind)
        assertEquals(CalendarTurnTransition.NEW, plan.transition)
        assertEquals(CalendarOp.SEARCH, plan.operation)
    }

    @Test fun `object references retain the real task source for completion and deletion`() {
        listOf("Отметь её выполненной", "Удали её", "Перенеси её на завтра").forEach { text ->
            val plan = afterTasks().prepare(text, now)
            assertEquals(text, CalendarObjectKind.TASK, plan.objectKind)
            assertEquals(CalendarTurnTransition.TARGETS, plan.transition)
        }
    }

    @Test fun `title field reply does not reinterpret the word event as a source switch`() {
        val session = CalendarSession()
        val initial = session.prepare("Создай задачу завтра", now)
        session.accept(CalendarTurnCommand(initial, initial.seed), now)
        session.await(setOf(CalendarField.TITLE))
        val reply = session.prepare("Событие года", now)
        assertEquals(CalendarObjectKind.TASK, reply.objectKind)
        assertEquals(CalendarTurnTransition.CONTINUE, reply.transition)
        val request = session.accept(CalendarTurnCommand(reply, reply.seed), now)
        assertEquals(CalendarOp.CREATE, request.op)
        assertEquals(CalendarObjectKind.TASK, request.objectKind)
        assertEquals("Событие года", request.title)
    }

    @Test fun `temporal follow-up retains task query even when previous results were empty`() {
        val session = afterTasks(withTargets = false)
        val plan = session.prepare("А завтра?", now)
        assertEquals(CalendarObjectKind.TASK, plan.objectKind)
        assertEquals(CalendarTurnTransition.TARGETS, plan.transition)
        val request = session.accept(CalendarTurnCommand(plan, plan.seed), now)
        assertEquals(CalendarObjectKind.TASK, request.objectKind)
        assertEquals("Проверка", request.query)
        assertEquals("2026-10-29", request.date)
        assertEquals("list-a", request.taskListId)
    }

    @Test fun `possessive pronoun in a new subject does not refer to prior task objects`() {
        val plan = afterTasks().prepare("Есть ли завтра встреча с его коллегой?", now)
        assertEquals(CalendarObjectKind.EVENT, plan.objectKind)
        assertEquals(CalendarTurnTransition.NEW, plan.transition)
    }
}
