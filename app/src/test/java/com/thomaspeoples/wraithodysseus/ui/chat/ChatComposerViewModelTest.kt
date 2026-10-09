package com.thomaspeoples.wraithodysseus.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.chat.ChatSwitch
import com.thomaspeoples.wraithodysseus.chat.Toggles
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The composer's behaviour in the view model: the queue, the slash commands, the header's actions. */
class ChatComposerViewModelTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private data class Call(val method: String, val path: String, val body: String)

    private val server = MockWebServer()
    private val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

    /** Held shut, a chat_stream request waits: the run stays going. */
    @Volatile private var streamGate = CountDownLatch(0)
    @Volatile private var stream = sse()
    @Volatile private var sessions = "[]"

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private fun sse() = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setHeader("X-Odysseus-Run-Id", "run-1").setBody(fixture("sse_chat_basic.txt"))

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val odysseus = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = checkNotNull(request.requestUrl).encodedPath
            calls += Call(request.method.orEmpty(), path, request.body.readUtf8())
            return when {
                path == "/api/models" -> json(fixture("models_mixed.json"))
                path == "/api/sessions" -> json(sessions)
                path.startsWith("/api/history/") -> json(
                    """{"history":[],"offset":0,"total":0,"has_more_before":false,"model":"org/qwen3-30b-a3b","endpoint_id":"ep_local01","name":"Trip plan"}""",
                )
                path == "/api/chat_stream" -> {
                    streamGate.await(10, TimeUnit.SECONDS)
                    stream
                }
                path == "/api/chat/resume/s1" -> MockResponse().setResponseCode(404)
                path == "/api/research/chat-jobs/s1" -> json("""{"jobs":[]}""")
                path == "/api/session" -> json(fixture("session_create.json"))
                path == "/api/session/s1/fork" -> json(fixture("fork_ok.json"))
                path == "/api/session/s1/compact" -> json(fixture("compact_ok.json"))
                path == "/api/session/s1/context" -> json(fixture("session_context.json"))
                path == "/api/session/s1/memory-injection" && failSwitch -> json("""{"detail":"boom"}""", 500)
                path == "/api/skills/slash-catalog" -> json(fixture("slash_catalog.json"))
                path == "/api/skills/summarize/invoke" -> json(fixture("skill_invoke.json"))
                path == "/api/presets" -> json(fixture("presets.json"))
                else -> json("{}")
            }
        }
    }

    @Volatile private var failSwitch = false

    @Before
    fun setUp() {
        server.dispatcher = odysseus
        server.start()
    }

    @After
    fun tearDown() {
        streamGate.countDown()
        server.shutdown()
    }

    private fun CoroutineScope.vm(canUseBash: Boolean = false) = ChatViewModel(
        api = ChatApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false)))),
        sessionId = "s1",
        saved = SavedStateHandle(),
        scope = this,
        jobPollMs = 10,
        canUseBash = canUseBash,
    )

    private fun calls(path: String) = synchronized(calls) { calls.filter { it.path == path } }

    private suspend fun until(what: String, check: () -> Boolean) {
        withTimeout(5_000) { while (!check()) delay(10) }
    }

    /** The form field [name] of a recorded multipart body. */
    private fun field(call: Call, name: String): String? =
        Regex("""name="$name"\r\n(?:Content-[^\r\n]*\r\n)*\r\n(.*?)\r\n--""", RegexOption.DOT_MATCHES_ALL).find(call.body)?.groupValues?.get(1)

    private suspend fun ChatViewModel.ready() = until("history") { !loading }

    // -- queue ---------------------------------------------------------------

    @Test
    fun oneMessageWaitsBehindARunningReplyAndIsSentWhenItEnds() = runBlocking {
        val vm = vm()
        vm.ready()
        streamGate = CountDownLatch(1)
        vm.draft = "one"
        vm.send()
        until("running") { vm.running }

        vm.draft = "two"
        vm.send()
        assertEquals("two", vm.queued?.text)
        assertEquals("", vm.draft)

        // A second one does not replace it, and stays in the composer.
        vm.draft = "three"
        vm.send()
        assertEquals("two", vm.queued?.text)
        assertEquals("three", vm.draft)

        streamGate.countDown()
        until("second stream") { calls("/api/chat_stream").size == 2 && !vm.running }
        assertEquals("one", field(calls("/api/chat_stream")[0], "message"))
        assertEquals("two", field(calls("/api/chat_stream")[1], "message"))
        assertNull(vm.queued)
        // The text typed meanwhile was left alone.
        assertEquals("three", vm.draft)
    }

    @Test
    fun aQueuedMessageCanBeTakenBack() = runBlocking {
        val vm = vm()
        vm.ready()
        streamGate = CountDownLatch(1)
        vm.draft = "one"
        vm.send()
        until("running") { vm.running }
        vm.draft = "later"
        vm.send()
        vm.cancelQueued()
        assertNull(vm.queued)
        assertEquals("later", vm.draft)
        streamGate.countDown()
        until("done") { !vm.running }
        assertEquals(1, calls("/api/chat_stream").size)
    }

    @Test
    fun aRunThatFailsGivesTheQueuedMessageBackInsteadOfSendingIt() = runBlocking {
        val vm = vm()
        vm.ready()
        stream = json("""{"detail":"No model selected"}""", 400)
        streamGate = CountDownLatch(1)
        vm.draft = "one"
        vm.send()
        until("running") { vm.running }
        vm.draft = "two"
        vm.send()
        streamGate.countDown()
        until("failed") { !vm.running && vm.queued == null }
        assertEquals(1, calls("/api/chat_stream").size)
        // "one" was refused and went back to the composer; "two" follows it there.
        assertEquals("one\ntwo", vm.draft)
        assertNotNull(vm.error)
    }

    // -- slash commands --------------------------------------------------------

    @Test
    fun renameCommandPatchesTheNameAndIsNotSentToTheModel() = runBlocking {
        val vm = vm()
        vm.ready()
        vm.draft = "/rename Holiday plans"
        vm.send()
        until("patch") { calls("/api/session/s1").any { it.method == "PATCH" } }
        assertEquals("name=Holiday%20plans", calls("/api/session/s1").single { it.method == "PATCH" }.body)
        until("title") { vm.title == "Holiday plans" }
        assertEquals("", vm.draft)
        assertTrue(calls("/api/chat_stream").isEmpty())
    }

    @Test
    fun renameWithNoNameOpensTheRenameDialog() = runBlocking {
        val vm = vm()
        vm.ready()
        vm.draft = "/rename"
        vm.send()
        assertTrue(vm.renaming)
        assertTrue(calls("/api/session/s1").none { it.method == "PATCH" })
    }

    @Test
    fun deleteAsksFirstAndOnlyThenDeletes() = runBlocking {
        val vm = vm()
        vm.ready()
        vm.draft = "/delete"
        vm.send()
        assertTrue(vm.confirmDelete)
        assertTrue(calls("/api/session/s1").none { it.method == "DELETE" })
        assertNull(vm.closed)

        vm.deleteChat()
        until("closed") { vm.closed != null }
        assertEquals(1, calls("/api/session/s1").count { it.method == "DELETE" })
        assertEquals("Chat deleted", vm.closed)
        assertTrue(calls("/api/chat_stream").isEmpty())
    }

    @Test
    fun archiveCommandPostsArchive() = runBlocking {
        val vm = vm()
        vm.ready()
        vm.draft = "/archive"
        vm.send()
        until("closed") { vm.closed != null }
        assertEquals("POST", calls("/api/session/s1/archive").single().method)
        assertEquals("Chat archived", vm.closed)
    }

    @Test
    fun compactCommandShowsTheServersMessage() = runBlocking {
        val vm = vm()
        vm.ready()
        vm.draft = "/compact"
        vm.send()
        assertEquals("Compacted: 12 msgs → 6 msgs (88.0% → 21.5%)", withTimeout(5_000) { vm.notices.first() })
        assertTrue(calls("/api/chat_stream").isEmpty())
    }

    @Test
    fun compactIsRefusedOnThePhoneWhileARunIsGoing() = runBlocking {
        val vm = vm()
        vm.ready()
        streamGate = CountDownLatch(1)
        vm.draft = "hi"
        vm.send()
        until("running") { vm.running }
        vm.compact()
        assertEquals("Wait for the reply to finish before compacting", withTimeout(5_000) { vm.notices.first() })
        assertTrue(calls("/api/session/s1/compact").isEmpty())
        streamGate.countDown()
        until("done") { !vm.running }
    }

    @Test
    fun forkCommandCopiesTheWholeChatAndOpensTheCopy() = runBlocking {
        val vm = vm()
        vm.ready()
        var opened: String? = null
        vm.openChat = { opened = it }
        vm.draft = "/fork"
        vm.send()
        until("opened") { opened != null }
        assertEquals("5b0f3c1e-7a44-4d0e-9d52-2f6c1a9e0b11", opened)
        assertEquals(Int.MAX_VALUE, JSONObject(calls("/api/session/s1/fork").single().body).getInt("keep_count"))
    }

    @Test
    fun newCommandStartsAChatOnTheSameModel() = runBlocking {
        val vm = vm()
        vm.ready()
        until("models") { vm.models.isNotEmpty() }
        var opened: String? = null
        vm.openChat = { opened = it }
        vm.draft = "/new"
        vm.send()
        until("opened") { opened != null }
        assertEquals("7d3c2f0e-1111-4a2b-9c3d-000000000001", opened)
        val create = calls("/api/session").single()
        assertEquals("ep_local01", field(create, "endpoint_id"))
        assertEquals("org/qwen3-30b-a3b", field(create, "model"))
    }

    @Test
    fun theNewButtonDoesTheSame() = runBlocking {
        val vm = vm()
        vm.ready()
        until("models") { vm.models.isNotEmpty() }
        var opened: String? = null
        vm.openChat = { opened = it }
        vm.newChat()
        until("opened") { opened != null }
        assertEquals("ep_local01", field(calls("/api/session").single(), "endpoint_id"))
    }

    @Test
    fun modelCommandShowsTheCurrentModelWithItsProvider() = runBlocking {
        val vm = vm()
        vm.ready()
        until("models") { vm.models.isNotEmpty() }
        vm.draft = "/model"
        vm.send()
        assertEquals("Local · Qwen3 30B", withTimeout(5_000) { vm.notices.first() })
    }

    @Test
    fun modelCommandWithANameSwitchesTheChatsModel() = runBlocking {
        val vm = vm()
        vm.ready()
        until("models") { vm.models.isNotEmpty() }
        vm.draft = "/model gpt-5"
        vm.send()
        until("patch") { calls("/api/session/s1").any { it.method == "PATCH" } }
        val body = calls("/api/session/s1").single { it.method == "PATCH" }.body
        assertTrue(body.contains("model=gpt-5"))
        assertTrue(body.contains("endpoint_id=ep_cloud01"))
        until("picked") { vm.selected?.model == "gpt-5" }
        assertEquals("Example Cloud", vm.selected?.endpointName)
        // The next message names the pick too.
        until("idle") { !vm.running }
        vm.draft = "hi"
        vm.send()
        until("sent") { calls("/api/chat_stream").isNotEmpty() }
        assertEquals("gpt-5", field(calls("/api/chat_stream").single(), "selected_model"))
        assertEquals("ep_cloud01", field(calls("/api/chat_stream").single(), "selected_endpoint_id"))
    }

    @Test
    fun helpCommandListsTheCommands() = runBlocking {
        val vm = vm()
        vm.ready()
        vm.draft = "/help"
        vm.send()
        assertTrue(checkNotNull(vm.helpText).contains("/compact"))
        assertTrue(calls("/api/chat_stream").isEmpty())
    }

    @Test
    fun aSkillIsInvokedAndItsPromptIsWhatGetsSent() = runBlocking {
        val vm = vm()
        vm.ready()
        vm.draft = "/summarize the notes"
        vm.send()
        until("sent") { calls("/api/chat_stream").isNotEmpty() && !vm.running }
        assertEquals("the notes", JSONObject(calls("/api/skills/summarize/invoke").single().body).getString("request"))
        val message = checkNotNull(field(calls("/api/chat_stream").single(), "message"))
        assertTrue(message.startsWith("Apply the skill below to my request."))
        assertTrue(message.endsWith("Request: the notes"))
    }

    @Test
    fun anUnknownSlashLineIsAnOrdinaryMessage() = runBlocking {
        val vm = vm()
        vm.ready()
        vm.draft = "/etc/hosts has what in it?"
        vm.send()
        until("sent") { calls("/api/chat_stream").isNotEmpty() && !vm.running }
        assertEquals("/etc/hosts has what in it?", field(calls("/api/chat_stream").single(), "message"))
    }

    @Test
    fun typingASlashAsksForTheSkillsAndSuggestsThem() = runBlocking {
        val vm = vm()
        vm.ready()
        vm.draft = "/summ"
        vm.noteDraft("/summ")
        until("skills") { vm.skills != null }
        assertEquals("/summarize", vm.suggestions().first().token)
    }

    // -- the chat's own mode, context and switches -------------------------------

    @Test
    fun anAgentChatStartsWithAgentAndWebOn() = runBlocking {
        sessions = """[{"id":"s1","name":"Trip plan","mode":"agent","updated_at":"2026-01-01T00:00:00"}]"""
        val vm = vm()
        vm.ready()
        vm.loadChatInfo()
        assertEquals(Toggles(agent = true, web = true), vm.toggles)
        assertEquals("Trip plan", vm.title)
    }

    @Test
    fun aChatChatStartsInChatModeWithWebOff() = runBlocking {
        sessions = """[{"id":"s1","name":"Trip plan","mode":"chat","updated_at":"2026-01-01T00:00:00"}]"""
        val vm = vm()
        vm.ready()
        vm.loadChatInfo()
        assertEquals(Toggles(), vm.toggles)
    }

    @Test
    fun theChatsModeDoesNotOverwriteSwitchesTheUserAlreadySet() = runBlocking {
        sessions = """[{"id":"s1","name":"Trip plan","mode":"agent","updated_at":"2026-01-01T00:00:00"}]"""
        val vm = vm()
        vm.ready()
        vm.updateToggles(Toggles(rag = true))
        vm.loadChatInfo()
        assertEquals(Toggles(rag = true), vm.toggles)
    }

    @Test
    fun contextFeedsTheRing() = runBlocking {
        val vm = vm()
        vm.ready()
        vm.refreshContext()
        assertEquals(71.1, checkNotNull(vm.context).percent, 0.0)
        assertEquals(14, vm.context?.messages)
    }

    @Test
    fun aSwitchShowsAtOnceAndIsPostedAndPutBackWhenRefused() = runBlocking {
        val vm = vm()
        vm.ready()
        vm.refreshContext()
        assertFalse(checkNotNull(vm.context).memoryInjection)

        vm.setSwitch(ChatSwitch.MemoryInjection, true)
        assertTrue(checkNotNull(vm.context).memoryInjection)
        until("post") { calls("/api/session/s1/memory-injection").isNotEmpty() }
        assertTrue(JSONObject(calls("/api/session/s1/memory-injection").single().body).getBoolean("enabled"))

        failSwitch = true
        vm.setSwitch(ChatSwitch.MemoryInjection, false)
        assertEquals("boom", withTimeout(5_000) { vm.notices.first() })
        until("reverted") { vm.context?.memoryInjection == true }
    }

    @Test
    fun savingGenerationSettingsSendsAllThreeAndKeepsTheAnswer() = runBlocking {
        val vm = vm()
        vm.ready()
        vm.refreshContext()
        vm.saveGeneration("on", 0.4, null)
        until("post") { calls("/api/session/s1/generation-settings").isNotEmpty() }
        val b = JSONObject(calls("/api/session/s1/generation-settings").single().body)
        assertEquals("on", b.getString("thinking_mode"))
        assertEquals(0.4, b.getDouble("temperature_override"), 0.0)
        assertTrue(b.isNull("max_tokens_override"))
    }

    @Test
    fun copyChatReadsEveryPage() = runBlocking {
        val vm = vm()
        vm.ready()
        assertEquals("", vm.transcript())
    }

    // -- the payload the view model sends ---------------------------------------

    @Test
    fun bashIsOnlySentTrueToThoseWhoMayUseIt() = runBlocking {
        for ((canUse, expected) in listOf(false to "false", true to "true")) {
            calls.clear()
            val vm = vm(canUseBash = canUse)
            vm.ready()
            vm.updateToggles(Toggles().toggleTerminal())
            vm.draft = "ls"
            vm.send()
            until("sent") { calls("/api/chat_stream").isNotEmpty() && !vm.running }
            assertEquals(expected, field(calls("/api/chat_stream").single(), "allow_bash"))
            assertEquals("agent", field(calls("/api/chat_stream").single(), "mode"))
        }
    }

    @Test
    fun bashFollowsAuthStatusThatArrivesAfterTheViewModelExists() = runBlocking {
        val vm = vm(canUseBash = false)
        vm.ready()
        vm.canUseBash = true
        vm.updateToggles(Toggles().toggleTerminal())
        vm.draft = "ls"
        vm.send()
        until("sent") { calls("/api/chat_stream").isNotEmpty() && !vm.running }
        assertEquals("true", field(calls("/api/chat_stream").single(), "allow_bash"))
    }
}
