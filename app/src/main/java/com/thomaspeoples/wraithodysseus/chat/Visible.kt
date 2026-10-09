package com.thomaspeoples.wraithodysseus.chat

/**
 * What the web UI shows of a stored message, ported as pure functions so the
 * rules are testable without a screen. The server saves more into a user row
 * than the person typed (file contents, vision-model descriptions, resume
 * prompts), and the web UI hides it; without the same rules the app would
 * show, copy and re-send that machinery.
 *
 * Every regex is the web's, with JS `$` written `\z`: Java's `$` also matches
 * before a final newline, JS's does not.
 *
 * The patterns that open with `\n*` (or `\s*`) also carry a guard, [NL_START],
 * so a match cannot start inside a run of newlines except where the last
 * match ended (`\G`). A match starting mid-run means one a character earlier
 * also matches, so the leftmost match, and the result, are the same. Without
 * the guard, every position of a long run rescans the rest of it: 30,000
 * newlines in an attached file froze the chat for seconds on each history
 * load. The guards inside the lookaheads do the same for the lazy bodies.
 * VisibleTest checks both the timing and the equivalence, hence `internal`.
 */

private const val NL_START = """(?:\G|(?<!\n)|(?!\n))"""
private const val NL_AHEAD = """(?:(?<!\n)|(?!\n))"""

/** static/js/sessions.js:168 `_stripUserVisionBlocks` (same pattern as
 * chatRenderer.js:3569): "[Image: name]" plus the description under it. */
internal val VISION_BLOCK = Regex(
    // (?<=\]\n): the body may end at once, before the header's own newline run.
    """$NL_START\n*\[Image: ([^\]]+)\]\n[\s\S]*?(?=(?:$NL_AHEAD|(?<=\]\n))\n*(?:\[Image: |\[Image attached: |=== File: |\[PDF content\]:)|\z)""",
)

/** chatRenderer.js:3577-3583, applied only when the row has attachments. */
internal val FILE_BLOCK_FENCED = Regex("""$NL_START\n*=== File: .+? ===\n\[Type: .+?\]\n+```[\s\S]*?```""")
internal val FILE_BLOCK_PLAIN = Regex("""$NL_START\n*=== File: .+? ===\n\[Type: .+?\]\n+[\s\S]*?(?=$NL_AHEAD\n*=== File:|\z)""")
internal val PDF_BLOCK = Regex("""$NL_START\n*\[PDF content[^\]]*\]:[\s\S]*?(?=$NL_AHEAD\n*(?:\[PDF content[^\]]*\]:|=== File:)|\z)""")
internal val IMAGE_MARKER = Regex("""$NL_START\n*\[Image attached: [^\]]+\]""")
internal val ATTACHED_MARKER = Regex("""$NL_START\n*\[Attached (?:document|non-text) file\]""")

/** chatRenderer.js:3480-3487: a supervisor self-check is an internal signal. */
private val SELF_CHECK = Regex("""^\s*\[Task\]\s+Self-check:""", RegexOption.IGNORE_CASE)

/**
 * A user row as the web shows it: the injected vision descriptions always
 * go; the file, PDF and image-marker text goes when the row carries
 * attachments (a typed "=== File:" in a plain message is the person's own).
 */
fun visibleUserText(raw: String, hasAttachments: Boolean): String {
    var text = VISION_BLOCK.replace(raw, "")
    if (hasAttachments) {
        text = text
            .replace(FILE_BLOCK_FENCED, "")
            .replace(FILE_BLOCK_PLAIN, "")
            .replace(PDF_BLOCK, "")
            .replace(IMAGE_MARKER, "")
            .replace(ATTACHED_MARKER, "")
    }
    return text.trim()
}

/**
 * Whether the web hides this whole row. [visibleText] is the user text after
 * [visibleUserText] (sessions.js:223-225); the metadata flags and the
 * "[Task] Self-check:" prefix hide a row of either role. The server's own
 * `hidden` flag never reaches the app: /api/history drops those rows.
 */
fun isHiddenTurn(role: String, visibleText: String, wakeCheckIn: Boolean, hiddenFromUserView: Boolean): Boolean {
    if (wakeCheckIn || hiddenFromUserView || SELF_CHECK.containsMatchIn(visibleText)) return true
    if (role != "user") return false
    val trimmed = visibleText.trim()
    return trimmed == "Continue where you left off" ||
        trimmed.startsWith("Your message was cut off.") ||
        trimmed.startsWith("Your previous response was interrupted.") ||
        visibleText.contains("[Instruction: Rewrite") ||
        visibleText.contains("[Instruction: Explain")
}

