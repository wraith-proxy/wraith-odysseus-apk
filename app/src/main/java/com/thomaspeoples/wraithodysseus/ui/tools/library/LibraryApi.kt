package com.thomaspeoples.wraithodysseus.ui.tools.library

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** A row of GET /api/documents/library. */
data class LibraryItem(
    val id: String,
    val title: String,
    /** The display language: "markdown", "text", "email", "pdf", a code language... */
    val language: String,
    /** The first 500 characters of the content. */
    val preview: String,
    val versionCount: Int,
    val sessionName: String?,
    val updatedAt: String?,
)

/** One page of the library; [total] counts every match, not just this page. */
data class LibraryPage(val items: List<LibraryItem>, val total: Int)

/** GET /api/document/{id}: the whole document. */
data class LibraryDoc(
    val id: String,
    val title: String,
    /** Null for a document that never had one, which the web edits as plain text. */
    val language: String?,
    val content: String,
    val versionCount: Int,
    val archived: Boolean,
    val updatedAt: String?,
) {
    /** A PDF is stored as a markdown wrapper holding its source marker; editing it as text would break the link to the file. */
    val isPdf: Boolean get() = content.contains("<!-- pdf_source upload_id=\"") || content.contains("<!-- pdf_form_source upload_id=\"")

    val isMarkdown: Boolean get() = language == "markdown" && !isPdf
}

/**
 * The document routes (routes/document/document_routes.py). Web parity notes:
 * the server has no version or conflict field on a save, so the web (document.js)
 * simply PUTs {content} and the last save wins; this does the same. Saving
 * unchanged content is a no-op on the server, and saves within a short window
 * of the last user edit are coalesced into one version. A delete is a soft
 * delete (is_active=false). Failures are HTTP errors with `detail`, which
 * [Http] turns into an [ApiException]; bodies are still checked for `error`.
 */
class LibraryApi(private val http: Http) {

    /** The web asks for 50 at a time, the server's maximum. */
    suspend fun list(search: String, archived: Boolean, offset: Int = 0, limit: Int = PAGE): LibraryPage {
        val body = get(listUrl(http.url("/api/documents/library"), search, archived, offset, limit))
        return parsePage(body)
    }

    suspend fun get(id: String): LibraryDoc {
        val text = get(http.url("/api/document/$id"))
        return parseDoc(text) ?: throw ApiException(200, "Unexpected response")
    }

    /** Saves [content] as the web does: PUT {content}. Returns the document the server now holds. */
    suspend fun save(id: String, content: String): LibraryDoc {
        val request = Request.Builder().url(http.url("/api/document/$id")).put(json(saveBody(content))).build()
        val text = http.call(request, retry = false)
        checkBody(text)
        return parseDoc(text) ?: throw ApiException(200, "Unexpected response")
    }

    /** Renames with the metadata-only PATCH, which leaves the content and versions alone. */
    suspend fun rename(id: String, title: String): LibraryDoc {
        val body = JSONObject().put("title", title).toString()
        val request = Request.Builder().url(http.url("/api/document/$id")).patch(json(body)).build()
        val text = http.call(request, retry = false)
        checkBody(text)
        return parseDoc(text) ?: throw ApiException(200, "Unexpected response")
    }

    /** A session-less library document. Never retried: a repeat would make a second one. */
    suspend fun create(title: String, language: String = "markdown", content: String = ""): LibraryDoc {
        val request = Request.Builder().url(http.url("/api/document")).post(json(createBody(title, language, content))).build()
        val text = http.call(request, retry = false)
        checkBody(text)
        return parseDoc(text) ?: throw ApiException(200, "Unexpected response")
    }

    suspend fun archive(id: String, archived: Boolean) {
        val url = http.url("/api/document/$id/archive").newBuilder().addQueryParameter("archived", archived.toString()).build()
        checkBody(http.call(Request.Builder().url(url).post("".toRequestBody()).build(), retry = false))
    }

    suspend fun delete(id: String) {
        checkBody(http.call(Request.Builder().url(http.url("/api/document/$id")).delete().build()))
    }

    private suspend fun get(url: HttpUrl): String {
        val text = http.call(Request.Builder().url(url).get().build())
        checkBody(text)
        return text
    }

    private fun json(body: String) = body.toRequestBody("application/json".toMediaType())

    companion object {
        const val PAGE = 50

        fun listUrl(base: HttpUrl, search: String, archived: Boolean, offset: Int, limit: Int): HttpUrl =
            base.newBuilder()
                .addQueryParameter("sort", "recent")
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("limit", limit.toString())
                .apply {
                    if (search.isNotBlank()) addQueryParameter("search", search.trim())
                    if (archived) addQueryParameter("archived", "true")
                }
                .build()

        fun saveBody(content: String): String = JSONObject().put("content", content).toString()

        fun createBody(title: String, language: String, content: String): String =
            JSONObject().put("title", title.ifBlank { "Untitled" }).put("language", language).put("content", content).toString()

        /** A 200 that is really a failure. */
        fun checkBody(body: String) {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: throw ApiException(200, "Unexpected response")
            (json.opt("error") as? String)?.takeIf { it.isNotBlank() }?.let { throw ApiException(200, it) }
            if (json.opt("success") == false || json.opt("ok") == false) {
                throw ApiException(200, (json.opt("message") as? String)?.takeIf { it.isNotBlank() } ?: "Request failed")
            }
        }

        fun parsePage(body: String): LibraryPage {
            val json = JSONObject(body)
            val arr = json.optJSONArray("documents")
            val items = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val o = arr?.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifEmpty { return@mapNotNull null }
                LibraryItem(
                    id = id,
                    title = o.str("title") ?: "Untitled",
                    language = o.str("language") ?: "text",
                    preview = o.optString("preview"),
                    versionCount = o.optInt("version_count", 1),
                    sessionName = o.str("session_name"),
                    updatedAt = o.str("updated_at"),
                )
            }
            return LibraryPage(items, json.optInt("total", items.size))
        }

        fun parseDoc(body: String): LibraryDoc? {
            val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val id = o.optString("id").ifEmpty { return null }
            return LibraryDoc(
                id = id,
                title = o.str("title") ?: "Untitled",
                language = o.str("language"),
                content = if (o.isNull("current_content")) "" else o.optString("current_content"),
                versionCount = o.optInt("version_count", 1),
                archived = o.optBoolean("archived"),
                updatedAt = o.str("updated_at"),
            )
        }

        private fun JSONObject.str(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}
