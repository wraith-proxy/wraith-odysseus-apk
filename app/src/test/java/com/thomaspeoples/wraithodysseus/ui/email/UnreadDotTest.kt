package com.thomaspeoples.wraithodysseus.ui.email

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreadDotTest {
    @Test fun dotOnlyForUnreadAndNotDone() {
        assertTrue(showUnreadDot(isRead = false, isAnswered = false, sent = false))
        assertFalse(showUnreadDot(isRead = false, isAnswered = true, sent = false))
        assertFalse(showUnreadDot(isRead = true, isAnswered = false, sent = false))
        assertFalse(showUnreadDot(isRead = false, isAnswered = false, sent = true))
    }
}
