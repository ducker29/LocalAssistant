package com.example.localassistant.calendar

// JDK 19+ changed default \b to ASCII word semantics. Android's regex implementation
// differs. Explicit Unicode letter/mark/number boundaries keep parsing identical without
// changing JDK/tool versions or depending on platform-specific inline regex flags.
private const val CALENDAR_WORD = "[\\p{L}\\p{M}\\p{N}_]"
private const val CALENDAR_BOUNDARY = "(?:(?<!$CALENDAR_WORD)(?=$CALENDAR_WORD)|(?<=$CALENDAR_WORD)(?!$CALENDAR_WORD))"

internal fun calendarRegex(pattern: String, options: Set<RegexOption> = emptySet()): Regex =
    Regex(pattern.replace("\\b", CALENDAR_BOUNDARY), options)

internal fun calendarRegex(pattern: String, option: RegexOption): Regex = calendarRegex(pattern, setOf(option))
