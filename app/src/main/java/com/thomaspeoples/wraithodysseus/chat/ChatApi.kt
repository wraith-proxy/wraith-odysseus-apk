package com.thomaspeoples.wraithodysseus.chat

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SseEvent
import com.thomaspeoples.wraithodysseus.net.SseParser
import com.thomaspeoples.wraithodysseus.net.sseFrames
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** An item of GET /api/sessions (a bare array). Times are the server's naive
 * UTC ISO strings; see parseServerTime. */
data class ChatSession(
    val id: String,
    val name: String,
    val model: String?,
    val endpointId: String?,
    /** "chat", "agent", "research", "research_pending" or null. */
    val mode: String?,
    val isImportant: Boolean,
    val updatedAt: String,
    /** The server already falls back to updated_at, then created_at. */
    val lastMessageAt: String = updatedAt,
    val createdAt: String = "",
    val folder: String? = null,
    val messageCount: Int = 0,
    val hasDocuments: Boolean = false,
    val hasImages: Boolean = false,
)

data class HistoryMessage(
    /** Only "user" and "assistant" are kept. */
    val role: String,
    /** Already flattened to text, including the JSON-string-of-parts case,
     * with any inline <think> block moved out into [thinking]. */
    val content: String,
    val dbId: String?,
    val thinking: String?,
    val attachments: List<UploadedFile>,
    /** The stored text before the think split, for an assistant edit: saving
     * the split-out [content] would drop the reasoning from the server's copy. */
    val raw: String = content,
    /** Sources, tool cards, metrics, mode and time from the row's metadata. */
    val details: MessageDetails = MessageDetails(),
)

/** POST /api/session/{sid}/fork. */
data class ForkResult(val id: String, val name: String, val kept: Int)

/** GET /api/history/{sid}?limit=50[&offset]. No offset means the last page;
 * the server returns rows [offset, offset + limit), so an older page is
 * max(offset - 50, 0) with a limit that stops where the shown rows begin. */
data class HistoryPage(
    val messages: List<HistoryMessage>,
    val offset: Int,
    val total: Int,
    val hasMoreBefore: Boolean,
    val model: String?,
    val endpointId: String?,
)

/** A model is the pair (endpoint, model id): the same id can be served by
 * several endpoints. */
data class ModelChoice(val endpointId: String, val endpointName: String, val model: String, val display: String)

/** GET /api/default-chat. Either field can be "" (no usable endpoint). */
data class DefaultChat(val endpointId: String, val model: String)

/** tool_approval_decision: "approve_task" (this task), "approve" (this chat
 * session) or "deny" -- the option's `value`, sent verbatim. */
data class ToolApproval(val approvalId: String, val decision: String)

/** One POST /api/chat_stream. An approval re-POST has an empty [message]. */
data class ChatRequest(
    val sessionId: String,
    val message: String,
    val toggles: Toggles,
    /** Only when the user changed it; null keeps the session's own model. */
    val selected: ModelChoice?,
    val attachmentIds: List<String>,
    val approval: ToolApproval?,
)

/** A live run. [runId] (X-Odysseus-Run-Id) is what stop needs; it is null on
 * an approval-deny stream, which has nothing to stop. */
class RunStream(val runId: String?, val events: Flow<SseEvent>)

data class UploadedFile(val id: String, val name: String, val mime: String)

/** An item of GET /api/search (a bare array). */
data class SearchHit(
    val messageId: String,
    val sessionId: String,
    val sessionName: String,
    val role: String,
    val snippet: String,
    val timestamp: String,
)

class ChatApi(private val http: Http) {

