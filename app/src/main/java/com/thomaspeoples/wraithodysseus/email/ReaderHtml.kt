package com.thomaspeoples.wraithodysseus.email

import java.net.URLDecoder

// Everything the HTML reader decides outside the WebView, so it can be tested
// without one. The WebView itself (ui/email/HtmlBody.kt) has JavaScript off and
// every load routed through decideRequest; the cleaning below is defence in
// depth and the dark styling, not the security boundary.

/** What to do with one sub-resource request from the mail's page. */
sealed interface RequestDecision {
    /** Answer with an empty response: nothing leaves the phone. */
    data object Block : RequestDecision

    /** Let the WebView load it itself (an image the user allowed, or a data: URI). */
    data object Allow : RequestDecision

    /** An inline MIME image: fetched with the app's authenticated client. */
    data class Cid(val cid: String) : RequestDecision
}

/**
 * Blocked unless it is a cid: image (always served) or, once the user has
 * tapped "Load images", an http/https request that asks for an image. The
 * Accept header is how a stylesheet, font or script is told apart from an
 * image: only an image request lists image/ types. Anything else (file:,
 * content:, ftp:, about:...) is blocked outright.
 */
fun decideRequest(url: String, accept: String?, allowImages: Boolean): RequestDecision {
    val scheme = url.substringBefore(':', "").lowercase()
    return when (scheme) {
        "cid" -> cidOf(url)?.let { RequestDecision.Cid(it) } ?: RequestDecision.Block
        "data" -> RequestDecision.Allow
        "http", "https" ->
            if (allowImages && accept != null && accept.contains("image/", ignoreCase = true) && !accept.contains("text/css", ignoreCase = true)) {
                RequestDecision.Allow
            } else {
                RequestDecision.Block
            }
        else -> RequestDecision.Block
    }
}

/** The Content-ID in a `cid:` URL, percent-decoded and without angle brackets; null when empty. */
fun cidOf(url: String): String? {
    if (!url.startsWith("cid:", ignoreCase = true)) return null
    val raw = url.substring(4)
    // URLDecoder would turn a literal "+" into a space; a Content-ID may hold one.
    val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
    return decoded.trim().removePrefix("<").removeSuffix(">").trim().takeIf { it.isNotEmpty() }
}

