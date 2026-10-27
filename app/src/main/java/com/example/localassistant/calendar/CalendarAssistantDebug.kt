package com.example.localassistant.calendar

import android.util.Log
import com.example.localassistant.BuildConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Temporary debug-only diagnostics. Never pass user text, result bodies or exception messages. */
object CalendarAssistantDebug {
    private const val TAG = "CalendarAssistantDebug"

    // Coroutine-local correlation survives Dispatchers.IO without mutable global request state.
    class Trace(val id: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Trace>
    }

    fun log(id: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, "[$id] $message")
    }

    suspend fun log(message: String) {
        log(currentCoroutineContext()[Trace]?.id ?: "untraced", message)
    }

    /** Fail-closed lexical view, also safe for malformed JSON/prose. Only schema vocabulary survives. */
    fun redactedRaw(raw: String): String {
        val allowed = setOf(
            "tool", "date", "time", "duration", "end_time", "all_day", "title",
            "description", "location", "event_id", "query", "days",
            "READ", "CREATE", "UPDATE", "DELETE", "SEARCH", "NONE",
        )
        val tokens = Regex("\"(?:\\\\.|[^\"\\\\])*\"|[{}\\[\\]:,`]|\\s+|[^\"{}\\[\\]:,`\\s]+|\"")
        return tokens.findAll(raw).joinToString("") { match ->
            val token = match.value
            when {
                token.startsWith('"') && token.endsWith('"') && token.length > 1 &&
                    token.substring(1, token.length - 1) in allowed -> token
                token.length == 1 && token[0] in "{}[]:,`" -> token
                token.isBlank() -> " "
                else -> "<redacted>"
            }
        }.take(1200)
    }

    /** All model-supplied strings are private, even date/time (the parser accepts arbitrary text). */
    fun parameters(request: CalendarToolRequest): String {
        fun field(value: String?) = if (value == null) "null" else "<redacted>"
        return "op=${request.op}, date=${field(request.date)}, time=${field(request.time)}, " +
            "duration=${field(request.duration)}, end_time=${field(request.endTime)}, " +
            "all_day=${request.allDay}, days=${request.days}, title=${field(request.title)}, " +
            "description=${field(request.description)}, location=${field(request.location)}, " +
            "event_id=${field(request.eventId)}, query=${field(request.query)}"
    }

    fun rejection(reason: String): String = when {
        reason == "Это не JSON-объект" -> "not_json_object"
        reason == "Не указано поле \"tool\"" -> "missing_tool"
        reason == "Неизвестная операция: NONE" -> "unsupported_NONE"
        reason.startsWith("Неизвестная операция:") -> "unknown_operation"
        reason.startsWith("\"days\" вне диапазона") -> "days_out_of_range"
        else -> "validation_rejected"
    }
}
