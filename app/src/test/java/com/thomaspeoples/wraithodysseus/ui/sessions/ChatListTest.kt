package com.thomaspeoples.wraithodysseus.ui.sessions

import com.thomaspeoples.wraithodysseus.chat.ChatSession
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChatListTest {
    private fun s(id: String, last: String, important: Boolean = false, name: String = id, mode: String? = null, model: String? = null) =
        ChatSession(id, name, model, null, mode, important, updatedAt = last, lastMessageAt = last)

    @Test
    fun favouritesFirstThenMostRecentlyActive() {
        val sessions = listOf(
            s("old", "2026-10-01T09:00:00"),
            s("star", "2026-09-01T09:00:00", important = true),
            s("new", "2026-10-08T09:00:00.500000"),
            s("broken", "not a date"),
        )
        assertEquals(listOf("star", "new", "old", "broken"), drawerOrder(sessions).map { it.id })
        // Home opens the latest chat, starred or not.
        assertEquals("new", mostRecent(sessions)?.id)
        assertNull(mostRecent(emptyList()))
    }

    @Test
    fun rowsLookLikeTheWebSidebar() {
        assertEquals("Starting a New Chapter · arthur:latest", rowLabel(s("a", "", name = "Starting a New Chapter", model = "arthur:latest")))
        assertEquals("Untitled · gpt-4o", rowLabel(s("a", "", name = "", model = "openai/gpt-4o")))
        assertEquals("Notes", rowLabel(s("a", "", name = "Notes")))
        assertSame(Lucide.Bookmark, sessionIcon(s("a", "", important = true, mode = "agent")))
        assertSame(Lucide.GitFork, sessionIcon(s("a", "", name = "⫝ Plan")))
        assertSame(Lucide.Terminal, sessionIcon(s("a", "", mode = "agent")))
        assertSame(Lucide.Search, sessionIcon(s("a", "", mode = "research_pending")))
        assertSame(Lucide.MessageSquare, sessionIcon(s("a", "", mode = null)))
    }
}
