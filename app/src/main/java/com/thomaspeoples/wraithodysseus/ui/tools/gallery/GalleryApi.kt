package com.thomaspeoples.wraithodysseus.ui.tools.gallery

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject

/** An item of GET /api/gallery/library (routes/gallery/gallery_helpers.py _image_to_dict). */
data class GalleryImage(
    val id: String,
    val filename: String,
    /** A server path such as /api/generated-image/<file>; needs the session cookie. */
    val url: String,
    /** The prompt for a generated image, the original file name for an upload. */
    val prompt: String,
    val favorite: Boolean,
    val albumId: String?,
    val width: Int?,
    val height: Int?,
    val fileSize: Long?,
    val createdAt: String?,
    val takenAt: String?,
) {
    /** The server also stores videos; the app has nothing to play them with. */
    val isVideo: Boolean get() = filename.substringAfterLast('.', "").lowercase() in VIDEO_EXTS
}

/** One page of the library. [total] counts every match, not just this page. */
data class GalleryPage(val items: List<GalleryImage>, val total: Int)

/** An item of GET /api/gallery/albums. */
data class Album(val id: String, val name: String, val count: Int)

/** What POST /api/gallery/upload did with a file. */
sealed interface UploadResult {
    data object Imported : UploadResult
    /** The server already holds this exact file for this user and skipped it. */
    data object Duplicate : UploadResult
}

/** A picked file: opened afresh for each attempt so nothing is held in memory. */
class UploadSource(val name: String, val mime: String?, val open: () -> InputStream?)

/**
 * The gallery routes. Web parity notes: the library is paged by offset and
 * limit (the server caps limit at 100) and returns its total; there is no
 * thumbnail route, so a thumbnail is the full file fetched with the app's
 * own cookie and downsampled while decoding; favourite is set with an
 * explicit PATCH rather than the web grid's toggle so a retry cannot undo it.
 */
class GalleryApi(private val http: Http) {

    suspend fun library(offset: Int, limit: Int = PAGE_SIZE, album: String? = null, favoritesOnly: Boolean = false): GalleryPage {
        val url = http.url("/api/gallery/library").newBuilder()
            .addQueryParameter("sort", "recent")
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
        if (album != null) url.addQueryParameter("album", album)
        if (favoritesOnly) url.addQueryParameter("favorites", "true")
        return parsePage(get(url.build().toString()))
    }

