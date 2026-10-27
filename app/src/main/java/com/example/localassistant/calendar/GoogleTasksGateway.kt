package com.example.localassistant.calendar

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Base64

data class GoogleTaskList(val id: String, val title: String)

/** Task IDs are only unique together with their list; keys never contain list titles. */
data class GoogleTaskRef(val listId: String, val taskId: String) {
    fun key(): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "task:${encoder.encodeToString(listId.toByteArray(Charsets.UTF_8))}:${encoder.encodeToString(taskId.toByteArray(Charsets.UTF_8))}"
    }
    companion object {
        fun parse(key: String): GoogleTaskRef {
            val parts = key.split(':')
            require(parts.size == 3 && parts[0] == "task") { "Неверный тип ссылки на задачу." }
            val decoder = Base64.getUrlDecoder()
            val list = String(decoder.decode(parts[1]), Charsets.UTF_8)
            val task = String(decoder.decode(parts[2]), Charsets.UTF_8)
            require(list.isNotBlank() && task.isNotBlank()) { "Неверная ссылка на задачу." }
            return GoogleTaskRef(list, task)
        }
    }
}

interface GoogleTasksGateway : CalendarGateway {
    suspend fun taskLists(): Result<List<GoogleTaskList>>
    suspend fun listTasks(listId: String?, min: ZonedDateTime?, max: ZonedDateTime?, limit: Int): Result<List<CalendarEvent>>
}

class AuthorizedTasksGateway(private val delegate: GoogleTasksGateway, auth: CalendarAuthorization) :
    GoogleTasksGateway, CalendarGateway by AuthorizedCalendarGateway(delegate, auth) {
    private val authorization = GoogleApiAuthorization(auth)
    override suspend fun taskLists() = authorization.run(true) { delegate.taskLists() }
    override suspend fun listTasks(listId: String?, min: ZonedDateTime?, max: ZonedDateTime?, limit: Int) =
        authorization.run(true) { delegate.listTasks(listId, min, max, limit) }
}

class HttpGoogleTasksGateway(tokenProvider: () -> String?, baseUrl: String = "https://tasks.googleapis.com/tasks/v1") : GoogleTasksGateway {
    private val http = GoogleApiHttp(tokenProvider, baseUrl, "Google Tasks")
    override suspend fun taskLists(): Result<List<GoogleTaskList>> {
        val pages = pages("/users/@me/lists", "/users/@me/lists", emptyList(), 100)
        if (pages.isFailure) return Result.failure(requireNotNull(pages.exceptionOrNull()))
        return parsed { pages.getOrNull().orEmpty().map {
            val id = it.getString("id"); require(id.isNotBlank())
            GoogleTaskList(id, it.optString("title", "Без названия"))
        } }
    }

    override suspend fun listEvents(timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int) = listTasks(null, timeMin, timeMax, limit)
    override suspend fun searchEvents(query: String, timeMin: ZonedDateTime?, timeMax: ZonedDateTime?, limit: Int) =
        listTasks(null, timeMin, timeMax, limit).map { items -> items.filter { CalendarEventSearch.matches(it, query) } }

    override suspend fun listTasks(listId: String?, min: ZonedDateTime?, max: ZonedDateTime?, limit: Int): Result<List<CalendarEvent>> {
        if (limit !in 1..CalendarGateway.MAX_RESULTS) return Result.failure(CalendarResultLimitException())
        val catalog = taskLists()
        if (catalog.isFailure) return Result.failure(requireNotNull(catalog.exceptionOrNull()))
        val lists = catalog.getOrNull().orEmpty().filter { listId == null || it.id == listId }
        if (listId != null && lists.isEmpty()) return Result.failure(CalendarApiException(CalendarApiError.NotFound))
        val result = mutableListOf<CalendarEvent>()
        for (list in lists) {
            val params = mutableListOf("showCompleted=true", "showHidden=true", "showAssigned=true", "showDeleted=false")
            min?.let { params += "dueMin=${enc("${it.toLocalDate()}T00:00:00.000Z")}" }
            max?.let { params += "dueMax=${enc("${it.toLocalDate()}T00:00:00.000Z")}" }
            val page = pages("/lists/${enc(list.id)}/tasks", "/lists/{list_id}/tasks", params, limit)
            if (page.isFailure) return Result.failure(requireNotNull(page.exceptionOrNull()))
            val converted = parsed { page.getOrNull().orEmpty().filterNot { it.optBoolean("deleted", false) }.map { task(it, list.id).copy(taskListTitle = list.title) }
                .filter { item ->
                    val date = item.start?.let(LocalDate::parse)
                    (min == null || (date != null && !date.isBefore(min.toLocalDate()))) &&
                        (max == null || (date != null && date.isBefore(max.toLocalDate())))
                } }
            if (converted.isFailure) return Result.failure(requireNotNull(converted.exceptionOrNull()))
            result += converted.getOrNull().orEmpty()
            if (result.size > limit) return Result.failure(CalendarResultLimitException())
        }
        return Result.success(result)
    }