    suspend fun sessions(): List<ChatSession> {
        val arr = JSONArray(http.call(get("/api/sessions")))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val updatedAt = o.str("updated_at").orEmpty()
            ChatSession(
                id = o.getString("id"),
                name = o.str("name").orEmpty(),
                model = o.str("model"),
                endpointId = o.str("endpoint_id"),
                mode = o.str("mode"),
                isImportant = o.optBoolean("is_important", false),
                updatedAt = updatedAt,
                lastMessageAt = o.str("last_message_at") ?: updatedAt,
                createdAt = o.str("created_at").orEmpty(),
                folder = o.str("folder")?.ifBlank { null },
                messageCount = o.optInt("message_count", 0),
                hasDocuments = o.optBoolean("has_documents", false),
                hasImages = o.optBoolean("has_images", false),
            )
        }
    }

    /** POST /api/session with skip_validation=true; returns the new id. */
    suspend fun createSession(name: String, endpointId: String, model: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("name", name)
            .addFormDataPart("endpoint_id", endpointId)
            .addFormDataPart("model", model)
            .addFormDataPart("skip_validation", "true")
            .build()
        return JSONObject(http.call(post("/api/session", body))).getString("id")
    }

    suspend fun history(sessionId: String, offset: Int?, limit: Int = PAGE): HistoryPage {
        val url = http.url("/api/history/${seg(sessionId)}").newBuilder()
            .addQueryParameter("limit", limit.toString())
            .apply { if (offset != null) addQueryParameter("offset", offset.toString()) }
            .build()
        return parseHistory(JSONObject(http.call(Request.Builder().url(url).build())))
    }

    /** Chat models only: items with model_type "image" or offline are dropped. */
    suspend fun models(): List<ModelChoice> = parseModels(JSONObject(http.call(get("/api/models"))))

    suspend fun defaultChat(): DefaultChat {
        val o = JSONObject(http.call(get("/api/default-chat")))
        return DefaultChat(o.str("endpoint_id").orEmpty(), o.str("model").orEmpty())
    }

    suspend fun chatStream(request: ChatRequest): RunStream {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .apply { formFields(request).forEach { (k, v) -> addFormDataPart(k, v) } }
            .build()
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        return stream(
            post("/api/chat_stream", body).newBuilder()
                // Minutes east of UTC, which is what getOffset already is.
                .header("X-Tz-Offset", (tz.getOffset(now) / 60_000).toString())
                .header("X-Tz-Name", tz.id)
                .build(),
            // Re-sent after a dropped connection it would be a second
            // message (or a 409 for an approval already used).
            retry = false,
        )
    }

    /** Replays the whole run from the start; null on 404 (no run running). */
    suspend fun resume(sessionId: String): RunStream? = try {
        stream(get("/api/chat/resume/${seg(sessionId)}"))
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    /** Without the run id the server always answers stopped:false. */
    suspend fun stop(sessionId: String, runId: String): Boolean {
        val request = post("/api/chat/stop/${seg(sessionId)}", ByteArray(0).toRequestBody())
            .newBuilder().header(RUN_ID, runId).build()
        return JSONObject(http.call(request)).optBoolean("stopped", false)
    }

    suspend fun upload(name: String, mime: String, bytes: ByteArray): UploadedFile {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("files", name, bytes.toRequestBody(mime.toMediaTypeOrNull()))
            .build()
        val f = JSONObject(http.call(post("/api/upload", body))).getJSONArray("files").getJSONObject(0)
        return UploadedFile(f.getString("id"), f.str("name") ?: name, f.str("mime") ?: mime)
    }

    /** A 320px JPEG for images; other files come back whole, so only ask for images. */
    suspend fun thumbnail(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        http.open(get("/api/upload/${seg(fileId)}?thumb=1"), Http.JSON_TIMEOUT_S).use { it.body?.bytes() ?: ByteArray(0) }
    }

    /**
     * Cuts the chat off just before one message and everything after it
     * (history_routes.py:390). Always by the message's database id: list
     * indexes drift when the server hides rows. Never retried, because a
     * repeat after a lost answer finds the message gone and says 404.
     */
    suspend fun truncate(sessionId: String, beforeMsgId: String): Int {
        require(beforeMsgId.isNotBlank()) { "before_msg_id" }
        val body = JSONObject().put("before_msg_id", beforeMsgId)
        val request = post("/api/session/${seg(sessionId)}/truncate", jsonBody(body))
        return JSONObject(http.call(request, retry = false)).optInt("kept", 0)
    }

    /** history_routes.py:447. Deleting an id twice is harmless, so it may retry. */
    suspend fun deleteMessages(sessionId: String, ids: List<String>) {
        require(ids.isNotEmpty() && ids.none { it.isBlank() }) { "msg_ids" }
        val body = JSONObject().put("msg_ids", JSONArray(ids))
        http.call(post("/api/session/${seg(sessionId)}/delete-messages", jsonBody(body)))
    }

    /** history_routes.py:527: replaces the stored text of one message. */
    suspend fun editMessage(sessionId: String, msgId: String, content: String) {
        require(msgId.isNotBlank()) { "msg_id" }
        val body = JSONObject().put("msg_id", msgId).put("content", content)
        http.call(post("/api/session/${seg(sessionId)}/edit-message", jsonBody(body)))
    }

    /** history_routes.py:782. Not retried: a repeat would make a second chat. */
    suspend fun fork(sessionId: String, keepCount: Int): ForkResult {
        val body = JSONObject().put("keep_count", keepCount)
        val o = JSONObject(http.call(post("/api/session/${seg(sessionId)}/fork", jsonBody(body)), retry = false))
        return ForkResult(o.getString("id"), o.str("name").orEmpty(), o.optInt("kept", 0))
    }

    /**
     * The `keep_count` that makes a fork end with message [dbId].
     *
     * The server slices its in-memory history, which is every stored row in
     * order (session_manager.py:165-195, hidden and tool rows included),
     * while /api/history drops the hidden ones and the app drops the tool
     * ones. So the position cannot be counted from what the app holds; it is
     * found instead. A page [lowerBound, lowerBound + width) contains the
     * message exactly when width > its position - lowerBound, so the smallest
     * such width is found by bisection, and keep_count is lowerBound + width.
     * [lowerBound] must be at or before the message's row (the first shown
     * row's offset plus the shown rows before it is). Null when the message
     * is not within [MAX_WINDOW] rows of it, i.e. the answer is not known.
     */
    suspend fun forkKeepCount(sessionId: String, dbId: String, lowerBound: Int): Int? {
        suspend fun within(width: Int) = history(sessionId, lowerBound, width).messages.any { it.dbId == dbId }
        if (!within(MAX_WINDOW)) return null
        var lo = 1
        var hi = MAX_WINDOW
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (within(mid)) hi = mid else lo = mid + 1
        }
        return lowerBound + lo
    }

    /**
     * POST /api/rewrite (chat_routes.py:5023): asks the model to rewrite
     * [original] by [instruction] and saves the result over the session's
     * LAST assistant row, so callers offer it for the last reply only.
     * [onText] gets the cleaned text so far as it streams. Reasoning deltas
     * are not part of the rewrite. Returns the final text.
     */
    suspend fun rewrite(sessionId: String, original: String, instruction: String, onText: (String) -> Unit): String {
        val body = JSONObject()
            .put("session_id", sessionId)
            .put("original_text", original)
            .put("instruction", instruction)
        val response = http.open(post("/api/rewrite", jsonBody(body)), Http.STREAM_TIMEOUT_S, retry = false)
        val text = StringBuilder()
        response.sseFrames().map(SseParser::decode).collect { e ->
            when (e) {
                is SseEvent.Delta -> if (!e.thinking) {
                    text.append(e.text)
                    onText(stripRewriteThinking(text.toString()))
                }
                is SseEvent.Error -> throw ApiException(e.status ?: 500, e.message)
                else -> Unit
            }
        }
        return stripRewriteThinking(text.toString()).ifEmpty { throw ApiException(502, "The model returned no rewritten text") }
    }

    suspend fun search(query: String, limit: Int = 25): List<SearchHit> {
        val url = http.url("/api/search").newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .build()
        val arr = JSONArray(http.call(Request.Builder().url(url).build()))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SearchHit(
                messageId = o.str("message_id").orEmpty(),
                sessionId = o.getString("session_id"),
                sessionName = o.str("session_name").orEmpty(),
                role = o.str("role").orEmpty(),
                snippet = o.str("content_snippet").orEmpty(),
                timestamp = o.str("timestamp").orEmpty(),
            )
        }
    }

    private suspend fun stream(request: Request, retry: Boolean = true): RunStream {
        val response = http.open(request, Http.STREAM_TIMEOUT_S, retry)
        return RunStream(response.header(RUN_ID), response.sseFrames().map(SseParser::decode))
    }

    private fun get(path: String) = Request.Builder().url(http.url(path)).build()

    private fun jsonBody(o: JSONObject) = o.toString().toRequestBody("application/json".toMediaType())

    private fun post(path: String, body: okhttp3.RequestBody) =
        Request.Builder().url(http.url(path)).post(body).build()

    companion object {
        const val PAGE = 50
        /** The most rows the server gives for one /api/history request. */
        const val MAX_WINDOW = 100
        private const val RUN_ID = "X-Odysseus-Run-Id"

        /** Model ids the server itself refuses to pick as a chat default. */
        private val NON_CHAT = listOf(
            "text-embedding", "embedding", "tts-", "whisper", "text-moderation", "moderation-", "dall-e", "rerank",
        )

        /**
         * The chat_stream form, as the web client sends it. allow_bash is
         * always present: the server only disables bash when the field is
         * there and not "true", so leaving it out would hand an admin's agent
         * bash regardless of the toggle. plan_mode is always "false" -- an
         * approval re-POST with it true gets a 409.
         */
        internal fun formFields(r: ChatRequest): List<Pair<String, String>> = buildList {
            val t = r.toggles
            val agent = t.agentMode
            add("message" to r.message)
            add("session" to r.sessionId)
            add("mode" to if (agent) "agent" else "chat")
            add("plan_mode" to "false")
            if (agent) add("allow_web_search" to t.web.toString())
            else if (t.web) add("use_web" to "true")
            if (t.research) add("use_research" to "true")
            add("allow_bash" to t.terminal.toString())
            r.selected?.let {
                add("selected_model" to it.model)
                add("selected_endpoint_id" to it.endpointId)
            }
            if (r.attachmentIds.isNotEmpty()) add("attachments" to JSONArray(r.attachmentIds).toString())
            r.approval?.let {
                add("tool_approval_id" to it.approvalId)
                add("tool_approval_decision" to it.decision)
            }
        }

        internal fun parseModels(root: JSONObject): List<ModelChoice> {
            val items = root.optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).map(items::getJSONObject)
                .filter { it.str("model_type") != "image" && !it.optBoolean("offline", false) }
                .flatMap { item ->
                    val endpointId = item.str("endpoint_id") ?: return@flatMap emptyList()
                    val endpointName = item.str("endpoint_name") ?: endpointId
                    fun pairs(ids: String, names: String): List<ModelChoice> {
                        val idArr = item.optJSONArray(ids) ?: return emptyList()
                        val nameArr = item.optJSONArray(names)
                        return (0 until idArr.length()).map { i ->
                            val id = idArr.getString(i)
                            ModelChoice(endpointId, endpointName, id, nameArr?.optString(i)?.ifBlank { null } ?: id)
                        }
                    }
                    (pairs("models", "models_display") + pairs("models_extra", "models_extra_display"))
                        .filter { m -> NON_CHAT.none { m.model.lowercase().contains(it) } }
                        .distinctBy { it.model }
                }
        }

        internal fun parseHistory(o: JSONObject): HistoryPage {
            val arr = o.optJSONArray("history") ?: JSONArray()
            val messages = (0 until arr.length()).map(arr::getJSONObject)
                .filter { it.str("role") == "user" || it.str("role") == "assistant" }
                .mapNotNull { m ->
                    val role = m.getString("role")
                    val meta = m.optJSONObject("metadata")
                    // An entry without an id cannot be fetched; it is skipped
                    // rather than failing the whole page.
                    val attachments = meta?.optJSONArray("attachments")?.let { a ->
                        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull {
                            val id = it.str("id") ?: return@mapNotNull null
                            UploadedFile(id, it.str("name").orEmpty(), it.str("mime").orEmpty())
                        }
                    } ?: emptyList()
                    val text = contentText(m.opt("content"))
                    // What the web shows of a user row; see Visible.kt.
                    val shown = if (role == "user") visibleUserText(text, attachments.isNotEmpty()) else text
                    if (isHiddenTurn(
                            role,
                            shown,
                            wakeCheckIn = meta?.optBoolean("wake_check_in", false) == true,
                            hiddenFromUserView = meta?.optBoolean("hidden_from_user_view", false) == true,
                        )
                    ) {
                        return@mapNotNull null
                    }
                    val split = if (role == "assistant") splitThinking(shown) else Split(shown, "")
                    val thinking = listOfNotNull(meta?.str("thinking"), split.thinking.ifBlank { null })
                        .joinToString("\n").ifBlank { null }
                    HistoryMessage(
                        role = role,
                        content = split.answer,
                        dbId = meta?.str("_db_id"),
                        thinking = thinking,
                        attachments = attachments,
                        raw = shown,
                        details = parseDetails(meta),
                    )
                }
            return HistoryPage(
                messages = messages,
                offset = o.optInt("offset", 0),
                total = o.optInt("total", messages.size),
                hasMoreBefore = o.optBoolean("has_more_before", false),
                model = o.str("model"),
                endpointId = o.str("endpoint_id"),
            )
        }

        /**
         * The server flattens list content to a string, except a stored JSON
         * string of parts, which it passes through verbatim. Either way the
         * text parts are joined; anything else is shown as it is.
         */
        internal fun contentText(content: Any?): String = when (content) {
            is JSONArray -> joinTextParts(content) ?: ""
            is String -> if (content.trimStart().startsWith("[")) {
                runCatching { JSONArray(content) }.getOrNull()?.let(::joinTextParts) ?: content
            } else content
            else -> ""
        }

        /** Null when this is not a list of parts after all (just text that
         * happens to start with a bracket). */
        private fun joinTextParts(parts: JSONArray): String? {
            val objects = (0 until parts.length()).map { parts.opt(it) as? JSONObject ?: return null }
            if (objects.any { it.str("type") == null }) return null
            return objects.filter { it.str("type") == "text" }.joinToString("\n") { it.str("text").orEmpty() }
        }

        private fun seg(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** A string field, with JSON null and absence both as null. */
internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else opt(key) as? String
