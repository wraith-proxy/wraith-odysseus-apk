package com.thomaspeoples.wraithodysseus.chat

import com.thomaspeoples.wraithodysseus.net.SseEvent
import org.json.JSONArray
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
 * The clock is an argument of [apply] (defaulting to the wall clock) so the
 * thinking timer stays testable.
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
    /** The model the person picked, when the server routed elsewhere. */
    val requestedModel: String? = null,
    /** Sources, tool cards, metrics and the rest of what the web shows. */
    val details: MessageDetails = MessageDetails(),
    /** Short muted lines (compacted, step limit, fallback...), in order. */
    val notices: List<String> = emptyList(),
    /** message_saved: the database id the reply is stored under. */
    val savedId: String? = null,
    /** agent_step: the round the agent has reached. */
    val round: Int? = null,
    /** plan_update: the agent's latest plan text. Kept, not shown. */
    val plan: String? = null,
    /** When the first event of this turn arrived (the header's time). */
    val startedMs: Long? = null,
    /** The thinking timer: first thinking delta to first answer delta. */
    val thinkStartMs: Long? = null,
    val thinkEndMs: Long? = null,
    /** Events are being replayed from the run's buffer (a resume), so the
     * clock says nothing about when they happened: no times are stamped. */
    val replayed: Boolean = false,
) {
    private val split get() = splitThinking(raw)
    val answer get() = split.answer
    val thinking get() = listOf(flaggedThinking.trim(), split.thinking).filter { it.isNotEmpty() }.joinToString("\n")

    /** The thinking time as the web prints it (seconds), once the answer has started. */
    val thinkingSeconds: String?
        get() = if (thinkStartMs != null && thinkEndMs != null) formatSeconds((thinkEndMs - thinkStartMs) / 1000.0) else null

    fun apply(event: SseEvent, nowMs: Long = System.currentTimeMillis()): TurnState = step(event, nowMs).stamp(nowMs)

    private fun step(event: SseEvent, nowMs: Long): TurnState = when (event) {
        is SseEvent.Delta -> when {
            event.thinking -> copy(flaggedThinking = flaggedThinking + event.text)
            event.replacesTurn -> copy(raw = event.text)
            else -> copy(raw = raw + event.text)
        }
        is SseEvent.Error -> copy(error = event.message, toolLine = null)
        is SseEvent.Event -> onEvent(event.type, event.json)
        SseEvent.Done, is SseEvent.Raw -> this
    }

    /** Start the clocks. Once the thinking is timed the text is not split again. */
    private fun stamp(now: Long): TurnState {
        if (replayed) return this
        var s = if (startedMs == null) copy(startedMs = now) else this
        if (s.thinkEndMs != null) return s
        if (s.thinkStartMs == null && s.thinking.isNotEmpty()) s = s.copy(thinkStartMs = now)
        if (s.thinkStartMs != null && s.answer.isNotEmpty()) s = s.copy(thinkEndMs = now)
        return s
    }

    private fun onEvent(type: String, json: JSONObject): TurnState = when (type) {
        "final_response" -> {
            val content = json.str("content")?.ifBlank { null } ?: json.str("delta")?.ifBlank { null }
            if (content != null) copy(raw = content) else this
        }
        "model_info" -> copy(model = json.str("model") ?: model, requestedModel = json.str("requested_model") ?: requestedModel)
        // routes/chat_routes.py:3766 and src/llm_core.py:544.
        "model_actual" -> copy(model = json.str("model") ?: model, requestedModel = json.str("requested_model") ?: requestedModel)
        "turn_mode" -> copy(details = details.copy(mode = json.str("mode"), autoEscalated = json.optBoolean("auto_escalated", false)))
        "web_sources" -> copy(details = details.copy(webSources = parseSources(json.optJSONArray("data"))))
        "research_sources" -> copy(details = details.copy(researchSources = parseSources(json.optJSONArray("data"))))
        "research_findings" -> copy(details = details.copy(findings = parseFindings(json.optJSONArray("data"))))
        "rag_sources" -> copy(details = details.copy(ragSources = parseRagSources(json.optJSONArray("data"))))
        "memories_used" -> copy(details = details.copy(memories = parseMemories(json.optJSONArray("data"))))
        "metrics" -> {
            // An empty early frame (chat_routes.py:4088) must not wipe real numbers.
            val m = json.optJSONObject("data")?.let(MessageMetrics::from)
            if (m != null && !m.isEmpty) copy(details = details.copy(metrics = m)) else this
        }
        "message_saved" -> copy(savedId = json.str("id") ?: savedId)
        "agent_step" -> copy(round = if (json.has("round")) json.optInt("round") else round)
        "plan_update" -> copy(plan = json.optJSONObject("data")?.str("plan") ?: plan)
        "generated_image" -> {
            val image = GeneratedImage.from(json)
            if (image == null || details.images.any { it.url == image.url }) this else copy(details = details.copy(images = details.images + image))
        }
        "tool_start" -> {
            val tool = json.str("tool").orEmpty()
            if (tool == "ask_user") this else copy(
                toolLine = "Running $tool: ${json.str("command").orEmpty()}".trimEnd(' ', ':'),
                details = details.copy(tools = details.tools + ToolCard.started(json)),
            )
        }
        "tool_progress" -> copy(details = details.copy(tools = details.tools.withProgress(json)))
        "tool_output" -> {
            val tool = json.str("tool").orEmpty()
            val exit = if (json.isNull("exit_code")) null else json.optInt("exit_code")
            when {
                tool == "ask_user" -> this
                else -> copy(
                    toolLine = if (exit == null) "$tool: ${json.str("output").orEmpty()}" else "$tool finished (exit $exit)",
                    details = details.copy(tools = details.tools.withOutput(json)),
                )
            }
        }
        "ask_user" -> json.optJSONObject("data")?.let { copy(ask = parseAsk(it), toolLine = null) } ?: this
        "tool_approval_resolved" -> copy(ask = null)
        // src/llm_core.py:4607: the answer came from another route, so the header names it.
        "fallback" -> copy(model = json.str("answered_by") ?: model, notices = notices + noticeFor(type, json).orEmpty())
        // doc_*, email_open, ui_control and audit frames are other panels' business.
        else -> noticeFor(type, json)?.let { copy(notices = notices + it) } ?: this
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

// -- what the web shows beside the text ----------------------------------

/** A web or research source: the title and the page it came from. */
data class Source(val title: String, val url: String) {
    /** The host without "www.", as the web prints beside the title. */
    val domain: String get() = hostOf(url)
}

/** A retrieved document chunk (rag_sources); [similarity] is 0..1. */
data class RagSource(val filename: String, val similarity: Double?, val snippet: String)

/** One of research_findings: a source plus what was taken from it. */
data class Finding(val title: String, val url: String, val summary: String)

/** memories_used: [type] is "pinned" or "recalled". */
data class MemoryUsed(val type: String, val text: String)

data class ToolDiff(val file: String, val text: String, val added: Int, val removed: Int, val newFile: Boolean)

/** An image an image tool made. The picture itself needs the server's login, so the app shows its prompt. */
data class GeneratedImage(val url: String, val prompt: String?, val model: String?, val size: String?) {
    companion object {
        /** generated_image (src/agent_loop.py:34148): `url`, plus the image_* keys the tool returned. */
        fun from(j: JSONObject): GeneratedImage? {
            val url = j.str("image_url")?.ifBlank { null } ?: j.str("url")?.ifBlank { null } ?: return null
            return GeneratedImage(url, j.str("image_prompt"), j.str("image_model"), j.str("image_size"))
        }
    }
}

/**
 * One tool call: live from tool_start / tool_progress / tool_output, or from
 * metadata.tool_events in history (where [running] is always false).
 */
data class ToolCard(
    val callId: String? = null,
    val tool: String,
    val command: String = "",
    val output: String = "",
    val exitCode: Int? = null,
    val running: Boolean = false,
    val diff: ToolDiff? = null,
    /** A base64 data URI, as the web accepts only that. */
    val screenshot: String? = null,
    /** Live: elapsed time or image step, from tool_progress. */
    val progress: String? = null,
    /** Live: the tail of stdout/stderr, from tool_progress. */
    val tail: String? = null,
) {
    /** The web's rule: no exit code, or 0, is success. */
    val ok: Boolean get() = exitCode == null || exitCode == 0

    companion object {
        /** tool_start (src/agent_loop.py:33426): tool, command, full_command, round, call_id. */
        fun started(j: JSONObject) = ToolCard(
            callId = j.str("call_id")?.ifBlank { null } ?: j.str("tool_call_id")?.ifBlank { null },
            tool = j.str("tool").orEmpty(),
            command = j.str("command") ?: j.str("full_command").orEmpty(),
            running = true,
        )

        /** tool_output (src/agent_loop.py:34035-34135) and a saved tool event (:34966) share these keys. */
        fun finished(j: JSONObject, callId: String? = null) = ToolCard(
            callId = callId,
            tool = j.str("tool").orEmpty(),
            command = j.str("command").orEmpty(),
            output = j.str("output").orEmpty(),
            exitCode = if (j.isNull("exit_code") || !j.has("exit_code")) null else (j.opt("exit_code") as? Number)?.toInt(),
            diff = j.optJSONObject("diff")?.let(::parseDiff),
            screenshot = safeScreenshot(j.str("screenshot")),
        )
    }
}

private val SAFE_SCREENSHOT = Regex("""^data:image/(?:png|jpe?g|gif|webp);base64,[a-z0-9+/=\s]+$""", RegexOption.IGNORE_CASE)

/** chatRenderer.js:94 safeToolScreenshotSrc: only an inline image is ever shown. */
internal fun safeScreenshot(raw: String?): String? = raw?.trim()?.takeIf { SAFE_SCREENSHOT.matches(it) }

private fun parseDiff(d: JSONObject): ToolDiff? {
    val text = d.str("text")?.ifBlank { null } ?: return null
    return ToolDiff(d.str("file") ?: "diff", text, d.optInt("added", 0), d.optInt("removed", 0), d.optBoolean("new_file", false))
}

/** tool_progress: a long-running tool's elapsed time and output tail
 * (src/containment.py:1160), or an image tool's step (routes/chat_routes.py:4044). */
internal fun List<ToolCard>.withProgress(j: JSONObject): List<ToolCard> {
    val at = indexOfLast { it.running && it.tool == j.str("tool").orEmpty() }.takeIf { it >= 0 } ?: indexOfLast { it.running }
    if (at < 0) return this
    val total = j.optInt("total", 0)
    val step = j.optInt("step", 0)
    val label = j.str("message")?.ifBlank { null }
    val value = when {
        total > 0 -> "$step/$total"
        j.has("percent") && !j.isNull("percent") -> "${Math.round(j.optDouble("percent"))}%"
        j.has("elapsed_s") -> formatSeconds(j.optDouble("elapsed_s")) + "s"
        j.has("elapsed") -> "${j.optInt("elapsed")}s"
        else -> null
    }
    val progress = listOfNotNull(label, value).joinToString(" ").ifBlank { null }
    val tail = j.str("tail")?.trim()?.ifBlank { null }
    return mapIndexed { i, c -> if (i == at) c.copy(progress = progress ?: c.progress, tail = tail ?: c.tail) else c }
}

/** tool_output closes the matching running card (by call id, else the latest of that tool), or is a card of its own. */
internal fun List<ToolCard>.withOutput(j: JSONObject): List<ToolCard> {
    val callId = j.str("call_id")?.ifBlank { null } ?: j.str("tool_call_id")?.ifBlank { null }
    val tool = j.str("tool").orEmpty()
    val at = indexOfLast { it.running && callId != null && it.callId == callId }.takeIf { it >= 0 }
        ?: indexOfLast { it.running && it.tool == tool }
    val done = ToolCard.finished(j, callId)
    if (at < 0) return this + done
    return mapIndexed { i, c -> if (i == at) done.copy(callId = c.callId ?: callId, command = done.command.ifBlank { c.command }) else c }
}

/**
 * Everything a message carries besides its text: filled live by the reducer
 * and from history metadata by [parseDetails], and drawn by one set of
 * composables, so the live bubble becomes the saved one without changing shape.
 */
data class MessageDetails(
    /** The model that answered, and the one asked for when they differ (history). */
    val model: String? = null,
    val requestedModel: String? = null,
    /** Epoch millis of the message (history metadata.timestamp). */
    val timeMs: Long? = null,
    /** "agent", "chat" or "research": how the turn ran. */
    val mode: String? = null,
    val autoEscalated: Boolean = false,
    /** history thinking_time, already in seconds. */
    val thinkingSeconds: String? = null,
    val webSources: List<Source> = emptyList(),
    val researchSources: List<Source> = emptyList(),
    val findings: List<Finding> = emptyList(),
    val ragSources: List<RagSource> = emptyList(),
    val memories: List<MemoryUsed> = emptyList(),
    val tools: List<ToolCard> = emptyList(),
    val images: List<GeneratedImage> = emptyList(),
    val metrics: MessageMetrics? = null,
) {
    /** The web shows research sources in place of web ones when both exist. */
    val sources: List<Source> get() = researchSources.ifEmpty { webSources }
    val sourcesLabel: String get() = if (researchSources.isNotEmpty()) "Research sources" else "Web sources"

    /** The ">_ auto" chip: chatRenderer.js:1526 userModePill, which marks agent and research turns. */
    val modeChip: String? get() = when (mode) {
        "agent" -> ">_ auto"
        "research" -> "research"
        else -> null
    }
}

/**
 * The numbers behind the footer, the stats popup and the context ring: the
 * `data` of a metrics event (src/agent_loop.py:16221-16241,
 * routes/chat_routes.py:4363-4378), and the same keys flat in a saved
 * message's metadata.
 */
data class MessageMetrics(
    val responseTime: Double? = null,
    val tokensPerSecond: Double? = null,
    /** "computed" means wall-clock speed rather than the backend's. */
    val tpsSource: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** usage_source == "real"; otherwise the counts are estimates. */
    val realUsage: Boolean = false,
    val contextPercent: Double? = null,
    val contextLength: Int? = null,
    val model: String? = null,
    val costUsd: Double? = null,
    val timeToFirstToken: Double? = null,
    val prepTime: Double? = null,
    val modelWaitTime: Double? = null,
    val prepBreakdown: List<Pair<String, Double>> = emptyList(),
    val toolSchemaCount: Int? = null,
    val toolSchemaNames: List<String> = emptyList(),
    val agentRounds: Int? = null,
    val toolCalls: Int? = null,
) {
    /** Nothing here worth a footer: the web returns before drawing it. */
    val isEmpty: Boolean get() =
        responseTime == null && tokensPerSecond == null && inputTokens == 0 && outputTokens == 0 && (contextPercent ?: 0.0) <= 0.0

    companion object {
        fun from(j: JSONObject): MessageMetrics {
            fun d(k: String): Double? = if (!j.has(k) || j.isNull(k)) null else when (val v = j.opt(k)) {
                is Number -> v.toDouble().takeIf { it.isFinite() }
                is String -> v.toDoubleOrNull()?.takeIf { it.isFinite() }
                else -> null
            }
            fun i(k: String): Int? = d(k)?.toInt()
            val breakdown = j.optJSONObject("agent_prep_breakdown")
            val names = j.optJSONArray("tool_schema_names")
            return MessageMetrics(
                responseTime = d("response_time"),
                tokensPerSecond = d("tokens_per_second"),
                tpsSource = j.str("tps_source"),
                inputTokens = i("input_tokens") ?: 0,
                outputTokens = i("output_tokens") ?: 0,
                realUsage = j.str("usage_source") == "real",
                contextPercent = d("context_percent"),
                contextLength = i("context_length"),
                model = j.str("model"),
                costUsd = d("cost_usd")?.takeIf { it > 0 },
                // The web prefers its own client-side measure, which the app does not take.
                timeToFirstToken = d("client_ttft") ?: d("time_to_first_token"),
                prepTime = d("agent_prep_time"),
                modelWaitTime = d("agent_model_wait_time"),
                prepBreakdown = breakdown?.keys()?.asSequence()?.mapNotNull { k -> breakdown.optDouble(k).takeIf { !it.isNaN() }?.let { k to it } }?.toList().orEmpty(),
                toolSchemaCount = i("tool_schema_count"),
                toolSchemaNames = (0 until (names?.length() ?: 0)).mapNotNull { names?.optString(it)?.trim()?.ifBlank { null } },
                agentRounds = i("agent_rounds"),
                toolCalls = i("tool_calls"),
            )
        }
    }
}

// -- history metadata ------------------------------------------------------

/**
 * A saved row's metadata (history_routes.py serves it whole; the keys are
 * written by routes/chat_helpers.py:1520 save_assistant_response and :762
 * for user rows). The metrics sit flat among them, as the web reads them.
 */
fun parseDetails(meta: JSONObject?): MessageDetails {
    if (meta == null) return MessageDetails()
    val metrics = MessageMetrics.from(meta).takeIf { !it.isEmpty }
    val events = meta.optJSONArray("tool_events")
    val saved = (0 until (events?.length() ?: 0)).mapNotNull { events?.optJSONObject(it) }
    return MessageDetails(
        model = meta.str("model") ?: meta.str("actual_model"),
        requestedModel = meta.str("requested_model") ?: meta.str("selected_model"),
        timeMs = meta.str("timestamp")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
        mode = (meta.str("interaction_mode") ?: meta.str("mode"))?.lowercase(),
        autoEscalated = meta.optBoolean("auto_escalated", false),
        thinkingSeconds = when (val t = meta.opt("thinking_time")) {
            is Number -> formatSeconds(t.toDouble())
            is String -> t.trim().ifBlank { null }
            else -> null
        },
        webSources = parseSources(meta.optJSONArray("web_sources")),
        researchSources = parseSources(meta.optJSONArray("research_sources")),
        findings = parseFindings(meta.optJSONArray("research_findings")),
        ragSources = parseRagSources(meta.optJSONArray("rag_sources")),
        memories = parseMemories(meta.optJSONArray("memories_used")),
        tools = saved.filter { it.str("tool") != "ask_user" }.map { ToolCard.finished(it) },
        images = saved.mapNotNull(::savedImage).distinctBy { it.url },
        metrics = metrics,
    )
}

/** static/js/generatedImageResult.js: a failed call has no image; an image tool may carry it in its JSON output. */
private fun savedImage(ev: JSONObject): GeneratedImage? {
    if (ev.has("error") && !ev.isNull("error") || (!ev.isNull("exit_code") && ev.optInt("exit_code") != 0)) return null
    if (ev.str("image_url") != null) return GeneratedImage.from(ev)
    if (ev.str("tool") !in setOf("generate_image", "edit_image")) return null
    val out = runCatching { JSONObject(ev.str("output").orEmpty()) }.getOrNull() ?: return null
    if (out.has("error") && !out.isNull("error")) return null
    return GeneratedImage.from(out)
}

private fun <T> JSONArray?.objects(map: (JSONObject) -> T?): List<T> =
    (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it)?.let(map) }

internal fun parseSources(a: JSONArray?): List<Source> = a.objects { s ->
    val url = s.str("url")?.ifBlank { null } ?: return@objects null
    Source(s.str("title")?.ifBlank { null } ?: hostOf(url), url)
}

internal fun parseFindings(a: JSONArray?): List<Finding> = a.objects { f ->
    val url = f.str("url") ?: return@objects null
    Finding(f.str("title")?.ifBlank { null } ?: hostOf(url), url, f.str("summary").orEmpty())
}

internal fun parseRagSources(a: JSONArray?): List<RagSource> = a.objects { r ->
    RagSource(r.str("filename").orEmpty(), (r.opt("similarity") as? Number)?.toDouble(), r.str("snippet").orEmpty())
}

internal fun parseMemories(a: JSONArray?): List<MemoryUsed> = a.objects { m ->
    MemoryUsed(m.str("type").orEmpty(), m.str("text").orEmpty())
}

// -- small formatting the web does in the same way ------------------------

internal fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")?.ifBlank { null } ?: url

internal fun formatSeconds(s: Double): String = String.format(java.util.Locale.ROOT, "%.1f", s)

/** chatRenderer.js:991 shortModel: the last path segment without .gguf, quantisation or shard suffix, cut to 25. */
fun shortModel(name: String?): String {
    if (name.isNullOrBlank()) return "..."
    var short = name.substringAfterLast('/')
        .replace(Regex("\\.gguf$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("-0000\\d-of-\\d+$"), "")
        .replace(Regex("[-_](Q\\d[_A-Z\\d]*|F16|F32|BF16|fp16|fp32)$", RegexOption.IGNORE_CASE), "")
    if (short.length > 25) {
        val size = Regex("^(.+?-\\d+[BbMm])").find(short)
        short = size?.groupValues?.get(1) ?: (short.substring(0, 22) + "…")
    }
    return short
}

/** The header's model name; "asked -> answered" when the server routed elsewhere (chatRenderer.js:1029). */
fun modelLabel(requested: String?, actual: String?): String? {
    val a = actual?.trim()?.ifBlank { null }
    val r = requested?.trim()?.ifBlank { null }
    return when {
        a == null && r == null -> null
        r == null || a == null || shortModel(r).equals(shortModel(a), ignoreCase = true) -> shortModel(a ?: r)
        else -> shortModel(r) + " -> " + shortModel(a)
    }
}

/** The short muted line the web shows for these events, or null for any other type. */
internal fun noticeFor(type: String, j: JSONObject): String? = when (type) {
    "compacted" -> "Context compacted — older messages summarized"
    "context_trimmed" -> {
        val d = j.optJSONObject("data")
        val before = d?.optInt("messages_before", 0) ?: 0
        val after = d?.optInt("messages_after", 0) ?: 0
        "Context trimmed for this model" + if (before > 0 && after > 0 && before > after) " ($after/$before messages sent)" else ""
    }
    "rounds_exhausted" -> j.optInt("rounds", 0).let { if (it > 0) "Reached the $it-step limit — not finished." else "Reached the step limit — not finished." }
    "budget_exceeded" -> "Tool budget reached (${j.optInt("used")}/${j.optInt("limit")} calls). Agent stopped."
    "loop_breaker_triggered" -> "[Agent guard: ${j.str("message")?.ifBlank { null } ?: j.str("reason")?.ifBlank { null } ?: "internal stop"}]"
    "teacher_takeover" -> "Teacher takeover: escalating to ${j.str("teacher_model")?.ifBlank { null } ?: "teacher"}" +
        (j.str("student_failure")?.ifBlank { null }?.let { " — $it" } ?: "")
    "escalation_failed" -> "Teacher could not solve it: ${j.str("reason").orEmpty()}"
    "fallback" -> "Fallback: ${shortModel(j.str("selected_model"))} failed — answered by ${shortModel(j.str("answered_by"))}"
    else -> null
}
