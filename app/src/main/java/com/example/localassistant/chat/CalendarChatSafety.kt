package com.example.localassistant.chat

/** Legacy tool reports lack metadata. Do not offer their success format for model imitation. */
internal object CalendarChatSafety {
    private val legacyReport = Regex("^(?:Обработано \\d+ из \\d+\\.|События на |Результаты поиска на |Событие создано:|Задача создана:)", RegexOption.IGNORE_CASE)
    fun excludeFromOrdinaryPrompt(message: ChatMessage): Boolean = message.calendarRelated ||
        (message.role == Role.ASSISTANT && legacyReport.containsMatchIn(message.text.trimStart()))
}
