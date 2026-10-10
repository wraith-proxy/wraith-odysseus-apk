package com.thomaspeoples.wraithodysseus.email

import com.thomaspeoples.wraithodysseus.ui.common.dateBucket
import com.thomaspeoples.wraithodysseus.ui.common.parseServerTime
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

// The web inbox's list logic (static/js/emailInbox.js and emailLibrary/*),
// kept free of Android so it can be tested on the JVM.

/** One entry of the filter picker. [value] is what GET /list takes as `filter`. */
data class InboxFilter(val value: String, val label: String)

object InboxFilters {
    const val ALL = "all"
    const val UNREAD = "unread"
    const val FAVORITES = "favorites"

    /** The status filters, in the web picker's order. */
    val STATUS = listOf(
        InboxFilter(ALL, "All"),
        InboxFilter(UNREAD, "Unread"),
        InboxFilter(FAVORITES, "Favorites"),
        InboxFilter("undone", "Undone"),
        InboxFilter("reminders", "Reminders"),
        InboxFilter("unanswered", "Unanswered"),
        InboxFilter("pending_30d", "Pending · 30d"),
        InboxFilter("stale_30d", "Stale · >30d"),
    )

    /** Tags the server's urgency task puts on mail. `tag:spam` is a verdict,
     * not a tag, but is asked for the same way. */
    val TAGS = listOf(
        InboxFilter("tag:urgent", "Urgent"),
        InboxFilter("tag:reply-soon", "Reply soon"),
        InboxFilter("tag:bills", "Bills"),
        InboxFilter("tag:receipt", "Receipt"),
        InboxFilter("tag:travel", "Travel"),
        InboxFilter("tag:spam", "Spam"),
    )

    fun label(value: String): String = (STATUS + TAGS).firstOrNull { it.value == value }?.label ?: "All"
}

/** The virtual folder the web calls `__scheduled__`: it is not on the mail
 * server, it is GET /scheduled. */
const val SCHEDULED_FOLDER = "__scheduled__"

private fun folderRole(folder: String): String {
    val f = folder.lowercase()
    return when {
        f == "inbox" -> "inbox"
        "sent" in f -> "sent"
        "starred" in f || "flagged" in f -> "starred"
        "draft" in f -> "drafts"
        "all mail" in f || "archive" in f -> "archive"
        "spam" in f || "junk" in f -> "junk"
        "trash" in f || "bin" in f || "deleted" in f -> "trash"
        else -> ""
    }
}

private val ROLE_ORDER = listOf("inbox", "sent", "starred", "archive", "junk", "trash", "drafts")

/**
 * The web's folder order (emailInbox.js sortedFolders): Inbox, Sent, Starred,
 * Archive, Junk, Trash, Drafts -- the first folder found for each -- then the
 * rest as the server listed them. Like the web, a Trash or Drafts that the
 * provider does not list is still offered, last, because moving to them works.
 */
fun sortedFolders(folders: List<String>): List<String> {
    val found = LinkedHashMap<String, String>()
    val others = mutableListOf<String>()
    for (f in folders) {
        val role = folderRole(f)
        if (role.isEmpty()) others += f else found.putIfAbsent(role, f)
    }
    val out = ROLE_ORDER.mapNotNull { found[it] } + others
    return out + listOf("Trash", "Drafts").filter { name -> out.none { folderRole(it) == name.lowercase() } }
}

/** What the folder picker shows for a raw IMAP name. */
fun folderDisplayName(folder: String): String {
    val f = folder.lowercase()
    return when {
        folder == SCHEDULED_FOLDER -> "Scheduled"
        f == "inbox" -> "Inbox"
        "all mail" in f -> "Archive / All Mail"
        "archive" in f -> "Archive"
        "spam" in f -> "Spam"
        "junk" in f -> "Junk"
        "trash" in f || "bin" in f || "deleted" in f -> "Trash"
        "sent" in f -> "Sent"
        "starred" in f || "flagged" in f -> "Starred"
        "draft" in f -> "Drafts"
        else -> folder
    }
}

/** Sent mail shows who it went to, and has no Done check. */
fun isSentFolder(folder: String) = "sent" in folder.lowercase()

/**
 * The sender's colour hue, 0..359. The web hashes the lowercased name as
 * `hash = (hash << 5) - hash + charCode | 0`, which is exactly Java's
 * String.hashCode (31 * h + char, wrapping at 32 bits, over UTF-16 units),
 * then takes it modulo 360 made non-negative. Blank names get 220.
 */
fun senderHue(name: String): Int =
    if (name.isEmpty()) 220 else Math.floorMod(name.lowercase().hashCode(), 360)

/** The name a row is labelled with: the sender, or for Sent the first To. */
fun rowName(email: EmailSummary, sent: Boolean): String {
    if (sent) {
        val first = email.to.split(',').firstOrNull().orEmpty().trim()
        val named = Regex("""^"?([^"<]+?)"?\s*<([^>]+)>\s*$""").find(first)?.groupValues?.get(1)?.trim()
        val name = named ?: first.substringBefore('@')
        if (name.isNotBlank()) return name
    }
    return email.fromName.ifBlank { email.fromAddress }
}

/** A day of the list: its web label and its rows, favourites first. */
data class DateGroup(val label: String, val emails: List<EmailSummary>)

/**
 * Rows grouped by [dateBucket], groups in order of first appearance (the
 * list arrives newest first), and inside each group the favourites pinned
 * to the top with both halves keeping the server's order.
 */
