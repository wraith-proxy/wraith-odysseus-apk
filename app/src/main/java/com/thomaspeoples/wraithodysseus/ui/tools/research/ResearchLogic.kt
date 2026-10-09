package com.thomaspeoples.wraithodysseus.ui.tools.research

import org.json.JSONObject

/** The format choices of the start form and the history filter (panel.js _HISTORY_FILTERS), by wire value. */
val CATEGORY_LABELS = linkedMapOf(
    "" to "Auto",
    "product" to "Product",
    "comparison" to "Compare",
    "howto" to "How-to",
    "factcheck" to "Fact-check",
)

/** Filter chips and badges also know "landscape" and call "Standard" what has no format. */
fun categoryName(category: String): String = when (category) {
    "" -> "Standard"
    "product" -> "Product"
    "comparison" -> "Comparison"
    "howto" -> "How-to"
    "factcheck" -> "Fact-check"
    "landscape" -> "Landscape"
    else -> category
}

/** The search engines the web form offers; blank is the server's default. */
val SEARCH_PROVIDERS = listOf("", "searxng", "duckduckgo", "tavily", "brave", "google", "serper")

/** Rounds: 0 is Auto, otherwise 1..20, which is what the server accepts. */
val ROUND_CHOICES = (0..20).toList()

/**
 * What the panel says a running job is doing (jobs.js formatPhase). [maxRounds]
 * is what the job was started with; 0 or unknown shows "Round 2" without a total.
 */
fun formatPhase(p: Progress, maxRounds: Int = 0): String {
    if (p.phase.isBlank()) return "Starting..."
    val rn = if (p.round > 0) (if (maxRounds > 0) "Round ${p.round}/$maxRounds: " else "Round ${p.round}: ") else ""
    return when (p.phase) {
        "probing" -> "Probing model..."
        "planning" -> "Planning research strategy..."
        "searching" -> "${rn}Searching (${p.queries} queries)"
        "reading" -> "${rn}Reading ${p.totalSources} sources"
        "analyzing" -> "${rn}Analyzing ${p.totalFindings} findings"
        "writing" -> "Writing report -- ${p.totalSources} sources"
        "error", "warning" -> p.message.ifBlank { p.phase }
        else -> p.phase
    }
}

/**
 * The progress bar (panel.js): rounds over the cap the user chose, or over 8
 * when Auto, since Auto has no known end; a "model only" job has no rounds, so
 * it sits at 68.
 */
fun progressPercent(round: Int, maxRounds: Int, mode: String): Int =
    if (mode == "explain" || maxRounds == -1) 68 else Math.min(100, Math.round(round.toFloat() / (if (maxRounds > 0) maxRounds else 8) * 100))

