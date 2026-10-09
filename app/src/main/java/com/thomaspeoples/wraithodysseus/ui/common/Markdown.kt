package com.thomaspeoples.wraithodysseus.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import kotlinx.coroutines.launch
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thomaspeoples.wraithodysseus.ui.theme.AppPalette
import com.thomaspeoples.wraithodysseus.ui.theme.CodeStyle
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.theme.Primary
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/** A reply split into runs of styled text, code blocks (boxed, scrolling
 * sideways, with a copy button) and tables (scrolling sideways). */
sealed interface MdBlock {
    data class Prose(val text: AnnotatedString) : MdBlock
    /** [table]: pipe lines that are not a valid table (no separator row);
     * shown monospace so the columns still line up. */
    data class Mono(val text: String, val table: Boolean = false) : MdBlock
    data class Table(val table: MdTable) : MdBlock
}

enum class MdAlign { Start, Center, End }

/** A pipe table: [header] cells, one [align] per column, and [rows] of the same width. Cells are plain text. */
data class MdTable(val header: List<String>, val align: List<MdAlign>, val rows: List<List<String>>)

private val SEPARATOR_CELL = Regex(""":?-+:?""")

/** Cells of one pipe line: edge pipes dropped, `\|` kept as a pipe in the cell. */
internal fun splitRow(line: String): List<String> {
    var t = line.trim()
    if (t.startsWith("|")) t = t.substring(1)
    if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < t.length) {
        val c = t[i]
        if (c == '\\' && i + 1 < t.length && t[i + 1] == '|') {
            cur.append('|')
            i += 2
            continue
        }
        if (c == '|') {
            cells += cur.toString().trim()
            cur.setLength(0)
        } else {
            cur.append(c)
        }
        i++
    }
    cells += cur.toString().trim()
    return cells
}

/**
 * A GitHub-style table from the lines of one paragraph: a header, then a
 * separator row of dashes (colons set the alignment), then rows. Null when
 * the second line is not such a separator or its width differs from the header.
 */
fun parsePipeTable(lines: List<String>): MdTable? {
    if (lines.size < 2) return null
    val header = splitRow(lines[0])
    val sep = splitRow(lines[1])
    if (header.isEmpty() || sep.size != header.size || !sep.all { SEPARATOR_CELL.matches(it) }) return null
    val align = sep.map { c ->
        when {
            c.startsWith(":") && c.endsWith(":") -> MdAlign.Center
            c.endsWith(":") -> MdAlign.End
            else -> MdAlign.Start
        }
    }
    val rows = lines.drop(2).map { l -> splitRow(l).let { r -> List(header.size) { r.getOrElse(it) { "" } } } }
    return MdTable(header, align, rows)
}

private val parser: Parser = Parser.builder().build()
private val InlineCode get() = SpanStyle(background = OdyColors.CodeBg, color = OdyColors.CodeFg, fontSize = 12.sp)
private val LinkStyles get() = TextLinkStyles(SpanStyle(color = Primary, textDecoration = TextDecoration.Underline))

fun markdownBlocks(markdown: String): List<MdBlock> = MdRenderer().apply { block(parser.parse(markdown), 0, false) }.finish()

private class MdRenderer {
    private val blocks = mutableListOf<MdBlock>()
    private var b = AnnotatedString.Builder()

    fun finish(): List<MdBlock> {
        flush()
        return blocks
    }

    private fun flush() {
        if (b.length > 0) blocks += MdBlock.Prose(b.toAnnotatedString())
        b = AnnotatedString.Builder()
    }

    /** Start a new block inside the current prose run. */
    private fun gap(tight: Boolean, indent: Int) {
        if (b.length > 0) b.append(if (tight) "\n" else "\n\n")
        if (indent > 0) b.append("    ".repeat(indent))
    }

