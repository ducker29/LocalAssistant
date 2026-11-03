package com.example.localassistant.calendar

import java.time.LocalDate
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Independent calendar expectations; no device clock, network, account or real event data. */
@RunWith(Parameterized::class)
class CalendarRelativeDateQueriesTest(
    private val label: String,
    private val expression: String,
    private val firstDay: String,
    private val endExclusive: String,
    private val clock: String,
) {
    private val now get() = ZonedDateTime.parse(clock)

    @Test fun `expression consumes its qualifiers and resolves exact local bounds`() {
        val match = CalendarTemporalExpression.find(expression)
        assertNotNull(label, match)
        val recognized = requireNotNull(match)
        val consumed = listOf(recognized.range) + recognized.extraRanges
        val remaining = expression.filterIndexed { index, _ -> consumed.none { index in it } }.trim()
        assertTrue("$label: unconsumed qualifier '$remaining'", remaining.isEmpty())
        assertRange(CalendarTime.queryRange(recognized.reference, null, 30, now))
    }

    @Test fun `full calendar question is read by date with no accidental event title filter`() {
        val question = "Какие события в календаре $expression?"
        val session = CalendarSession()
        val plan = session.prepare(question, now)
        assertEquals(label, CalendarObjectKind.EVENT, plan.objectKind)
        assertEquals(label, CalendarTurnTransition.NEW, plan.transition)
        assertEquals(label, CalendarOp.READ, plan.operation)
        assertFalse(label, CalendarField.QUERY in plan.seed.values)
        val request = session.accept(CalendarTurnCommand(plan, plan.seed), now)
        assertEquals(label, null, request.query)
        assertRange(CalendarTime.queryRange(request.date, request.days, 7, now))
    }

    @Test fun `possessive calendar question keeps the same bounds without searching for u menya`() {
        val session = CalendarSession()
        val plan = session.prepare("Какие события у меня $expression?", now)
        assertEquals(label, CalendarOp.READ, plan.operation)
        assertFalse(label, CalendarField.QUERY in plan.seed.values)
        val request = session.accept(CalendarTurnCommand(plan, plan.seed), now)
        assertEquals(label, null, request.query)
        assertRange(CalendarTime.queryRange(request.date, request.days, 7, now))
    }

    @Test fun `tense and possessive framing does not turn a date listing into a title query`() {
        val today = now.toLocalDate()
        val tense = when {
            !LocalDate.parse(endExclusive).isAfter(today) -> "были"
            LocalDate.parse(firstDay).isAfter(today) -> "будут"
            else -> "есть"
        }
        val session = CalendarSession()
        val plan = session.prepare("Какие события $tense у меня $expression?", now)
        assertEquals(label, CalendarOp.READ, plan.operation)
        assertFalse(label, CalendarField.QUERY in plan.seed.values)
        val request = session.accept(CalendarTurnCommand(plan, plan.seed), now)
        assertEquals(label, null, request.query)
        assertRange(CalendarTime.queryRange(request.date, request.days, 7, now))
    }

    @Test fun `named search retains literal title and removes all temporal words from filter`() {
        val session = CalendarSession()
        val plan = session.prepare("Найди «Проверка оборудования» $expression?", now)
        assertEquals(label, CalendarOp.SEARCH, plan.operation)
        val request = session.accept(CalendarTurnCommand(plan, plan.seed), now)
        assertEquals(label, "Проверка оборудования", request.query)
        assertRange(CalendarTime.queryRange(request.date, request.days, 30, now))
    }

    private fun assertRange(actual: CalendarTime.QueryRange) {
        // Midnight through the next local midnight, not a fixed count of seconds.
        assertEquals(label, LocalDate.parse(firstDay).atStartOfDay(now.zone), actual.start)
        assertEquals(label, LocalDate.parse(endExclusive).atStartOfDay(now.zone), actual.endExclusive)
        assertTrue(label, actual.endExclusive.isAfter(actual.start))
    }

    companion object {
        private const val CLOCK = "2026-10-28T23:30:00+03:00[Europe/Moscow]"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Any>> {
            val cases = mutableListOf<Array<Any>>()
            fun add(phrase: String, first: String, end: String, at: String = CLOCK, suffix: String = "") {
                cases.add(arrayOf(phrase + suffix, phrase, first, end, at))
            }
            listOf(
                Triple("позавчера", "2026-10-26", "2026-10-27"),
                Triple("вчера", "2026-10-27", "2026-10-28"),
                Triple("сегодня", "2026-10-28", "2026-10-29"),
                Triple("завтра", "2026-10-29", "2026-10-30"),
                Triple("послезавтра", "2026-10-30", "2026-10-31"),
            ).forEach { (phrase, first, end) -> add(phrase, first, end) }
            listOf(
                Triple(listOf("эта неделя", "на этой неделе"), "2026-10-26", "2026-11-02"),
                Triple(listOf("прошлая неделя", "на прошлой неделе"), "2026-10-19", "2026-10-26"),
                Triple(listOf("следующая неделя", "на следующей неделе"), "2026-11-02", "2026-11-09"),
            ).forEach { (phrases, first, end) -> phrases.forEach { add(it, first, end) } }

            data class Weekday(val nominative: String, val accusative: String, val gender: Int, val date: String, val preposition: String)
            val weekdays = listOf(
                Weekday("понедельник", "понедельник", 0, "2026-10-26", "в"),
                Weekday("вторник", "вторник", 0, "2026-10-27", "во"),
                Weekday("среда", "среду", 1, "2026-10-28", "в"),
                Weekday("четверг", "четверг", 0, "2026-10-29", "в"),
                Weekday("пятница", "пятницу", 1, "2026-10-30", "в"),
                Weekday("суббота", "субботу", 1, "2026-10-31", "в"),
                Weekday("воскресенье", "воскресенье", 2, "2026-11-01", "в"),
            )
            val nominatives = listOf(
                listOf("этот", "эта", "это"), listOf("прошлый", "прошлая", "прошлое"), listOf("следующий", "следующая", "следующее"),
            )
            val accusatives = listOf(
                listOf("этот", "эту", "это"), listOf("прошлый", "прошлую", "прошлое"), listOf("следующий", "следующую", "следующее"),
            )
            val weekOffsets = listOf(0L, -1L, 1L)
            for (day in weekdays) {
                val current = LocalDate.parse(day.date)
                add("${day.preposition} ${day.accusative}", current.toString(), current.plusDays(1).toString())
                for (kind in weekOffsets.indices) {
                    val date = current.plusWeeks(weekOffsets[kind])
                    add("${nominatives[kind][day.gender]} ${day.nominative}", date.toString(), date.plusDays(1).toString())
                    add("в ${accusatives[kind][day.gender]} ${day.accusative}", date.toString(), date.plusDays(1).toString())
                }
            }
            add("завтра", "2027-01-01", "2027-01-02", "2026-12-31T23:59:00+03:00[Europe/Moscow]", " [year end]")
            add("вчера", "2026-12-31", "2027-01-01", "2027-01-01T00:01:00+03:00[Europe/Moscow]", " [year start]")
            add("эта неделя", "2026-12-28", "2027-01-04", "2027-01-01T12:00:00+03:00[Europe/Moscow]", " [cross-year week]")
            add("следующая неделя", "2027-01-04", "2027-01-11", "2026-12-31T12:00:00+03:00[Europe/Moscow]", " [cross-year week]")
            add("эта пятница", "2026-10-30", "2026-10-31", "2026-10-31T12:00:00+03:00[Europe/Moscow]", " [Friday already past]")
            add("следующая пятница", "2026-11-06", "2026-11-07", "2026-10-31T12:00:00+03:00[Europe/Moscow]", " [Saturday anchor]")
            add("прошлая пятница", "2026-10-23", "2026-10-24", "2026-11-01T12:00:00+03:00[Europe/Moscow]", " [Sunday anchor]")
            add("завтра", "2026-10-25", "2026-10-26", "2026-10-24T22:00:00+02:00[Europe/Berlin]", " [25-hour day]")
            add("завтра", "2026-03-29", "2026-03-30", "2026-03-28T22:00:00+01:00[Europe/Berlin]", " [23-hour day]")
            add("на этой неделе", "2026-03-23", "2026-03-30", "2026-03-25T12:00:00+01:00[Europe/Berlin]", " [167-hour week]")
            return cases
        }
    }
}