    override suspend fun getEvent(id: String): Result<CalendarEvent?> {
        val ref = GoogleTaskRef.parse(id)
        return http.request("GET", "/lists/${enc(ref.listId)}/tasks/${enc(ref.taskId)}", null, "/lists/{list_id}/tasks/{task_id}") {
            val json = JSONObject(it)
            if (json.optBoolean("deleted", false)) null else task(json, ref.listId)
        }
    }

    override suspend fun createEvent(payload: CreateEventPayload): Result<CalendarEvent> {
        require(payload.end == null && payload.location == null) { "Google Tasks не поддерживает время, длительность и место события." }
        val body = JSONObject().put("title", payload.title)
        payload.start.takeIf(String::isNotBlank)?.let { body.put("due", "${LocalDate.parse(it)}T00:00:00.000Z") }
        payload.description?.let { body.put("notes", it) }
        payload.taskStatus?.let { body.put("status", it.wire) }
        return http.request("POST", "/lists/${enc(payload.calendarId)}/tasks", body.toString(), "/lists/{list_id}/tasks") {
            task(JSONObject(it), payload.calendarId)
        }
    }

    override suspend fun updateEvent(id: String, payload: UpdateEventPayload): Result<CalendarEvent> {
        val ref = GoogleTaskRef.parse(id)
        require(payload.end == null && payload.location == null) { "Google Tasks не поддерживает время и место события." }
        val body = JSONObject()
        payload.title?.let { body.put("title", it) }
        payload.description?.let { body.put("notes", it) }
        if (payload.clearDate) body.put("due", JSONObject.NULL)
        else payload.start?.let { body.put("due", "${LocalDate.parse(it)}T00:00:00.000Z") }
        payload.taskStatus?.let {
            body.put("status", it.wire)
            if (it == GoogleTaskStatus.NEEDS_ACTION) body.put("completed", JSONObject.NULL)
        }
        return http.request("PATCH", "/lists/${enc(ref.listId)}/tasks/${enc(ref.taskId)}", body.toString(), "/lists/{list_id}/tasks/{task_id}") {
            task(JSONObject(it), ref.listId)
        }
    }

    override suspend fun deleteEvent(id: String): Result<Unit> {
        val ref = GoogleTaskRef.parse(id)
        // A parent delete may cascade: never remove unseen subtasks through a flat confirmation.
        val children = pages("/lists/${enc(ref.listId)}/tasks", "/lists/{list_id}/tasks",
            listOf("showCompleted=true", "showHidden=true", "showAssigned=true", "showDeleted=false"), CalendarGateway.MAX_RESULTS)
        if (children.isFailure) return Result.failure(requireNotNull(children.exceptionOrNull()))
        if (children.getOrNull().orEmpty().any { it.optString("parent", "") == ref.taskId }) return Result.failure(
            CalendarApiException(CalendarApiError.InvalidRequest("У задачи есть подзадачи. Удалите или перенесите их отдельно перед удалением родителя.")))
        return http.request("DELETE", "/lists/${enc(ref.listId)}/tasks/${enc(ref.taskId)}", null, "/lists/{list_id}/tasks/{task_id}") { Unit }
    }

    override suspend fun primaryCalendarName() = "Google Tasks"

    private suspend fun pages(path: String, route: String, parameters: List<String>, limit: Int): Result<List<JSONObject>> {
        val result = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var next: String? = null
        do {
            val params = parameters + "maxResults=100" + listOfNotNull(next?.let { "pageToken=${enc(it)}" })
            val page = http.request("GET", "$path?${params.joinToString("&")}", null, route, ::JSONObject)
            if (page.isFailure) return Result.failure(requireNotNull(page.exceptionOrNull()))
            val json = requireNotNull(page.getOrNull())
            val items = json.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) result += items.getJSONObject(i)
            next = json.optString("nextPageToken", "").takeIf(String::isNotBlank)
            if (result.size > limit || (next != null && (result.size >= limit || !seen.add(next) || seen.size > 20))) {
                return Result.failure(CalendarResultLimitException())
            }
        } while (next != null)
        return Result.success(result)
    }

    private fun task(json: JSONObject, listId: String): CalendarEvent {
        return GoogleTasksJson.task(json, listId)
    }
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun <T> parsed(block: () -> T): Result<T> = try { Result.success(block()) }
        catch (t: CancellationException) { throw t }
        catch (_: Exception) { Result.failure(CalendarApiException(CalendarApiError.Network(null))) }
}

internal object GoogleTasksJson {
    fun task(json: JSONObject, listId: String): CalendarEvent {
        // due is a calendar date encoded as RFC3339, not an instant to shift by device timezone.
        val date = json.optString("due", "").takeIf(String::isNotBlank)?.substringBefore('T')?.let { LocalDate.parse(it).toString() }
        val rawId = json.getString("id")
        require(rawId.isNotBlank() && listId.isNotBlank())
        return CalendarEvent(GoogleTaskRef(listId, rawId).key(), json.optString("title", ""), date, null, false, null,
            json.optString("notes", "").takeIf(String::isNotBlank), CalendarObjectKind.TASK, listId, null,
            GoogleTaskStatus.fromWire(json.optString("status", "needsAction")),
            json.optString("parent", "").takeIf(String::isNotBlank), json.optJSONObject("assignmentInfo") != null)
    }
}
