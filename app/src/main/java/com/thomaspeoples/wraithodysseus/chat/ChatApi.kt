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
import okhttp3.FormBody
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
    /** The chat's name, which the history route also reports. */
    val name: String? = null,
)

/**
 * A model is the pair (endpoint, model id): the same id can be served by
 * several endpoints. [url] is the endpoint's chat URL (switching a chat's
 * model names it, see [ChatApi.setModel]); [category] is "local" for the
 * user's own servers; [reasoningLevels] are the efforts the model accepts
 * (models_metadata.supported_reasoning_levels), empty for most.
 */
data class ModelChoice(
    val endpointId: String,
    val endpointName: String,
    val model: String,
    val display: String,
    val url: String = "",
    val category: String = "",
    val reasoningLevels: List<String> = emptyList(),
)

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
    /** privileges.can_use_bash: allow_bash is only ever true for those who have it. */
    val canBash: Boolean = true,
    /** The chat's reasoning effort (thinking_mode "effort:x"), if one is set. */
    val reasoningEffort: String? = null,
)

/** A live run. [runId] (X-Odysseus-Run-Id) is what stop needs; it is null on
 * an approval-deny stream, which has nothing to stop. */
class RunStream(val runId: String?, val events: Flow<SseEvent>)

data class UploadedFile(val id: String, val name: String, val mime: String)

