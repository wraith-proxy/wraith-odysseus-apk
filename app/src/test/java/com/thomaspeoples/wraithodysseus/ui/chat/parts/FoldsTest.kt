package com.thomaspeoples.wraithodysseus.ui.chat.parts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldsTest {

    @Test
    fun aBoxOpenedInTheLiveBubbleStaysOpenOnTheSavedReply() {
        val map = mutableMapOf<String, Boolean>()
        Folds(map, "older").set("3 sources", true)
        Folds(map, LIVE_FOLDS).set("2 sources", true)
        Folds(map, LIVE_FOLDS).set("run_python", false)

        handOverFolds(map, "msg-9")

        assertTrue(Folds(map, "msg-9").isOpen("2 sources"))
        assertFalse(Folds(map, "msg-9").isOpen("run_python"))
        assertFalse(Folds(map, LIVE_FOLDS).isOpen("2 sources"))
        assertTrue(Folds(map, "older").isOpen("3 sources"))
    }

    @Test
    fun withNoSavedReplyTheLiveFoldsAreDropped() {
        val map = mutableMapOf<String, Boolean>()
        Folds(map, LIVE_FOLDS).set("2 sources", true)
        handOverFolds(map, null)
        assertEquals(emptyMap<String, Boolean>(), map)
    }
}
