package com.thomaspeoples.wraithodysseus.chat

import com.thomaspeoples.wraithodysseus.net.Http
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

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
