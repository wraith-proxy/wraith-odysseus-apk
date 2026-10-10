package com.thomaspeoples.wraithodysseus.email

import com.thomaspeoples.wraithodysseus.net.ApiException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One item of an attachment list (GET /attachments/{uid}, or the `attachments`
 * of a read). [index] is the position the server counts when asked for the
 * file. A related (earlier in the thread) attachment names the message it
 * actually lives in with [sourceUid] / [sourceFolder].
 */
data class EmailAttachment(
    val index: Int,
    val filename: String,
    val contentType: String,
    val size: Long,
    val isInline: Boolean,
    val contentId: String,
    val sourceUid: String? = null,
    val sourceFolder: String? = null,
)

internal fun parseAttachments(list: JSONArray?): List<EmailAttachment> {
    if (list == null) return emptyList()
    return try {
        (0 until list.length()).map {
            val o = list.getJSONObject(it)
            EmailAttachment(
                index = o.getInt("index"),
                filename = o.optString("filename"),
                contentType = o.optString("content_type"),
                size = o.optLong("size"),
                isInline = o.optBoolean("is_inline"),
                contentId = o.optString("content_id"),
                // The web reads source_uid || uid, so a plain uid counts too.
                sourceUid = (if (o.isNull("source_uid")) o.optString("uid") else o.optString("source_uid")).takeIf { s -> s.isNotBlank() },
                sourceFolder = o.optString("source_folder").takeIf { s -> s.isNotBlank() && s != "null" },
            )
        }
    } catch (_: JSONException) {
        throw ApiException(200, "Unexpected response")
    }
}

private val IMAGE_NAME = Regex("""\.(png|jpe?g|gif|bmp|svg|webp)$""", RegexOption.IGNORE_CASE)
private val OUTLOOK_PLACEHOLDER = Regex("""^image\d{3,}\.(png|jpe?g|gif)$""", RegexOption.IGNORE_CASE)
private val SIGNATURE_NAME = Regex("""^(signature|logo|sig|footer|banner)[-_\d]*\.(png|jpe?g|gif|svg)$""", RegexOption.IGNORE_CASE)
private val CALENDAR_NAME = Regex("""\.(calendar|ics|ical)$""", RegexOption.IGNORE_CASE)
private const val SIGNATURE_MAX_BYTES = 30 * 1024

/**
 * The web reader's `_isLikelySignatureImage` (emailLibrary/attachments.js),
 * rule for rule: an image by extension that is an Outlook "image001.png"
 * placeholder, is named like a signature or logo, or is under 30 KB (a size
 * of 0 means unknown and does not count). Such files are not shown as
 * attachments; the web keeps them in a muted "filtered" group, as does the app.
 */
fun isLikelySignatureImage(a: EmailAttachment): Boolean {
    if (a.filename.isEmpty()) return false
    val name = a.filename.lowercase()
    if (!IMAGE_NAME.containsMatchIn(name)) return false
    if (OUTLOOK_PLACEHOLDER.containsMatchIn(name)) return true
    if (SIGNATURE_NAME.containsMatchIn(name)) return true
    return a.size in 1 until SIGNATURE_MAX_BYTES
}

/** The calendar invites the web offers "Add to calendar" for. */
fun isCalendarFile(name: String): Boolean = CALENDAR_NAME.containsMatchIn(name)

private val JSON_TYPE = Regex("""^\s*application/(?:[\w.+-]+\+)?json\b""", RegexOption.IGNORE_CASE)

/** True for a JSON Content-Type, which on the attachment route means an error. */
fun isJsonType(contentType: String?): Boolean = contentType != null && JSON_TYPE.containsMatchIn(contentType)

/** The text of a JSON error body from the attachment route, or a generic one. */
fun attachmentError(body: String): String =
    runCatching { JSONObject(body).opt("error") as? String }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Could not download the attachment"

/** A name that is safe as a single path segment in the app's cache. */
fun safeFileName(name: String, fallback: String = "attachment"): String {
    val cleaned = name.replace(Regex("""[\\/\u0000-\u001f:*?"<>|]"""), "_").trim().trim('.')
    return cleaned.ifEmpty { fallback }.take(120)
}

/** The MIME type to open [name] with when the platform has none for it. */
fun fallbackMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "eml" -> "message/rfc822"
    "ics", "ical", "calendar" -> "text/calendar"
    "md", "markdown" -> "text/markdown"
    else -> "application/octet-stream"
}

/** "12 KB" or "1.4 MB". */
fun attachmentSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(java.util.Locale.ENGLISH, bytes / 1048576.0)
}

/** Strips the markers the model sometimes leaves around a translation (the web does the same). */
internal fun cleanTranslation(text: String): String =
    text.replace(Regex("""^\s*<<<TRANSLATION>>>\s*""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*<<<END>>>\s*$""", RegexOption.IGNORE_CASE), "")
        .trim()