/** Synthesized speech: [mime] is audio/mpeg or audio/wav. */
class Speech(val bytes: ByteArray, val mime: String)

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

    /**
     * history_routes.py:637: merges [metadata] into the LAST assistant row,
     * so callers use it only for the last reply. The web keeps a reply's
     * earlier versions (chat.js:7216) and which one is on show this way.
     * Setting the same keys twice is harmless, so it may retry.
     */
    suspend fun updateLastMeta(sessionId: String, metadata: JSONObject) {
        val body = JSONObject().put("metadata", metadata)
        http.call(post("/api/session/${seg(sessionId)}/update-last-meta", jsonBody(body)))
    }

    /**
     * history_routes.py:688: after a "continue" turn, joins the last two
     * assistant rows (the stopped reply and its continuation) with
     * [separator] and drops the hidden "continue" user row between them.
     * False when the server found nothing to join (it only joins rows with
     * the continue marker directly between). Not retried.
     */
    suspend fun mergeLastAssistant(sessionId: String, separator: String): Boolean {
        val body = JSONObject().put("separator", separator)
        val request = post("/api/session/${seg(sessionId)}/merge-last-assistant", jsonBody(body))
        return JSONObject(http.call(request, retry = false)).optBoolean("merged", false)
    }

    /** research_routes.py:645. Every background job of the chat, oldest first. */
    suspend fun researchJobs(sessionId: String): List<ResearchJob> =
        parseJobs(JSONObject(http.call(get("/api/research/chat-jobs/${seg(sessionId)}"))))

    /** GET /api/tts/stats (tts_routes.py:19): whether reading aloud can work from the app. */
    suspend fun ttsEnabled(): Boolean = parseTtsEnabled(JSONObject(http.call(get("/api/tts/stats"))))

    /**
     * POST /api/tts/synthesize (tts_routes.py:27) with the format "audio":
     * the spoken text as MP3 or WAV bytes. The server cuts the text at 5000
     * characters. A failure is an ApiException carrying the server's message.
     */
    suspend fun synthesize(text: String): Speech {
        val body = JSONObject().put("text", text).put("format", "audio")
        return withContext(Dispatchers.IO) {
            http.open(post("/api/tts/synthesize", jsonBody(body)), Http.STREAM_TIMEOUT_S).use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                if (bytes.isEmpty()) throw ApiException(502, "The server returned no audio")
                Speech(bytes, r.header("Content-Type")?.substringBefore(';')?.trim()?.ifBlank { null } ?: "audio/mpeg")
            }
        }
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

    // -- the chat itself -----------------------------------------------------

    /**
     * PATCH /api/session/{sid} with the model switch (session_routes.py:660,
     * form fields model, endpoint_url, endpoint_id). The server needs
     * endpoint_url present, though with a registered endpoint_id it rebuilds
     * the URL itself; non-admins must name a registered endpoint.
     */
    suspend fun setModel(sessionId: String, m: ModelChoice) {
        val body = FormBody.Builder()
            .add("model", m.model)
            .add("endpoint_url", m.url)
            .apply { if (m.endpointId.isNotBlank()) add("endpoint_id", m.endpointId) }
            .build()
        http.call(Request.Builder().url(http.url("/api/session/${seg(sessionId)}")).patch(body).build())
    }

    /** PATCH /api/session/{sid} with name=. Setting the same name twice is harmless. */
    suspend fun renameSession(sessionId: String, name: String) {
        require(name.isNotBlank()) { "name" }
        val body = FormBody.Builder().add("name", name).build()
        http.call(Request.Builder().url(http.url("/api/session/${seg(sessionId)}")).patch(body).build())
    }

    /** DELETE /api/session/{sid} (session_routes.py:849). A starred chat is refused with 403 and the server's own message. */
    suspend fun deleteSession(sessionId: String) {
        http.call(Request.Builder().url(http.url("/api/session/${seg(sessionId)}")).delete().build(), retry = false)
    }

    /** POST /api/session/{sid}/archive (session_routes.py:942). */
    suspend fun archiveSession(sessionId: String) {
        http.call(post("/api/session/${seg(sessionId)}/archive", ByteArray(0).toRequestBody()), retry = false)
    }

    /** GET /api/session/{sid}/context: the ring and the Chat Context panel. */
    suspend fun context(sessionId: String): ChatContext =
        parseContext(JSONObject(http.call(get("/api/session/${seg(sessionId)}/context"))))

    /**
     * POST /api/session/{sid}/compact (history_routes.py:1093). Returns the
     * server's own message: "Compacted: 12 msgs -> 6 msgs (80% -> 20%)", or
     * "Not enough messages to compact" (a 200, for a chat under 6 messages).
     * A chat with a run going is refused with 409 and a message. It makes a
     * model call, so it waits as long as a stream may and is never retried.
     */
    suspend fun compact(sessionId: String): String {
        val body = JSONObject().toString().toRequestBody("application/json".toMediaType())
        val o = JSONObject(http.call(post("/api/session/${seg(sessionId)}/compact", body), Http.STREAM_TIMEOUT_S, retry = false))
        return o.str("message") ?: "Compacted"
    }

    /** POST /api/session/{sid}/memory-extraction, memory-injection or skill-injection: {"enabled"}. */
    suspend fun setSwitch(sessionId: String, which: ChatSwitch, enabled: Boolean) {
        val body = JSONObject().put("enabled", enabled)
        http.call(post("/api/session/${seg(sessionId)}/${which.path}", jsonBody(body)))
    }

    /**
     * POST /api/session/{sid}/generation-settings (history_routes.py:1023).
     * The web always sends all three; a null override clears it. A
     * [reasoningEffort] ("default" clears) wins over [thinkingMode] on the server.
     */
    suspend fun setGeneration(
        sessionId: String,
        thinkingMode: String,
        temperature: Double?,
        maxTokens: Int?,
        reasoningEffort: String? = null,
    ): GenerationSettings {
        val body = JSONObject()
            .put("thinking_mode", thinkingMode)
            .put("temperature_override", temperature ?: JSONObject.NULL)
            .put("max_tokens_override", maxTokens ?: JSONObject.NULL)
        reasoningEffort?.let { body.put("reasoning_effort", it) }
        return parseGeneration(JSONObject(http.call(post("/api/session/${seg(sessionId)}/generation-settings", jsonBody(body)))))
    }

    /** GET /api/presets: the prompt presets, for the composer's preset_id. */
    suspend fun presets(): List<Preset> = parsePresets(JSONObject(http.call(get("/api/presets"))))

    /** GET /api/skills/slash-catalog: skills that can be run as slash commands. */
    suspend fun skillCommands(): List<SkillCommand> = parseSkills(JSONObject(http.call(get("/api/skills/slash-catalog"))))

    /**
     * POST /api/skills/{name}/invoke (skills_routes.py:1928): the skill-pinned
     * prompt, which the web then sends as an ordinary message. 404 for a name
     * that is not an invokable skill.
     */
    suspend fun invokeSkill(name: String, request: String): String {
        val body = JSONObject().put("request", request)
        return JSONObject(http.call(post("/api/skills/${seg(name)}/invoke", jsonBody(body)), retry = false)).getString("message")
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
         * bash regardless of the toggle, and it is true only for an agent
         * turn whose user may use bash. Plan mode makes the turn an agent turn
         * (chat.js:2551) and is never sent with an approval re-POST.
         */
        internal fun formFields(r: ChatRequest): List<Pair<String, String>> = buildList {
            val t = r.toggles
            val agent = t.agentMode
            add("message" to r.message)
            add("session" to r.sessionId)
            add("mode" to if (agent) "agent" else "chat")
            // An approval re-POST with plan_mode true gets a 409.
            add("plan_mode" to (t.planMode && r.approval == null).toString())
            if (agent) add("allow_web_search" to t.web.toString())
            else if (t.web) add("use_web" to "true")
            if (t.research) add("use_research" to "true")
            add("allow_bash" to (agent && t.terminal && r.canBash).toString())
            // The web sends use_rag=false whenever its RAG switch is off, which is its default.
            if (!t.rag) add("use_rag" to "false")
            t.preset?.takeIf { it.isNotBlank() }?.let { add("preset_id" to it) }
            r.reasoningEffort?.let { add("reasoning_effort" to it) }
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

        internal fun parseJobs(root: JSONObject): List<ResearchJob> {
            val arr = root.optJSONArray("jobs") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
                val progress = o.optJSONObject("progress")
                ResearchJob(
                    id = o.str("id") ?: return@mapNotNull null,
                    tool = o.str("tool").orEmpty(),
                    query = o.str("query").orEmpty(),
                    status = o.str("status").orEmpty(),
                    rounds = o.optInt("rounds", 0).takeIf { it > 0 },
                    sourceCount = if (o.has("source_count")) o.optInt("source_count") else null,
                    outcome = o.str("outcome"),
                    phase = progress?.str("phase"),
                    round = progress?.optInt("round", 0)?.takeIf { it > 0 },
                    totalSources = if (progress?.has("total_sources") == true) progress.optInt("total_sources") else null,
                )
            }
        }

        /**
         * Reading aloud needs audio the server makes: "browser" is the web's
         * own speech engine (synthesize refuses it) and "disabled" is off.
         * `available` already folds in the user's tts_enabled setting.
         */
        internal fun parseTtsEnabled(o: JSONObject): Boolean =
            o.optBoolean("available", false) && o.optBoolean("ready", false) &&
                o.str("provider").orEmpty().let { it.isNotEmpty() && it != "browser" && it != "disabled" }

        internal fun parseModels(root: JSONObject): List<ModelChoice> {
            val items = root.optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).map(items::getJSONObject)
                .filter { it.str("model_type") != "image" && !it.optBoolean("offline", false) }
                .flatMap { item ->
                    val endpointId = item.str("endpoint_id") ?: return@flatMap emptyList()
                    val endpointName = item.str("endpoint_name") ?: endpointId
                    val url = item.str("url").orEmpty()
                    val category = item.str("category").orEmpty()
                    val metadata = item.optJSONObject("models_metadata")
                    fun pairs(ids: String, names: String): List<ModelChoice> {
                        val idArr = item.optJSONArray(ids) ?: return emptyList()
                        val nameArr = item.optJSONArray(names)
                        return (0 until idArr.length()).map { i ->
                            val id = idArr.getString(i)
                            ModelChoice(
                                endpointId, endpointName, id, nameArr?.optString(i)?.ifBlank { null } ?: id,
                                url, category, reasoningLevels(metadata?.optJSONObject(id)),
                            )
                        }
                    }
                    (pairs("models", "models_display") + pairs("models_extra", "models_extra_display"))
                        .filter { m -> NON_CHAT.none { m.model.lowercase().contains(it) } }
                        .distinctBy { it.model }
                }
        }

        /** supported_reasoning_levels: strings, or objects with an `effort` (modelPicker.js:1077). */
        private fun reasoningLevels(meta: JSONObject?): List<String> {
            val arr = meta?.optJSONArray("supported_reasoning_levels") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                (arr.opt(i) as? String ?: (arr.opt(i) as? JSONObject)?.str("effort"))?.trim()?.lowercase()?.ifEmpty { null }
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
                    // The web (chatRenderer.js:3722) shows the chosen kept version
                    // in place of the stored text. An edit is saved over the
                    // stored text only, so an edited reply shows that instead.
                    val parsed = parseDetails(meta)
                    val details = if (parsed.edited || parsed.variants.size < 2) parsed.copy(variants = emptyList()) else parsed
                    val row = HistoryMessage(
                        role = role,
                        content = split.answer,
                        dbId = meta?.str("_db_id"),
                        thinking = thinking,
                        attachments = attachments,
                        raw = shown,
                        details = details,
                    )
                    if (role == "assistant" && details.variants.size > 1 && details.shownVariant != details.variants.lastIndex) {
                        showVariant(row, details.shownVariant)
                    } else row
                }
            return HistoryPage(
                messages = messages,
                offset = o.optInt("offset", 0),
                total = o.optInt("total", messages.size),
                hasMoreBefore = o.optBoolean("has_more_before", false),
                model = o.str("model"),
                endpointId = o.str("endpoint_id"),
                name = o.str("name"),
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

/**
 * [m] showing its kept version [index]: text and reasoning come from the
 * version, and the reply's own flagged reasoning is dropped, as it belongs to
 * the newest text.
 */
internal fun showVariant(m: HistoryMessage, index: Int): HistoryMessage {
    val v = m.details.variants.getOrNull(index) ?: return m
    val split = splitThinking(v.raw)
    return m.copy(
        content = split.answer,
        raw = v.raw,
        thinking = split.thinking.ifBlank { null },
        details = m.details.copy(variantIndex = index),
    )
}

/** A string field, with JSON null and absence both as null. */
internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else opt(key) as? String
