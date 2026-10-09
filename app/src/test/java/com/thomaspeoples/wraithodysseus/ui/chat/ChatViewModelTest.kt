package com.thomaspeoples.wraithodysseus.ui.chat

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.lifecycle.SavedStateHandle
import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.chat.ModelChoice
import com.thomaspeoples.wraithodysseus.chat.Speech
import com.thomaspeoples.wraithodysseus.chat.SpeechOutput
import com.thomaspeoples.wraithodysseus.chat.Toggles
import com.thomaspeoples.wraithodysseus.chat.UploadedFile
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatViewModelTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private val server = MockWebServer()
    private val historyPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Rows in the session; a finished run adds to it. */
    @Volatile private var total = 80
    /** What POST /api/chat_stream answers. */
    @Volatile private var chatStream = MockResponse().setResponseCode(500)
    /** Held shut, an older-page request waits on it. */
    @Volatile private var olderGate = CountDownLatch(0)
    /** Rows deleted from the session, e.g. from the web UI. */
    @Volatile private var deleted = emptySet<Int>()
    /** What GET /api/chat/resume answers, once [resumeGate] opens. */
    @Volatile private var resume = MockResponse().setResponseCode(404)
    @Volatile private var resumeGate = CountDownLatch(0)

    /** What GET /api/research/chat-jobs answers, one entry per poll; the last repeats. */
    private val jobAnswers: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val jobPolls = java.util.concurrent.atomic.AtomicInteger()
    /** Delivering a job saves a reply: the rows grow when this poll count is reached. */
    @Volatile private var deliverAtPoll = Int.MAX_VALUE
    @Volatile private var ttsStats = """{"available":true,"ready":true,"provider":"local"}"""
    @Volatile private var synthesize = MockResponse().setHeader("Content-Type", "audio/mpeg").setBody("ID3")
    private val synthBodies: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    /** The server's own paging over [total] rows: no offset means the last
     * page, and a page is rows [offset, offset + limit). */
    private val odysseus = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = checkNotNull(request.requestUrl)
            when (url.encodedPath) {
                "/api/models" -> return MockResponse().setBody("""{"items":[]}""")
                "/api/upload" -> return MockResponse().setBody(fixture("upload.json"))
                "/api/chat_stream" -> return chatStream
                "/api/research/chat-jobs/s1" -> {
                    val n = jobPolls.incrementAndGet()
                    if (n >= deliverAtPoll) total = 82
                    return MockResponse().setBody(if (jobAnswers.size > 1) jobAnswers.removeAt(0) else jobAnswers.firstOrNull() ?: """{"jobs":[]}""")
                }
                "/api/tts/stats" -> return MockResponse().setBody(ttsStats)
                "/api/tts/synthesize" -> {
                    synthBodies += request.body.readUtf8()
                    return synthesize
                }
                "/api/chat/resume/s1" -> {
                    resumeGate.await(10, TimeUnit.SECONDS)
                    return resume
                }
            }
            historyPaths += checkNotNull(request.path)
            val limit = url.queryParameter("limit")!!.toInt()
            val asked = url.queryParameter("offset")?.toInt()
            if (asked != null) olderGate.await(10, TimeUnit.SECONDS)
            val ids = (0 until total).filter { it !in deleted }
            val total = ids.size
            val offset = asked ?: maxOf(total - limit, 0)
            val rows = ids.subList(offset, minOf(offset + limit, total)).map { i ->
                JSONObject()
                    .put("role", if (i % 2 == 0) "user" else "assistant")
                    .put("content", "message $i")
                    .put("metadata", JSONObject().put("_db_id", "m-$i"))
            }
            val body = JSONObject()
                .put("history", JSONArray(rows))
                .put("offset", offset)
                .put("total", total)
                .put("has_more_before", offset > 0)
            return MockResponse().setBody(body.toString())
        }
    }

    @Before
    fun setUp() {
        server.dispatcher = odysseus
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun CoroutineScope.vm(speech: SpeechOutput? = null) = ChatViewModel(
        api = ChatApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false)))),
        sessionId = "s1",
        saved = SavedStateHandle(),
        scope = this,
        speech = speech,
        jobPollMs = 10,
    )

    private class FakeSpeech : SpeechOutput {
        val played = mutableListOf<Speech>()
        var stops = 0
        var ended: (() -> Unit)? = null
        override suspend fun play(speech: Speech, onEnd: () -> Unit) {
            played += speech
            ended = onEnd
        }
        override fun stop() {
            stops++
        }
    }

    private fun job(status: String, extra: String = "") =
        """{"jobs":[{"id":"job1","tool":"research","query":"cats","status":"$status","rounds":2$extra}]}"""


    private suspend fun CoroutineScope.settle() = coroutineContext.job.children.forEach { it.join() }

    @Test
    fun loadingOlderNeverRepeatsARowEvenWhenTheLastPageIsShort() = runBlocking {
        val vm = vm()
        settle()
        // 80 rows: the last page is rows 30..79.
        assertEquals((30 until 80).map { "m-$it" }, vm.messages.map { it.dbId })
        assertTrue(vm.hasOlder)

        vm.loadOlder()
        vm.loadOlder() // a double tap
        settle()

        assertEquals((0 until 80).map { "m-$it" }, vm.messages.map { it.dbId })
        assertFalse(vm.hasOlder)
        // Exactly the gap, asked for once.
        assertEquals(listOf("/api/history/s1?limit=50", "/api/history/s1?limit=30&offset=0"), historyPaths)
    }

    @Test
    fun aReloadDuringLoadOlderLeavesNoGap() = runBlocking {
        val vm = vm()
        settle()
        olderGate = CountDownLatch(1)
        vm.loadOlder() // asks for rows 0..29, and waits
        // Meanwhile a run saves two rows and its end reloads the last page.
        total = 82
        chatStream = MockResponse().setHeader("Content-Type", "text/event-stream")
            .setHeader("X-Odysseus-Run-Id", "run-1").setBody(fixture("sse_chat_basic.txt"))
        vm.draft = "hi"
        vm.send()
        while (vm.running) delay(10)
        assertEquals((32 until 82).map { "m-$it" }, vm.messages.map { it.dbId })
        olderGate.countDown()
        settle()

        // The stale page is not glued on in front of rows 30 and 31's gap.
        assertEquals((32 until 82).map { "m-$it" }, vm.messages.map { it.dbId })
        vm.loadOlder()
        settle()
        assertEquals((0 until 82).map { "m-$it" }, vm.messages.map { it.dbId })
    }

    @Test
    fun loadingOlderAfterAnEarlierRowIsDeletedSkipsRowsAlreadyShown() = runBlocking {
        val vm = vm()
        settle()
        // Deleted elsewhere: row 30 slides back into the older page.
        deleted = setOf(5)
        vm.loadOlder()
        settle()
        val ids = vm.messages.map { it.dbId }
        assertEquals(((0 until 80) - 5).map { "m-$it" }, ids)
    }

    @Test
    fun aResumeProbeLeavesTheScreenAloneUntilARunTurnsUp() = runBlocking {
        val vm = vm()
        settle()
        vm.error = "earlier"
        resumeGate = CountDownLatch(1)
        vm.attach()
        delay(50)
        assertFalse(vm.running)
        assertNull(vm.live)
        assertEquals("earlier", vm.error)
        resumeGate.countDown()
        settle()
        assertFalse(vm.running)
        assertEquals("earlier", vm.error)
    }

    @Test
    fun aPendingApprovalSurvivesFailuresThatSentNothing() = runBlocking {
        val vm = vm()
        settle()
        chatStream = MockResponse().setHeader("Content-Type", "text/event-stream")
            .setHeader("X-Odysseus-Run-Id", "run-1").setBody(fixture("sse_tool_approval.txt"))
        vm.draft = "email carol"
        vm.send()
        settle()
        val gate = checkNotNull(vm.ask)
        assertEquals("apr_5f1c0d9e2b7a4c3d", gate.approvalId)

        // A resume that cannot reach the server.
        resume = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        vm.attach()
        settle()
        assertEquals("Could not reach the server", vm.error)
        assertEquals(gate, vm.ask)

        // An approval whose re-POST never got through can be tried again.
        chatStream = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        vm.decide(gate.options.first())
        settle()
        assertEquals("Could not reach the server", vm.error)
        assertEquals(gate, vm.ask)

        // An expired one cannot.
        chatStream = MockResponse().setResponseCode(409).setBody(fixture("chat_stream_409_approval.json"))
        vm.decide(gate.options.first())
        settle()
        assertEquals("Approval expired", vm.error)
        assertNull(vm.ask)
    }

    @Test
    fun aSendRefusedBeforeTheStreamOpensGoesBackIntoTheComposer() = runBlocking {
        val vm = vm()
        settle()
        chatStream = MockResponse().setResponseCode(429).setBody(fixture("chat_stream_429_quota.json"))
        vm.attachFile { Triple("cat.png", "image/png", ByteArray(4)) }
        settle()
        val files = vm.attachments
        assertEquals(listOf("f-9a8b7c6d5e4f"), files.map { it.id })
        val shown = vm.messages
        val reloads = historyPaths.size

        vm.draft = "  hello  "
        vm.send()
        assertEquals("", vm.draft)
        settle()

        assertEquals("Daily message limit reached (50). Try again in 24 hours.", vm.error)
        assertEquals("hello", vm.draft)
        assertEquals(files, vm.attachments)
        assertEquals(shown, vm.messages)
        assertEquals(reloads, historyPaths.size)
        assertFalse(vm.running)
    }

    @Test
    fun anUnreadableFileIsReportedNotDropped() = runBlocking {
        val vm = vm()
        settle()
        vm.attachFile { null }
        settle()
        assertEquals("Could not read that file", vm.error)
        assertEquals(emptyList<UploadedFile>(), vm.attachments)
        assertFalse(vm.uploading)
    }

    /** Null when nothing was saved, which brings back the default on restore. */
    private fun <T> roundTrip(saver: Saver<T, Any>, value: T): T? =
        with(saver) { SaverScope { true }.save(value) }?.let(saver::restore)

    @Test
    fun draftStateSurvivesASaveAndRestore() {
        val toggles = Toggles(agent = true, web = true, terminal = false, research = false)
        assertEquals(toggles, roundTrip(ChatViewModel.TogglesSaver, toggles))

        val model = ModelChoice("ep-1", "Local", "qwen3-30b-a3b", "Qwen3 30B")
        assertEquals(model, roundTrip(ChatViewModel.ModelSaver, model))
        assertNull(roundTrip(ChatViewModel.ModelSaver, null))

        val files = listOf(UploadedFile("f-1", "cat.png", "image/png"), UploadedFile("f-2", "notes.txt", "text/plain"))
        assertEquals(files, roundTrip(ChatViewModel.FilesSaver, files))
        assertNull(roundTrip(ChatViewModel.FilesSaver, emptyList()))
    }

    // -- background replies -------------------------------------------------

    @Test
    fun aRunningJobIsPolledUntilItIsDeliveredThenTheReplyIsLoaded() = runBlocking {
        val vm = vm()
        settle()
        assertEquals("m-79", vm.messages.last().dbId)
        jobAnswers += job("running", ""","progress":{"phase":"searching","round":1,"total_sources":2}""")
        jobAnswers += job("running")
        jobAnswers += job("ready")
        jobAnswers += job("delivered", ""","source_count":5,"outcome":"complete"""")
        deliverAtPoll = 4
        val reloads = historyPaths.size

        vm.onResume()
        // The first resume does not reload: opening the chat just did.
        settle()

        assertEquals(4, jobPolls.get())
        // Polling stopped by itself: nothing is running any more.
        delay(60)
        assertEquals(4, jobPolls.get())
        assertEquals("job1", vm.jobs.single().id)
        assertEquals("delivered", vm.jobs.single().status)
        // One reload, after the job left running/ready, brought the reply in.
        assertEquals(reloads + 1, historyPaths.size)
        assertEquals("m-81", vm.messages.last().dbId)
    }

    @Test
    fun aJobStartedByAMessageSentHereIsPolledAndItsReplyLoaded() = runBlocking {
        val vm = vm()
        settle()
        // Not resumed again: only the send's run end can start the watch.
        chatStream = MockResponse().setHeader("Content-Type", "text/event-stream")
            .setHeader("X-Odysseus-Run-Id", "run-1").setBody(fixture("sse_chat_basic.txt"))
        jobAnswers += job("running")
        jobAnswers += job("delivered", ""","source_count":5,"outcome":"complete"""")
        deliverAtPoll = 2
        vm.draft = "research x"
        vm.send()
        while (vm.running) delay(10)
        settle()
        delay(60)
        assertEquals(2, jobPolls.get())
        assertEquals("delivered", vm.jobs.single().status)
        assertEquals("m-81", vm.messages.last().dbId)
    }

    @Test
    fun noJobsMeansOneAskAndNoReload() = runBlocking {
        val vm = vm()
        settle()
        val reloads = historyPaths.size
        vm.onResume()
        settle()
        assertEquals(1, jobPolls.get())
        assertEquals(reloads, historyPaths.size)
        assertTrue(vm.jobs.isEmpty())
    }

    @Test
    fun leavingTheScreenStopsThePolling() = runBlocking {
        val vm = vm()
        settle()
        jobAnswers += job("running")
        vm.onResume()
        delay(80)
        assertTrue(jobPolls.get() >= 2)
        vm.onPause()
        settle()
        val polls = jobPolls.get()
        delay(80)
        assertEquals(polls, jobPolls.get())
        // Coming back asks again.
        vm.onResume()
        delay(40)
        assertTrue(jobPolls.get() > polls)
        vm.onPause()
        settle()
    }

    @Test
    fun aDiscardedJobIsNotShownOrWaitedFor() = runBlocking {
        val vm = vm()
        settle()
        jobAnswers += job("discarded")
        vm.onResume()
        settle()
        assertEquals(1, jobPolls.get())
        assertTrue(vm.jobs.isEmpty())
    }

    @Test
    fun resumingBringsInNewRowsWithoutLosingEarlierPages() = runBlocking {
        val vm = vm()
        settle()
        vm.loadOlder()
        settle()
        assertEquals((0 until 80).map { "m-$it" }, vm.messages.map { it.dbId })
        vm.onResume()
        vm.onPause()
        settle()
        // A background reply was saved meanwhile.
        total = 82
        vm.onResume()
        settle()
        assertEquals((0 until 82).map { "m-$it" }, vm.messages.map { it.dbId })
        assertFalse(vm.hasOlder)
        vm.onPause()
    }

    // -- read aloud ----------------------------------------------------------

    @Test
    fun readAloudSendsTheVisibleTextAndTappingAgainStops() = runBlocking {
        val speech = FakeSpeech()
        val vm = vm(speech)
        settle()
        assertTrue(vm.ttsEnabled)
        val reply = vm.messages.last()

        vm.readAloud(reply)
        settle()
        assertEquals(1, speech.played.size)
        assertEquals("ID3", String(speech.played.single().bytes))
        assertEquals("audio/mpeg", speech.played.single().mime)
        assertEquals("message 79", JSONObject(synthBodies.single()).getString("text"))
        assertEquals("m-79", vm.speaking)

        // Tapping again while it plays stops it.
        val stops = speech.stops
        vm.readAloud(reply)
        assertNull(vm.speaking)
        assertEquals(stops + 1, speech.stops)

        // It ends by itself when the audio does.
        vm.readAloud(reply)
        settle()
        assertEquals("m-79", vm.speaking)
        speech.ended!!.invoke()
        assertNull(vm.speaking)
    }

    @Test
    fun readAloudNeedsAnEnabledServerAndReportsFailure() = runBlocking {
        ttsStats = """{"available":false,"ready":false,"provider":"local"}"""
        val off = vm(FakeSpeech())
        settle()
        assertFalse(off.ttsEnabled)
        assertFalse(vm().ttsEnabled)
        settle()

        ttsStats = """{"available":true,"ready":true,"provider":"local"}"""
        synthesize = MockResponse().setResponseCode(503).setBody("""{"detail":{"message":"TTS service not available"}}""")
        val speech = FakeSpeech()
        val vm = vm(speech)
        settle()
        vm.readAloud(vm.messages.last())
        settle()
        assertEquals("TTS service not available", withTimeout(2_000) { vm.notices.first() })
        assertNull(vm.speaking)
        assertTrue(speech.played.isEmpty())
    }
}
