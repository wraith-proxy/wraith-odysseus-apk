package com.thomaspeoples.wraithodysseus.ui.chat.parts

import com.thomaspeoples.wraithodysseus.chat.MemoryUsed
import com.thomaspeoples.wraithodysseus.chat.MessageMetrics
import java.util.Locale

/*
 * The footer's numbers as the web prints them (chatRenderer.js
 * displayMetrics, :2619), kept as plain functions so the rules are tested
 * without a screen.
 */

/** How full the context window is: the ring's colour. */
enum class RingLevel { Ok, Warn, High }

/** chatRenderer.js:2745: red from 85%, orange from 70%, green below. */
fun ringLevel(percent: Double): RingLevel = when {
    percent >= 85 -> RingLevel.High
    percent >= 70 -> RingLevel.Warn
    else -> RingLevel.Ok
}

/** The web's `$` + 4 decimals under a cent, else 3. */
fun formatCost(usd: Double): String = "$" + String.format(Locale.ROOT, if (usd < 0.01) "%.4f" else "%.3f", usd)

/**
 * The one figure beside the actions, in the web's fallback order: tok/s,
 * else cost, else the response time. Null when none of them is known, in
 * which case the web draws neither the figure nor the ring.
 */
fun footerLabel(m: MessageMetrics): String? = when {
    m.tokensPerSecond != null -> String.format(Locale.ROOT, "%.2f tok/s", m.tokensPerSecond)
    m.costUsd != null -> formatCost(m.costUsd)
    m.responseTime != null -> String.format(Locale.ROOT, "%.3fs", m.responseTime)
    else -> null
}

/** The ring shows only beside a figure, and only when some context is used. */
fun ringPercent(m: MessageMetrics): Double? = m.contextPercent?.takeIf { it > 0 && footerLabel(m) != null }

private fun n(v: Int) = String.format(Locale.ROOT, "%,d", v)
private fun secs(v: Double, digits: Int = 3) = String.format(Locale.ROOT, "%.${digits}f", v) + "s"

/** A group of label/value rows in the stats popup. */
typealias StatsSection = List<Pair<String, String>>

/**
 * Every row of the web's "Message stats" popup (chatRenderer.js:2679-2705),
 * in its sections. The "Session" total cost row is left out: it needs a
 * running ledger of every reply, which the app does not keep.
 */
fun statsSections(m: MessageMetrics): List<StatsSection> {
    val tilde = if (m.realUsage) "" else "~"
    val tps = m.tokensPerSecond?.let { String.format(Locale.ROOT, "%.2f tok/s", it) } ?: "n/a"
    val sections = listOf(
        listOf(
            "Model" to (m.model ?: "Unknown").substringAfterLast('/'),
            "Input" to "${n(m.inputTokens)} tokens$tilde",
            "Output" to "${n(m.outputTokens)} tokens$tilde",
            "Total" to "${n(m.inputTokens + m.outputTokens)} tokens",
        ),
        listOfNotNull(
            (if (m.tpsSource == "computed") "Speed (wall)" else "Speed") to tps,
            m.responseTime?.let { "Time" to secs(it) },
            m.prepTime?.let { "Prep" to secs(it) },
            m.modelWaitTime?.let { "Model wait" to secs(it) },
            m.timeToFirstToken?.let { "TTFT" to secs(it) },
        ),
        listOfNotNull(
            m.toolSchemaCount?.let { "Tool schemas" to n(it) },
            m.agentRounds?.let { "Agent rounds" to n(it) },
            m.toolCalls?.let { "Tool calls" to n(it) },
            m.costUsd?.let { "Cost" to formatCost(it) },
        ),
        m.prepBreakdown.map { (k, v) -> k.replace('_', ' ') to secs(v) },
    )
    val context = m.contextPercent?.takeIf { it > 0 }?.let { listOf("Context" to "${Math.round(it)}% used") }.orEmpty()
    return (sections + listOf(context)).filter { it.isNotEmpty() }
}

/** The "Context Window" popup behind the ring (chatRenderer.js:2779-2800). */
fun contextRows(m: MessageMetrics): StatsSection {
    fun count(v: Int) = if (v > 0) n(v) else "?"
    val pct = m.contextPercent ?: 0.0
    return listOf(
        "Used" to count(m.inputTokens),
        "Total" to count(m.contextLength ?: 0),
        "Model" to (m.model ?: "Unknown").substringAfterLast('/'),
        "Usage" to "${Math.round(pct)}%",
        "Window" to count(m.contextLength ?: 0) + " tokens",
    )
}

/** The memory chip's text: "2 pinned, 1 recalled" (chatRenderer.js:2429). Empty when none of either. */
fun memoryChip(memories: List<MemoryUsed>): String {
    val pinned = memories.count { it.type == "pinned" }
    val recalled = memories.count { it.type == "recalled" }
    return listOfNotNull(pinned.takeIf { it > 0 }?.let { "$it pinned" }, recalled.takeIf { it > 0 }?.let { "$it recalled" }).joinToString(", ")
}

// -- link, output and diff rules -------------------------------------------

/** chatRenderer.js _safeHref: only a web page is ever opened from a source row. */
fun isWebUrl(url: String): Boolean = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

/** Tool output past [limit] characters is cut and offered whole behind "Show all". Returns the shown text and how many characters were cut. */
fun truncateOutput(text: String, limit: Int = OUTPUT_LIMIT): Pair<String, Int> =
    if (text.length <= limit) text to 0 else text.take(limit).trimEnd() to (text.length - limit)

const val OUTPUT_LIMIT = 1200

enum class DiffKind { Meta, Hunk, Add, Del, Context }

data class DiffLine(val kind: DiffKind, val text: String)

/** chatRenderer.js:3374: colour carries add/del, so the leading marker is dropped. */
fun diffLines(text: String): List<DiffLine> = text.split('\n').map { line ->
    when {
        line.startsWith("+++") || line.startsWith("---") -> DiffLine(DiffKind.Meta, line)
        line.startsWith("@@") -> DiffLine(DiffKind.Hunk, line)
        line.startsWith("+") -> DiffLine(DiffKind.Add, line.drop(1))
        line.startsWith("-") -> DiffLine(DiffKind.Del, line.drop(1))
        line.startsWith(" ") -> DiffLine(DiffKind.Context, line.drop(1))
        else -> DiffLine(DiffKind.Context, line)
    }
}
