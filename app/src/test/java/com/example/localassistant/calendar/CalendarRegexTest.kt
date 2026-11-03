package com.example.localassistant.calendar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarRegexTest {
    @Test fun `Russian words use explicit Unicode boundaries without matching longer words`() {
        val expression = calendarRegex("\\bзавтра\\b", RegexOption.IGNORE_CASE)
        listOf("завтра", "ЗАВТРА?", "на завтра!", "📅 завтра").forEach { assertTrue(it, expression.containsMatchIn(it)) }
        listOf("послезавтра", "завтрашний", "завтра_2", "незавтра").forEach { assertFalse(it, expression.containsMatchIn(it)) }
    }

    @Test fun `ASCII words and numbers retain ordinary boundary behaviour`() {
        val expression = calendarRegex("\\b(?:test|30)\\b")
        listOf("test!", "30 октября", "(test)").forEach { assertTrue(it, expression.containsMatchIn(it)) }
        listOf("testing", "130", "test_2").forEach { assertFalse(it, expression.containsMatchIn(it)) }
    }

    @Test fun `conflicting weekday qualifiers are not silently converted to a date`() {
        val now = java.time.ZonedDateTime.parse("2026-10-28T12:00:00+03:00[Europe/Moscow]")
        val reference = requireNotNull(CalendarTemporalExpression.find("в следующую пятницу на прошлой неделе")).reference
        try {
            CalendarTime.resolveDate(reference, now)
            throw AssertionError("Conflicting weeks require clarification")
        } catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("Уточните")) }
    }
}
