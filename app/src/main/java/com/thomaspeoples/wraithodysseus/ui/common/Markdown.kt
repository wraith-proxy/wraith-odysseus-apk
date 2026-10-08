package com.thomaspeoples.wraithodysseus.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** A reply split into runs of styled text and code blocks, which get their
 * own boxed, horizontally scrolling rendering. */
sealed interface MdBlock {
    data class Prose(val text: AnnotatedString) : MdBlock
    /** [table]: a pipe table, which core commonmark leaves as a paragraph;
     * shown monospace so the columns line up. */
    data class Mono(val text: String, val table: Boolean = false) : MdBlock
}

private val parser: Parser = Parser.builder().build()
private val InlineCode = SpanStyle(background = OdyColors.CodeBg, color = OdyColors.CodeFg, fontSize = 12.sp)
private val LinkStyles = TextLinkStyles(SpanStyle(color = Primary, textDecoration = TextDecoration.Underline))

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
                blocks += MdBlock.Mono(plain(node), table = true)
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
        return lines.size >= 2 && lines.all { it.trimStart().startsWith("|") }
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
    val blocks = remember(text) { markdownBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Prose -> Text(block.text, style = MaterialTheme.typography.bodyMedium, color = color)
                is MdBlock.Mono -> Text(
                    block.text,
                    style = CodeStyle,
                    color = OdyColors.CodeFg,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OdyColors.CodeBg, MaterialTheme.shapes.small)
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                )
            }
        }
    }
}
