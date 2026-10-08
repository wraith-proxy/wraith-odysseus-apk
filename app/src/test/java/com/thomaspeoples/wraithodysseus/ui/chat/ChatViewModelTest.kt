package com.thomaspeoples.wraithodysseus.ui.chat

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.lifecycle.SavedStateHandle
import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.chat.ModelChoice
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
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
            }
            historyPaths += checkNotNull(request.path)
            val limit = url.queryParameter("limit")!!.toInt()
            val asked = url.queryParameter("offset")?.toInt()
            if (asked != null) olderGate.await(10, TimeUnit.SECONDS)
            val total = total
            val offset = asked ?: maxOf(total - limit, 0)
            val rows = (offset until minOf(offset + limit, total)).map { i ->
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

    private fun CoroutineScope.vm() = ChatViewModel(
        api = ChatApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false)))),
        sessionId = "s1",
        saved = SavedStateHandle(),
        scope = this,
    )

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
}
