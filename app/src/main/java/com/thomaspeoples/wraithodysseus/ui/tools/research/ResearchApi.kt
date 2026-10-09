package com.thomaspeoples.wraithodysseus.ui.tools.research

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.sseFrames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What the start form sends. [maxRounds] 0 is "Auto" (the server then runs up
 * to 20); a blank string means "Default" and is left out of the request, as
 * the web UI's panel drops every falsy setting (research/panel.js
 * _readSettings).
 */
data class ResearchSettings(
    val maxRounds: Int = 0,
    val searchProvider: String = "",
    val endpointId: String = "",
    val model: String = "",
    /** product, comparison, howto or factcheck; blank is "Auto". */
    val category: String = "",
)

/** One model of the form's picker. A model is chosen, not an endpoint: the endpoint it came from rides along. */
data class ModelChoice(val model: String, val endpointId: String, val label: String)

/** One finished report of GET /api/research/library. Times are epoch seconds. */
data class LibraryItem(
    val id: String,
    val query: String,
    val category: String,
    val mode: String,
    val sourceCount: Int,
    val status: String,
    val duration: String,
    val startedAt: Double,
    val completedAt: Double,
)

/** The `progress` the researcher reports; every field is optional on the wire. */
data class Progress(
    val phase: String = "",
    val round: Int = 0,
    val queries: Int = 0,
    val totalSources: Int = 0,
    val totalFindings: Int = 0,
    val message: String = "",
    val model: String = "",
)

/** One row of the live "what is it doing" trace: the plan (action) or what was fetched (navigation). */
data class TraceRow(val tool: String, val target: String, val status: String, val meta: String)

/**
 * A job as this screen follows it. [status] is running, done, cancelled or
 * error. [startedAtMs] is wall-clock milliseconds. [settings] is what the job
 * was started with, when it was started here, so it can be retried or edited.
 */
data class LiveJob(
    val id: String,
    val query: String,
    val status: String = "running",
    val progress: Progress = Progress(),
    val startedAtMs: Long = 0,
    val category: String = "",
    val mode: String = "research",
    val sourceState: String = "",
    val actionTrace: List<TraceRow> = emptyList(),
    val navigationTrace: List<TraceRow> = emptyList(),
    val error: String? = null,
    val model: String = "",
    val settings: ResearchSettings? = null,
) {
    val running: Boolean get() = status == "running"
}

data class Source(val title: String, val url: String, val kind: String, val retrieval: String, val score: Int?)

data class Finding(val title: String, val url: String, val summary: String)

/** POST /api/research/result-peek: the report text and what it rests on. */
data class ResearchResult(
    val result: String,
    val sources: List<Source>,
    val findings: List<Finding>,
    val sourceState: String,
    val category: String,
    val mode: String,
    val actionTrace: List<TraceRow>,
    val navigationTrace: List<TraceRow>,
)

/**
 * The deep-research routes (routes/research/research_routes.py). Failures
 * are HTTP errors, which [Http] turns into [ApiException]; [checkBody] guards
 * the odd 200 that carries one anyway.
 */
class ResearchApi(private val http: Http) {

    /**
     * GET /api/auth/features: false only when an admin switched Deep Research
     * off, the one case in which the web UI hides it (app.js, deep_research).
     * Anything unreadable counts as on, as the web's `.catch(() => {})` does.
     */
    suspend fun featureEnabled(): Boolean {
        return try {
            JSONObject(http.call(Request.Builder().url(http.url("/api/auth/features")).build())).opt("deep_research") != false
        } catch (_: ApiException) {
            true
        } catch (_: IOException) {
            true
        } catch (_: JSONException) {
            true
        }
    }

    /**
     * GET /api/model-endpoints, which the server restricts to admins: the
     * models of every enabled LLM endpoint, once each. The web UI's picker
     * stays at "Default" when this is refused, and so does this.
     */
    suspend fun models(): List<ModelChoice> =
        parseModels(http.call(Request.Builder().url(http.url("/api/model-endpoints")).build()))

    /** GET /api/research/library: every finished report, newest first; sorting and search are done here. */
    suspend fun library(limit: Int = 200): List<LibraryItem> {
        val url = http.url("/api/research/library").newBuilder().addQueryParameter("sort", "recent")
            .addQueryParameter("limit", limit.toString()).build()
        val json = checkBody(http.call(Request.Builder().url(url).build()))
        val rows = json.optJSONArray("research") ?: JSONArray()
        return guard { (0 until rows.length()).map { rows.getJSONObject(it) }.mapNotNull(::parseLibraryItem) }
    }

    /** GET /api/research/active: this user's running jobs. */
    suspend fun active(): List<LiveJob> {
        val json = checkBody(http.call(Request.Builder().url(http.url("/api/research/active")).build()))
        val rows = json.optJSONArray("active") ?: JSONArray()
        return guard { (0 until rows.length()).map { rows.getJSONObject(it) }.mapNotNull(::parseActive) }
    }

