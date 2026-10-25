package com.thomaspeoples.wraithodysseus.demo

import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.chat.ChatRequest
import com.thomaspeoples.wraithodysseus.chat.Toggles
import com.thomaspeoples.wraithodysseus.email.EmailApi
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import com.thomaspeoples.wraithodysseus.ui.tools.calendar.CalendarApi
import com.thomaspeoples.wraithodysseus.ui.tools.gallery.GalleryApi
import com.thomaspeoples.wraithodysseus.ui.tools.library.LibraryApi
import com.thomaspeoples.wraithodysseus.ui.tools.memory.MemoryApi
import com.thomaspeoples.wraithodysseus.ui.tools.notes.NotesApi
import com.thomaspeoples.wraithodysseus.ui.tools.research.ResearchApi
import com.thomaspeoples.wraithodysseus.ui.tools.skills.SkillsApi
import com.thomaspeoples.wraithodysseus.ui.tools.tasks.TasksApi
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test

private class FakeSession(
    override var baseUrl: HttpUrl?,
    override var cookie: SessionCookie?,
) : SessionState

class DemoTest {
    private val http = Http(FakeSession(Demo.origin, Demo.cookie))

    private val fixtures: Map<String, String> =
        demoChat + demoMail + demoPlanner + demoKnowledge + demoMisc

    @Test
    fun fixturesAreJson() {
        for ((k, v) in fixtures) {
            val t = v.trimStart()
            if (t.startsWith("data:") || t.startsWith("event:") || t.startsWith("<")) continue
            val parsed = JSONTokener(v).nextValue()
            assertTrue(
                "fixture $k is not a JSON object or array",
                parsed is JSONObject || parsed is JSONArray,
            )
        }
    }

    @Test
    fun keysAreMethodAndPath() {
        val re = Regex("^(GET|POST|PUT|DELETE) /\\S*$")
        for (k in fixtures.keys) {
            assertTrue("key '$k' must be METHOD /path", re.matches(k))
            assertFalse("key '$k' must not contain '?'", k.contains('?'))
        }
    }

    @Test
    fun unmapped() {
        assertEquals(
            "GET /api/demo-unmapped",
            "{}" to "application/json",
            Demo.respond("GET", "/api/demo-unmapped"),
        )
        assertTrue(
            "POST /api/demo-unmapped",
            Demo.respond("POST", "/api/demo-unmapped").first.contains("\"success\":true"),
        )
    }

    @Test
    fun chatParses() = runBlocking {
        val sessions = ChatApi(http).sessions()
        assertTrue("ChatApi.sessions empty", sessions.isNotEmpty())
        for (s in sessions) {
            assertTrue(
                "ChatApi.history(${s.id}) messages empty",
                ChatApi(http).history(s.id, null).messages.isNotEmpty(),
            )
        }
        assertTrue("ChatApi.models empty", ChatApi(http).models().isNotEmpty())
    }

    @Test
    fun newChatReplyStaysAfterReload() = runBlocking {
        val api = ChatApi(http)
        val id = api.createSession("New chat", "ep-demo", "demo-llm")
        assertNull("no run is live", api.resume(id))
        api.chatStream(ChatRequest(id, "hello demo", Toggles(), null, emptyList(), null)).events.collect {}
        val texts = api.history(id, null).messages.map { it.content }
        assertTrue(texts.toString(), "hello demo" in texts && DEMO_REPLY in texts)
    }

    @Test
    fun queryFiltersWork() {
        fun rows(path: String, query: String?, key: String) =
            JSONObject(Demo.respond("GET", path, query).first).getJSONArray(key).let { a -> (0 until a.length()).map(a::getJSONObject) }
        val unread = rows("/api/email/list", "filter=unread&limit=50", "emails")
        assertTrue(unread.isNotEmpty() && unread.none { it.optBoolean("is_read") })
        assertTrue(rows("/api/email/list", "filter=all", "emails").any { it.optBoolean("is_read") })
        val archived = rows("/api/notes", "archived=true", "notes")
        assertTrue(archived.isNotEmpty() && archived.all { it.optBoolean("archived") })
        assertTrue(rows("/api/notes", null, "notes").none { it.optBoolean("archived") })
    }

    @Test
    fun galleryImagesDecodeAndWritesAnswer() = runBlocking {
        for (g in listOf("demo-img-1.png", "demo-img-3.jpg")) {
            val png = GalleryApi(http).imageBytes("/api/generated-image/$g")
            assertEquals(0x89, png[0].toInt() and 0xff)
        }
        assertEquals("Forked chat", ChatApi(http).fork("demo-s1", 2).name)
        assertTrue(ChatApi(http).invokeSkill("any", "x").isNotBlank())
        assertEquals("demo-file", ChatApi(http).upload("a.txt", "text/plain", byteArrayOf(1)).id)
        assertEquals("ev1", JSONObject(Demo.respond("POST", "/api/calendar/events").first).getJSONObject("event").getString("uid"))
    }

    @Test
    fun mailParses() = runBlocking {
        assertTrue("EmailApi.accounts empty", EmailApi(http).accounts().isNotEmpty())
        val page = EmailApi(http).unreadPage(null, 0)
        assertTrue("EmailApi.unreadPage emails empty", page.emails.isNotEmpty())
        for (e in page.emails) {
            EmailApi(http).read(e.accountId, e.folder, e.uid)
        }
    }

    @Test
    fun toolsParse() = runBlocking {
        assertTrue("NotesApi.list empty", NotesApi(http).list(false).isNotEmpty())
        assertTrue("TasksApi.list empty", TasksApi(http).list().isNotEmpty())
        assertTrue("MemoryApi.list empty", MemoryApi(http).list().isNotEmpty())
        assertTrue("SkillsApi.list empty", SkillsApi(http).list().isNotEmpty())
        assertTrue("LibraryApi.list items empty", LibraryApi(http).list("", false).items.isNotEmpty())
        assertTrue("CalendarApi.calendars empty", CalendarApi(http).calendars().isNotEmpty())
        assertTrue("ResearchApi.library empty", ResearchApi(http).library().isNotEmpty())
        assertTrue("GalleryApi.library items empty", GalleryApi(http).library(0).items.isNotEmpty())
        assertTrue("GalleryApi.albums empty", GalleryApi(http).albums().isNotEmpty())
    }
}
