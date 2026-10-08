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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** An item of GET /api/sessions (a bare array). */
data class ChatSession(
    val id: String,
    val name: String,
    val model: String?,
    val endpointId: String?,
    /** "chat", "agent", "research_pending" or null. */
    val mode: String?,
    val isImportant: Boolean,
    val updatedAt: String,
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
)

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
            ChatSession(
                id = o.getString("id"),
                name = o.str("name").orEmpty(),
                model = o.str("model"),
                endpointId = o.str("endpoint_id"),
                mode = o.str("mode"),
                isImportant = o.optBoolean("is_important", false),
                updatedAt = o.str("updated_at").orEmpty(),
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
                .build()
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

    private suspend fun stream(request: Request): RunStream {
        val response = http.open(request, Http.STREAM_TIMEOUT_S)
        return RunStream(response.header(RUN_ID), response.sseFrames().map(SseParser::decode))
    }

    private fun get(path: String) = Request.Builder().url(http.url(path)).build()

    private fun post(path: String, body: okhttp3.RequestBody) =
        Request.Builder().url(http.url(path)).post(body).build()

    companion object {
        const val PAGE = 50
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
                .map { m ->
                    val meta = m.optJSONObject("metadata")
                    val text = contentText(m.opt("content"))
                    val split = if (m.str("role") == "assistant") splitThinking(text) else Split(text, "")
                    val thinking = listOfNotNull(meta?.str("thinking"), split.thinking.ifBlank { null })
                        .joinToString("\n").ifBlank { null }
                    HistoryMessage(
                        role = m.getString("role"),
                        content = split.answer,
                        dbId = meta?.str("_db_id"),
                        thinking = thinking,
                        // An entry without an id cannot be fetched; it is skipped
                        // rather than failing the whole page.
                        attachments = meta?.optJSONArray("attachments")?.let { a ->
                            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull {
                                val id = it.str("id") ?: return@mapNotNull null
                                UploadedFile(id, it.str("name").orEmpty(), it.str("mime").orEmpty())
                            }
                        } ?: emptyList(),
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