    /**
     * POST /api/research/start. Never retried by OkHttp: a second send would
     * start a second job, which runs for minutes and costs model time.
     */
    suspend fun start(query: String, settings: ResearchSettings): LiveJob {
        val body = startBody(query, settings).toString().toRequestBody(JSON)
        val json = checkBody(http.call(Request.Builder().url(http.url("/api/research/start")).post(body).build(), retry = false))
        val id = json.optString("session_id")
        if (id.isBlank()) throw ApiException(200, "The server did not say which job it started")
        return LiveJob(
            id = id,
            query = json.str("query").ifBlank { query },
            startedAtMs = System.currentTimeMillis(),
            category = json.str("category"),
            mode = json.str("mode").ifBlank { "research" },
            settings = settings,
        )
    }

    /**
     * GET /api/research/stream/{id}: the SSE progress feed the web UI follows
     * with an EventSource. Each frame is one JSON object; the last has
     * `final` true. The flow ends when the server closes the stream and throws
     * IOException/ApiException like any call.
     */
    suspend fun stream(id: String): Flow<JSONObject> {
        val response = http.open(Request.Builder().url(url("stream", id)).build(), STREAM_READ_TIMEOUT_S)
        return response.sseFrames().map { frame ->
            runCatching { JSONObject(frame.data) }.getOrElse { JSONObject() }
        }
    }

    /** GET /api/research/status/{id}: what the web UI falls back to when the stream breaks. */
    suspend fun status(id: String): JSONObject =
        checkBody(http.call(Request.Builder().url(url("status", id)).build()))

    /** POST /api/research/cancel/{id}; false when it was no longer running. */
    suspend fun cancel(id: String): Boolean {
        val json = checkBody(http.call(Request.Builder().url(url("cancel", id)).post(EMPTY).build()))
        return json.optBoolean("cancelled")
    }

    /** POST /api/research/result-peek/{id}: the result without consuming it. */
    suspend fun result(id: String): ResearchResult {
        val json = checkBody(http.call(Request.Builder().url(url("result-peek", id)).post(EMPTY).build()))
        return guard { parseResult(json) }
    }

    /** GET /api/research/report/{id}: the visual report, an HTML page. */
    suspend fun report(id: String): String =
        http.call(Request.Builder().url(url("report", id)).build())

    /** DELETE /api/research/{id}: removes the report from the server's disk. */
    suspend fun delete(id: String) {
        checkBody(http.call(Request.Builder().url(http.url("/api/research").newBuilder().addPathSegment(id).build()).delete().build()))
    }

    /** POST /api/research/spinoff/{id}: a new chat primed with the report; its id. */
    suspend fun spinoff(id: String): String {
        val json = checkBody(http.call(Request.Builder().url(url("spinoff", id)).post(EMPTY).build(), retry = false))
        return json.optString("session_id").ifBlank { throw ApiException(200, "The server returned no chat") }
    }

    /** `/api/research/{verb}/{id}`; segments are encoded one by one, so an id can never add a path. */
    private fun url(verb: String, id: String): HttpUrl =
        http.url("/api/research").newBuilder().addPathSegment(verb).addPathSegment(id).build()

