package com.thomaspeoples.wraithodysseus.ui.tools.calendar

import androidx.compose.runtime.saveable.Saver
import java.time.LocalDateTime

/*
 * Savers for the editor's drafts. The activity is recreated on rotation (the
 * manifest has no configChanges), so anything typed must be written to the
 * saved-state bundle as plain strings, booleans and lists, which is all these
 * produce. A null value is saved as an empty list.
 */

/** A date and time as its ISO text. */
val LocalDateTimeSaver: Saver<LocalDateTime, String> = Saver(save = { it.toString() }, restore = { LocalDateTime.parse(it) })

internal fun CalEvent.pack(): List<Any> =
    listOf(uid, seriesUid, summary, start.toString(), end.toString(), allDay, description, location, rrule, calendarHref, calendarName, color, recurring)

internal fun unpackEvent(l: List<*>) = CalEvent(
    uid = l[0] as String,
    seriesUid = l[1] as String,
    summary = l[2] as String,
    start = LocalDateTime.parse(l[3] as String),
    end = LocalDateTime.parse(l[4] as String),
    allDay = l[5] as Boolean,
    description = l[6] as String,
    location = l[7] as String,
    rrule = l[8] as String,
    calendarHref = l[9] as String,
    calendarName = l[10] as String,
    color = l[11] as String,
    recurring = l[12] as Boolean,
)

internal fun EventDraft.pack(): List<Any?> = listOf(summary, allDay, start.toString(), end.toString(), description, location, rrule, calendarHref)

internal fun unpackDraft(l: List<*>) = EventDraft(
    summary = l[0] as String,
    allDay = l[1] as Boolean,
    start = LocalDateTime.parse(l[2] as String),
    end = LocalDateTime.parse(l[3] as String),
    description = l[4] as String,
    location = l[5] as String,
    rrule = l[6] as String,
    calendarHref = l[7] as String?,
)

/** The quick-add result the confirm dialog is showing, so a rotation does not drop the model's answer. */
val QuickParseSaver: Saver<QuickParse?, Any> = Saver(
    save = { p -> if (p == null) emptyList<Any>() else listOf(p.summary, p.dtstart, p.dtend, p.allDay, p.location, p.description) },
    restore = { v ->
        val l = v as List<*>
        if (l.isEmpty()) null else QuickParse(l[0] as String, l[1] as String, l[2] as String, l[3] as Boolean, l[4] as String, l[5] as String)
    },
)
