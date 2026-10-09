package com.thomaspeoples.wraithodysseus.chat

import com.thomaspeoples.wraithodysseus.net.SseEvent
import org.json.JSONObject

data class Split(val answer: String, val thinking: String)

private val OPEN_TAGS = listOf("<think>", "<thinking>")

/**
 * Some models put their reasoning inline as <think>…</think> (or
 * <thinking>) in the ordinary text instead of flagged thinking deltas. The
 * whole accumulated text is re-split on every update -- it is small -- so a
 * tag cut across two deltas needs no partial-tag state. An unclosed block
 * means the model is still thinking: everything after it is reasoning.
 */
fun splitThinking(raw: String): Split {
    val answer = StringBuilder()
    val thinking = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val next = OPEN_TAGS.map { raw.indexOf(it, i) to it }.filter { it.first >= 0 }.minByOrNull { it.first }
        if (next == null) {
            answer.append(raw, i, raw.length)
            break
        }
        val (open, tag) = next
        answer.append(raw, i, open)
        val close = "</" + tag.substring(1)
        val start = open + tag.length
        val end = raw.indexOf(close, start)
        if (end < 0) {
            thinking.append(raw, start, raw.length)
            i = raw.length
        } else {
            thinking.append(raw, start, end)
            i = end + close.length
        }
    }
    return Split(answer.toString().trim(), thinking.toString().trim())
}

data class AskOption(val label: String, val description: String, val value: String?)

/**
 * What a tool-approval gate will run: the "action" object of the server's
 * public_payload (src/tool_approvals.py:196-249). [content] is the complete
 * sealed input -- the command, path or JSON arguments -- so the person
 * approves what will actually happen rather than only the question.
 */
data class ApprovalAction(
    val tool: String,
    val content: String,
    val effects: List<String>,
    val workspace: String?,
    val documentId: String?,
    val documentVersion: Int?,
    val digest: String?,
) {
    /** The same lines, in the same order, as the web card (chatRenderer.js:3047). */
    fun lines(): List<String> = listOfNotNull(
        tool.ifBlank { "tool" },
        content.ifEmpty { null },
        effects.takeIf { it.isNotEmpty() }?.let { "Effects: ${it.joinToString(", ")}" },
        workspace?.let { "Workspace: $it" },
        documentId?.let { "Document: $it" },
        documentVersion?.let { "Document version: $it" },
        digest?.let { "Approval fingerprint: $it" },
    )
}

/** A plain ask_user question, or (with [approvalId]) a tool-approval gate,
 * whose options carry the decision `value` to re-POST. */
data class AskUser(
    val question: String,
    val description: String,
    val options: List<AskOption>,
    val multi: Boolean,
    val approvalId: String?,
    val action: ApprovalAction? = null,
)

/**
 * One assistant turn as the stream builds it. A pure reducer, so a resume
 * can start again from [TurnState()] and replay the run without doubling.
 */
data class TurnState(
    /** Answer text as streamed, inline think tags and all. */
    val raw: String = "",
    /** Flagged thinking deltas. */
    val flaggedThinking: String = "",
    val toolLine: String? = null,
    val ask: AskUser? = null,
    val error: String? = null,
    val model: String? = null,
) {
    private val split get() = splitThinking(raw)
    val answer get() = split.answer
    val thinking get() = listOf(flaggedThinking.trim(), split.thinking).filter { it.isNotEmpty() }.joinToString("\n")

    fun apply(event: SseEvent): TurnState = when (event) {
        is SseEvent.Delta -> when {
            event.thinking -> copy(flaggedThinking = flaggedThinking + event.text)
            event.replacesTurn -> copy(raw = event.text)
            else -> copy(raw = raw + event.text)
        }
        is SseEvent.Error -> copy(error = event.message, toolLine = null)
        is SseEvent.Event -> onEvent(event.type, event.json)
        SseEvent.Done, is SseEvent.Raw -> this
    }

    private fun onEvent(type: String, json: JSONObject): TurnState = when (type) {
        "final_response" -> {
            val content = json.str("content")?.ifBlank { null } ?: json.str("delta")?.ifBlank { null }
            if (content != null) copy(raw = content) else this
        }
        "model_info" -> copy(model = json.str("model"))
        "tool_start" -> {
            val tool = json.str("tool").orEmpty()
            if (tool == "ask_user") this else copy(toolLine = "Running $tool: ${json.str("command").orEmpty()}".trimEnd(' ', ':'))
        }
        "tool_output" -> {
            val tool = json.str("tool").orEmpty()
            val exit = if (json.isNull("exit_code")) null else json.optInt("exit_code")
            when {
                tool == "ask_user" -> this
                exit == null -> copy(toolLine = "$tool: ${json.str("output").orEmpty()}")
                else -> copy(toolLine = "$tool finished (exit $exit)")
            }
        }
        "ask_user" -> json.optJSONObject("data")?.let { copy(ask = parseAsk(it), toolLine = null) } ?: this
        "tool_approval_resolved" -> copy(ask = null)
        else -> this
    }

    private fun parseAsk(d: JSONObject): AskUser {
        val opts = d.optJSONArray("options")
        return AskUser(
            question = d.str("question").orEmpty(),
            description = d.str("description").orEmpty(),
            options = (0 until (opts?.length() ?: 0)).mapNotNull { opts?.optJSONObject(it) }.map {
                AskOption(it.str("label").orEmpty(), it.str("description").orEmpty(), it.str("value"))
            },
            multi = d.optBoolean("multi", false),
            approvalId = if (d.str("kind") == "tool_approval") d.str("approval_id") else null,
            action = d.optJSONObject("action")?.let(::parseAction),
        )
    }

    private fun parseAction(a: JSONObject): ApprovalAction {
        val effects = a.optJSONArray("effects")
        return ApprovalAction(
            tool = a.str("tool").orEmpty(),
            content = a.str("content").orEmpty(),
            effects = (0 until (effects?.length() ?: 0)).mapNotNull { effects?.optString(it)?.ifBlank { null } },
            workspace = a.str("workspace")?.ifBlank { null },
            documentId = a.str("document_id")?.ifBlank { null },
            documentVersion = if (a.isNull("document_version")) null else a.optInt("document_version"),
            digest = a.str("digest")?.ifBlank { null },
        )
    }
}
