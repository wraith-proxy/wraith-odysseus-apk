package com.thomaspeoples.wraithodysseus.ui.tools.memory

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One item of GET /api/memory. [timestamp] is epoch seconds (the server
 * stamps it on add and on every edit), [uses] counts the chats it was put
 * into, and a pinned memory is always in context rather than found by search.
 */
data class Memory(
    val id: String,
    val text: String,
    val category: String,
    val pinned: Boolean,
    val timestamp: Long,
    val uses: Int,
)

/** The categories the web UI's picker offers (memory.js MEMORY_CATEGORIES). */
val MEMORY_CATEGORIES = listOf("fact", "identity", "preference", "contact", "project", "goal", "task")

/** The two preferences the web UI's Memory panel owns. */
const val PREF_MEMORY_ENABLED = "memory_enabled"
const val PREF_AUTO_MEMORY = "auto_memory"

/**
 * The memory routes and the two prefs behind the panel's toggles. Real
 * failures are HTTP errors (FastAPI `detail`), which [Http] already turns
 * into [ApiException]; [checkBody] guards the odd 200 that carries one.
 */
class MemoryApi(private val http: Http) {

    /** GET /api/memory: every memory of this user, in the server's order. */
    suspend fun list(): List<Memory> {
        val json = checkBody(http.call(Request.Builder().url(url().build()).build()))
        val rows = json.optJSONArray("memory") ?: JSONArray()
        return try {
            (0 until rows.length()).map { parse(rows.getJSONObject(it)) }
        } catch (_: JSONException) {
            throw ApiException(200, "Unexpected response")
        }
    }

    /**
     * POST /api/memory/add. Never retried: a lost answer must not make two
     * memories. The server answers 200 with a `message` instead of adding
     * when the text already exists; that comes back as false.
     */
    suspend fun add(text: String, category: String): Boolean {
        val body = JSONObject().put("text", text).put("category", category).toString().toRequestBody(JSON)
        val json = checkBody(http.call(Request.Builder().url(url("add").build()).post(body).build(), retry = false))
        return !json.has("message")
    }

    /** PUT /api/memory/{id}: form fields, as the web sends them. */
    suspend fun update(id: String, text: String, category: String) {
        val body = FormBody.Builder().add("text", text).add("category", category).build()
        checkBody(http.call(Request.Builder().url(url(id).build()).put(body).build()))
    }

    /** POST /api/memory/{id}/pin with `pinned` as a form field. */
    suspend fun pin(id: String, pinned: Boolean) {
        val body = FormBody.Builder().add("pinned", pinned.toString()).build()
        checkBody(http.call(Request.Builder().url(url(id, "pin").build()).post(body).build()))
    }

    /** DELETE /api/memory/{id}. */
    suspend fun delete(id: String) {
        checkBody(http.call(Request.Builder().url(url(id).build()).delete().build()))
    }

    /** GET /api/prefs/{key}. As in the web UI, anything but an explicit false is on. */
    suspend fun pref(key: String): Boolean {
        val json = checkBody(http.call(Request.Builder().url(prefUrl(key)).build()))
        return json.opt("value") != false
    }

    /** PUT /api/prefs/{key} with `{"value": on}`. */
    suspend fun setPref(key: String, on: Boolean) {
        val body = JSONObject().put("value", on).toString().toRequestBody(JSON)
        checkBody(http.call(Request.Builder().url(prefUrl(key)).put(body).build()))
    }

    private fun prefUrl(key: String): HttpUrl =
        http.url("/api/prefs").newBuilder().addPathSegment(key).build()

    /** Segments are encoded one by one, so an id can never add a path. */
    private fun url(vararg segments: String): HttpUrl.Builder =
        http.url("/api/memory").newBuilder().apply { segments.forEach { addPathSegment(it) } }

    companion object {
        private val JSON = "application/json".toMediaType()

        internal fun checkBody(body: String): JSONObject {
            val json = runCatching { JSONObject(body) }.getOrElse { throw ApiException(200, "Unexpected response") }
            val error = (json.opt("error") as? String)?.takeIf { it.isNotBlank() }
            if (error != null) throw ApiException(200, error)
            if (json.opt("success") == false || json.opt("ok") == false) throw ApiException(200, "Request failed")
            return json
        }

        internal fun parse(o: JSONObject) = Memory(
            id = o.getString("id"),
            text = if (o.isNull("text")) "" else o.optString("text"),
            category = (if (o.isNull("category")) "" else o.optString("category")).ifBlank { "fact" },
            pinned = o.optBoolean("pinned"),
            timestamp = o.optLong("timestamp"),
            uses = o.optInt("uses"),
        )
    }
}

/**
 * What the list shows, as memory.js getFilteredMemories: a case-insensitive
 * substring match on the text (the web search is local too, not the server's
 * semantic one), the chosen [category] when set, newest first, and pinned
 * ones floated to the top (the sort is stable, so newest stays within each).
 */
fun filterMemories(all: List<Memory>, query: String, category: String?): List<Memory> {
    val q = query.trim().lowercase()
    return all
        .filter { q.isEmpty() || it.text.lowercase().contains(q) }
        .filter { category == null || it.category == category }
        .sortedByDescending { it.timestamp }
        .sortedByDescending { it.pinned }
}

/** The categories present, in the picker's order then any others, with counts for the chips. */
fun categoryCounts(all: List<Memory>): List<Pair<String, Int>> {
    val counts = all.groupingBy { it.category }.eachCount()
    val known = MEMORY_CATEGORIES.filter { it in counts }
    return (known + counts.keys.filter { it !in MEMORY_CATEGORIES }.sorted()).map { it to counts.getValue(it) }
}
