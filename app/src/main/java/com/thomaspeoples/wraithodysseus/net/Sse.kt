package com.thomaspeoples.wraithodysseus.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import org.json.JSONObject

/** One complete SSE frame: the optional `event:` name and its `data:` lines
 * joined with newlines. */
data class SseFrame(val event: String?, val data: String)

/** What a frame means to the chat layer. Unknown event types still come
 * through as [Event]; deciding to ignore them is the caller's call. */
sealed interface SseEvent {
    data object Done : SseEvent
    /** [replacesTurn]: the server is restarting this turn's answer text, so
     * clear it before appending (agent mode, `replacement_scope:"turn"`). */
    data class Delta(val text: String, val thinking: Boolean, val replacesTurn: Boolean) : SseEvent
    data class Event(val type: String, val json: JSONObject) : SseEvent
    data class Error(val message: String, val status: Int?) : SseEvent
    data class Raw(val text: String) : SseEvent
}

/**
 * Line-at-a-time frame assembler. The server only ever sends single-line
 * `data:` frames with LF endings, but CRLF and multi-line data are part of
 * the SSE format, cost nothing to accept, and a proxy in between is allowed
 * to produce them.
 */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Feed one line (without its terminator); returns a frame when the line
     * is the blank one that ends it. */
    fun feed(rawLine: String): SseFrame? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) {
            val frame = if (hasData) SseFrame(event, data.toString()) else null
            event = null
            data.setLength(0)
            hasData = false
            return frame
        }
        if (line.startsWith(":")) return null // comment, e.g. ": heartbeat 3"
        val field = line.substringBefore(':')
        val value = if (':' in line) line.substringAfter(':').removePrefix(" ") else ""
        when (field) {
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "event" -> event = value
        }
        return null
    }

    companion object {
        /**
         * `type` is checked before `delta` because final_response carries both
         * (with `delta` as a fallback copy of `content`), and it must replace
         * the answer rather than be appended to it.
         */
        fun decode(frame: SseFrame): SseEvent {
            if (frame.data == "[DONE]") return SseEvent.Done
            val json = runCatching { JSONObject(frame.data) }.getOrNull()
            val status = (json?.opt("status") as? Number)?.toInt()
            if (frame.event == "error" || (status != null && status >= 400)) {
                return SseEvent.Error(errorText(json, status, frame.data), status)
            }
            if (json == null) return SseEvent.Raw(frame.data)
            val type = json.opt("type") as? String
            if (type != null) return SseEvent.Event(type, json)
            val delta = json.opt("delta") as? String ?: return SseEvent.Raw(frame.data)
            val thinking = json.optBoolean("thinking", false)
            return SseEvent.Delta(
                text = delta,
                thinking = thinking,
                replacesTurn = !thinking && json.opt("replacement_scope") == "turn",
            )
        }

        /** Same precedence as the web client: `text`, then `error` (a string
         * or an object with `message`), then "Error <status>". */
        private fun errorText(json: JSONObject?, status: Int?, raw: String): String {
            if (json == null) return raw.ifBlank { "Error" }
            (json.opt("text") as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            when (val error = json.opt("error")) {
                is String -> if (error.isNotBlank()) return error
                is JSONObject -> (error.opt("message") as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return if (status != null) "Error $status" else "Error"
        }
    }
}

/**
 * The frames of a 2xx event-stream response, read on IO. Cancelling the
 * collector closes the response, which is what unblocks a read sitting
 * between heartbeats. A dropped connection surfaces as an IOException from
 * collect, which is the cue to resume.
 *
 * On HTTP/1.1, closing under a blocked read does not wake it with an
 * IOException: the next read (or close itself) can throw
 * IllegalStateException instead. Once cancelled, whatever the read or the
 * close throws is noise and is dropped, or it would outlive the cancel.
 */
fun Response.sseFrames(): Flow<SseFrame> {
    val response = this
    return channelFlow {
        launch(Dispatchers.IO) {
            try {
                val source = checkNotNull(response.body) { "No body" }.source()
                val parser = SseParser()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    parser.feed(line)?.let { send(it) }
                }
            } catch (e: Exception) {
                if (isActive) throw e
            }
            channel.close()
        }
        awaitClose { runCatching { response.close() } }
    }
}
