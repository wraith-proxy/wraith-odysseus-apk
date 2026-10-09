package com.thomaspeoples.wraithodysseus.ui.sessions

import com.thomaspeoples.wraithodysseus.chat.ChatSession
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.dateBucket
import com.thomaspeoples.wraithodysseus.ui.common.parseServerTime
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.json.JSONException

/** The web sidebar's sort choices (index.html #session-sort-dropdown). Its
 * fourth, "Group", has an AI delete throwaway chats, so it is left out. */
enum class SortMode(val key: String, val label: String) {
    Active("active", "Last Active"),
    Newest("newest", "Newest First"),
    Folder("group", "By Folder");

    companion object {
        fun of(key: String?): SortMode = entries.firstOrNull { it.key == key } ?: Active
    }
}

/** The web shows this many chats of a group before "Show N more". */
const val GROUP_ROWS = 10

/** One line of the drawer's chat list. */
sealed interface ListItem {
    val key: String
}

/** A collapsible group heading: Favorites, a date, a folder or Unsorted. */
data class GroupHeader(override val key: String, val label: String, val collapsed: Boolean, val count: Int) : ListItem

data class ChatItem(val session: ChatSession) : ListItem {
    override val key get() = "chat-" + session.id
}

/** The button that opens the rest of group [group]. */
data class ShowMore(val group: String, val hidden: Int) : ListItem {
    override val key get() = "more-$group"
}

/**
 * What sessions.js leaves out of its list: the Assistant folder, an unsaved
 * comparison and the Nobody/Incognito chats. (Archived chats and the
 * server's own hidden names never come from /api/sessions.)
 */
fun visibleSessions(all: List<ChatSession>): List<ChatSession> = all.filter {
    val name = it.name.trim()
    val folder = it.folder.orEmpty().trim()
    folder != "Assistant" &&
        !(it.name.startsWith("[CMP] ") && !folder.startsWith("Compare:")) &&
        name != "Nobody" && name != "Incognito"
}

private fun instant(vararg values: String?): Instant? = values.firstNotNullOfOrNull { parseServerTime(it?.ifBlank { null }) }

/** The web's "last active": last message, else updated, else created. */
fun lastActiveTime(s: ChatSession): Instant? = instant(s.lastMessageAt, s.updatedAt, s.createdAt)

private fun createdTime(s: ChatSession): Instant? = instant(s.createdAt, s.lastMessageAt, s.updatedAt)

/** Names for "Move to folder": those in use, A to Z. */
fun folderNames(all: List<ChatSession>): List<String> =
    visibleSessions(all).mapNotNull { it.folder?.trim()?.ifEmpty { null } }.distinct().sortedBy { it.lowercase() }

/**
 * The chat list as the web sidebar shows it. Last Active and Newest First
 * are one list: favourites in a block of their own, then date groups
 * (Newest groups by creation date, so its headings never repeat; the web
 * groups it by last message and repeats them). By Folder gives each folder
 * a section, favourites first, then the unfiled chats, under Unsorted when
 * there are folders. A group shows [GROUP_ROWS] rows until its key is in
 * [expanded]; a key in [collapsed] shows only its heading.
 */
fun buildChatList(
    sessions: List<ChatSession>,
    sort: SortMode,
    now: Instant,
    collapsed: Set<String>,
    expanded: Set<String>,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<ListItem> {
    val out = mutableListOf<ListItem>()
    fun group(key: String, label: String?, rows: List<ChatSession>) {
        val shut = key in collapsed
        if (label != null) out += GroupHeader(key, label, shut, rows.size)
        if (shut) return
        val shown = if (key in expanded) rows else rows.take(GROUP_ROWS)
        shown.forEach { out += ChatItem(it) }
        if (shown.size < rows.size) out += ShowMore(key, rows.size - shown.size)
    }

    val byActivity = visibleSessions(sessions).sortedByDescending { lastActiveTime(it) ?: Instant.EPOCH }
    if (sort == SortMode.Folder) {
        val filed = byActivity.filter { !it.folder.isNullOrBlank() }.groupBy { it.folder!!.trim() }
        filed.forEach { (name, rows) -> group("folder:$name", name, rows.sortedByDescending { it.isImportant }) }
        val unfiled = byActivity.filter { it.folder.isNullOrBlank() }.sortedByDescending { it.isImportant }
        if (unfiled.isNotEmpty()) group("unsorted", if (filed.isEmpty()) null else "Unsorted", unfiled)
        return out
    }

    val ordered = if (sort == SortMode.Newest) byActivity.sortedByDescending { createdTime(it) ?: Instant.EPOCH } else byActivity
    val (stars, rest) = ordered.partition { it.isImportant }
    if (stars.isNotEmpty()) group("fav", "Favorites", stars)
    val time = if (sort == SortMode.Newest) ::createdTime else ::lastActiveTime
    rest.groupBy { dateBucket(time(it), now, zone, locale) }.forEach { (label, rows) -> group("date:$label", label, rows) }
    return out
}

/** Which of [ids] a bulk delete may send: the server refuses a favourite
 * with 403, so those are held back and counted. */
data class BulkPlan(val ids: List<String>, val skippedStarred: Int)

fun bulkDeletePlan(ids: Collection<String>, sessions: List<ChatSession>): BulkPlan {
    val starred = sessions.filter { it.isImportant }.map { it.id }.toSet()
    val (skip, send) = ids.partition { it in starred }
    return BulkPlan(send, skip.size)
}

/** The outcome of one call per chat. [error] is the first failure's message. */
data class BulkResult(val done: List<String>, val failed: Int, val error: String?)

/** Runs [op] for each id on its own, in order, so one refusal never stops the rest. */
suspend fun bulkRun(ids: List<String>, op: suspend (String) -> Unit): BulkResult {
    val done = mutableListOf<String>()
    var failed = 0
    var error: String? = null
    for (id in ids) {
        try {
            op(id)
            done += id
        } catch (e: ApiException) {
            failed++
            if (error == null) error = userMessage(e)
        } catch (e: IOException) {
            failed++
            if (error == null) error = userMessage(e)
        } catch (e: JSONException) {
            failed++
            if (error == null) error = userMessage(e)
        }
    }
    return BulkResult(done, failed, error)
}

/** "3 chats deleted, 1 favourite skipped, 1 failed: reason". */
fun bulkSummary(verb: String, result: BulkResult, skippedStarred: Int): String = buildList {
    add("${result.done.size} ${if (result.done.size == 1) "chat" else "chats"} $verb")
    if (skippedStarred > 0) add("$skippedStarred ${if (skippedStarred == 1) "favourite" else "favourites"} skipped")
    if (result.failed > 0) add("${result.failed} failed" + (result.error?.let { ": $it" } ?: ""))
}.joinToString(", ")
