package com.thomaspeoples.wraithodysseus.ui.tools.notes

import okio.BufferedSource
import java.util.Base64

/** Biggest drawing, in bytes, the viewer will hold: a canvas PNG is far smaller, a photo painted over is not. */
const val DRAWING_MAX_BYTES = 12L * 1024 * 1024

private val DATA_URL = Regex("^data:image/(?:png|jpe?g|gif|webp);base64,", RegexOption.IGNORE_CASE)
private val WEB_URL = Regex("^https?://", RegexOption.IGNORE_CASE)

/**
 * Where a drawing note's picture lives. The web saves a drawing as a PNG file and keeps only its
 * address in image_url (notes.js _uploadCanvasAsPng, "payload.image_url = upload.url"), and shows it
 * through _safeImgSrc, which accepts a server path, an http(s) URL or an image data URL. The same
 * three are accepted here; anything else is [Unusable] and shows a placeholder.
 */
sealed interface DrawingSource {
    /** The picture itself, decoded from a data URL. */
    class Inline(val bytes: ByteArray) : DrawingSource

    /** A server path or http(s) URL, to be fetched through [NotesApi.drawingBytes]. */
    data class Remote(val url: String) : DrawingSource

    /** Missing, malformed or too big. */
    data object Unusable : DrawingSource
}

/** Sorts [imageUrl] into a [DrawingSource]. Pure: no network, and never throws. */
fun drawingSource(imageUrl: String?, maxBytes: Long = DRAWING_MAX_BYTES): DrawingSource {
    val v = imageUrl?.trim().orEmpty()
    if (v.isEmpty() || v.any { it.isISOControl() || it.isWhitespace() }) return DrawingSource.Unusable
    DATA_URL.find(v)?.let { head ->
        val payload = v.substring(head.range.last + 1)
        // Base64 is 4 characters per 3 bytes: refuse before decoding anything big.
        if (payload.isEmpty() || payload.length.toLong() / 4 * 3 - 2 > maxBytes) return DrawingSource.Unusable
        val bytes = try {
            Base64.getDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            return DrawingSource.Unusable
        }
        return if (bytes.isEmpty() || bytes.size > maxBytes) DrawingSource.Unusable else DrawingSource.Inline(bytes)
    }
    // The web also takes "./x" and "../x" (notes.js _safeImgSrc), relative to its page at the
    // server root, where ".." cannot climb higher: both are "/x".
    var path = v
    if (path.startsWith("./") || path.startsWith("../")) {
        while (path.startsWith("./") || path.startsWith("../")) path = path.substringAfter('/')
        path = "/$path"
    }
    // "//host" and "/\host" are other hosts to a URL parser, not paths on the server.
    if (path.startsWith("/")) return if (path.length > 1 && path[1] != '/' && path[1] != '\\') DrawingSource.Remote(path) else DrawingSource.Unusable
    if (WEB_URL.containsMatchIn(v)) return DrawingSource.Remote(v)
    return DrawingSource.Unusable
}

/**
 * The body of an answer, or null when it is over [max]. [contentLength] is -1 for a chunked answer, which
 * has no length to check: read one byte past the cap to notice.
 */
fun readCapped(contentLength: Long, source: BufferedSource, max: Long = DRAWING_MAX_BYTES): ByteArray? {
    if (contentLength > max) return null
    return if (source.request(max + 1)) null else source.readByteArray()
}
