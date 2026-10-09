package com.thomaspeoples.wraithodysseus.ui.tools.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** One day of the agenda, with the events that touch it. */
data class AgendaDay(val date: LocalDate, val events: List<CalEvent>)

/** The day an event ends on, inclusive: an all-day end is exclusive, and a timed end at exactly midnight belongs to the day before. */
fun CalEvent.lastDay(): LocalDate = when {
    !end.isAfter(start) -> start.toLocalDate()
    allDay || end.toLocalTime().toSecondOfDay() == 0 && end.nano == 0 -> end.toLocalDate().minusDays(1)
    else -> end.toLocalDate()
}

fun CalEvent.coversDay(day: LocalDate): Boolean = !day.isBefore(start.toLocalDate()) && !day.isAfter(lastDay())

/** Events of calendars the user has not switched off. */
fun visibleEvents(events: List<CalEvent>, hidden: Set<String>): List<CalEvent> =
    if (hidden.isEmpty()) events else events.filter { it.calendarHref !in hidden }

/** All-day first, then by start time, then by title. */
private val DAY_ORDER = compareBy<CalEvent>({ !it.allDay }, { it.start }, { it.summary.lowercase() })

/**
 * The agenda: every day from [from] up to [toExclusive] that has an event,
 * grouped by day. A multi-day event appears on each day it spans. Today is
 * always there even when empty, as on the web, so "nothing today" is visible.
 */
fun agendaDays(events: List<CalEvent>, from: LocalDate, toExclusive: LocalDate, today: LocalDate): List<AgendaDay> {
    val byDay = sortedMapOf<LocalDate, MutableList<CalEvent>>()
    for (e in events) {
        var day = maxOf(e.start.toLocalDate(), from)
        val last = minOf(e.lastDay(), toExclusive.minusDays(1))
        while (!day.isAfter(last)) {
            byDay.getOrPut(day) { mutableListOf() }.add(e)
            day = day.plusDays(1)
        }
    }
    if (!today.isBefore(from) && today.isBefore(toExclusive)) byDay.getOrPut(today) { mutableListOf() }
    return byDay.map { (d, list) -> AgendaDay(d, list.sortedWith(DAY_ORDER)) }
}

/** The 42 days of a month view, as the web draws it (six weeks, from the week holding the 1st). */
fun monthGrid(month: YearMonth, sundayFirst: Boolean): List<LocalDate> {
    val first = month.atDay(1)
    val back = if (sundayFirst) first.dayOfWeek.value % 7 else first.dayOfWeek.value - 1
    val start = first.minusDays(back.toLong())
    return List(42) { start.plusDays(it.toLong()) }
}

/** Each grid day to its events, in day order. */
fun eventsByDay(events: List<CalEvent>, grid: List<LocalDate>): Map<LocalDate, List<CalEvent>> =
    grid.associateWith { day -> events.filter { it.coversDay(day) }.sortedWith(DAY_ORDER) }

/** "Today", "Tomorrow", then "Friday 9 October" (with the year when it is not this year). */
fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val base = when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> null
    }
    val pattern = if (day.year == today.year) "EEEE d MMMM" else "EEEE d MMMM yyyy"
    val full = day.format(DateTimeFormatter.ofPattern(pattern, locale))
    return if (base == null) full else "$base, $full"
}

/**
 * The time line of an event on [day]. A multi-day timed event says when it
 * starts on its first day and when it ends on its last, and is "All day"
 * between.
 */
fun timeLabel(e: CalEvent, day: LocalDate, locale: Locale = Locale.getDefault()): String {
    if (e.allDay) return "All day"
    val f = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val first = e.start.toLocalDate()
    val last = e.lastDay()
    return when {
        first == last -> if (e.end == e.start) e.start.format(f) else "${e.start.format(f)} – ${e.end.format(f)}"
        day == first -> "From ${e.start.format(f)}"
        day == last -> "Until ${e.end.format(f)}"
        else -> "All day"
    }
}

/** The repeat choices the web's form offers, as (label, RRULE). */
val REPEAT_OPTIONS: List<Pair<String, String>> = listOf(
    "Does not repeat" to "",
    "Daily" to "FREQ=DAILY",
    "Weekly" to "FREQ=WEEKLY",
    "Weekdays" to "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
    "Monthly" to "FREQ=MONTHLY",
    "Yearly" to "FREQ=YEARLY",
)

/** An RRULE the form has no option for (imported, or set by the assistant) is shown as such and kept. */
fun repeatLabel(rrule: String): String = REPEAT_OPTIONS.firstOrNull { it.second == rrule }?.first ?: "Custom repeat"

/**
 * "#rrggbb" or "#rgb" as an opaque ARGB int. The server also puts other
 * things in `color` (the web's "bg:<url>" picture sentinel), which are null here.
 */
fun parseHexColor(text: String?): Int? {
    val s = text?.trim().orEmpty()
    if (!s.startsWith("#")) return null
    val hex = s.drop(1).let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
    if (hex.length != 6) return null
    val v = hex.toIntOrNull(16) ?: return null
    return 0xFF000000.toInt() or v
}

/** An event's own colour, else its calendar's. */
fun eventColor(e: CalEvent, calendars: List<CalCalendar>): Int? =
    parseHexColor(e.color) ?: parseHexColor(calendars.firstOrNull { it.href == e.calendarHref }?.color)

/**
 * The quick-add result as something to save, or null when the text it came
 * back with cannot be read. The model's all-day end is taken as the last day
 * (a "Mon to Wed" phrase), so the exclusive end is a day later; a timed event
 * with no usable end gets the hour the server's own prompt defaults to.
 */
fun QuickParse.toDraft(calendarHref: String?): EventDraft? = try {
    if (allDay) {
        val s = LocalDate.parse(dtstart.take(10))
        val lastDay = runCatching { LocalDate.parse(dtend.take(10)) }.getOrDefault(s).let { if (it.isBefore(s)) s else it }
        EventDraft(summary, true, s.atStartOfDay(), lastDay.plusDays(1).atStartOfDay(), description, location, "", calendarHref)
    } else {
        val s = LocalDateTime.parse(dtstart)
        val e = runCatching { LocalDateTime.parse(dtend) }.getOrNull()?.takeIf { it.isAfter(s) } ?: s.plusHours(1)
        EventDraft(summary, false, s, e, description, location, "", calendarHref)
    }
} catch (_: java.time.format.DateTimeParseException) {
    null
}
