package com.thomaspeoples.wraithodysseus.ui.tools.skills

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One user skill of GET /api/skills. [status] is "draft" (the web's default
 * for a skill with none), "published" (offered to the model and to the "/"
 * autocomplete) or "binned" (set aside by an audit). [confidence] is 0..1.
 */
data class Skill(
    val name: String,
    val description: String,
    val category: String,
    val tags: List<String>,
    val status: String,
    val uses: Int,
    val confidence: Double,
    val version: String,
) {
    val published: Boolean get() = status == "published"
}

/**
 * The skills routes. A skill is addressed by name, as the web does. Failures
 * are HTTP errors, which [Http] turns into [ApiException]; [checkBody] guards
 * the odd 200 that carries one anyway.
 */
class SkillsApi(private val http: Http) {

    /**
     * GET /api/skills without the built-in entries: the web lists those in
     * its own section, read through other routes, and they are the server's
     * own instructions rather than something this user published.
     */
    suspend fun list(): List<Skill> {
        val json = checkBody(http.call(Request.Builder().url(url().build()).build()))
        val rows = json.optJSONArray("skills") ?: JSONArray()
        return try {
            (0 until rows.length()).map { rows.getJSONObject(it) }
                .filter { it.optString("source") != "builtin" }
                .mapNotNull(::parse)
        } catch (_: JSONException) {
            throw ApiException(200, "Unexpected response")
        }
    }

    /** GET /api/skills/{name}/markdown: the raw SKILL.md. */
    suspend fun markdown(name: String): String {
        val json = checkBody(http.call(Request.Builder().url(url(name, "markdown").build()).build()))
        return json.optString("markdown")
    }

    /** PUT /api/skills/{name} with just the status, which is all the web sends to (un)publish. */
    suspend fun setStatus(name: String, status: String) {
        val body = JSONObject().put("status", status).toString().toRequestBody(JSON)
        checkBody(http.call(Request.Builder().url(url(name).build()).put(body).build()))
    }

    /** DELETE /api/skills/{name}. The web ignores a failure here; this does not. */
    suspend fun delete(name: String) {
        checkBody(http.call(Request.Builder().url(url(name).build()).delete().build()))
    }

    /** Segments are encoded one by one, so a name can never add a path. */
    private fun url(vararg segments: String): HttpUrl.Builder =
        http.url("/api/skills").newBuilder().apply { segments.forEach { addPathSegment(it) } }

    companion object {
        private val JSON = "application/json".toMediaType()

        internal fun checkBody(body: String): JSONObject {
            val json = runCatching { JSONObject(body) }.getOrElse { throw ApiException(200, "Unexpected response") }
            val error = (json.opt("error") as? String)?.takeIf { it.isNotBlank() }
            if (error != null) throw ApiException(200, error)
            if (json.opt("success") == false || json.opt("ok") == false) throw ApiException(200, "Request failed")
            return json
        }

        private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

        /** A legacy record has no `name` but an `id`, which the routes accept too; one with neither is dropped. */
        internal fun parse(o: JSONObject): Skill? {
            val name = o.str("name").ifBlank { o.str("id") }
            if (name.isBlank()) return null
            val tags = o.optJSONArray("tags")
            return Skill(
                name = name,
                description = o.str("description"),
                category = o.str("category"),
                tags = if (tags == null) emptyList() else (0 until tags.length()).map { tags.optString(it).trim() }.filter { it.isNotEmpty() },
                status = o.str("status").ifBlank { "draft" },
                uses = o.optInt("uses"),
                confidence = o.optDouble("confidence", 0.0).let { if (it.isNaN()) 0.0 else it },
                version = o.str("version"),
            )
        }
    }
}

/** The status filter of the list. */
enum class SkillFilter(val label: String) { All("All"), Published("Published"), Draft("Draft"), Binned("Bin") }

/**
 * The list, as skills.js _getFilteredSkills: a case-insensitive match of the
 * query on name, description, category and tags; "All" hides the binned
 * ones as the web's default view does, and "Bin" shows only those;
 * published first, then most used, then by name.
 */
fun filterSkills(all: List<Skill>, query: String, filter: SkillFilter): List<Skill> {
    val q = query.trim().lowercase()
    fun matches(s: Skill) = q.isEmpty() || listOf(s.name, s.description, s.category).any { it.lowercase().contains(q) } ||
        s.tags.any { it.lowercase().contains(q) }
    return all.filter(::matches).filter {
        when (filter) {
            SkillFilter.All -> it.status != "binned"
            SkillFilter.Published -> it.status == "published"
            SkillFilter.Draft -> it.status == "draft"
            SkillFilter.Binned -> it.status == "binned"
        }
    }.sortedWith(compareByDescending<Skill> { it.published }.thenByDescending { it.uses }.thenBy { it.name.lowercase() })
}

private const val BOM = '\uFEFF'

/** SKILL.md starts with a `---` front matter block the card already shows as fields; this drops it for reading. */
fun stripFrontMatter(markdown: String): String {
    val text = markdown.trimStart(BOM)
    if (!text.startsWith("---")) return text
    val lines = text.lines()
    if (lines[0].trim() != "---") return text
    val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return text
    return lines.drop(end + 1).joinToString("\n").trimStart('\n')
}
