package com.thomaspeoples.wraithodysseus.ui.tools.notes

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The colours the web offers (static/js/notes.js COLORS), in its order. */
val NOTE_COLORS = listOf("red", "orange", "yellow", "green", "blue", "purple")

/** The web's pale card fills (COLOR_HEX), as 0xRRGGBB. A name the web does
 * not know, such as its "bg:<image>" custom background, has none. */
fun noteColorRgb(color: String): Int? = when (color) {
    "red" -> 0xF0B5BA
    "orange" -> 0xE8CCB2
    "yellow" -> 0xF2DFBD
    "green" -> 0xCCE0BC
    "blue" -> 0xB0D7F7
    "purple" -> 0xE2BCEE
    else -> null
}

/** Calendar-made reminder notes are the calendar's, and the web lists none of them. */
fun isCalendarNote(n: Note) = n.source == "calendar" && n.label == "calendar"

private fun hasTime(due: String?) = due != null && Regex("T\\d{2}:\\d{2}").containsMatchIn(due)

/** The tags of a note (_noteTags): its label split on spaces, # dropped, and
 * "reminder" added when the due date has a time. */
fun noteTags(n: Note): List<String> {
    val tags = n.label.trim().split(Regex("\\s+")).toMutableList()
    if (hasTime(n.dueDate)) tags.add("reminder")
    return tags.map { it.trimStart('#').trim() }.filter { it.isNotEmpty() }.distinct()
}

/** The label field's text as the web saves it: tags split on spaces, # dropped, repeats removed. */
fun normaliseLabel(raw: String): String =
    raw.trim().split(Regex("\\s+")).map { it.trimStart('#').trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" ")

/** Every tag across [notes], for the label chips, sorted. */
fun allTags(notes: List<Note>): List<String> = notes.flatMap(::noteTags).distinct().sorted()

/** A due date as a point in local time. A date alone is midnight. Null if unreadable. */
fun parseDue(due: String?, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? {
    val text = due?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        when {
            'T' !in text -> LocalDate.parse(text).atStartOfDay()
            text.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(text.substringAfter('T')) ->
                OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDateTime()
            else -> LocalDateTime.parse(text)
        }
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun fullyDone(n: Note) = n.isChecklist && n.items.isNotEmpty() && n.items.all { it.done }

/** A reminder whose time has come and which is not finished (_hasActiveReminder). */
fun hasActiveReminder(n: Note, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (n.archived || fullyDone(n)) return false
    val due = parseDue(n.dueDate, zone) ?: return false
    return !due.atZone(zone).toInstant().isAfter(now)
}

/**
 * The web's order (renderNotes): archived notes newest-changed first;
 * otherwise pinned first, then notes whose reminder is due, then the manual
 * sort_order, then newest-changed. Stable, so ties keep the server's order.
 */
fun sortNotes(notes: List<Note>, archivedView: Boolean, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<Note> {
    fun updated(n: Note) = com.thomaspeoples.wraithodysseus.ui.common.parseServerTime(n.updatedAt)?.toEpochMilli() ?: 0L
    if (archivedView) return notes.sortedByDescending(::updated)
    return notes.sortedWith(
        compareByDescending<Note> { it.pinned }
            .thenByDescending { hasActiveReminder(it, now, zone) }
            .thenBy { it.sortOrder }
            .thenByDescending(::updated),
    )
}

/** What the list shows: calendar notes hidden, then the [label] tag, then the search [query]
 * (title, text, label and checklist rows, case-insensitive). */
fun filterNotes(notes: List<Note>, label: String?, query: String): List<Note> {
    val q = query.trim().lowercase()
    return notes.filter { n ->
        !isCalendarNote(n) &&
            (label == null || label in noteTags(n)) &&
            (
                q.isEmpty() || n.title.lowercase().contains(q) || n.content.lowercase().contains(q) ||
                    n.label.lowercase().contains(q) || n.items.any { it.text.lowercase().contains(q) }
                )
    }
}

/** A due date for a card (_formatDueDate): overdue, a time today, tomorrow, or a short date. */
data class DueText(val text: String, val overdue: Boolean)

fun dueText(due: String?, now: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): DueText? {
    val at = parseDue(due, zone) ?: return null
    val timed = hasTime(due)
    val today = now.atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(today, at.toLocalDate())
    val time = if (timed) at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)) else ""
    val overdue = if (timed) at.atZone(zone).toInstant().isBefore(now) else days < 0
    val text = when {
        overdue -> "overdue"
        days == 0L -> if (timed) time else "today"
        days == 1L -> if (timed) "tomorrow $time" else "tomorrow"
        else -> at.format(DateTimeFormatter.ofPattern("d MMM", locale)) + if (timed) " $time" else ""
    }
    return DueText(text, overdue)
}

/** A repeat value in words; "" for none. The web stores daily, yearly,
 * weekly[:day] and monthly[:...] and a few legacy names. */
fun repeatText(repeat: String): String = when {
    repeat.isBlank() || repeat == "none" -> ""
    repeat == "daily" -> "daily"
    repeat == "yearly" -> "yearly"
    repeat.startsWith("weekly") -> "weekly"
    repeat.startsWith("monthly") -> "monthly"
    else -> repeat
}

/** The editor's text box for a checklist turned into a note, one row per line. */
fun itemsToText(items: List<NoteItem>): String = items.joinToString("\n") { it.text }

/** A note turned into a checklist: one unticked row per non-blank line. */
fun textToItems(text: String, newId: () -> String): List<NoteItem> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { NoteItem(newId(), it, false) }

/** "2/5" for a checklist with rows, else "". */
fun progress(n: Note): String = if (n.isChecklist && n.items.isNotEmpty()) "${n.items.count { it.done }}/${n.items.size}" else ""

/** Move [id] one place in [ids]; the list is unchanged at either end or if absent. */
fun moveId(ids: List<String>, id: String, by: Int): List<String> {
    val from = ids.indexOf(id)
    val to = from + by
    if (from < 0 || to !in ids.indices) return ids
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}

/** The sort_order that puts a saved note above its unpinned siblings (the web's rule). */
fun topSortOrder(notes: List<Note>, exceptId: String?): Int =
    notes.filter { !it.pinned && it.id != exceptId }.fold(0) { m, n -> minOf(m, n.sortOrder) } - 1

/**
 * Which block of the list a note belongs to: 0 pinned, 1 reminder due, 2 the rest. [sortNotes]
 * always puts these blocks in that order whatever the sort_order values say, so a drag that
 * crossed from one block into another would snap back on the next load.
 */
fun orderBlock(n: Note, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Int = when {
    n.pinned -> 0
    hasActiveReminder(n, now, zone) -> 1
    else -> 2
}

/**
 * [ids] after dragging [id] over [overId]: [id] takes the place of [overId] and the ones between
 * shift by one. Unchanged if either is absent, they are the same, or they sit in different
 * blocks ([blockOf]), since the web's order would put the note straight back.
 */
fun dragMove(ids: List<String>, blockOf: (String) -> Int, id: String, overId: String): List<String> {
    val from = ids.indexOf(id)
    val to = ids.indexOf(overId)
    if (from < 0 || to < 0 || from == to || blockOf(id) != blockOf(overId)) return ids
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}
