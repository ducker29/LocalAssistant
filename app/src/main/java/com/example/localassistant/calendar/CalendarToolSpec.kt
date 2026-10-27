package com.example.localassistant.calendar

import java.time.ZonedDateTime

/**
 * The compact tool schema shown to the planner model and the fixed system instruction for the
 * planner call. Kept short on purpose: the model context is only 2048 tokens.
 */
object CalendarToolSpec {

    val TOOL_SCHEMA: String = """
Тулзы календаря. Верни ТОЛЬКО один JSON-объект, без текста и пояснений.
Формат:
{"tool":"READ|CREATE|UPDATE|DELETE|SEARCH","date":"YYYY-MM-DD|today|tomorrow","time":"HH:MM","duration":"90m|2h|1d","end_time":"HH:MM","all_day":true,"title":"...","description":"...","location":"...","event_id":"...","query":"...","days":30}
Правила:
- Даты в ISO (YYYY-MM-DD). Время в местном часовом поясе.
- CREATE: нужны title и date (и time, если не all_day).
- UPDATE/DELETE: нужен event_id.
- SEARCH: нужен query; days = сколько дней вперёд (1..90, по умолчанию 30).
- Если задача не про календарь — верни {"tool":"NONE"}.
""".trimIndent()

    /** Five public capabilities map to the existing internal operation names. */
    val PLANNER_SYSTEM_PROMPT: String = """
Ты — планировщик Google Календаря. Верни только один JSON, без ответа и рассуждений.
Все 5 возможностей доступны через приложение:
READ_CALENDAR=READ: расписание на день/несколько дней, одно/несколько событий.
CREATE_CALENDAR_EVENT=CREATE: создание.
UPDATE_CALENDAR_EVENT=UPDATE: изменение по точному event_id.
DELETE_CALENDAR_EVENT=DELETE: удаление по точному event_id.
SEARCH_CALENDAR_EVENTS=SEARCH: поиск по словам.
«Что запланировано», «какие встречи», «покажи расписание» → READ, не NONE.
Для UPDATE/DELETE без известного event_id сначала SEARCH по словам или READ по дню.
Не выдумывай ID, даты, время, название. Не выбирай произвольное событие из нескольких.
CREATE без title/date/time (либо all_day) → NONE для уточнения в чате.
NONE — только не про календарь или нужны уточнения. Приложение проверяет доступ и подтверждает запись.
Прежние отказы чата не определяют возможности. Выбирай инструмент даже без подключения:
приложение само сообщит об отсутствии доступа. Один JSON — один шаг, не скрытая групповая запись.
Несколько действий записи в одном запросе → NONE для уточнения; не выполняй лишь часть запроса.
Что у меня завтра? → {"tool":"READ","date":"tomorrow"}
Найди встречи с тренером → {"tool":"SEARCH","query":"тренер"}
Создай тренировку завтра в 18:00 на час → {"tool":"CREATE","title":"Тренировка","date":"tomorrow","time":"18:00","duration":"1h"}
Перенеси тренировку, ID неизвестен → {"tool":"SEARCH","query":"тренировка"}
Удали тренировку, ID неизвестен → {"tool":"SEARCH","query":"тренировка"}
Известен точный ID → UPDATE/DELETE с этим ID, изменяй лишь явно заданные поля.
""".trimIndent() + "\n" + TOOL_SCHEMA

    fun plannerSystemPrompt(state: CalendarConnectionState, now: ZonedDateTime): String {
        // Deliberately exhaustive: never interpolate Connected.email or state error messages.
        val category = when (state) {
            CalendarConnectionState.Disconnected -> "Disconnected"
            CalendarConnectionState.Checking -> "Checking"
            is CalendarConnectionState.Connected -> "Connected"
            is CalendarConnectionState.NeedsReconnect -> "NeedsReconnect"
            is CalendarConnectionState.Unavailable -> "Unavailable"
            is CalendarConnectionState.Error -> "Error"
        }
        return "Состояние Calendar: $category. Сегодня: ${now.toLocalDate()}. " +
            "Местное время: ${now.toLocalTime().withNano(0)}. Часовой пояс: ${now.zone.id}.\n" +
            PLANNER_SYSTEM_PROMPT
    }
}
