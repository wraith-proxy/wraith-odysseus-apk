package com.thomaspeoples.wraithodysseus.email

import org.json.JSONObject

// POST /api/email/ai-reply with stream:true (routes/email/email_routes.py:6204).
// The same call the web UI makes (static/js/document.js ~6800-6860).

/** What the server's own text says at most; the route truncates to this. */
const val AI_HINT_MAX = 2000

/**
 * [to] and [subject] are the composer's current values; [originalBody] is the
 * plain text of the message being answered. [userHint] is the optional
 * "Context" box; blank is left out of the JSON entirely, because the server
 * skips its reply cache only when a hint is present (email_routes.py ~6246).
 */
data class AiReplyRequest(
    val to: String,
    val subject: String,
    val originalBody: String,
    val messageId: String,
    val uid: String,
    val folder: String,
    val accountId: String?,
    val userHint: String = "",
)

/** The request for replying to [original] with the composer showing [to] and [subject]. */
fun aiReplyRequest(original: EmailMessage, to: String, subject: String, accountId: String?, hint: String) =
    AiReplyRequest(
        to = to,
        subject = subject,
        originalBody = quoteText(original),
        messageId = original.messageId,
        uid = original.uid,
        folder = original.folder,
        accountId = accountId,
        userHint = hint,
    )

/**
 * Deliberately no `model` and no `session_id`: with neither, the server picks
 * its utility model and then the default, exactly as the web UI does.
 * `fast` is always true (the web's default "Submit" button).
 */
internal fun aiReplyJson(r: AiReplyRequest): JSONObject = JSONObject().apply {
    put("to", r.to)
    put("subject", r.subject)
    put("original_body", r.originalBody)
    r.userHint.trim().takeIf { it.isNotEmpty() }?.let { put("user_hint", it.take(AI_HINT_MAX)) }
    put("message_id", r.messageId)
    put("uid", r.uid)
    put("folder", r.folder)
    r.accountId?.let { put("account_id", it) }
    put("stream", true)
    put("fast", true)
}

/** One data: frame of the stream. */
sealed interface AiReplyFrame {
    /** The full visible text so far, not a delta. "" means start over. */
    data class Preview(val text: String) : AiReplyFrame
    /** The last frame, and the only authoritative one: previews are cut at
     * the first "<". Failures arrive here too, as HTTP 200 with success:false. */
    data class Result(val success: Boolean, val reply: String?, val error: String?, val model: String?) : AiReplyFrame
}

/** How a generation ended. */
sealed interface AiReplyOutcome {
    data class Reply(val text: String, val model: String?) : AiReplyOutcome
    /** [message] is the server's own sentence, verbatim. */
    data class Failed(val message: String) : AiReplyOutcome
}

/** Where a stream has got to: the text to show, and the outcome once known. */
data class AiReplyProgress(val text: String = "", val outcome: AiReplyOutcome? = null)

/** Null for a frame that is not JSON or of a type this client does not know. */
fun parseAiReplyFrame(data: String): AiReplyFrame? {
    val json = runCatching { JSONObject(data) }.getOrNull() ?: return null
    return when (json.opt("type")) {
        "reply" -> AiReplyFrame.Preview(json.opt("text") as? String ?: "")
        "result" -> AiReplyFrame.Result(
            success = json.opt("success") == true,
            reply = json.opt("reply") as? String,
            error = (json.opt("error") as? String)?.takeIf { it.isNotBlank() },
            model = (json.opt("model_used") as? String)?.takeIf { it.isNotBlank() },
        )
        else -> null
    }
}

/**
 * Same test as the web: success with a non-empty reply is a draft, anything
 * else is the server's error (or a generic one). Frames after the result are
 * ignored; there should be none.
 */
fun reduceAiReply(state: AiReplyProgress, frame: AiReplyFrame): AiReplyProgress {
    if (state.outcome != null) return state
    return when (frame) {
        is AiReplyFrame.Preview -> state.copy(text = frame.text)
        is AiReplyFrame.Result -> state.copy(
            outcome = if (frame.success && !frame.reply.isNullOrEmpty()) AiReplyOutcome.Reply(frame.reply, frame.model)
            else AiReplyOutcome.Failed(frame.error ?: "Failed to generate reply"),
        )
    }
}

/** The composer body for a draft [text]: above the quoted original. */
fun aiReplyBody(text: String, quote: String): String = text + quote
