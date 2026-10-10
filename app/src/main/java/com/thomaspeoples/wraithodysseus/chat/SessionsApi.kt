package com.thomaspeoples.wraithodysseus.chat

import com.thomaspeoples.wraithodysseus.net.Http
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** One row of GET /api/sessions/archived (session_routes.py:1043). */
data class ArchivedSession(
    val id: String,
    val name: String,
    val model: String?,
    val messageCount: Int,
    val createdAt: String,
    val updatedAt: String,
    val isImportant: Boolean,
)

/** A page of archived chats; [total] counts every match, not just this page. */
data class ArchivedPage(val sessions: List<ArchivedSession>, val total: Int)

/** The archive's sort keys: those the server's sort_map knows. */
enum class ArchiveSort(val key: String, val label: String) {
    Recent("recent", "Recent"),
    Oldest("oldest", "Oldest"),
    MostMessages("most-messages", "Most messages"),
    Alpha("alpha", "A\u2013Z"),
}

/**
 * What the web sidebar's chat menu asks of the server. Rename, delete and
 * archive are [ChatApi]'s; this adds the folder, the favourite flag and the
 * whole-chat text for Copy Chat. PATCH /api/session/{sid} and POST
 * .../important read Form(...) fields, so a JSON body would be ignored.
 */
class SessionsApi(private val http: Http, private val chat: ChatApi = ChatApi(http)) {

    suspend fun rename(sessionId: String, name: String) = chat.renameSession(sessionId, name)

    /** PATCH /api/session/{sid} with folder=. An empty name takes the chat out of its folder. */
    suspend fun moveToFolder(sessionId: String, folder: String) {
        val body = FormBody.Builder().add("folder", folder.trim()).build()
        http.call(Request.Builder().url(http.url("/api/session/${seg(sessionId)}")).patch(body).build())
    }

    /** POST /api/session/{sid}/important with important=true|false. */
    suspend fun setFavorite(sessionId: String, favorite: Boolean) {
        val body = FormBody.Builder().add("important", favorite.toString()).build()
        http.call(Request.Builder().url(http.url("/api/session/${seg(sessionId)}/important")).post(body).build())
    }

    suspend fun archive(sessionId: String) = chat.archiveSession(sessionId)

    /**
     * GET /api/sessions/archived, a page at a time (the web asks for 20).
     * The server filters by name and sorts; it is the only list of archived
     * chats, as /api/sessions leaves them out. [search] is sent only when it
     * has text, as the web does.
     */
    suspend fun archived(search: String, offset: Int, limit: Int = ARCHIVE_PAGE, sort: ArchiveSort = ArchiveSort.Recent): ArchivedPage {
        val url = http.url("/api/sessions/archived").newBuilder()
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", sort.key)
            .apply { if (search.isNotBlank()) addQueryParameter("search", search.trim()) }
            .build()
        return parseArchived(JSONObject(http.call(Request.Builder().url(url).build())))
    }

    /** POST /api/session/{sid}/unarchive. There is no /restore. */
    suspend fun unarchive(sessionId: String) {
        http.call(Request.Builder().url(http.url("/api/session/${seg(sessionId)}/unarchive")).post(ByteArray(0).toRequestBody()).build(), retry = false)
    }

    /** A starred chat is refused with 403 SESSION_STARRED; [UNFAVORITE_FIRST] is said before asking. */
    suspend fun delete(sessionId: String) = chat.deleteSession(sessionId)

    /**
     * GET /api/history/{sid} with no limit, which is the server's only way to
     * get every row (a limit is capped at 100), formatted as the web's Copy
     * Chat does. Null when the chat has no messages.
     */
    suspend fun chatText(sessionId: String): String? {
        val url = http.url("/api/history/${seg(sessionId)}")
        val root = JSONObject(http.call(Request.Builder().url(url).build()))
        return formatChat(root)
    }

    private fun seg(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val ARCHIVE_PAGE = 20

        internal fun parseArchived(root: JSONObject): ArchivedPage {
            val arr = root.optJSONArray("sessions")
            val rows = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it) }.map { o ->
                ArchivedSession(
                    id = o.getString("id"),
                    name = o.str("name").orEmpty(),
                    model = o.str("model"),
                    messageCount = o.optInt("message_count", 0),
                    createdAt = o.str("created_at").orEmpty(),
                    updatedAt = o.str("updated_at").orEmpty(),
                    isImportant = o.optBoolean("is_important", false),
                )
            }
            return ArchivedPage(rows, root.optInt("total", rows.size))
        }

        /** The web's toast for a delete that the server would refuse. */
        const val UNFAVORITE_FIRST = "Unfavorite before deleting"

        /**
         * sessions.js "Copy Chat": "You: " or "AI: " and the trimmed text, a
         * blank line between. Reasoning stays, as it is part of the stored
         * text. Content may be a string, a list of parts or a JSON string of
         * parts; only text parts are kept (the web would print the raw JSON).
         */
        internal fun formatChat(root: JSONObject): String? {
            val arr = root.optJSONArray("history") ?: return null
            val lines = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { it.str("role") == "user" || it.str("role") == "assistant" }
                .map { m ->
                    val who = if (m.str("role") == "user") "You" else "AI"
                    "$who: ${ChatApi.contentText(m.opt("content")).trim()}"
                }
            return lines.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        }
    }
}