    suspend fun albums(): List<Album> {
        val arr = JSONObject(get("/api/gallery/albums")).optJSONArray("albums") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifEmpty { return@mapNotNull null }
            Album(id, o.optString("name"), o.optInt("count"))
        }
    }

    /** Sets (not toggles) the favourite flag; returns the value the server now holds. */
    suspend fun setFavorite(id: String, favorite: Boolean): Boolean {
        val body = JSONObject().put("favorite", favorite).toString().toRequestBody(JSON)
        val request = Request.Builder().url(http.url("/api/gallery/$id")).patch(body).build()
        val text = http.call(request)
        checkBody(text)
        return JSONObject(text).optBoolean("favorite", favorite)
    }

    /** The server soft-deletes and removes the file; a missing id is a 404. */
    suspend fun delete(id: String) {
        checkBody(http.call(Request.Builder().url(http.url("/api/gallery/$id")).delete().build()))
    }

    /**
     * Sends one file with the web's multipart fields: `file`, and `album_id`
     * when an album is chosen. Never retried: a repeat would at best be a
     * "duplicate" answer and at worst a second copy of a renamed file.
     */
    suspend fun upload(source: UploadSource, albumId: String?): UploadResult {
        val file = object : RequestBody() {
            override fun contentType(): MediaType? = (source.mime ?: "application/octet-stream").toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val input = source.open() ?: throw java.io.IOException("Could not read ${source.name}")
                input.use { sink.writeAll(it.source()) }
            }
        }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", uploadName(source.name, source.mime), file)
        if (albumId != null) form.addFormDataPart("album_id", albumId)
        val request = Request.Builder().url(http.url("/api/gallery/upload")).post(form.build()).build()
        return parseUpload(http.call(request, readTimeoutSeconds = UPLOAD_TIMEOUT_S, retry = false))
    }

    /**
     * The bytes behind [GalleryImage.url], fetched with the session cookie (and only for the server's own origin).
     * Originals have no thumbnail route, so anything over [IMAGE_MAX_BYTES] comes back empty (undecodable, shown
     * as a placeholder) rather than filling memory four at a time.
     */
    suspend fun imageBytes(url: String): ByteArray {
        val target = if (url.startsWith("/")) http.url(url) else url.toHttpUrl()
        val response = http.open(Request.Builder().url(target).get().build(), Http.SEND_TIMEOUT_S)
        return withContext(Dispatchers.IO) { response.use { r ->
            val body = r.body ?: return@use ByteArray(0)
            if (body.contentLength() > IMAGE_MAX_BYTES) return@use ByteArray(0)
            // Chunked answers have no length: read one byte past the cap to notice.
            val source = body.source()
            if (source.request(IMAGE_MAX_BYTES + 1)) ByteArray(0) else source.readByteArray()
        } }
    }

    private suspend fun get(path: String): String {
        val text = http.call(Request.Builder().url(path.takeIf { it.startsWith("http") }?.toHttpUrl() ?: http.url(path)).get().build())
        checkBody(text)
        return text
    }

    companion object {
        /** Under the server's cap of 100; a screenful and a bit more. */
        const val PAGE_SIZE = 60
        const val UPLOAD_TIMEOUT_S = 180L
        const val IMAGE_MAX_BYTES = 25L * 1024 * 1024
        private val JSON = "application/json".toMediaType()
        val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif")

        /** A 200 that is really a failure: `error`, or `success`/`ok` false. */
        fun checkBody(body: String) {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: throw ApiException(200, "Unexpected response")
            (json.opt("error") as? String)?.takeIf { it.isNotBlank() }?.let { throw ApiException(200, it) }
            if (json.opt("success") == false || json.opt("ok") == false) {
                throw ApiException(200, (json.opt("message") as? String)?.takeIf { it.isNotBlank() } ?: "Request failed")
            }
        }

        fun parsePage(body: String): GalleryPage {
            val json = JSONObject(body)
            val arr = json.optJSONArray("items")
            val items = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { parseImage(arr.optJSONObject(it)) }
            return GalleryPage(items, json.optInt("total", items.size))
        }

        fun parseImage(o: JSONObject?): GalleryImage? {
            o ?: return null
            val id = o.optString("id").ifEmpty { return null }
            val filename = o.optString("filename").ifEmpty { return null }
            return GalleryImage(
                id = id,
                filename = filename,
                url = o.str("url") ?: "/api/generated-image/$filename",
                prompt = o.optString("prompt"),
                favorite = o.optBoolean("favorite"),
                albumId = o.str("album_id"),
                width = if (o.isNull("width")) null else o.optInt("width").takeIf { it > 0 },
                height = if (o.isNull("height")) null else o.optInt("height").takeIf { it > 0 },
                fileSize = if (o.isNull("file_size")) null else o.optLong("file_size"),
                createdAt = o.str("created_at"),
                takenAt = o.str("taken_at"),
            )
        }

        /**
         * A duplicate comes back as `ok:false, duplicate:true`, which is not
         * an error; any other `ok:false` or an `error` is, with the server's text.
         */
        fun parseUpload(body: String): UploadResult {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: throw ApiException(200, "Unexpected response")
            if (json.optBoolean("duplicate")) return UploadResult.Duplicate
            checkBody(body)
            return UploadResult.Imported
        }

        /** The server picks image vs video and rejects unknown types by the file name's extension, so a name without one gets it from the MIME type. */
        fun uploadName(name: String, mime: String?): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in IMAGE_EXTS || ext in VIDEO_EXTS) return name
            val add = when (mime) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> return name
            }
            return "${name.trimEnd('.')}.$add"
        }

        private fun JSONObject.str(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}

private val VIDEO_EXTS = setOf("mp4", "mov", "webm", "mkv", "m4v")
