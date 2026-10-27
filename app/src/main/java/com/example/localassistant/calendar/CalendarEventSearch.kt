package com.example.localassistant.calendar

/** Local token matching avoids losing Russian plurals/ё in Google free-text query matching. */
internal object CalendarEventSearch {
    private val stop = setOf("с", "со", "в", "во", "на", "у", "и", "или", "по", "для", "к", "ко", "из", "от", "о", "об", "про", "мне")
    private val russianWord = Regex("[а-я]+")
    private val suffixes = listOf("иями", "ями", "ами", "ого", "ему", "ому", "ыми", "ими", "еем", "ием", "ией", "иям", "иях",
        "ая", "яя", "ые", "ие", "ей", "ею", "ея", "ее", "ой", "ый", "ий", "их", "ых", "ым", "им", "ом", "ем", "ую", "юю", "ов", "ев", "ам", "ям", "ах", "ях", "ия", "ию", "ии", "а", "я", "ы", "и", "у", "ю", "е", "о", "ь")
        .sortedByDescending { it.length }
    private fun tokens(text: String) = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase(java.util.Locale.forLanguageTag("ru")).replace('ё', 'е'))
        .map { it.value }.filter { it !in stop }.toList()
    private fun stem(word: String): String {
        if (!russianWord.matches(word)) return word
        val suffix = suffixes.firstOrNull { word.endsWith(it) && word.length - it.length >= 3 }
        return if (suffix == null) word else word.dropLast(suffix.length)
    }
    fun matches(event: CalendarEvent, query: String, titleOnly: Boolean = false): Boolean {
        fun forms(word: String) = setOf(word, stem(word))
        val requested = tokens(query).map(::forms)
        if (requested.isEmpty()) return false
        val actual = tokens(if (titleOnly) event.title else listOfNotNull(event.title, event.description, event.location).joinToString(" "))
            .flatMap(::forms).toSet()
        return requested.all { expected -> expected.any { it in actual } }
    }
}