/** m:ss, as jobs.js formatElapsed. */
fun formatElapsed(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * One stream frame applied to a job (jobs.js _connectStream). A frame is the
 * progress object plus `status`; the last one has `final`, with `error` text
 * when the job failed. `not_found` means the server no longer knows the job.
 * The frame is the whole progress, not a delta, so it replaces the old one.
 */
fun applyFrame(job: LiveJob, frame: JSONObject): LiveJob {
    val status = frame.optString("status")
    if (status == "not_found") return job.copy(status = "error", error = "The server no longer has this research")
    // The closing frame carries no progress; the last real one stays on show.
    var next = if (frame.optBoolean("final")) job else job.copy(progress = ResearchApi.parseProgress(frame))
    if (frame.has("source_state") && frame.optString("source_state").isNotBlank()) next = next.copy(sourceState = frame.optString("source_state"))
    if (frame.optJSONArray("navigation_trace") != null) next = next.copy(navigationTrace = ResearchApi.parseNavigationTrace(frame.optJSONArray("navigation_trace")))
    if (frame.optJSONArray("action_trace") != null) next = next.copy(actionTrace = ResearchApi.parseActionTrace(frame.optJSONArray("action_trace")))
    frame.optString("category").takeIf { it.isNotBlank() }?.let { next = next.copy(category = it) }
    frame.optString("mode").takeIf { it.isNotBlank() }?.let { next = next.copy(mode = it) }
    frame.optString("model").takeIf { it.isNotBlank() && next.model.isBlank() }?.let { next = next.copy(model = it) }
    if (frame.optBoolean("final")) {
        next = next.copy(status = finalStatus(status), error = frame.optString("error").takeIf { it.isNotBlank() })
    }
    return next
}

/** done and cancelled stay themselves; anything else that ended a job is a failure. */
private fun finalStatus(status: String) = if (status == "done" || status == "cancelled") status else "error"

/**
 * GET /api/research/status applied to a job: the fallback when the stream
 * breaks. A finished state ends the job the same way a final frame does.
 */
fun applyStatus(job: LiveJob, status: JSONObject): LiveJob {
    var next = job.copy(progress = ResearchApi.parseProgress(status.optJSONObject("progress")))
    status.optString("source_state").takeIf { it.isNotBlank() }?.let { next = next.copy(sourceState = it) }
    if (status.optJSONArray("navigation_trace") != null) next = next.copy(navigationTrace = ResearchApi.parseNavigationTrace(status.optJSONArray("navigation_trace")))
    if (status.optJSONArray("action_trace") != null) next = next.copy(actionTrace = ResearchApi.parseActionTrace(status.optJSONArray("action_trace")))
    status.optString("category").takeIf { it.isNotBlank() }?.let { next = next.copy(category = it) }
    status.optString("mode").takeIf { it.isNotBlank() }?.let { next = next.copy(mode = it) }
    val s = status.optString("status")
    if (s.isNotBlank() && s != "running") next = next.copy(status = finalStatus(s))
    return next
}

/**
 * The jobs this screen knows after GET /api/research/active. A job already
 * known keeps what it has (it is followed by its own stream, which is newer
 * than this snapshot, and keeps the settings it was started with); a new one
 * is adopted, which is how a job started by the agent or the web UI turns up.
 * Finished and failed jobs stay until dismissed.
 */
fun mergeActive(current: List<LiveJob>, active: List<LiveJob>): List<LiveJob> {
    val known = current.map { it.id }.toSet()
    return current + active.filter { it.id !in known }
}

/** History sorts: the web's "Recent / Oldest / Most sources / A-Z". */
enum class HistorySort(val label: String) { Recent("Recent"), Oldest("Oldest"), Sources("Most sources"), Alpha("A-Z") }

/** The category filter of the history; null is "all". */
fun historyCategories(items: List<LibraryItem>): List<Pair<String, Int>> =
    items.groupingBy { it.category }.eachCount().toList().sortedBy { (c, _) ->
        listOf("", "product", "comparison", "howto", "factcheck", "landscape").indexOf(c).let { if (it < 0) 99 else it }
    }

/**
 * The History list (panel.js _getVisibleHistory): finished reports only,
 * matching the search on the question, in the chosen order. Equal keys keep
 * the server's newest-first order.
 */
fun visibleHistory(items: List<LibraryItem>, search: String, sort: HistorySort, category: String?): List<LibraryItem> {
    val q = search.trim().lowercase()
    val shown = items.filter { it.status == "done" && (category == null || it.category == category) && (q.isEmpty() || q in it.query.lowercase()) }
    return when (sort) {
        HistorySort.Recent -> shown.sortedByDescending { it.completedAt }
        HistorySort.Oldest -> shown.sortedBy { it.completedAt }
        HistorySort.Sources -> shown.sortedByDescending { it.sourceCount }
        HistorySort.Alpha -> shown.sortedBy { it.query.lowercase() }
    }
}

/** What "Copy report" puts on the clipboard (panel.js _copyResult): question, report, raw findings, sources. */
fun copyText(query: String, r: ResearchResult): String = buildString {
    append("# ").append(query).append("\n\n").append(r.result)
    if (r.findings.isNotEmpty()) {
        append("\n\n---\n## Raw Findings\n")
        r.findings.forEach { append("\n### ").append(it.title.ifBlank { "Untitled" }).append("\nSource: ").append(it.url).append('\n').append(it.summary).append('\n') }
    }
    if (r.sources.isNotEmpty()) {
        append("\n\n---\n## Sources\n")
        append(r.sources.joinToString("\n") { "- [${it.title.ifBlank { it.url }}](${it.url})" })
    }
}

/** A source or link is opened only if it is http or https; anything else (javascript:, file:, intent:) is not a link. */
fun safeHref(raw: String?): String? {
    val url = raw?.trim().orEmpty()
    val lower = url.lowercase()
    return url.takeIf { (lower.startsWith("http://") || lower.startsWith("https://")) && url.length > 8 }
}

/** A source's badges (panel.js _researchSourceBadges): its kind, a non-default way it was read, and its score. */
fun sourceBadges(s: Source): String = listOfNotNull(
    s.kind.takeIf { it.isNotBlank() },
    s.retrieval.takeIf { it.isNotBlank() && it != "fetch" },
    s.score?.toString(),
).joinToString(" · ")