    companion object {
        private val JSON = "application/json".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody()
        private const val STREAM_READ_TIMEOUT_S = 300L

        internal fun startBody(query: String, s: ResearchSettings): JSONObject = JSONObject().apply {
            put("query", query)
            if (s.maxRounds > 0) put("max_rounds", s.maxRounds)
            if (s.searchProvider.isNotBlank()) put("search_provider", s.searchProvider)
            if (s.endpointId.isNotBlank()) put("endpoint_id", s.endpointId)
            if (s.model.isNotBlank()) put("model", s.model)
            if (s.category.isNotBlank()) put("category", s.category)
        }

        internal fun checkBody(body: String): JSONObject {
            val json = runCatching { JSONObject(body) }.getOrElse { throw ApiException(200, "Unexpected response") }
            val error = (json.opt("error") as? String)?.takeIf { it.isNotBlank() }
            if (error != null) throw ApiException(200, error)
            if (json.opt("success") == false || json.opt("ok") == false) throw ApiException(200, "Request failed")
            return json
        }

        private inline fun <T> guard(block: () -> T): T =
            try {
                block()
            } catch (_: JSONException) {
                throw ApiException(200, "Unexpected response")
            }

        private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

        private fun JSONObject.num(key: String): Double = optDouble(key, 0.0).let { if (it.isNaN()) 0.0 else it }

        internal fun parseModels(body: String): List<ModelChoice> {
            val rows = runCatching { JSONArray(body) }.getOrElse { throw ApiException(200, "Unexpected response") }
            val endpoints = guard {
                (0 until rows.length()).map { rows.getJSONObject(it) }.filter {
                    it.optBoolean("is_enabled") && it.optString("model_type", "llm").ifBlank { "llm" }.lowercase() == "llm"
                }
            }
            val seen = HashSet<String>()
            val out = ArrayList<ModelChoice>()
            endpoints.forEach { ep ->
                val models = ep.optJSONArray("models") ?: JSONArray()
                val name = ep.str("name").ifBlank { ep.str("base_url") }
                (0 until models.length()).map { models.optString(it) }.filter { it.isNotBlank() }
                    .sortedBy { it.lowercase() }
                    .forEach { m ->
                        if (seen.add(m)) out += ModelChoice(m, ep.str("id"), if (endpoints.size > 1) "$m · $name" else m)
                    }
            }
            return out
        }

        internal fun parseLibraryItem(o: JSONObject): LibraryItem? {
            val id = o.str("id")
            if (id.isBlank()) return null
            return LibraryItem(
                id = id,
                query = o.str("query"),
                category = o.str("category"),
                mode = o.str("mode").ifBlank { "research" },
                sourceCount = o.optInt("source_count"),
                status = o.str("status").ifBlank { "done" },
                duration = o.str("duration"),
                startedAt = o.num("started_at"),
                completedAt = o.num("completed_at"),
            )
        }

        internal fun parseProgress(o: JSONObject?): Progress =
            if (o == null) Progress() else Progress(
                phase = o.str("phase"),
                round = o.optInt("round"),
                queries = o.optInt("queries"),
                totalSources = o.optInt("total_sources"),
                totalFindings = o.optInt("total_findings"),
                message = o.str("message"),
                model = o.str("model"),
            )

        internal fun parseActive(o: JSONObject): LiveJob? {
            val id = o.str("session_id")
            if (id.isBlank()) return null
            val started = o.num("started_at")
            return LiveJob(
                id = id,
                query = o.str("query"),
                progress = parseProgress(o.optJSONObject("progress")),
                startedAtMs = if (started > 0) (started * 1000).toLong() else System.currentTimeMillis(),
                category = o.str("category"),
                mode = o.str("mode").ifBlank { "research" },
                sourceState = o.str("source_state"),
                actionTrace = parseActionTrace(o.optJSONArray("action_trace")),
                navigationTrace = parseNavigationTrace(o.optJSONArray("navigation_trace")),
            )
        }

        /** research/panel.js _renderActionTrace: "Round 2" / "Plan", the tool, and its query or url. */
        internal fun parseActionTrace(rows: JSONArray?): List<TraceRow> = objects(rows).map { item ->
            val round = if (item.has("round") && !item.isNull("round") && item.optString("round").toDoubleOrNull() != null) {
                "Round ${item.optInt("round")}"
            } else {
                "Plan"
            }
            val skipped = item.optString("status") == "skipped"
            val via = item.str("requested_by").takeIf { it.isNotBlank() && it != item.str("tool") }?.let { "via $it" }.orEmpty()
            val meta = if (skipped) {
                "skipped" + item.str("reason").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            } else {
                listOf(item.str("source"), via).filter { it.isNotBlank() }.joinToString(" · ")
            }
            TraceRow(round, "${item.str("tool").ifBlank { "tool" }}: ${item.str("query").ifBlank { item.str("url") }}", if (skipped) "skipped" else "", meta)
        }

        /** research/panel.js _renderNavigationTrace: what was fetched, its status and how good the source looked. */
        internal fun parseNavigationTrace(rows: JSONArray?): List<TraceRow> = objects(rows).map { item ->
            val meta = ArrayList<String>()
            if (item.has("results") && item.optString("results").toDoubleOrNull() != null) meta += "${item.optInt("results")} results"
            item.str("source_kind").takeIf { it.isNotBlank() }?.let { meta += it }
            if (item.has("source_score") && item.optString("source_score").toDoubleOrNull() != null) meta += "${item.optInt("source_score")}/100"
            item.str("retrieval").takeIf { it.isNotBlank() && it != "fetch" }?.let { meta += it }
            TraceRow(
                tool = item.str("tool").ifBlank { "tool" },
                target = item.str("query").ifBlank { item.str("title").ifBlank { item.str("url") } },
                status = item.str("status").ifBlank { "unknown" },
                meta = meta.joinToString(" · "),
            )
        }

        private fun objects(rows: JSONArray?): List<JSONObject> =
            if (rows == null) emptyList() else (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }

        internal fun parseResult(o: JSONObject): ResearchResult {
            val sources = o.optJSONArray("sources")
            val findings = o.optJSONArray("raw_findings")
            return ResearchResult(
                result = o.str("result"),
                sources = objects(sources).map {
                    Source(
                        title = it.str("title"),
                        url = it.str("url"),
                        kind = it.str("source_kind"),
                        retrieval = it.str("retrieval"),
                        score = if (it.has("source_score") && it.optString("source_score").toDoubleOrNull() != null) {
                            it.optDouble("source_score").toInt().coerceIn(0, 100)
                        } else {
                            null
                        },
                    )
                },
                findings = objects(findings).map { Finding(it.str("title"), it.str("url"), it.str("summary")) },
                sourceState = o.str("source_state"),
                category = o.str("category"),
                mode = o.str("mode"),
                actionTrace = parseActionTrace(o.optJSONArray("action_trace")),
                navigationTrace = parseNavigationTrace(o.optJSONArray("navigation_trace")),
            )
        }
    }
}
