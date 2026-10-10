package com.thomaspeoples.wraithodysseus.chat

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionsApiTest {
    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private val server = MockWebServer()
    private lateinit var api: SessionsApi

    @Before
    fun setUp() {
        server.start()
        api = SessionsApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun ok(body: String = "{}") = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun formBody(r: okhttp3.mockwebserver.RecordedRequest): String {
        assertTrue(r.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
        return r.body.readUtf8()
    }

    @Test
    fun renameIsAPatchWithAFormName() = runBlocking {
        server.enqueue(ok())
        api.rename("s 1", "New name")
        val r = server.takeRequest()
        assertEquals("PATCH", r.method)
        assertEquals("/api/session/s%201", r.path)
        assertEquals("name=New%20name", formBody(r))
    }

    @Test
    fun movingToAFolderAndOutOfOneIsAFormField() = runBlocking {
        server.enqueue(ok())
        server.enqueue(ok())
        api.moveToFolder("s1", " Work ")
        api.moveToFolder("s1", "")
        val a = server.takeRequest()
        assertEquals("PATCH", a.method)
        assertEquals("/api/session/s1", a.path)
        assertEquals("folder=Work", formBody(a))
        assertEquals("folder=", formBody(server.takeRequest()))
    }

    @Test
    fun favouriteIsAFormBooleanNotJson() = runBlocking {
        server.enqueue(ok())
        server.enqueue(ok())
        api.setFavorite("s1", true)
        api.setFavorite("s1", false)
        val a = server.takeRequest()
        assertEquals("POST", a.method)
        assertEquals("/api/session/s1/important", a.path)
        assertEquals("important=true", formBody(a))
        assertEquals("important=false", formBody(server.takeRequest()))
    }

    @Test
    fun archiveAndDeleteUseTheirOwnVerbs() = runBlocking {
        server.enqueue(ok())
        server.enqueue(ok())
        api.archive("s1")
        api.delete("s1")
        val a = server.takeRequest()
        assertEquals("POST", a.method)
        assertEquals("/api/session/s1/archive", a.path)
        val d = server.takeRequest()
        assertEquals("DELETE", d.method)
        assertEquals("/api/session/s1", d.path)
    }

    @Test
    fun aStarredChatIsRefusedWithTheServersWords() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json")
                .setBody("""{"detail":{"error":"SESSION_STARRED","message":"Unstar the session before deleting it"}}"""),
        )
        val e = runCatching { api.delete("s1") }.exceptionOrNull() as ApiException
        assertEquals(403, e.status)
        assertEquals("Unstar the session before deleting it", e.message)
    }

    @Test
    fun copyChatAsksForTheWholeHistoryAndFormatsItLikeTheWeb() = runBlocking {
        server.enqueue(
            ok(
                """{"history":[
                {"role":"system","content":"hidden"},
                {"role":"user","content":"  Hello  "},
                {"role":"assistant","content":"<think>hmm</think>Hi there"},
                {"role":"user","content":[{"type":"text","text":"look"},{"type":"image_url","image_url":{"url":"x"}},{"type":"text","text":"here"}]},
                {"role":"assistant","content":"[{\"type\":\"text\",\"text\":\"parts as a string\"}]"}
                ]}""",
            ),
        )
        val text = api.chatText("s1")
        // No limit: the server pages only when asked to.
        assertEquals("/api/history/s1", server.takeRequest().path)
        assertEquals(
            "You: Hello\n\nAI: <think>hmm</think>Hi there\n\nYou: look\nhere\n\nAI: parts as a string",
            text,
        )
    }

    @Test
    fun anEmptyChatHasNothingToCopy() = runBlocking {
        server.enqueue(ok("""{"history":[]}"""))
        assertNull(api.chatText("s1"))
    }

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    @Test
    fun archivedAsksForAPageWithTheSearchAndSortAndParsesIt() = runBlocking {
        server.enqueue(ok(fixture("sessions_archived.json")))
        val page = api.archived(" trip ", 20, sort = ArchiveSort.MostMessages)
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/api/sessions/archived?offset=20&limit=20&sort=most-messages&search=trip", r.path)
        assertEquals(41, page.total)
        assertEquals(2, page.sessions.size)
        val first = page.sessions[0]
        assertEquals("Old trip plan", first.name)
        assertEquals("org/qwen3-30b-a3b", first.model)
        assertEquals(14, first.messageCount)
        assertEquals("2026-09-02T09:30:00.000000", first.updatedAt)
        // A null model and null times are tolerated.
        assertNull(page.sessions[1].model)
        assertEquals("", page.sessions[1].updatedAt)
        assertTrue(page.sessions[1].isImportant)
    }

    @Test
    fun archivedLeavesOutAnEmptySearch() = runBlocking {
        server.enqueue(ok("""{"sessions": [], "total": 0}"""))
        assertEquals(0, api.archived("  ", 0).sessions.size)
        assertEquals("/api/sessions/archived?offset=0&limit=20&sort=recent", server.takeRequest().path)
    }

    @Test
    fun unarchiveIsAPostToUnarchiveNotRestore() = runBlocking {
        server.enqueue(ok("""{"status": "unarchived"}"""))
        api.unarchive("s 1")
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/session/s%201/unarchive", r.path)
    }
}
