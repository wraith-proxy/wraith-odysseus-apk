package com.thomaspeoples.wraithodysseus.ui.chat

import com.thomaspeoples.wraithodysseus.ui.chat.MessageAction.Copy
import com.thomaspeoples.wraithodysseus.ui.chat.MessageAction.Delete
import com.thomaspeoples.wraithodysseus.ui.chat.MessageAction.Edit
import com.thomaspeoples.wraithodysseus.ui.chat.MessageAction.Fork
import com.thomaspeoples.wraithodysseus.ui.chat.MessageAction.Regenerate
import com.thomaspeoples.wraithodysseus.ui.chat.MessageAction.Resend
import com.thomaspeoples.wraithodysseus.ui.chat.MessageAction.SelectText
import com.thomaspeoples.wraithodysseus.ui.chat.MessageAction.Share
import com.thomaspeoples.wraithodysseus.ui.chat.MessageAction.Shorter
import com.thomaspeoples.wraithodysseus.ui.chat.MessageAction.Simpler
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageActionsTest {

    @Test
    fun holdingAReplyOffersEverything() {
        assertEquals(
            listOf(Copy, SelectText, Share, Fork, Edit, Regenerate, Shorter, Simpler, Delete),
            fullMenu(user = false, lastReply = true, canChange = true),
        )
    }

    @Test
    fun rewriteAndExplainAreForTheLastReplyOnly() {
        assertEquals(
            listOf(Copy, SelectText, Share, Fork, Edit, Regenerate, Delete),
            fullMenu(user = false, lastReply = false, canChange = true),
        )
    }

    @Test
    fun holdingAUserMessage() {
        assertEquals(listOf(Copy, SelectText, Edit, Resend, Delete), fullMenu(user = true, lastReply = false, canChange = true))
    }

    @Test
    fun whileARunGoesOrWithoutAnIdOnlyReadingActionsRemain() {
        assertEquals(listOf(Copy, SelectText, Share), fullMenu(user = false, lastReply = true, canChange = false))
        assertEquals(listOf(Copy, SelectText), fullMenu(user = true, lastReply = false, canChange = false))
        assertEquals(emptyList<MessageAction>(), overflowMenu(user = false, lastReply = true, canChange = false))
        assertEquals(listOf(Copy), overflowMenu(user = true, lastReply = false, canChange = false))
    }

    @Test
    fun theFooterIsTheWebsDefaultsAndTheOverflowIsTheRest() {
        assertEquals(listOf(Copy, Delete), footerButtons(user = false))
        assertEquals(listOf(Fork, Edit, Regenerate, Shorter, Simpler), overflowMenu(user = false, lastReply = true, canChange = true))
        assertEquals(listOf(Fork, Edit, Regenerate), overflowMenu(user = false, lastReply = false, canChange = true))
        assertEquals(listOf(Edit, Delete), footerButtons(user = true))
        assertEquals(listOf(Copy, Resend), overflowMenu(user = true, lastReply = false, canChange = true))
    }
}
