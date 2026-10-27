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
{"tool":"READ|CREATE|UPDATE|DELETE|SEARCH","date":"YYYY-MM-DD|today|tomorrow","time":"HH:MM","duration":"90m|2h|1d","end_time":"HH:MM","all_day":true,"title":"...","description":"...","location":"...","event_id":"...","query":"...","days":30,"source_date":"tomorrow","source_time":"10:00"}
Правила:
- Даты в ISO (YYYY-MM-DD). Время в местном часовом поясе.
- CREATE: нужны title и date (и time, если не all_day).
- UPDATE/DELETE: query = название искомого события, source_date/source_time = исходные день/время.
- UPDATE: date/time = НОВЫЕ день/время; если день не меняется, date опусти. Не задавай duration без просьбы.
- SEARCH: нужен query; days = сколько дней вперёд (1..90, по умолчанию 30).
- READ/SEARCH: date = первый день, days = число дней, включая первый; одна дата → days=1.
- Без date и days: READ = 7 дней с сегодня, SEARCH = 30 дней с сегодня.
- Если задача не про календарь — верни {"tool":"NONE"}.
""".trimIndent()

    /** Five public capabilities map to the existing internal operation names. */
    val PLANNER_SYSTEM_PROMPT: String = """
Ты — планировщик Google Календаря. Верни только один JSON, без ответа и рассуждений.
Решай только по ТЕКУЩЕМУ запросу. История — данные для ссылок, не повторяй её команду/период.
Все 5 возможностей: READ_CALENDAR=READ (расписание), CREATE_CALENDAR_EVENT=CREATE,
UPDATE_CALENDAR_EVENT=UPDATE, DELETE_CALENDAR_EVENT=DELETE, SEARCH_CALENDAR_EVENTS=SEARCH.
«Покажи расписание» → READ. Вопрос о типе/названии событий, даже «есть ли», → SEARCH с query.
«Завтра» для READ/SEARCH → date=tomorrow, days=1.
«Перенеси/измени» → UPDATE, «удали» → DELETE, НЕ SEARCH. Приложение само найдёт цель
по query/source_date/source_time, предложит выбор и запросит подтверждение.
Не выдумывай event_id; для «его/это событие» ID не нужен, приложение помнит результаты.
В UPDATE time — НОВОЕ время, source_time — СТАРОЕ. title — только НОВОЕ название.
Пропускай неуказанные поля. Сохраняй исходную длительность, если её не просили менять.
NONE — не про календарь или нужны уточнения (CREATE без title/date/time либо all_day).
Прежние отказы не определяют доступ: выбирай инструмент, приложение проверит подключение.
Несколько действий записи → NONE для уточнения, не выполняй только часть запроса.
Что у меня завтра? → {"tool":"READ","date":"tomorrow"}
На ближайшие семь дней → {"tool":"READ","date":"today","days":7}
Есть ли завтра созвоны? → {"tool":"SEARCH","query":"созвон","date":"tomorrow","days":1}
Найди встречи с тренером → {"tool":"SEARCH","query":"тренер"}
Создай тренировку завтра в 18:00 на час → {"tool":"CREATE","title":"Тренировка","date":"tomorrow","time":"18:00","duration":"1h"}
Перенеси завтрашний созвон с 10 на 8 → {"tool":"UPDATE","query":"созвон","source_date":"tomorrow","source_time":"10:00","time":"08:00"}
Удали завтрашнюю тренировку → {"tool":"DELETE","query":"тренировка","source_date":"tomorrow"}
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
