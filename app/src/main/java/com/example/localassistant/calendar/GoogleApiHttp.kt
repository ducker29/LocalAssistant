package com.example.localassistant.calendar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Shared transport. Only caller-supplied fixed route templates may enter diagnostics. */
internal class GoogleApiHttp(private val tokenProvider: () -> String?, private val baseUrl: String, private val service: String) {
    suspend fun <T> request(method: String, path: String, body: String?, route: String, parse: (String) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            val endpoint = baseUrl + route
            CalendarAssistantDebug.log("HTTP method=$method, endpoint=$endpoint, status=not_sent")
            val token = tokenProvider()
            if (token.isNullOrBlank()) return@withContext Result.failure(CalendarAuthorizationRequiredException("Подключите Google-аккаунт заново."))
            var connection: HttpURLConnection? = null
            var status: Int? = null
            var stage = "connect"
            try {
                connection = URL(baseUrl + path).openConnection() as HttpURLConnection
                connection.requestMethod = method
                connection.connectTimeout = 20_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    stage = "write_request"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                stage = "response_status"
                val code = connection.responseCode
                status = code
                CalendarAssistantDebug.log("HTTP method=$method, endpoint=$endpoint, status=$code")
                stage = "read_response"
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code in 200..299) {
                    stage = "parse_response"
                    val result = parse(response)
                    CalendarAssistantDebug.log("HTTP method=$method, endpoint=$endpoint, status=$code, error=None")
                    Result.success(result)
                } else {
                    val error = error(code, response)
                    CalendarAssistantDebug.log("HTTP method=$method, endpoint=$endpoint, status=$code, error=${error::class.simpleName}")
                    Result.failure(CalendarApiException(error))
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Exception) {
                CalendarAssistantDebug.log("HTTP method=$method, endpoint=$endpoint, status=$status, stage=$stage, exception=${t::class.simpleName}")
                Result.failure(CalendarApiException(CalendarApiError.Network(null)))
            } finally {
                connection?.disconnect()
            }
        }

    private fun error(code: Int, body: String): CalendarApiError {
        val reason = try {
            val error = JSONObject(body).optJSONObject("error")
            error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason", "")
                ?.takeIf(String::isNotBlank) ?: error?.optJSONArray("details")?.let { details ->
                    (0 until details.length()).firstNotNullOfOrNull { details.optJSONObject(it)?.optString("reason", "")?.takeIf(String::isNotBlank) }
                }
        } catch (_: Exception) { null }
        return when (code) {
            400 -> CalendarApiError.InvalidRequest()
            401 -> CalendarApiError.Unauthorized
            403 -> when (reason) {
                "rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded", "dailyLimitExceeded", "limitExceeded" -> CalendarApiError.RateLimited
                "accessNotConfigured", "keyInvalid", "apiKeyInvalid", "serviceDisabled", "SERVICE_DISABLED" ->
                    CalendarApiError.Configuration("Включите $service API в Google Cloud-проекте приложения.")
                else -> CalendarApiError.Forbidden
            }
            404 -> CalendarApiError.NotFound
            409, 412 -> CalendarApiError.Conflict
            429 -> CalendarApiError.RateLimited
            else -> CalendarApiError.Server("HTTP $code")
        }
    }
}
