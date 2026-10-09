package com.thomaspeoples.wraithodysseus.chat

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private val server = MockWebServer()
    private lateinit var api: ChatApi

    @Before
    fun setUp() {
        server.start()
        api = ChatApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(name: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(fixture(name))

    /** The named parts of a multipart/form-data body, in order. */
    private fun formParts(r: RecordedRequest): List<Pair<String, String>> {
        val boundary = checkNotNull(r.getHeader("Content-Type")).substringAfter("boundary=")
        return r.body.readUtf8().split("--$boundary").mapNotNull { part ->
            val name = Regex("name=\"([^\"]+)\"").find(part)?.groupValues?.get(1) ?: return@mapNotNull null
            name to part.substringAfter("\r\n\r\n").removeSuffix("\r\n")
        }
    }

    // -- history -----------------------------------------------------------

    @Test
    fun historyJoinsPartsAndSplitsThinking() = runBlocking {
        server.enqueue(json("history_page.json"))
        val page = api.history("s1", null)
        assertEquals("/api/history/s1?limit=50", server.takeRequest().path)

        assertEquals(10, page.offset)
        assertTrue(page.hasMoreBefore)
        assertEquals("qwen3-30b-a3b", page.model)
        val (q, a, photo, empty) = page.messages
        assertEquals("What's a good name for a grey cat?", q.content)
        assertEquals("Grey things: pebble, ash, smoke.", a.thinking)
        // A stored JSON string of parts: only the text part survives.
        assertEquals("Describe this photo", photo.content)
        assertEquals(listOf("cat.png"), photo.attachments.map { it.name })
        // An empty reply stored as just a think block.
        assertEquals("", empty.content)
        assertEquals("no visible reply was produced", empty.thinking)
    }

    @Test
    fun olderPagesAskForTheirOffset() = runBlocking {
        server.enqueue(json("history_page.json"))
        api.history("s1", 0)
        assertEquals("/api/history/s1?limit=50&offset=0", server.takeRequest().path)
        server.enqueue(json("history_page.json"))
        api.history("s1", 0, limit = 30)
        assertEquals("/api/history/s1?limit=30&offset=0", server.takeRequest().path)
    }

    @Test
    fun anAttachmentWithoutAnIdIsSkippedNotFatal() {
        val page = ChatApi.parseHistory(
            JSONObject(
                """{"history":[{"role":"user","content":"hi","metadata":{"attachments":[{"name":"x.png"},{"id":"f-1","name":"y.png","mime":"image/png"}]}}]}""",
            ),
        )
        assertEquals(listOf(UploadedFile("f-1", "y.png", "image/png")), page.messages.single().attachments)
    }

    @Test
    fun contentShapes() {
        assertEquals("a\nb", ChatApi.contentText(org.json.JSONArray("""[{"type":"text","text":"a"},{"type":"text","text":"b"}]""")))
        assertEquals("[1, 2] is a list", ChatApi.contentText("[1, 2] is a list"))
        assertEquals("[not json", ChatApi.contentText("[not json"))
        assertEquals("", ChatApi.contentText(null))
    }

    // -- models ------------------------------------------------------------

    @Test
    fun onlyOnlineChatModelsArePickable() {
        val models = ChatApi.parseModels(JSONObject(fixture("models.json")))
        assertEquals(listOf("qwen3-30b-a3b", "llama-3.1-8b-instruct"), models.map { it.model })
        assertEquals("Qwen3 30B A3B", models[0].display)
        assertTrue(models.all { it.endpointId == "ep_local01" })
    }

    @Test
    fun newChatFallsBackFromAnEmptyDefault() {
        val models = ChatApi.parseModels(JSONObject(fixture("models.json")))
        assertEquals("qwen3-30b-a3b", pickNewChatModel(DefaultChat("ep_local01", "qwen3-30b-a3b"), models)?.model)
        assertEquals("qwen3-30b-a3b", pickNewChatModel(DefaultChat("", ""), models)?.model)
        assertNull(pickNewChatModel(DefaultChat("", ""), emptyList()))
        assertEquals("qwen3-30b-a3b 9:41:07 AM", newChatName("org/qwen3-30b-a3b", "9:41:07 AM"))
        // Whatever the phone's locale, the time must keep the one shape the
        // server auto-titles (routes/chat_helpers.py's name pattern).
        val name = newChatName("qwen3-30b-a3b", newChatTime(java.time.LocalTime.of(9, 5, 7)))
        assertEquals("qwen3-30b-a3b 9:05:07", name)
        assertTrue(Regex("""^.+ \d{1,2}:\d{2}:\d{2}(\s*(AM|PM))?$""").matches(name))
    }

    // -- run control -------------------------------------------------------

    @Test
    fun approvalRePostSendsTheDecisionAndNoPlanMode() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(fixture("sse_tool_approval_denied.txt")))
        val stream = api.chatStream(
            ChatRequest("s1", "", Toggles(agent = true), null, emptyList(), ToolApproval("apr_5f1c0d9e2b7a4c3d", "deny"))
        )
        // The deny stream has no run, so nothing to stop.
        assertNull(stream.runId)

        val request = server.takeRequest()
        assertEquals("/api/chat_stream", request.path)
        assertTrue(request.getHeader("X-Tz-Offset")!!.toInt() in -840..840)
        val fields = formParts(request).toMap()
        assertEquals("", fields["message"])
        assertEquals("s1", fields["session"])
        assertEquals("apr_5f1c0d9e2b7a4c3d", fields["tool_approval_id"])
        assertEquals("deny", fields["tool_approval_decision"])
        assertEquals("false", fields["plan_mode"])
        assertEquals("false", fields["allow_bash"])
        assertFalse("selected_model" in fields)
    }

    @Test
    fun anExpiredApprovalIsA409() = runBlocking {
        server.enqueue(json("chat_stream_409_approval.json", 409))
        val e = runCatching {
            api.chatStream(ChatRequest("s1", "", Toggles(), null, emptyList(), ToolApproval("apr_x", "approve")))
        }.exceptionOrNull() as ApiException
        assertEquals(409, e.status)
    }

    @Test
    fun aNormalMessageCarriesModelAndAttachments() = runBlocking {
        server.enqueue(MockResponse().setHeader("X-Odysseus-Run-Id", "run-1").setBody(fixture("sse_chat_basic.txt")))
        val pick = ModelChoice("ep_local01", "Local llama", "qwen3-30b-a3b", "Qwen3 30B A3B")
        val stream = api.chatStream(ChatRequest("s1", "hi", Toggles(), pick, listOf("f-1", "f-2"), null))
        assertEquals("run-1", stream.runId)
        val fields = formParts(server.takeRequest()).toMap()
        assertEquals("qwen3-30b-a3b", fields["selected_model"])
        assertEquals("ep_local01", fields["selected_endpoint_id"])
        assertEquals("""["f-1","f-2"]""", fields["attachments"])
        assertFalse("tool_approval_id" in fields)
    }

    @Test
    fun stopSendsTheRunId() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"stopped": true}"""))
        assertTrue(api.stop("s1", "run-1"))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/chat/stop/s1", r.path)
        assertEquals("run-1", r.getHeader("X-Odysseus-Run-Id"))
    }

    @Test
    fun resumeWithNoRunIsNull() = runBlocking {
        server.enqueue(json("chat_resume_404.json", 404))
        assertNull(api.resume("s1"))
    }

    // -- the rest ----------------------------------------------------------

    @Test
    fun uploadUsesTheFilesField() = runBlocking {
        server.enqueue(json("upload.json"))
        val f = api.upload("cat.png", "image/png", byteArrayOf(1, 2, 3))
        assertEquals(UploadedFile("f-9a8b7c6d5e4f", "cat.png", "image/png"), f)
        assertEquals(listOf("files"), formParts(server.takeRequest()).map { it.first })
    }

    @Test
    fun sessionsSearchAndCreateParse() = runBlocking {
        server.enqueue(json("sessions.json"))
        server.enqueue(json("search.json"))
        server.enqueue(json("session_create.json"))
        val sessions = api.sessions()
        assertEquals(2, sessions.size)
        assertTrue(sessions[0].isImportant)
        assertNull(sessions[1].endpointId)
        assertEquals("2026-10-07T18:02:10.500000", sessions[0].lastMessageAt)
        assertEquals(14, sessions[0].messageCount)
        assertNull(sessions[0].folder)

        val hits = api.search("cat")
        assertEquals("7d3c2f0e-1111-4a2b-9c3d-000000000001", hits.single().sessionId)
        server.takeRequest()
        assertEquals("/api/search?q=cat&limit=25", server.takeRequest().path)

        assertEquals("7d3c2f0e-1111-4a2b-9c3d-000000000001", api.createSession("x", "ep_local01", "qwen3-30b-a3b"))
        val fields = formParts(server.takeRequest()).toMap()
        assertEquals("true", fields["skip_validation"])
        assertEquals("ep_local01", fields["endpoint_id"])
    }
}
