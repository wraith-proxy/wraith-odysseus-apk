package com.thomaspeoples.wraithodysseus.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    private fun prose(md: String) = markdownBlocks(md).map {
        when (it) {
            is MdBlock.Prose -> it.text.text
            is MdBlock.Mono -> "<mono${if (it.table) " table" else ""}>${it.text}"
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

    @Test
    fun pipeTablesAreMonospace() {
        val blocks = prose("| a | b |\n|---|---|\n| 1 | 2 |")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].startsWith("<mono table>| a | b |\n"))
    }
}
