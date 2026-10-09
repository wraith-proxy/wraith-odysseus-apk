package com.thomaspeoples.wraithodysseus.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The server's timestamps: ISO 8601, naive and in UTC (core/database.py),
 * occasionally with an offset. Null when absent or unreadable. The web UI
 * parses the naive ones as local time, so near midnight its groups can be an
 * hour or two off; these are read as the UTC they are.
 */
fun parseServerTime(value: String?): Instant? {
    val text = value?.trim()?.replaceFirst(' ', 'T')?.ifEmpty { null } ?: return null
    return try {
        OffsetDateTime.parse(text).toInstant()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/**
 * The web UI's group for a list row (sessions.js _dateBucketLabel, and the
 * same steps in the email list): Today, Yesterday, the weekday within a
 * week, then "28 September" (with the year when it is not this year) up to
 * 29 days, "30 days ago" in steps of 30, "6 months ago" from 180 days and
 * "N years ago" from 365. Days are counted between local midnights. A date in
 * the future (a skewed clock) gets the dated label, as on the web.
 */
fun dateBucket(time: Instant?, now: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    time ?: return "Older"
    val day = time.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val diff = ChronoUnit.DAYS.between(day, today)
    return when {
        diff == 0L -> "Today"
        diff == 1L -> "Yesterday"
        diff in 2..6 -> day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        diff >= 365 -> (diff / 365).let { "$it ${if (it == 1L) "year" else "years"} ago" }
        diff >= 180 -> "6 months ago"
        diff >= 30 -> "${diff / 30 * 30} days ago"
        else -> monthDay(day, withYear = day.year != today.year, locale)
    }
}

/** The locale's long date ("28 September 2025", "September 28, 2025"),
 * with the year and whatever joins it dropped when it is not wanted.
 * ponytail: a pattern that wraps the year in letters (y年) keeps the letters. */
private fun monthDay(date: LocalDate, withYear: Boolean, locale: Locale): String {
    val long = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.LONG, null, IsoChronology.INSTANCE, locale)
    val pattern = if (withYear) long else long.replace(Regex("""[\s,]*y+[\s,]*"""), " ").trim()
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}
