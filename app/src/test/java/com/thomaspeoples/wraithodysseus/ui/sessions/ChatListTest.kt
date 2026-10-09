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
        assertSame(Lucide.MessageSquare, sessionIcon(s("a", "", mode = "research_pending")))
        assertSame(Lucide.MessageSquare, sessionIcon(s("a", "", mode = null)))
    }
}

class ChatListModelTest {
    private fun s(
        id: String,
        last: String = "2026-10-08T09:00:00",
        created: String = last,
        important: Boolean = false,
        name: String = id,
        folder: String? = null,
        mode: String? = null,
        docs: Boolean = false,
        images: Boolean = false,
    ) = ChatSession(id, name, null, null, mode, important, updatedAt = last, lastMessageAt = last, createdAt = created, folder = folder, hasDocuments = docs, hasImages = images)

    private val london = java.time.ZoneId.of("Europe/London")
    private val now = java.time.Instant.parse("2026-10-08T18:00:00Z")

    private fun build(
        sessions: List<ChatSession>,
        sort: SortMode = SortMode.Active,
        collapsed: Set<String> = emptySet(),
        expanded: Set<String> = emptySet(),
    ) = buildChatList(sessions, sort, now, collapsed, expanded, london, java.util.Locale.UK)

    private fun shape(items: List<ListItem>) = items.map {
        when (it) {
            is GroupHeader -> "# " + it.label
            is ChatItem -> it.session.id
            is ShowMore -> "+" + it.hidden
        }
    }

    @Test
    fun iconPriorityIsGroupForkDocsImagesAgentResearchChat() {
        assertSame(Lucide.User, sessionIcon(s("a", name = "[GRP] Team", docs = true)))
        assertSame(Lucide.GitFork, sessionIcon(s("a", name = "Fork: x", docs = true)))
        assertSame(Lucide.FileText, sessionIcon(s("a", docs = true, images = true, mode = "agent")))
        assertSame(Lucide.Image, sessionIcon(s("a", images = true, mode = "agent")))
        assertSame(Lucide.Terminal, sessionIcon(s("a", mode = "agent")))
        assertSame(Lucide.Search, sessionIcon(s("a", mode = "research")))
        assertSame(Lucide.MessageSquare, sessionIcon(s("a")))
        assertSame(Lucide.Bookmark, sessionIcon(s("a", name = "[GRP] Team", important = true)))
    }

    @Test
    fun labelDropsTheMarkersTheIconsStandFor() {
        assertEquals("Plan", rowLabel(s("a", name = "Fork: Plan")))
        assertEquals("Plan", rowLabel(s("a", name = "⫝ Plan")))
        assertEquals("Team", rowLabel(s("a", name = "[GRP] Team")))
    }

    @Test
    fun lastActiveGroupsByDayWithFavouritesFirst() {
        val list = build(
            listOf(
                s("old", "2026-10-01T09:00:00"),
                s("star", "2026-09-01T09:00:00", important = true),
                s("today", "2026-10-08T09:00:00"),
                s("today2", "2026-10-08T10:00:00"),
                s("yesterday", "2026-10-07T09:00:00"),
            ),
        )
        assertEquals(
            listOf("# Favorites", "star", "# Today", "today2", "today", "# Yesterday", "yesterday", "# 1 October", "old"),
            shape(list),
        )
    }

    @Test
    fun newestFirstOrdersAndGroupsByCreation() {
        val list = build(
            listOf(
                s("busy", last = "2026-10-08T09:00:00", created = "2026-09-01T09:00:00"),
                s("fresh", last = "2026-10-07T09:00:00", created = "2026-10-08T08:00:00"),
            ),
            SortMode.Newest,
        )
        assertEquals(listOf("# Today", "fresh", "# 30 days ago", "busy"), shape(list))
    }

    @Test
    fun byFolderGivesEachFolderASectionAndTheRestUnsorted() {
        val list = build(
            listOf(
                s("a", "2026-10-08T09:00:00", folder = "Work"),
                s("b", "2026-10-08T10:00:00", folder = "Work", important = true),
                s("c", "2026-10-08T11:00:00"),
                s("d", "2026-10-08T08:00:00", folder = "Play"),
            ),
            SortMode.Folder,
        )
        assertEquals(listOf("# Work", "b", "a", "# Play", "d", "# Unsorted", "c"), shape(list))
        // No folders at all: a plain list.
        assertEquals(listOf("c"), shape(build(listOf(s("c")), SortMode.Folder)))
    }

    @Test
    fun hiddenChatsAreLeftOut() {
        val list = build(
            listOf(
                s("keep"), s("assist", folder = "Assistant"), s("cmp", name = "[CMP] x"),
                s("incog", name = "Incognito"), s("nobody", name = " Nobody "),
                s("saved", name = "[CMP] y", folder = "Compare:1"),
            ),
        )
        assertEquals(listOf("keep", "saved"), list.filterIsInstance<ChatItem>().map { it.session.id }.sorted())
    }

    @Test
    fun aGroupShowsTenThenShowNMoreAndCanCollapse() {
        val many = (1..13).map { s("c%02d".format(it), "2026-10-08T%02d:00:00".format(it)) }
        val shut = build(many)
        assertEquals(1 + 10 + 1, shut.size)
        assertEquals(ShowMore("date:Today", 3), shut.last())
        assertEquals(14, build(many, expanded = setOf("date:Today")).size)
        assertEquals(listOf("# Today"), shape(build(many, collapsed = setOf("date:Today"))))
        assertEquals(true, (build(many, collapsed = setOf("date:Today")).first() as GroupHeader).collapsed)
    }

    @Test
    fun bulkDeleteSkipsFavouritesAndCountsThem() {
        val all = listOf(s("a"), s("b", important = true), s("c"))
        val plan = bulkDeletePlan(listOf("a", "b", "c"), all)
        assertEquals(listOf("a", "c"), plan.ids)
        assertEquals(1, plan.skippedStarred)
    }

    @Test
    fun bulkRunSendsEachIdAlone() = kotlinx.coroutines.runBlocking {
        val sent = mutableListOf<String>()
        val r = bulkRun(listOf("a", "b", "c")) {
            sent += it
            if (it == "b") throw com.thomaspeoples.wraithodysseus.net.ApiException(403, "Unstar the session before deleting it")
        }
        assertEquals(listOf("a", "b", "c"), sent)
        assertEquals(listOf("a", "c"), r.done)
        assertEquals(1, r.failed)
        assertEquals("2 chats deleted, 1 favorite skipped, 1 failed: Unstar the session before deleting it", bulkSummary("deleted", r, 1))
    }

    @Test
    fun sortModeFallsBackToLastActive() {
        assertEquals(SortMode.Folder, SortMode.of("group"))
        assertEquals(SortMode.Active, SortMode.of("manual"))
        assertEquals(SortMode.Active, SortMode.of(null))
    }
}
