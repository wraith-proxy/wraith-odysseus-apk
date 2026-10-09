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

    // -- variants, continue, jobs, speech ----------------------------------

    @Test
    fun updateLastMetaPostsTheMetadataObject() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        val kept = listOf(Variant("old", "original"), Variant("new", "regen"))
        api.updateLastMeta("s1", variantsMeta(kept, 1))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/session/s1/update-last-meta", r.path)
        val meta = JSONObject(r.body.readUtf8()).getJSONObject("metadata")
        assertEquals(1, meta.getInt("variantIndex"))
        assertEquals(listOf("old", "new"), (0 until 2).map { meta.getJSONArray("variants").getJSONObject(it).getString("raw") })
        assertEquals("original", meta.getJSONArray("variants").getJSONObject(0).getString("label"))
    }

    @Test
    fun updateLastMetaErrorsSurface() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Session not found"}"""))
        val e = runCatching { runBlocking { api.updateLastMeta("s1", JSONObject().put("variantIndex", 0)) } }.exceptionOrNull() as ApiException
        assertEquals(404, e.status)
        assertEquals("Session not found", e.message)
    }

    @Test
    fun mergeLastAssistantSendsTheSeparatorAndReadsMerged() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok","merged":true}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok","merged":false,"reason":"no_continue_marker"}"""))
        assertTrue(api.mergeLastAssistant("s1", "\n\n*(continued)*\n\n"))
        val r = server.takeRequest()
        assertEquals("/api/session/s1/merge-last-assistant", r.path)
        assertEquals("\n\n*(continued)*\n\n", JSONObject(r.body.readUtf8()).getString("separator"))
        assertFalse(api.mergeLastAssistant("s1", "\n\n"))
    }

    @Test
    fun mergeLastAssistantErrorsSurface() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))
        val e = runCatching { runBlocking { api.mergeLastAssistant("s1", "\n\n") } }.exceptionOrNull() as ApiException
        assertEquals("boom", e.message)
    }

    @Test
    fun researchJobsParseWhatTheWebReads() = runBlocking {
        server.enqueue(json("chat_jobs.json"))
        val jobs = api.researchJobs("s1")
        assertEquals("/api/research/chat-jobs/s1", server.takeRequest().path)
        // The entry without an id is dropped.
        assertEquals(listOf("job_run1", "job_done1", "job_bad1"), jobs.map { it.id })
        val (running, delivered, noSources) = jobs
        assertTrue(running.active)
        assertEquals("Searching", researchCardState(running).label)
        assertEquals("Round 2/3 \u00B7 11 sources \u00B7 You can keep chatting.", researchCardState(running).detail)
        assertFalse(delivered.active)
        assertEquals("Research finished", researchCardState(delivered).label)
        assertEquals("7 sources \u00B7 See the update in chat.", researchCardState(delivered).detail)
        assertEquals("No sources found", researchCardState(noSources).label)
        assertEquals(JobTone.Error, researchCardState(noSources).tone)
    }

    @Test
    fun researchJobsErrorsAndEmptyBodies() = runBlocking {
        server.enqueue(MockResponse().setBody("""{}"""))
        assertEquals(emptyList<ResearchJob>(), api.researchJobs("s1"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Not authenticated"}"""))
        val e = runCatching { api.researchJobs("s1") }.exceptionOrNull() as ApiException
        assertEquals(401, e.status)
    }

    @Test
    fun ttsIsEnabledOnlyForAServerSideVoice() = runBlocking {
        server.enqueue(json("tts_stats.json"))
        assertTrue(api.ttsEnabled())
        assertEquals("/api/tts/stats", server.takeRequest().path)
        // Off in settings: the server folds that into available/ready.
        server.enqueue(MockResponse().setBody("""{"available":false,"ready":false,"provider":"local"}"""))
        assertFalse(api.ttsEnabled())
        // The web's own speech engine cannot make audio for the app.
        server.enqueue(MockResponse().setBody("""{"available":true,"ready":true,"provider":"browser"}"""))
        assertFalse(api.ttsEnabled())
        server.enqueue(MockResponse().setBody("""{"available":true,"ready":true,"provider":"disabled"}"""))
        assertFalse(api.ttsEnabled())
    }

    @Test
    fun synthesizePostsTheTextAndReturnsTheAudio() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(okio.Buffer().write(byteArrayOf(0x49, 0x44, 0x33, 1, 2))))
        val speech = api.synthesize("Hello there")
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/tts/synthesize", r.path)
        val body = JSONObject(r.body.readUtf8())
        assertEquals("Hello there", body.getString("text"))
        assertEquals("audio", body.getString("format"))
        assertEquals("audio/mpeg", speech.mime)
        assertEquals(5, speech.bytes.size)
    }

    @Test
    fun synthesizeErrorsCarryTheServersMessage() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":{"message":"TTS service not available"}}"""))
        val e = runCatching { runBlocking { api.synthesize("x") } }.exceptionOrNull() as ApiException
        assertEquals(503, e.status)
        assertEquals("TTS service not available", e.message)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav"))
        val empty = runCatching { runBlocking { api.synthesize("x") } }.exceptionOrNull() as ApiException
        assertEquals("The server returned no audio", empty.message)
    }

    @Test
    fun historyReadsMarkersAndShowsTheChosenVersion() {
        val page = ChatApi.parseHistory(
            JSONObject(
                """{"history":[
                  {"role":"assistant","content":"cut off","metadata":{"_db_id":"a1","stopped":true}},
                  {"role":"assistant","content":"nothing","metadata":{"_db_id":"a2","stopped":true,"cancelled":true}},
                  {"role":"assistant","content":"fixed","metadata":{"_db_id":"a3","edited":true}},
                  {"role":"assistant","content":"third","metadata":{"_db_id":"a4","variantIndex":0,
                    "variants":[{"raw":"first","label":"original","html":"<p>first</p>"},{"raw":"second","label":"regen"},{"raw":"third","label":"regen"}]}},
                  {"role":"assistant","content":"third","metadata":{"_db_id":"a5","variants":[{"raw":"x","label":"original"},{"raw":"third","label":"shorter"}]}},
                  {"role":"assistant","content":"edited text","metadata":{"_db_id":"a6","edited":true,"variantIndex":0,
                    "variants":[{"raw":"x","label":"original"},{"raw":"y","label":"regen"}]}}
                ]}""",
            ),
        )
        val m = page.messages
        val stopped = m[0]; val cancelled = m[1]; val edited = m[2]
        val shownFirst = m[3]; val newest = m[4]; val editedVariants = m[5]
        assertTrue(stopped.details.stopped)
        assertFalse(stopped.details.cancelled)
        assertTrue(cancelled.details.cancelled)
        assertTrue(edited.details.edited)
        // The chosen older version replaces the stored text; the list is kept whole.
        assertEquals("first", shownFirst.content)
        assertEquals(0, shownFirst.details.shownVariant)
        assertEquals(3, shownFirst.details.variants.size)
        assertEquals("<p>first</p>", shownFirst.details.variants[0].html)
        // No index means the newest, which is the stored text.
        assertEquals("third", newest.content)
        assertEquals(1, newest.details.shownVariant)
        // An edit is saved over the stored text only, so it wins over the kept versions.
        assertEquals("edited text", editedVariants.content)
        assertTrue(editedVariants.details.variants.isEmpty())
    }

    @Test
    fun keptVersionsStartFromTheOriginalAndRoundTrip() {
        assertEquals(listOf(Variant("a", "original")), keptVariants(emptyList(), "a"))
        val have = listOf(Variant("a", "original"), Variant("b", "regen", "<p>b</p>"))
        assertEquals(have, keptVariants(have, "ignored"))
        val meta = variantsMeta(have, 1)
        assertEquals("<p>b</p>", meta.getJSONArray("variants").getJSONObject(1).getString("html"))
        assertFalse(meta.getJSONArray("variants").getJSONObject(0).has("html"))
    }
}
