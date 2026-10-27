package com.example.localassistant.calendar

import java.time.LocalDate
import java.time.ZoneId
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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoogleTasksWorkflowTest {
    private val now = ZonedDateTime.parse("2026-10-27T12:00:00+03:00[Europe/Moscow]")
    private class EventsMustNotBeCalled : CalendarGateway {
        override suspend fun listEvents(timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int): Result<List<CalendarEvent>> = error("Task routed to Events")
        override suspend fun searchEvents(query: String, timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int): Result<List<CalendarEvent>> = error("Task routed to Events")
        override suspend fun getEvent(id: String): Result<CalendarEvent?> = error("Task routed to Events")
        override suspend fun createEvent(payload: CreateEventPayload): Result<CalendarEvent> = error("Task routed to Events")
        override suspend fun updateEvent(id: String, payload: UpdateEventPayload): Result<CalendarEvent> = error("Task routed to Events")
        override suspend fun deleteEvent(id: String): Result<Unit> = error("Task routed to Events")
        override suspend fun primaryCalendarName() = "primary"
    }
    private class Tasks : GoogleTasksGateway {
        val list = GoogleTaskList("list-a", "Личные")
        var item = CalendarEvent(GoogleTaskRef(list.id, "task-a").key(), "Проверка", "2026-10-28", null, false, null, null,
            CalendarObjectKind.TASK, list.id, list.title, GoogleTaskStatus.NEEDS_ACTION)
        var created: CreateEventPayload? = null
        var updated: UpdateEventPayload? = null
        override suspend fun taskLists() = Result.success(listOf(list))
        override suspend fun listTasks(listId: String?, min: ZonedDateTime?, max: ZonedDateTime?, limit: Int) =
            Result.success(listOf(item).filter { listId == null || listId == it.taskListId })
        override suspend fun listEvents(timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int) = listTasks(null, timeMin, timeMax, limit)
        override suspend fun searchEvents(query: String, timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int) = listEvents(timeMin, timeMax, limit)
        override suspend fun getEvent(id: String): Result<CalendarEvent?> = Result.success(item.takeIf { it.id == id }?.copy(taskListTitle = null))
        override suspend fun createEvent(payload: CreateEventPayload): Result<CalendarEvent> {
            created = payload
            item = item.copy(title = payload.title, start = payload.start.takeIf(String::isNotBlank), description = payload.description)
            return Result.success(item.copy(taskListTitle = null))
        }
        override suspend fun updateEvent(id: String, payload: UpdateEventPayload): Result<CalendarEvent> {
            require(id == item.id)
            updated = payload
            item = item.copy(title = payload.title ?: item.title,
                start = if (payload.clearDate) null else payload.start ?: item.start,
                taskStatus = payload.taskStatus ?: item.taskStatus, description = payload.description ?: item.description)
            return Result.success(item.copy(taskListTitle = null))
        }
        override suspend fun deleteEvent(id: String) = Result.success(Unit)
        override suspend fun primaryCalendarName() = "Google Tasks"
    }
    private fun executor(tasks: Tasks) = CalendarToolExecutor(EventsMustNotBeCalled(), { true }, { now }, tasks)

    @Test fun `task reference includes list identity and round trips reserved characters`() {
        val first = GoogleTaskRef("list:one", "id/+")
        val second = GoogleTaskRef("list:two", "id/+")
        assertNotEquals(first.key(), second.key())
        assertEquals(first, GoogleTaskRef.parse(first.key()))
    }

    @Test fun `due keeps its date in western timezones and never claims an all-day event`() {
        val task = GoogleTasksJson.task(JSONObject().put("id", "synthetic").put("title", "Проверка")
            .put("due", "2026-10-31T00:00:00.000Z").put("status", "needsAction"), "list-a")
        val zone = ZoneId.of("America/Los_Angeles")
        assertEquals(CalendarObjectKind.TASK, task.kind)
        assertEquals(LocalDate.parse("2026-10-31"), task.startInZone(zone)?.toLocalDate())
        assertFalse(task.timeLabel(zone) == "Весь день")
        assertTrue(task.timeLabel(zone).contains("недоступно"))
    }

    @Test fun `native task creation uses selected list and date only after confirmation`() = runTest {
        val tasks = Tasks()
        val executor = executor(tasks)
        val plan = executor.prepareTurn("Создай задачу Проверка завтра в списке «Личные»")
        val result = async { executor.executeSuspend(executor.commandFor(plan, null)) }
        runCurrent()
        assertNotNull(executor.pendingConfirmation.value)
        assertEquals(null, tasks.created)
        executor.resolvePending(true)
        assertTrue(result.await() is CalendarToolResult.Ok)
        assertEquals("Проверка", tasks.created?.title)
        assertEquals("list-a", tasks.created?.calendarId)
        assertEquals("2026-10-28", tasks.created?.start)
        assertEquals(null, tasks.created?.end)
    }

    @Test fun `completion and undo preserve native task identity and due date`() = runTest {
        val tasks = Tasks()
        val executor = executor(tasks)
        val search = executor.prepareTurn("Найди задачу Проверка")
        assertTrue(executor.executeSuspend(executor.commandFor(search, null)) is CalendarToolResult.Ok)
        val complete = executor.prepareTurn("Отметь ее выполненной")
        val done = async { executor.executeSuspend(executor.commandFor(complete, null)) }
        runCurrent()
        executor.resolvePending(true)
        assertTrue(done.await() is CalendarToolResult.Ok)
        assertEquals(GoogleTaskStatus.COMPLETED, tasks.updated?.taskStatus)
        assertEquals("2026-10-28", tasks.item.start)
        val undo = executor.prepareTurn("Верни ее к выполнению")
        val undone = async { executor.executeSuspend(executor.commandFor(undo, null)) }
        runCurrent()
        executor.resolvePending(true)
        assertTrue(undone.await() is CalendarToolResult.Ok)
        assertEquals(GoogleTaskStatus.NEEDS_ACTION, tasks.updated?.taskStatus)
        assertEquals(GoogleTaskRef("list-a", "task-a").key(), tasks.item.id)
    }

    @Test fun `unsupported task time cannot create an event and can be removed without losing draft`() = runTest {
        val tasks = Tasks()
        val executor = executor(tasks)
        val first = executor.prepareTurn("Создай задачу Проверка завтра в 16 часов")
        assertTrue(executor.executeSuspend(executor.commandFor(first, null)) is CalendarToolResult.Clarification)
        assertEquals(null, tasks.created)
        val dateOnly = executor.prepareTurn("Без времени")
        val pending = async { executor.executeSuspend(executor.commandFor(dateOnly, null)) }
        runCurrent()
        assertEquals("Проверка", executor.pendingConfirmation.value?.title)
        assertEquals("2026-10-28", executor.pendingConfirmation.value?.date)
        executor.resolvePending(false)
        assertTrue(pending.await() is CalendarToolResult.Error)
        assertEquals(null, tasks.created)
    }
}
