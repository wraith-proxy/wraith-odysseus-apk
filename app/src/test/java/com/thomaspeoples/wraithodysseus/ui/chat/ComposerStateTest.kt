package com.thomaspeoples.wraithodysseus.ui.chat

import com.thomaspeoples.wraithodysseus.chat.ChatContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The small decisions the composer, header and Chat Context sheet make. */
class ComposerStateTest {

    @Test
    fun theBigButtonFollowsTheInputAndTheRun() {
        assertEquals(BigButton.New, bigButton(hasInput = false, running = false))
        assertEquals(BigButton.Send, bigButton(hasInput = true, running = false))
        assertEquals(BigButton.Stop, bigButton(hasInput = false, running = true))
        // Typing during a reply offers to queue the message (↑), not Stop.
        assertEquals(BigButton.Send, bigButton(hasInput = true, running = true))
    }

    @Test
    fun theRingIsGreenBelow70OrangeFrom70RedFrom85() {
        val green = contextColor(0.0)
        assertEquals(green, contextColor(69.9))
        val orange = contextColor(70.0)
        assertEquals(orange, contextColor(84.9))
        val red = contextColor(85.0)
        assertEquals(red, contextColor(100.0))
        assertNotEquals(green, orange)
        assertNotEquals(orange, red)
        assertNotEquals(green, red)
    }

    @Test
    fun theHeaderCountsMessages() {
        assertEquals("· 12 msgs", headerSubtitle(12))
        assertEquals("· 0 msgs", headerSubtitle(0))
        assertNull(headerSubtitle(null))
    }

    @Test
    fun theSheetFormatsUsedTokensAndMaxTokens() {
        val c = ChatContext(91000, 128000, 71.1, 14, "m", true, true, true, true, "off", false, null, null)
        assertEquals("91,000 / 128,000", usedText(c))
        assertEquals("No limit", maxTokensText(null))
        assertEquals("No limit", maxTokensText(8448))
        assertEquals("4,096", maxTokensText(4096))
        assertEquals("8,192", maxTokensText(8192))
    }

    @Test
    fun theTopOfTheMaxTokensSliderClearsTheOverride() {
        assertNull(maxTokensToSave(8448f))
        assertEquals(8192, maxTokensToSave(8192f))
        assertEquals(256, maxTokensToSave(256f))
    }
}
