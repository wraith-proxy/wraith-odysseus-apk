package com.thomaspeoples.wraithodysseus.ui.common

import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    private fun prose(md: String) = markdownBlocks(md).map {
        when (it) {
            is MdBlock.Prose -> it.text.text
            is MdBlock.Mono -> "<mono${if (it.table) " table" else ""}>${it.text}"
            is MdBlock.Table -> "<table>"
        }
    }

    @Test
    fun inlineMarkupKeepsOnlyTheText() {
        assertEquals(listOf("How about Pebble, Ash or Smokey?"), prose("How about **Pebble**, *Ash* or `Smokey`?"))
    }

    @Test
    fun codeBlocksAreSeparate() {
        assertEquals(listOf("Run:", "<mono>ls -la\npwd", "Done."), prose("Run:\n\n```sh\nls -la\npwd\n```\n\nDone."))
    }

    @Test
    fun listsGetMarkers() {
        assertEquals(listOf("• one\n• two"), prose("- one\n- two"))
        assertEquals(listOf("3. a\n4. b"), prose("3. a\n4. b"))
    }

    private fun table(md: String) = (markdownBlocks(md).single() as MdBlock.Table).table

    @Test
    fun pipeTablesBecomeRealTables() {
        val t = table("| a | b |\n|---|:-:|\n| 1 | 2 |\n| 3 | 4 |")
        assertEquals(listOf("a", "b"), t.header)
        assertEquals(listOf(MdAlign.Start, MdAlign.Center), t.align)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), t.rows)
    }

    @Test
    fun tablesNeedNoEdgePipesAndSupportAlignment() {
        val t = table("x | y | z\n:-- | --: | ---\nl | r | n")
        assertEquals(listOf(MdAlign.Start, MdAlign.End, MdAlign.Start), t.align)
        assertEquals(listOf("l", "r", "n"), t.rows[0])
    }

    @Test
    fun shortAndLongRowsAreFittedToTheHeader() {
        val t = table("| a | b |\n|---|---|\n| 1 |\n| 1 | 2 | 3 |")
        assertEquals(listOf(listOf("1", ""), listOf("1", "2")), t.rows)
    }

    @Test
    fun anEscapedPipeStaysInTheCell() {
        assertEquals(listOf("a|b", "c"), splitRow("| a\\|b | c |"))
    }

    @Test
    fun pipeLinesWithoutASeparatorStayMonospace() {
        val blocks = prose("| a | b |\n| 1 | 2 |")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].startsWith("<mono table>| a | b |"))
    }

    @Test
    fun aSeparatorOfTheWrongWidthIsNotATable() {
        assertEquals(null, parsePipeTable(listOf("| a | b |", "|---|", "| 1 | 2 |")))
    }

    @Test
    fun mermaidAndMathStayCodeBlocks() {
        assertEquals(listOf("<mono>graph TD; A-->B"), prose("```mermaid\ngraph TD; A-->B\n```"))
        assertEquals(listOf("<mono>E = mc^2"), prose("```math\nE = mc^2\n```"))
    }

    @Test
    fun onlyWebLinksAreTappable() {
        val text = (markdownBlocks("[a](tel:123) [b](javascript:x) [c](intent://x) [d](HTTPS://example.com) [e](http://lan)").single() as MdBlock.Prose).text
        assertEquals("a b c d e", text.text)
        assertEquals(
            listOf("HTTPS://example.com", "http://lan"),
            text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Url).url },
        )
    }
}
