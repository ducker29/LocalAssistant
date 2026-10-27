package com.example.localassistant.calendar

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

    /** The full planner system prompt: behaviour + the schema. */
    val PLANNER_SYSTEM_PROMPT: String = """
Ты — планировщик локального ассистента. Решай, нужно ли действие в Google Календаре.
Если нужно — верни ровно один JSON-объект с вызовом тулзы по схеме ниже. Иначе — {"tool":"NONE"}.
Никогда не выводи JSON за пределы одного объекта. Не выдумывай event_id: используй event_id,
полученные в результате предыдущего вызова.
""".trimIndent() + "\n" + TOOL_SCHEMA
}
