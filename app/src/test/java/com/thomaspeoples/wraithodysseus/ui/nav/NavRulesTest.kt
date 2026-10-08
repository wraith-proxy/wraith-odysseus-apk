package com.thomaspeoples.wraithodysseus.ui.nav

import com.thomaspeoples.wraithodysseus.net.SessionCookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavRulesTest {

    private val now = 1_000_000L

    @Test
    fun `no cookie starts at login`() {
        assertEquals(Routes.LOGIN, startRoute(null, now))
    }

    @Test
    fun `expired cookie starts at login, the boundary included`() {
        assertEquals(Routes.LOGIN, startRoute(SessionCookie("v", now - 1, true), now))
        assertEquals(Routes.LOGIN, startRoute(SessionCookie("v", now, true), now))
    }

    @Test
    fun `live cookie starts at chats`() {
        assertEquals(Routes.CHATS, startRoute(SessionCookie("v", now + 1, true), now))
    }

    @Test
    fun `identity needs both server and username`() {
        assertEquals("alice@https://ody.example.test/", identity("https://ody.example.test/", "alice"))
        assertNull(identity(null, "alice"))
        assertNull(identity("https://ody.example.test/", null))
    }

    @Test
    fun `same account returns to the last route`() {
        val who = identity("https://ody.example.test/", "alice")
        assertTrue(returnAfterLogin(who, who))
    }

    @Test
    fun `another user or server starts clean`() {
        val alice = identity("https://ody.example.test/", "alice")
        assertFalse(returnAfterLogin(alice, identity("https://ody.example.test/", "bob")))
        assertFalse(returnAfterLogin(alice, identity("https://other.example.test/", "alice")))
    }

    @Test
    fun `a login with no expiry behind it does not return`() {
        assertFalse(returnAfterLogin(null, identity("https://ody.example.test/", "alice")))
        assertFalse(returnAfterLogin(null, null))
    }
}
