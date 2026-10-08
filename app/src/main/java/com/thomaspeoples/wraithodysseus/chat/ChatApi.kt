package com.thomaspeoples.wraithodysseus.chat

import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SseEvent
import kotlinx.coroutines.flow.Flow

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
    /** Already flattened to text, including the JSON-string-of-parts case. */
    val content: String,
    val dbId: String?,
    val thinking: String?,
)

/** GET /api/history/{sid}?limit=50[&offset]. No offset means the last page;
 * older pages are at max(offset - 50, 0) while [hasMoreBefore]. */
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

enum class ChatMode { CHAT, AGENT }

/** tool_approval_decision: "approve_task" (this task), "approve" (this chat
 * session) or "deny" -- the option's `value`, sent verbatim. */
data class ToolApproval(val approvalId: String, val decision: String)

/** One POST /api/chat_stream. [allowBash] is always sent: leaving it out
 * lets an admin's agent use bash regardless of the toggle. plan_mode is
 * never sent as true (an approval re-POST with it gets a 409). */
data class ChatRequest(
    val sessionId: String,
    val message: String,
    val mode: ChatMode,
    val webSearch: Boolean,
    val research: Boolean,
    val allowBash: Boolean,
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
    suspend fun sessions(): List<ChatSession> = TODO("U3")
    /** POST /api/session with skip_validation=true; returns the new id. */
    suspend fun createSession(name: String, endpointId: String, model: String): String = TODO("U3")
    suspend fun history(sessionId: String, offset: Int?): HistoryPage = TODO("U3")
    /** Chat models only: items with model_type "image" or offline are dropped. */
    suspend fun models(): List<ModelChoice> = TODO("U3")
    suspend fun defaultChat(): DefaultChat = TODO("U3")
    suspend fun chatStream(request: ChatRequest): RunStream = TODO("U3")
    /** Replays the whole run from the start; null on 404 (no run running). */
    suspend fun resume(sessionId: String): RunStream? = TODO("U3")
    suspend fun stop(sessionId: String, runId: String): Boolean = TODO("U3")
    suspend fun upload(name: String, mime: String, bytes: ByteArray): UploadedFile = TODO("U3")
    suspend fun search(query: String, limit: Int = 25): List<SearchHit> = TODO("U3")
}