/** The tags whose whole element goes (the web's sanitiser removes the same set). */
private val BLOCK_TAGS = "script|iframe|object|embed|form|style|svg|math|noscript|frameset|frame|applet|portal|template"
private val BLOCK_ELEMENT = Regex("""<($BLOCK_TAGS)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val LONE_TAG = Regex("""</?(?:$BLOCK_TAGS|link|base|meta)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val STYLE_ATTR = Regex("""\bstyle\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
private val COLOR_ATTR = Regex("""\s(?:color|bgcolor|face|background)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE)

/** One start tag, quotes respected so a `>` inside an attribute value does not end it. */
private val TAG = Regex("""<[a-zA-Z](?:"[^"]*"|'[^']*'|[^>"'])*>""")

/** Declarations the web drops from inline styles so the theme can take over. */
private val STRIPPED_PROPS = setOf("color", "background", "background-color", "font-family", "font", "-webkit-text-fill-color", "position", "z-index")

/**
 * What the web's `_sanitizeHtml` does without a DOM: whole dangerous elements
 * go (including every <style> block), and inline styles lose colour,
 * background, font, position and z-index, plus any declaration with a script
 * or data URL. The CSS in [readerDocument] covers the rest with !important.
 */
fun cleanMailHtml(html: String): String {
    var out = html
    // Nested or split tags can survive one pass, so repeat until stable (the web does 4).
    repeat(4) { out = LONE_TAG.replace(BLOCK_ELEMENT.replace(out, ""), "") }
    // Attributes are only edited inside a tag: a message that says
    // "set color=red" or "style = x" must keep its visible text.
    return TAG.replace(out) { tag -> cleanAttributes(tag.value) }
}

private fun cleanAttributes(tag: String): String {
    val out = COLOR_ATTR.replace(tag, "")
    return STYLE_ATTR.replace(out) { m ->
        val quote = m.groupValues[1][0]
        val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
        val kept = value.split(';').map { it.trim() }.filter { decl ->
            val lower = decl.lowercase().replace(Regex("""\s+"""), "")
            decl.contains(':') &&
                !lower.contains("javascript:") && !lower.contains("vbscript:") && !lower.contains("data:") && !lower.contains("expression(") &&
                decl.substringBefore(':').trim().lowercase() !in STRIPPED_PROPS
        }
        if (kept.isEmpty()) "" else "style=$quote${kept.joinToString("; ").replace(quote.toString(), "")}$quote"
    }
}

/** Mail that refers to an http(s) image, which stays blocked until "Load images". */
fun hasRemoteImages(html: String): Boolean =
    Regex("""<(?:img|source)\b[^>]*\b(?:src|srcset)\s*=\s*["']?\s*(?:https?:|//)""", RegexOption.IGNORE_CASE).containsMatchIn(html) ||
        Regex("""url\(\s*["']?\s*(?:https?:|//)""", RegexOption.IGNORE_CASE).containsMatchIn(html)

/**
 * The dark stylesheet. Like the web, the sender's colours, backgrounds, fonts
 * and positioning are thrown away so every message reads in the app's own
 * foreground on its own background; !important plus the `body *` selector
 * beat the mail's rules and any presentational attribute left in the markup.
 * [fg] and [link] are CSS colours such as "#E6E6E6".
 */
fun darkCss(fg: String, link: String, rule: String): String = """
html,body{margin:0;padding:0;background:transparent!important;color:$fg!important;
font:15px/1.5 sans-serif!important;overflow-wrap:anywhere;word-break:break-word;}
body *{color:inherit!important;background:transparent!important;background-image:none!important;
font-family:inherit!important;position:static!important;z-index:auto!important;
-webkit-text-fill-color:currentColor!important;box-shadow:none!important;max-width:100%!important;box-sizing:border-box;}
body a,body a *{color:$link!important;text-decoration:underline;}
img{height:auto!important;max-width:100%!important;}
table{width:auto!important;table-layout:auto!important;border-collapse:collapse;}
blockquote{margin:8px 0 8px 4px!important;padding-left:12px;border-left:3px solid $rule;opacity:.8;}
pre,code{white-space:pre-wrap!important;}
mark{background:rgba(255,213,79,.35)!important;color:inherit!important;}
hr{border:0;border-top:1px solid $rule;}
""".trimIndent()

/** The whole page handed to the WebView for [bodyHtml]. */
fun readerDocument(bodyHtml: String, fg: String, link: String, rule: String): String =
    "<!doctype html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<style>${darkCss(fg, link, rule)}</style></head><body>${cleanMailHtml(bodyHtml)}</body></html>"

/**
 * A rough plain text for mail that has HTML only (the web's own fallback for
 * reply and forward). Comments, <head>, <style> and <script> go with their
 * contents first: the tag strip alone would leave a style block's CSS in the
 * text, which is what ended up at the top of quoted replies.
 */
fun htmlToText(html: String): String = html
    .replace(Regex("""<!--[\s\S]*?-->"""), "")
    .replace(Regex("""<head\b[\s\S]*?</head\s*>""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""<style\b[\s\S]*?</style\s*>""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""<script\b[\s\S]*?</script\s*>""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("""</p>""", RegexOption.IGNORE_CASE), "\n\n")
    .replace(Regex("""</(?:div|li|tr|h[1-6])\s*>""", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("""<[^>]+>"""), "")
    .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
    .replace(Regex("""[ \t]+\n"""), "\n")
    .replace(Regex("""\n{3,}"""), "\n\n")
    .trim()
