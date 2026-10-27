package com.example.localassistant.calendar

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.ZonedDateTime

/**
 * Google Calendar REST client over [HttpURLConnection]. The access token is supplied per call and
 * is never written to logs. Only status codes and short, user-safe details cross into
 * [CalendarApiError].
 */
class HttpCalendarGateway(
    private val tokenProvider: () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : CalendarGateway {

    override suspend fun listEvents(
        timeMin: ZonedDateTime?,
        timeMax: ZonedDateTime?,
        limit: Int,
    ): Result<List<CalendarEvent>> {
        val query = buildListQuery(timeMin, timeMax, limit, search = null)
        return request("GET", "/events?$query", null) { CalendarJson.parseEventList(it) }
    }

    override suspend fun searchEvents(
        query: String,
        timeMin: ZonedDateTime?,
        timeMax: ZonedDateTime?,
        limit: Int,
    ): Result<List<CalendarEvent>> {
        val search = URLEncoder.encode(query, "UTF-8")
        val q = buildListQuery(timeMin, timeMax, limit, search = search)
        return request("GET", "/events?$q", null) { CalendarJson.parseEventList(it) }
    }

    override suspend fun getEvent(id: String): Result<CalendarEvent?> =
        request("GET", "/events/${enc(id)}", null) { body ->
            CalendarJson.parseEvent(body)
        }

    override suspend fun createEvent(payload: CreateEventPayload): Result<CalendarEvent> {
        val url = "/calendars/${enc(payload.calendarId)}/events"
        return request("POST", url, CalendarJson.eventPayload(payload).toString()) {
            CalendarJson.parseEvent(it) ?: throw IOException("Пустой ответ при создании")
        }
    }

    override suspend fun updateEvent(id: String, payload: UpdateEventPayload): Result<CalendarEvent> =
        request("PUT", "/calendars/primary/events/${enc(id)}",
            CalendarJson.updatePayload(payload).toString()) {
            CalendarJson.parseEvent(it) ?: throw IOException("Пустой ответ при обновлении")
        }

    override suspend fun deleteEvent(id: String): Result<Unit> =
        request("DELETE", "/calendars/primary/events/${enc(id)}", null) { Unit }

    override suspend fun primaryCalendarName(): String = "primary"

    private suspend fun <T> request(
        method: String,
        path: String,
        body: String?,
        parse: (String) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        Log.i(TAG, "gateway: $method ${path.take(80)}")
        val token = tokenProvider()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "gateway: NO TOKEN — failing")
            return@withContext Result.failure(IllegalStateException("Календарь не подключён"))
        }
        var connection: HttpURLConnection? = null
        try {
            val url = URL(baseUrl + path)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            Log.i(TAG, "gateway: HTTP $code, body_len=${responseBody.length}")
            if (code in 200..299) {
                Result.success(parse(responseBody))
            } else {
                val err = mapError(code, responseBody)
                Log.w(TAG, "gateway: error $code -> ${err::class.simpleName}: ${err.message}")
                Result.failure(CalendarApiException(err))
            }
        } catch (t: CalendarApiException) {
            Result.failure(t)
        } catch (t: IOException) {
            Log.w(TAG, "gateway: IOException: ${t.message}")
            Result.failure(CalendarApiException(CalendarApiError.Network(t.message)))
        } catch (t: Exception) {
            Log.w(TAG, "gateway: Exception: ${t::class.simpleName}: ${t.message}")
            Result.failure(CalendarApiException(CalendarApiError.Network(t.message ?: "Ошибка запроса")))
        } finally {
            connection?.disconnect()
        }
    }

    private fun mapError(code: Int, body: String): CalendarApiError = when (code) {
        // 401 is the only status that means the access token must be replaced.
        401 -> CalendarApiError.Unauthorized
        403 -> when (errorReason(body)) {
            "rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded",
            "dailyLimitExceeded", "limitExceeded" -> CalendarApiError.RateLimited

            "accessNotConfigured", "keyInvalid", "apiKeyInvalid" ->
                CalendarApiError.Configuration(shortDetail(body))

            else -> CalendarApiError.Forbidden
        }

        404 -> CalendarApiError.NotFound
        429 -> CalendarApiError.RateLimited
        in 500..599 -> CalendarApiError.Server(shortDetail(body))
        else -> CalendarApiError.Server("HTTP $code")
    }

    private fun errorReason(body: String): String? = try {
        JSONObject(body).optJSONObject("error")
            ?.optJSONArray("errors")
            ?.optJSONObject(0)
            ?.optString("reason")
    } catch (t: Exception) {
        null
    }

    private fun shortDetail(body: String): String? {
        try {
            val error = JSONObject(body).optJSONObject("error") ?: return "HTTP ошибка"
            return error.optString("message", "Ошибка сервера").take(120)
        } catch (t: Exception) {
            return "Ошибка сервера"
        }
    }

    private fun buildListQuery(
        timeMin: ZonedDateTime?,
        timeMax: ZonedDateTime?,
        limit: Int,
        search: String?,
    ): String {
        val parts = mutableListOf<String>()
        timeMin?.let { parts += "timeMin=${enc(CalendarTime.rfc3339(it))}" }
        timeMax?.let { parts += "timeMax=${enc(CalendarTime.rfc3339(it))}" }
        parts += "maxResults=$limit"
        search?.let { parts += "q=$it" }
        return parts.joinToString("&")
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val TAG = "CalGateway"
        const val DEFAULT_BASE_URL =
            "https://www.googleapis.com/calendar/v3/users/me"
        const val TIMEOUT_MS = 20_000
    }
}