    fun block(node: Node, indent: Int, tight: Boolean) {
        when (node) {
            is Paragraph -> if (isTable(node)) {
                flush()
                val text = plain(node)
                blocks += parsePipeTable(text.lines())?.let { MdBlock.Table(it) } ?: MdBlock.Mono(text, table = true)
            } else {
                gap(tight, indent)
                inlines(node)
            }
            is Heading -> {
                gap(false, indent)
                val size = when (node.level) { 1 -> 18.sp; 2 -> 16.sp; else -> 14.sp }
                b.withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = size)) { inlines(node) }
            }
            is FencedCodeBlock -> code(node.literal)
            is IndentedCodeBlock -> code(node.literal)
            is HtmlBlock -> {
                gap(tight, indent)
                b.append(node.literal.trimEnd())
            }
            is ListBlock -> list(node, indent, tight)
            is BlockQuote -> b.withStyle(SpanStyle(color = Muted, fontStyle = FontStyle.Italic)) {
                children(node).forEach { block(it, indent, tight) }
            }
            is ThematicBreak -> {
                gap(false, indent)
                b.withStyle(SpanStyle(color = Muted)) { append("────────") }
            }
            else -> children(node).forEach { block(it, indent, tight) }
        }
    }

    private fun code(literal: String) {
        flush()
        blocks += MdBlock.Mono(literal.trimEnd('\n'))
    }

    /** [tight]: whether the list itself sits tight in an enclosing list item. */
    private fun list(list: ListBlock, indent: Int, tight: Boolean) {
        var n = (list as? OrderedList)?.markerStartNumber ?: 1
        children(list).filterIsInstance<ListItem>().forEachIndexed { i, item ->
            gap(if (i == 0) tight else list.isTight, indent)
            b.append(if (list is BulletList) "• " else "${n++}. ")
            children(item).forEachIndexed { j, child ->
                if (j == 0 && child is Paragraph) inlines(child) else block(child, indent + 1, true)
            }
        }
    }

    private fun inlines(parent: Node) {
        children(parent).forEach { node ->
            when (node) {
                is MdText -> b.append(node.literal)
                is Code -> b.withStyle(InlineCode) { append(node.literal) }
                is Emphasis -> b.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inlines(node) }
                is StrongEmphasis -> b.withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inlines(node) }
                is Link -> b.withLink(LinkAnnotation.Url(node.destination, LinkStyles)) { inlines(node) }
                is Image -> b.append(plain(node).ifEmpty { "image" })
                is SoftLineBreak -> b.append(" ")
                is HardLineBreak -> b.append("\n")
                is HtmlInline -> b.append(node.literal)
                else -> inlines(node)
            }
        }
    }

    private fun isTable(p: Paragraph): Boolean {
        val lines = plain(p).lines()
        // GitHub tables may omit the edge pipes, so a valid separator row also counts.
        return lines.size >= 2 && (lines.all { it.trimStart().startsWith("|") } || parsePipeTable(lines) != null)
    }

    /** The paragraph's text with line breaks kept, ignoring inline markup. */
    private fun plain(node: Node): String = buildString {
        fun walk(n: Node) {
            when (n) {
                is MdText -> append(n.literal)
                is Code -> append(n.literal)
                is SoftLineBreak, is HardLineBreak -> append('\n')
                else -> children(n).forEach(::walk)
            }
        }
        children(node).forEach(::walk)
    }

    private fun children(node: Node): List<Node> =
        generateSequence(node.firstChild) { it.next }.toList()
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    // Keyed on the palette too: inline code and link colours are baked into the spans.
    val blocks = remember(text, AppPalette.current) { markdownBlocks(text) }
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val copy = { code: String ->
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("code", code))
        scope.launch { snackbar.showSnackbar("Copied") }
        Unit
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Prose -> Text(block.text, style = MaterialTheme.typography.bodyMedium, color = color)
                is MdBlock.Mono -> CodeBlock(block.text, onCopy = { copy(block.text) })
                is MdBlock.Table -> TableBlock(block.table, color)
            }
        }
    }
}

/** A code block: boxed, scrolling sideways, with a copy button in its corner. A hold still reaches the bubble's actions. */
@Composable
private fun CodeBlock(code: String, onCopy: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(OdyColors.CodeBg, MaterialTheme.shapes.small)) {
        Text(
            code,
            style = CodeStyle,
            color = OdyColors.CodeFg,
            softWrap = false,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 36.dp),
        )
        IconButton(onClick = onCopy, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
            Icon(Lucide.Copy, contentDescription = "Copy code", tint = Muted, modifier = Modifier.size(16.dp))
        }
    }
}

/** A table whose columns are as wide as their widest cell, scrolling sideways when wider than the bubble. */
@Composable
private fun TableBlock(table: MdTable, color: Color) {
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, OdyColors.BubbleBorder, shape)
            .horizontalScroll(rememberScrollState()),
    ) {
        table.header.indices.forEach { c ->
            val textAlign = when (table.align[c]) {
                MdAlign.Start -> TextAlign.Start
                MdAlign.Center -> TextAlign.Center
                MdAlign.End -> TextAlign.End
            }
            Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 48.dp)) {
                Text(
                    table.header[c],
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    textAlign = textAlign,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth().background(OdyColors.UserBubble).padding(horizontal = 10.dp, vertical = 6.dp),
                )
                table.rows.forEach { row ->
                    Text(
                        row[c],
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                        textAlign = textAlign,
                        softWrap = false,
                        modifier = Modifier.fillMaxWidth().border(width = 0.5.dp, color = OdyColors.BubbleBorder.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
