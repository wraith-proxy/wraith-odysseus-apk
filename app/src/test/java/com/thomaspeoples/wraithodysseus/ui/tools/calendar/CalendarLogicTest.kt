package com.thomaspeoples.wraithodysseus.ui.tools.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarLogicTest {

    private fun day(d: Int) = LocalDate.of(2026, 10, d)

    private fun timed(uid: String, from: LocalDateTime, to: LocalDateTime, cal: String = "c1", title: String = uid) =
        CalEvent(uid, uid, title, from, to, false, "", "", "", cal, "", "", false)

    private fun allDay(uid: String, from: LocalDate, toExclusive: LocalDate, cal: String = "c1") =
        CalEvent(uid, uid, uid, from.atStartOfDay(), toExclusive.atStartOfDay(), true, "", "", "", cal, "", "", false)

    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m)

    @Test
    fun agendaGroupsByDayAllDayFirstThenByTime() {
        val events = listOf(
            timed("late", at(13, 15), at(13, 16)),
            timed("early", at(13, 8), at(13, 9)),
            allDay("hol", day(13), day(14)),
            timed("next", at(14, 10), at(14, 11)),
        )
        val days = agendaDays(events, day(12), day(20), today = day(12))
        assertEquals(listOf(day(12), day(13), day(14)), days.map { it.date })
        assertTrue(days[0].events.isEmpty())
        assertEquals(listOf("hol", "early", "late"), days[1].events.map { it.uid })
    }

    @Test
    fun agendaRepeatsAMultiDayEventOnEachDayAndClipsToTheRange() {
        val trip = allDay("trip", day(10), day(15))
        val days = agendaDays(listOf(trip), day(12), day(14), today = day(1))
        assertEquals(listOf(day(12), day(13)), days.map { it.date })
        assertEquals(listOf("trip"), days[0].events.map { it.uid })
    }

    @Test
    fun agendaOnlyAddsTodayWhenInsideTheRange() {
        assertEquals(emptyList<AgendaDay>(), agendaDays(emptyList(), day(12), day(20), today = day(25)))
        assertEquals(listOf(day(12)), agendaDays(emptyList(), day(12), day(20), today = day(12)).map { it.date })
    }

    @Test
    fun anEventEndingAtMidnightDoesNotSpillOntoTheNextDay() {
        val e = timed("night", at(13, 20), at(14, 0))
        assertEquals(day(13), e.lastDay())
        assertTrue(e.coversDay(day(13)))
        assertFalse(e.coversDay(day(14)))
        val overnight = timed("over", at(13, 22), at(14, 2))
        assertTrue(overnight.coversDay(day(14)))
    }

    @Test
    fun hiddenCalendarsAreFilteredOut() {
        val events = listOf(timed("a", at(13, 8), at(13, 9), cal = "c1"), timed("b", at(13, 8), at(13, 9), cal = "c2"))
        assertEquals(listOf("b"), visibleEvents(events, setOf("c1")).map { it.uid })
        assertEquals(2, visibleEvents(events, emptySet()).size)
    }

    @Test
    fun monthGridIsSixWeeksStartingOnMondayOrSunday() {
        // 1 October 2026 is a Thursday.
        val mon = monthGrid(YearMonth.of(2026, 10), sundayFirst = false)
        assertEquals(42, mon.size)
        assertEquals(LocalDate.of(2026, 9, 28), mon.first())
        assertEquals(LocalDate.of(2026, 11, 8), mon.last())
        val sun = monthGrid(YearMonth.of(2026, 10), sundayFirst = true)
        assertEquals(LocalDate.of(2026, 9, 27), sun.first())
        assertEquals(LocalDate.of(2026, 11, 7), sun.last())
    }

    @Test
    fun monthGridStartsOnTheFirstWhenItIsTheFirstWeekday() {
        // 1 June 2026 is a Monday; 1 February 2026 is a Sunday.
        assertEquals(LocalDate.of(2026, 6, 1), monthGrid(YearMonth.of(2026, 6), false).first())
        assertEquals(LocalDate.of(2026, 2, 1), monthGrid(YearMonth.of(2026, 2), true).first())
        assertEquals(LocalDate.of(2026, 1, 26), monthGrid(YearMonth.of(2026, 2), false).first())
    }

    @Test
    fun eventsByDayCoversEveryGridDay() {
        val grid = monthGrid(YearMonth.of(2026, 10), false)
        val map = eventsByDay(listOf(allDay("trip", day(12), day(15)), timed("t", at(13, 9), at(13, 10))), grid)
        assertEquals(42, map.size)
        assertEquals(listOf("trip"), map[day(12)]!!.map { it.uid })
        assertEquals(listOf("trip", "t"), map[day(13)]!!.map { it.uid })
        assertTrue(map[day(15)]!!.isEmpty())
    }

    @Test
    fun dayLabelsNameTodayTomorrowAndTheYearWhenDifferent() {
        val en = Locale.ENGLISH
        assertEquals("Today, Monday 12 October", dayLabel(day(12), day(12), en))
        assertEquals("Tomorrow, Tuesday 13 October", dayLabel(day(13), day(12), en))
        assertEquals("Friday 16 October", dayLabel(day(16), day(12), en))
        assertEquals("Friday 1 January 2027", dayLabel(LocalDate.of(2027, 1, 1), day(12), en))
    }

    @Test
    fun timeLabelsHandleAllDayAndMultiDay() {
        val en = Locale.ENGLISH
        assertEquals("All day", timeLabel(allDay("a", day(12), day(13)), day(12), en))
        val span = timed("s", at(12, 22), at(14, 8))
        assertTrue(timeLabel(span, day(12), en).startsWith("From"))
        assertEquals("All day", timeLabel(span, day(13), en))
        assertTrue(timeLabel(span, day(14), en).startsWith("Until"))
        assertTrue(timeLabel(timed("t", at(12, 9), at(12, 10)), day(12), en).contains("–"))
    }

    @Test
    fun repeatLabelsKnowTheWebChoices() {
        assertEquals("Weekdays", repeatLabel("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"))
        assertEquals("Does not repeat", repeatLabel(""))
        assertEquals("Custom repeat", repeatLabel("FREQ=MONTHLY;INTERVAL=2"))
    }

    @Test
    fun colorsAreHexOnly() {
        assertEquals(0xFF5B8ABF.toInt(), parseHexColor("#5b8abf"))
        assertEquals(0xFFAABBCC.toInt(), parseHexColor("#abc"))
        assertNull(parseHexColor("bg:/uploads/x.png"))
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#12"))
        val cal = CalCalendar("c1", "P", "#112233", "local")
        val e = timed("a", at(12, 9), at(12, 10)).copy(color = "bg:/x")
        assertEquals(0xFF112233.toInt(), eventColor(e, listOf(cal)))
        assertEquals(0xFF010203.toInt(), eventColor(e.copy(color = "#010203"), listOf(cal)))
    }
}