// -- tool blocks (chatRenderer.js:810-891, stripToolBlocks :1555) ---------

private val TOOL_BLOCK_PATTERNS = listOf(
    """\[TOOL_CALL\][\s\S]*?\[/TOOL_CALL\]""",
    """<\s*[｜|]+\s*DSML\s*[｜|]+\s*tool_calls\s*>[\s\S]*?(?:<\s*/\s*[｜|]+\s*DSML\s*[｜|]+\s*tool_calls\s*>|\z)""",
    """<\s*[｜|]+\s*DSML\s*[｜|]+\s*invoke\b[^>]*>[\s\S]*?(?:<\s*/\s*[｜|]+\s*DSML\s*[｜|]+\s*invoke\s*>|\z)""",
    """<\s*/?\s*[｜|]+\s*DSML\s*[｜|]+[^>]*>""",
    """<(?:\w+:)?(?:tool_call|function_call)>[\s\S]*?</(?:\w+:)?(?:tool_call|function_call)>""",
    """<invoke\s+name=['"][^'"]*['"]>[\s\S]*?</invoke>""",
    """(?:\[\s*)?\{\s*"function"\s*:\s*\{[\s\S]*?\}\s*,\s*"id"\s*:\s*"[^"]*"\s*,\s*"type"\s*:\s*"function"\s*\}\s*\]?""",
    """</?\|(?:assistant|assistan|user|system|tool)\|>?|</\|end\|>?""",
).map { Regex(it, RegexOption.IGNORE_CASE) }
private val QWEN_BARE_MARKER = Regex(
    """(?:^|[\t\r\n ])(?:/?\|end\||\|end|end\|)(?=[\t\r\n ]|\z)|(?:^|[\r\n])[ \t]*assistan(?:t)?[ \t]*(?=[\r\n]|\z)""",
    setOf(RegexOption.IGNORE_CASE),
)
internal val TOOL_NARRATION = Regex(
    // Guarded like NL_START, for whitespace: a match starting on whitespace
    // after whitespace can only be the bare `\s*stdout:` form, which also
    // matches a character earlier.
    """(?:\G|(?<!\s)|(?!\s))(?:The (?:result|output) shows?:?\s*)?-?\s*(?:stdout|stderr|exit_code):\s*.+""",
    RegexOption.IGNORE_CASE,
)
private val BLANK_RUNS = Regex("""\n{3,}""")

/**
 * The web's stripToolBlocks, minus its fenced "```tool_name" pattern: the web
 * builds that one from the server's GET /api/tools list, which the app does
 * not fetch, so such a fence stays in the copied text.
 */
fun stripToolBlocks(text: String): String {
    var cleaned = text
    for (p in TOOL_BLOCK_PATTERNS) cleaned = p.replace(cleaned, "")
    cleaned = QWEN_BARE_MARKER.replace(cleaned, " ")
    cleaned = TOOL_NARRATION.replace(cleaned, "")
    return BLANK_RUNS.replace(cleaned, "\n\n").trim()
}

/**
 * An assistant reply as the web copies it (chatRenderer.js:1583
 * copyMessageText): tool blocks gone, reasoning gone, and the raw text when
 * that leaves nothing (a turn cut off mid-thinking).
 */
fun visibleAssistantText(raw: String): String {
    val answer = splitThinking(stripToolBlocks(raw)).answer
    return answer.ifBlank { raw }
}

private val THINK_BLOCK = Regex("""<(?:think(?:ing)?|thought)(?:\s+[^>]*)?>[\s\S]*?</(?:think(?:ing)?|thought)>""", RegexOption.IGNORE_CASE)
private val THINK_CLOSE = Regex("""</(?:think(?:ing)?|thought)>""", RegexOption.IGNORE_CASE)
private val THINK_ORPHAN = Regex("""</?(?:think(?:ing)?|thought)(?:\s+[^>]*)?>""", RegexOption.IGNORE_CASE)

/**
 * The rewrite stream's text with any reasoning markup peeled off, as
 * chat.js:7898-7905 does: complete blocks, then a bare closing tag whose
 * opener never came (everything before it is reasoning), then stray tags.
 */
fun stripRewriteThinking(text: String): String {
    var t = THINK_BLOCK.replace(text, "")
    val close = THINK_CLOSE.find(t)
    if (close != null) t = t.substring(close.range.last + 1)
    return THINK_ORPHAN.replace(t, "").trim()
}
