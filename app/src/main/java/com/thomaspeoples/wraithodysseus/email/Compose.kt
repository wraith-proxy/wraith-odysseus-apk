package com.thomaspeoples.wraithodysseus.email

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// The composer's pure decisions, taken from the web UI (static/js/document.js
// _ATTACH_RE / schedule presets, emailLibrary/replyRecipients.js) so a message
// written on the phone behaves the way it does in the browser.

/** A suggestion for a recipient field. */
data class Contact(val name: String, val address: String)

/** "Name <a@b>" for a recipient chip; a bare address when there is no useful name.
 * A name holding a comma or other header punctuation is quoted, or
 * [splitAddresses] would cut it in two. */
fun formatAddress(name: String, address: String): String {
    val n = name.trim()
    if (n.isEmpty() || n.equals(address, ignoreCase = true)) return address
    val safe = if (n.any { it in ",;<>@\"" }) "\"${n.replace("\\", "\\\\").replace("\"", "\\\"")}\"" else n
    return "$safe <$address>"
}

/** What a chip shows: the name when there is one, else the address. */
fun chipLabel(entry: String): String {
    val bare = bareAddress(entry)
    val name = entry.substringBefore('<', "").trim().trim('"')
    return name.ifEmpty { bare.ifEmpty { entry.trim() } }
}

/** Adds [more] to [list], skipping an address already there (compared bare, case-insensitive). */
fun addRecipients(list: List<String>, more: List<String>): List<String> {
    val seen = list.map(::bareAddress).toMutableSet()
    return list + more.map(String::trim).filter { it.isNotEmpty() && seen.add(bareAddress(it)) }
}

/** The recipients a field sends: its chips plus whatever is typed but not yet committed. */
fun recipientHeader(chips: List<String>, pending: String): String =
    addRecipients(chips, splitAddresses(pending.replace(';', ','))).joinToString(", ")

// -- attachment warning ---------------------------------------------------

/** The web's word list, verbatim. */
private val ATTACH_WORDS = Regex(
    "\\b(attach(ed|ment|ments|ing)?|enclosed|enclosing|PFA|find attached|see attached|ci-joint|en pi[eè]ce jointe|ajout[eé]|joint|jointe|anbei|im Anhang|beigef[uü]gt|添付|fichier joint)\\b",
    RegexOption.IGNORE_CASE,
)
private val QUOTE_SEPARATOR = Regex("^-{5,}\\s*(Previous|Forwarded) message\\s*-{5,}\\s*$", RegexOption.IGNORE_CASE)
private val ATTRIBUTION = Regex("^On .+ wrote:\\s*$", RegexOption.IGNORE_CASE)

/** [text] without the quoted original: everything from a separator or an "On ... wrote:" line, and any "> " line. */
fun ownText(text: String): String {
    val kept = mutableListOf<String>()
    for (line in text.lines()) {
        if (QUOTE_SEPARATOR.matches(line.trim()) || ATTRIBUTION.matches(line.trim())) break
        if (!line.trimStart().startsWith(">")) kept += line
    }
    return kept.joinToString("\n")
}

/** True when what the person wrote (not the quote) talks about an attachment. */
fun mentionsAttachment(text: String): Boolean = ATTACH_WORDS.containsMatchIn(ownText(text))

// -- schedule send ---------------------------------------------------------

/** The four quick choices of the web's Schedule Send box. */
enum class SchedulePreset(val label: String) {
    InAnHour("In 1 hour"),
    InThreeHours("In 3 hours"),
    TomorrowMorning("Tomorrow 9am"),
    MondayMorning("Monday 9am"),
}

/** When [preset] fires, counted from [now] in its own zone. Monday is never
 * today: on a Monday it means next week's, as in the web. */
fun presetTime(preset: SchedulePreset, now: ZonedDateTime): ZonedDateTime = when (preset) {
    SchedulePreset.InAnHour -> now.plusHours(1)
    SchedulePreset.InThreeHours -> now.plusHours(3)
    SchedulePreset.TomorrowMorning -> now.toLocalDate().plusDays(1).atTime(9, 0).atZone(now.zone)
    SchedulePreset.MondayMorning -> {
        val days = (DayOfWeek.MONDAY.value + 7 - now.dayOfWeek.value).let { if (it == 0) 7 else it }
        now.toLocalDate().plusDays(days.toLong()).atTime(9, 0).atZone(now.zone)
    }
}

/** The UTC instant the server wants as send_at: whole minutes, as the web's
 * datetime-local box has, with a trailing Z. */
fun sendAtIso(at: ZonedDateTime): String =
    DateTimeFormatter.ISO_INSTANT.format(at.toInstant().truncatedTo(ChronoUnit.MINUTES))

/** The server refuses a time that has passed; this stops it before the round trip. */
fun isFuture(at: ZonedDateTime, now: Instant): Boolean = at.toInstant().isAfter(now)

// -- quoting an HTML-only original -----------------------------------------

private val HIDDEN_BLOCKS = listOf("style", "script", "head").map {
    Regex("<$it\\b[^>]*>([\\s\\S]*?)</$it\\s*>", RegexOption.IGNORE_CASE)
}
private val COMMENT = Regex("<!--([\\s\\S]*?)-->")

/**
 * The text to quote or forward from [m]. The server builds `body` for an
 * HTML mail by deleting the tags and keeping what was between them, so a
 * <style> block's rules (and comments, and a <title>) land at the top of it.
 * When any of that hidden text turns up in `body`, it came from the HTML and
 * the text is rebuilt from `bodyHtml` without it. A real text/plain part is
 * left exactly as sent.
 */
fun quoteText(m: EmailMessage): String {
    if (m.bodyHtml.isBlank()) return m.body
    if (m.body.isBlank()) return htmlToText(m.bodyHtml)
    val hidden = (HIDDEN_BLOCKS.flatMap { r -> r.findAll(m.bodyHtml).map { it.groupValues[1] }.toList() } +
        COMMENT.findAll(m.bodyHtml).map { it.groupValues[1] }.toList())
        .mapNotNull { chunk -> chunk.lines().map(String::trim).firstOrNull { it.length >= 4 } }
    return if (hidden.any { m.body.contains(it) }) htmlToText(m.bodyHtml) else m.body
}