fun groupByDate(
    emails: List<EmailSummary>,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<DateGroup> {
    val groups = LinkedHashMap<String, MutableList<EmailSummary>>()
    for (e in emails) {
        val time = if (e.dateEpoch > 0) Instant.ofEpochMilli((e.dateEpoch * 1000).toLong()) else null
        groups.getOrPut(dateBucket(time, now, zone, locale)) { mutableListOf() }.add(e)
    }
    return groups.map { (label, rows) ->
        val (favourites, rest) = rows.partition { it.isFlagged }
        DateGroup(label, favourites + rest)
    }
}

/** "just now", "5m ago", "3h ago", "2d ago": the web's `Last:` wording. */
fun relativeTime(iso: String?, now: Instant = Instant.now()): String {
    val then = parseServerTime(iso) ?: return ""
    val seconds = maxOf(0L, Duration.between(then, now).seconds)
    if (seconds < 45) return "just now"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m ago"
    val hours = minutes / 60
    if (hours < 24) return "${hours}h ago"
    return "${hours / 24}d ago"
}

/** "Last: 5m ago", or "" when the server gave no time. */
fun lastSynced(iso: String?, now: Instant = Instant.now()): String =
    relativeTime(iso, now).let { if (it.isEmpty()) "" else "Last: $it" }

/** One of the quick times in "Remind to reply". */
data class ReminderPreset(val label: String, val due: LocalDateTime)

/**
 * The web's three presets (aiReply.js _showLibRemindSubmenu): Later today is
 * 18:00, or three hours from now when 18:00 is under an hour away or past;
 * Tomorrow is 08:00; Next week is 08:00 on the coming Monday (a week from
 * Monday itself).
 */
fun reminderPresets(now: LocalDateTime): List<ReminderPreset> {
    val sixPm = now.toLocalDate().atTime(18, 0)
    val later = if (Duration.between(now, sixPm).toMinutes() < 60) now.plusHours(3) else sixPm
    val tomorrow = now.toLocalDate().plusDays(1).atTime(LocalTime.of(8, 0))
    val sundayZero = now.dayOfWeek.value % 7
    val toMonday = ((8 - sundayZero) % 7).let { if (it == 0) 7 else it }
    val nextWeek = now.toLocalDate().plusDays(toMonday.toLong()).atTime(LocalTime.of(8, 0))
    return listOf(
        ReminderPreset("Later today", later),
        ReminderPreset("Tomorrow", tomorrow),
        ReminderPreset("Next week", nextWeek),
    )
}

/** JavaScript's encodeURIComponent: everything but A-Z a-z 0-9 - _ . ! ~ * ' ( ). */
internal fun encodeUriComponent(s: String): String = URLEncoder.encode(s, "UTF-8")
    .replace("+", "%20").replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~")

private val DUE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

/** The first name of the sender, capitalised, or "someone". */
internal fun senderFirstName(email: EmailSummary): String {
    val name = email.fromName.trim().ifEmpty { email.fromAddress.substringBefore('@') }
    val first = name.replace(Regex("""^["']|["']$"""), "").trim().split(Regex("""[\s,]+""")).firstOrNull().orEmpty()
    return if (first.isEmpty()) "someone" else first.replaceFirstChar { it.uppercase() }
}

/**
 * The note POST /api/notes gets for "Remind to reply", field for field what
 * the web sends: a todo "Reply: subject" with one unchecked item, a link
 * back to the message, and `due_date` as local time without a zone. Without
 * [due] there is no due_date, so nothing fires. [origin] is the server root.
 * One difference: the web's list rows have no sender field, so it always
 * writes "Reply to someone"; this names the sender it plainly meant to.
 */
fun reminderNote(email: EmailSummary, due: LocalDateTime?, origin: String): JSONObject {
    val subject = email.subject.ifBlank { "(no subject)" }
    val link = "$origin/#email=${encodeUriComponent(email.folder)}:${email.uid}"
    return JSONObject().apply {
        put("title", "Reply: $subject")
        put("note_type", "todo")
        put("items", JSONArray().put(JSONObject().put("text", "Reply to ${senderFirstName(email)}: $subject").put("checked", false)))
        put("content", "Open email: $link")
        put("label", "email reminder")
        put("source", "email")
        if (due != null) put("due_date", due.format(DUE_FORMAT))
    }
}

/**
 * Search results as the web paints them: what the local index found first,
 * in its order, then whatever only the full search found, newest first. A
 * row in both is kept once, where the local pass put it.
 */
fun mergeSearch(local: List<EmailSummary>, server: List<EmailSummary>): List<EmailSummary> {
    val seen = HashSet<String>()
    val head = local.filter { seen.add(it.key) }
    val tail = server.filter { seen.add(it.key) }.sortedByDescending { it.dateEpoch }
    return head + tail
}

/** The "..." menu of a search. */
data class SearchOptions(
    val notDone: Boolean = false,
    val attachments: Boolean = false,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
) {
    val active get() = notDone || attachments || from != null || to != null
}

/** Narrows search results. The server's search has no such parameters, so
 * like the web this is applied to what came back. */
fun applySearchOptions(emails: List<EmailSummary>, o: SearchOptions, zone: ZoneId = ZoneId.systemDefault()): List<EmailSummary> =
    emails.filter { e ->
        val day = Instant.ofEpochMilli((e.dateEpoch * 1000).toLong()).atZone(zone).toLocalDate()
        (!o.notDone || !e.isAnswered) &&
            (!o.attachments || e.hasAttachments) &&
            (o.from == null || !day.isBefore(o.from)) &&
            (o.to == null || !day.isAfter(o.to))
    }
