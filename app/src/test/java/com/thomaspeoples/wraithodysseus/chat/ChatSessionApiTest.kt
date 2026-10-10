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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The calls behind the header, the Chat Context sheet and the slash commands: path, method, body, parse, error. */
class ChatSessionApiTest {

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

    private fun ok(body: String = "{}") = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun failure(block: suspend () -> Unit): ApiException = runBlocking {
        try {
            block()
            fail("expected an ApiException")
            error("unreachable")
        } catch (e: ApiException) {
            e
        }
    }

    private fun form(r: RecordedRequest): Map<String, String> =
        r.body.readUtf8().split("&").filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split("=", limit = 2)
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }

    // -- model switch --------------------------------------------------------

    @Test
    fun switchingModelPatchesModelEndpointUrlAndEndpointId() = runBlocking {
        server.enqueue(ok("""{"id":"s 1","model":"gpt-5"}"""))
        api.setModel("s 1", ModelChoice("ep_cloud01", "Example Cloud", "gpt-5", "GPT-5", "https://api.example.com/v1/chat/completions", "api"))
        val r = server.takeRequest()
        assertEquals("PATCH", r.method)
        assertEquals("/api/session/s%201", r.path)
        // session_routes.py:660 reads Form fields; a JSON body would be ignored.
        assertTrue(checkNotNull(r.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded"))
        assertEquals(
            mapOf("model" to "gpt-5", "endpoint_url" to "https://api.example.com/v1/chat/completions", "endpoint_id" to "ep_cloud01"),
            form(r),
        )
    }

    @Test
    fun modelSwitchWithoutAnEndpointIdStillSendsTheUrlField() = runBlocking {
        server.enqueue(ok())
        api.setModel("s1", ModelChoice("", "x", "m", "m"))
        // The server only switches when endpoint_url is present, even if empty.
        assertEquals(mapOf("model" to "m", "endpoint_url" to ""), form(server.takeRequest()))
    }

    @Test
    fun aRefusedModelSwitchIsTheServersOwnWords() {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody("""{"detail":"Choose a registered model endpoint"}"""))
        val e = failure { api.setModel("s1", ModelChoice("", "x", "m", "m", "http://raw")) }
        assertEquals(403, e.status)
        assertEquals("Choose a registered model endpoint", e.message)
    }

    // -- rename, delete, archive -----------------------------------------------

    @Test
    fun renameIsAFormPatchOfName() = runBlocking {
        server.enqueue(ok("""{"id":"s1","name":"New name"}"""))
        api.renameSession("s1", "New name")
        val r = server.takeRequest()
        assertEquals("PATCH", r.method)
        assertEquals("/api/session/s1", r.path)
        assertEquals(mapOf("name" to "New name"), form(r))
    }

    @Test
    fun renameRefusesABlankNameBeforeAskingTheServer() {
        try {
            runBlocking { api.renameSession("s1", "  ") }
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun deleteIsADeleteOfTheSession() = runBlocking {
        server.enqueue(ok("""{"status":"deleted"}"""))
        api.deleteSession("s1")
        val r = server.takeRequest()
        assertEquals("DELETE", r.method)
        assertEquals("/api/session/s1", r.path)
    }

    @Test
    fun deletingAStarredChatShowsTheServersMessage() {
        server.enqueue(json("error_403_session_starred.json", 403))
        val e = failure { api.deleteSession("s1") }
        assertEquals(403, e.status)
        assertEquals("Unstar the session before deleting it", e.message)
    }

    @Test
    fun archiveIsAPostWithNoBody() = runBlocking {
        server.enqueue(ok("""{"status":"archived"}"""))
        api.archiveSession("s1")
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/session/s1/archive", r.path)
        assertEquals(0L, r.bodySize)
    }

    @Test
    fun archivingAMissingChatIsA404() {
        server.enqueue(json("error_404_detail.json", 404))
        assertEquals(404, failure { api.archiveSession("s1") }.status)
    }

    // -- context -------------------------------------------------------------

    @Test
    fun contextParsesTheRingAndTheChatsSettings() = runBlocking {
        server.enqueue(json("session_context.json"))
        val c = api.context("s1")
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/api/session/s1/context", r.path)
        assertEquals(91000, c.usedTokens)
        assertEquals(128000, c.contextLength)
        assertEquals(71.1, c.percent, 0.0)
        assertEquals(14, c.messages)
        assertEquals("org/qwen3-30b-a3b", c.model)
        assertTrue(c.canCompact)
        assertTrue(c.memoryExtraction)
        assertFalse(c.memoryInjection)
        assertTrue(c.skillInjection)
        assertEquals("effort:high", c.thinkingMode)
        assertEquals("high", c.reasoningEffort)
        assertTrue(c.thinkingSupported)
        assertEquals(0.7, c.temperatureOverride!!, 0.0)
        assertNull(c.maxTokensOverride)
    }

    @Test
    fun contextSwitchesDefaultToOnAndMissingFieldsToZero() = runBlocking {
        server.enqueue(json("session_context_bare.json"))
        val c = api.context("s1")
        assertTrue(c.memoryExtraction && c.memoryInjection && c.skillInjection)
        assertEquals("off", c.thinkingMode)
        assertNull(c.reasoningEffort)
        assertNull(c.temperatureOverride)
        assertFalse(c.canCompact)
    }

    @Test
    fun contextOfAMissingChatIsA404() {
        server.enqueue(json("error_404_detail.json", 404))
        assertEquals(404, failure { api.context("s1") }.status)
    }

    // -- compact -------------------------------------------------------------

    @Test
    fun compactPostsAndReturnsTheServersMessage() = runBlocking {
        server.enqueue(json("compact_ok.json"))
        assertEquals("Compacted: 12 msgs → 6 msgs (88.0% → 21.5%)", api.compact("s1"))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/session/s1/compact", r.path)
        assertTrue(checkNotNull(r.getHeader("Content-Type")).startsWith("application/json"))
    }

    @Test
    fun compactOfAShortChatIsAMessageNotAnError() = runBlocking {
        // history_routes.py:1110 answers 200 with this text.
        server.enqueue(json("compact_short.json"))
        assertEquals("Not enough messages to compact", api.compact("s1"))
    }

    @Test
    fun compactDuringARunShowsTheServersRefusal() {
        server.enqueue(json("compact_409.json", 409))
        val e = failure { api.compact("s1") }
        assertEquals(409, e.status)
        assertEquals("Session has an active run; try compacting after it finishes", e.message)
        // Not retried: a repeat would summarize twice.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aShortChatRefusedWith400ShowsItsMessageToo() {
        server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody("""{"detail":"Not enough messages to compact"}"""))
        val e = failure { api.compact("s1") }
        assertEquals(400, e.status)
        assertEquals("Not enough messages to compact", e.message)
    }

    // -- per-chat switches and generation settings ------------------------------

    @Test
    fun eachSwitchPostsEnabledToItsOwnRoute() = runBlocking {
        for ((which, path) in listOf(
            ChatSwitch.MemoryExtraction to "memory-extraction",
            ChatSwitch.MemoryInjection to "memory-injection",
            ChatSwitch.SkillInjection to "skill-injection",
        )) {
            server.enqueue(ok("""{"status":"success"}"""))
            api.setSwitch("s1", which, false)
            val r = server.takeRequest()
            assertEquals("POST", r.method)
            assertEquals("/api/session/s1/$path", r.path)
            assertTrue(checkNotNull(r.getHeader("Content-Type")).startsWith("application/json"))
            assertEquals(false, JSONObject(r.body.readUtf8()).getBoolean("enabled"))
        }
    }

    @Test
    fun aRefusedSwitchIsAnError() {
        server.enqueue(json("error_404_detail.json", 404))
        assertEquals(404, failure { api.setSwitch("s1", ChatSwitch.SkillInjection, true) }.status)
    }

    @Test
    fun generationSettingsSendAllThreeAndNullClears() = runBlocking {
        server.enqueue(json("generation_ok.json"))
        val saved = api.setGeneration("s1", "on", 0.4, null)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/session/s1/generation-settings", r.path)
        val b = JSONObject(r.body.readUtf8())
        assertEquals("on", b.getString("thinking_mode"))
        assertEquals(0.4, b.getDouble("temperature_override"), 0.0)
        // An explicit null: the server clears the override only when the key is there.
        assertTrue(b.has("max_tokens_override") && b.isNull("max_tokens_override"))
        assertFalse(b.has("reasoning_effort"))
        assertEquals(GenerationSettings("on", 0.4, null), saved)
    }

    @Test
    fun aReasoningEffortIsSentAlongside() = runBlocking {
        server.enqueue(json("generation_ok.json"))
        api.setGeneration("s1", "off", null, 4096, reasoningEffort = "high")
        val b = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("high", b.getString("reasoning_effort"))
        assertTrue(b.isNull("temperature_override"))
        assertEquals(4096, b.getInt("max_tokens_override"))
    }

    @Test
    fun anInvalidThinkingModeIsA400() {
        server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody("""{"detail":"Invalid thinking mode"}"""))
        assertEquals("Invalid thinking mode", failure { api.setGeneration("s1", "maybe", null, null) }.message)
    }

    // -- presets and skills ---------------------------------------------------

    @Test
    fun presetsListsNamedEntriesAndHidesAnUnusedCustomOne() = runBlocking {
        server.enqueue(json("presets.json"))
        val presets = api.presets()
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/api/presets", r.path)
        // Keys come back in the server's order on a device; the JVM test library does not keep it.
        assertEquals(setOf(Preset("code_analyze", "Code Analyze"), Preset("brainstorm", "Brainstorm")), presets.toSet())
    }

    @Test
    fun anEnabledCustomPresetIsOffered() {
        assertEquals(
            setOf(Preset("reason", "Reason"), Preset("custom", "My persona")),
            parsePresets(JSONObject(fixture("presets_custom_on.json"))).toSet(),
        )
    }

    @Test
    fun aCustomPresetWithoutAnEnabledKeyIsOfferedOnlyWhenItHoldsSomething() {
        val ids = { json: String -> parsePresets(JSONObject(json)).map { it.id } }
        assertEquals(listOf("custom"), ids("""{"custom": {"name": "Mine", "system_prompt": "p"}}"""))
        assertEquals(listOf("custom"), ids("""{"custom": {"name": "Custom", "temperature": 0.5}}"""))
        assertEquals(emptyList<String>(), ids("""{"custom": {"name": "Custom", "temperature": 1.0}}"""))
        assertEquals(emptyList<String>(), ids("""{"custom": {"name": "Mine", "system_prompt": "p", "enabled": false}}"""))
    }

    @Test
    fun skillCatalogParsesNamesAndHelp() = runBlocking {
        server.enqueue(json("slash_catalog.json"))
        val skills = api.skillCommands()
        assertEquals("/api/skills/slash-catalog", server.takeRequest().path)
        assertEquals(
            listOf(
                SkillCommand("summarize", "Summarize text", "Skills / writing"),
                SkillCommand("deploy-check", "", "Skills / general"),
            ),
            skills,
        )
    }

    @Test
    fun invokingASkillPostsTheRequestAndReturnsThePromptToSend() = runBlocking {
        server.enqueue(json("skill_invoke.json"))
        val message = api.invokeSkill("deploy check", "the notes")
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/skills/deploy%20check/invoke", r.path)
        assertEquals("the notes", JSONObject(r.body.readUtf8()).getString("request"))
        assertTrue(message.endsWith("Request: the notes"))
    }

    @Test
    fun invokingAnUnknownSkillIsA404WithTheServersWords() {
        server.enqueue(json("skill_404.json", 404))
        val e = failure { api.invokeSkill("nope", "") }
        assertEquals(404, e.status)
        assertEquals("Skill is not available for slash invocation", e.message)
    }

    // -- models ---------------------------------------------------------------

    @Test
    fun modelsCarryTheirUrlCategoryAndReasoningLevels() {
        val models = ChatApi.parseModels(JSONObject(fixture("models_mixed.json")))
        val qwen = models.first { it.model == "org/qwen3-30b-a3b" }
        assertEquals("http://llm.example.internal:8080/v1/chat/completions", qwen.url)
        assertEquals("local", qwen.category)
        assertTrue(qwen.reasoningLevels.isEmpty())
        assertEquals(listOf("low", "high"), models.first { it.model == "gpt-5" }.reasoningLevels)
    }

    @Test
    fun historyReportsTheChatsName() {
        val page = ChatApi.parseHistory(JSONObject("""{"history":[],"name":"Trip plan","model":"m","endpoint_id":"e"}"""))
        assertEquals("Trip plan", page.name)
    }
}
