package com.thomaspeoples.wraithodysseus.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.chat.HistoryMessage
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** The view model's message actions against a stand-in for the server. */
class ChatMessageActionsTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private class Row(val id: String, val role: String, var content: String, val hidden: Boolean = false)

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private val server = MockWebServer()

    /** Every stored row, hidden and tool ones too (session_manager.py:165-195). */
    private val rows = Collections.synchronizedList(
        mutableListOf(
            Row("m-1", "user", "Q1"),
            Row("t-1", "tool", "ls output"),
            Row("m-2", "assistant", "A1"),
            Row("m-3", "user", "Q2"),
            Row("h-1", "assistant", "compaction summary", hidden = true),
            Row("m-4", "assistant", "A2"),
        ),
    )
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val bodies: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())

    /** Overrides for single endpoints. */
    @Volatile private var truncate: MockResponse? = null
    @Volatile private var chatStream: MockResponse? = null

    private fun reply(r: MockResponse?, otherwise: () -> MockResponse) = r ?: otherwise()

    private val odysseus = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = checkNotNull(request.requestUrl)
            val path = url.encodedPath
            val body = request.body.readUtf8()
            if (path.startsWith("/api/session") || path == "/api/rewrite" || path == "/api/chat_stream") {
                requests += path
                bodies[path] = body
            }
            return when {
                path == "/api/models" -> MockResponse().setBody("""{"items":[]}""")
                path.startsWith("/api/chat/resume") -> MockResponse().setResponseCode(404).setBody(fixture("chat_resume_404.json"))
                path == "/api/chat_stream" -> reply(chatStream) {
                    MockResponse().setHeader("Content-Type", "text/event-stream").setHeader("X-Odysseus-Run-Id", "run-1")
                        .setBody(fixture("sse_chat_basic.txt"))
                }
                path.endsWith("/truncate") -> reply(truncate) {
                    val id = JSONObject(body).getString("before_msg_id")
                    val at = rows.indexOfFirst { it.id == id }
                    if (at < 0) {
                        MockResponse().setResponseCode(404).setBody(fixture("truncate_404.json"))
                    } else {
                        while (rows.size > at) rows.removeAt(rows.size - 1)
                        MockResponse().setBody(fixture("truncate_ok.json"))
                    }
                }
                path.endsWith("/delete-messages") -> {
                    val ids = JSONObject(body).getJSONArray("msg_ids").let { a -> (0 until a.length()).map(a::getString) }
                    rows.removeAll { it.id in ids }
                    MockResponse().setBody(fixture("delete_messages_ok.json"))
                }
                path.endsWith("/edit-message") -> {
                    val b = JSONObject(body)
                    rows.first { it.id == b.getString("msg_id") }.content = b.getString("content")
                    MockResponse().setBody(fixture("edit_message_ok.json"))
                }
                path.endsWith("/fork") -> MockResponse().setBody(fixture("fork_ok.json"))
                path == "/api/rewrite" -> {
                    // chat_routes.py:5096-5121: the last assistant row takes the new text.
                    rows.last { it.role == "assistant" && !it.hidden }.content = "Grey things: pebble, ash."
                    MockResponse().setHeader("Content-Type", "text/event-stream").setBody(fixture("sse_rewrite.txt"))
                }
                path.startsWith("/api/history/") -> history(url)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun history(url: HttpUrl): MockResponse {
        val limit = url.queryParameter("limit")!!.toInt().coerceIn(1, 100)
        val snapshot = rows.toList()
        val offset = (url.queryParameter("offset")?.toInt() ?: maxOf(snapshot.size - limit, 0)).coerceIn(0, snapshot.size)
        val page = snapshot.subList(offset, minOf(offset + limit, snapshot.size)).filter { !it.hidden }.map {
            JSONObject().put("role", it.role).put("content", it.content).put("metadata", JSONObject().put("_db_id", it.id))
        }
        return MockResponse().setBody(
            JSONObject().put("history", JSONArray(page)).put("offset", offset).put("total", snapshot.size)
                .put("has_more_before", offset > 0).toString(),
        )
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

    /** Until nothing is left running, including jobs a finishing job launched. */
    private suspend fun CoroutineScope.settle() {
        while (coroutineContext.job.children.any { it.isActive }) coroutineContext.job.children.toList().forEach { it.join() }
    }

    private fun ChatViewModel.msg(id: String) = messages.first { it.dbId == id }

    private suspend fun ChatViewModel.notice() = withTimeout(2_000) { notices.first() }

    private fun actionRequests() = requests.filter { it != "/api/models" }

    // -- delete ------------------------------------------------------------

    @Test
    fun deletingAUserMessageTakesItsReplyAndNothingMore() = runBlocking {
        val vm = vm()
        settle()
        assertEquals(listOf("m-1", "m-2", "m-3", "m-4"), vm.messages.map { it.dbId })

        vm.delete(vm.msg("m-1"))
        settle()

        // chat.js:7593: the user message and the reply that follows it.
        assertEquals(JSONObject("""{"msg_ids":["m-1","m-2"]}""").toString(), JSONObject(bodies.getValue("/api/session/s1/delete-messages")).toString())
        assertEquals(listOf("m-3", "m-4"), vm.messages.map { it.dbId })
        assertEquals("Message deleted", vm.notice())
        assertFalse(vm.running)
    }

    @Test
    fun deletingAReplyTakesTheMessageItAnswers() = runBlocking {
        val vm = vm()
        settle()
        assertEquals(listOf("m-4", "m-3"), vm.deletionOf(vm.msg("m-4")).map { it.dbId })
        vm.delete(vm.msg("m-4"))
        settle()
        assertEquals(listOf("m-1", "m-2"), vm.messages.map { it.dbId })
        // Only the two ids are deleted; the tool and hidden rows stay.
        assertEquals(listOf("m-1", "t-1", "m-2", "h-1"), rows.map { it.id })
    }

    // -- edit / resend / regenerate ---------------------------------------

    @Test
    fun editingAUserMessageCutsBeforeItThenSendsTheEditedText() = runBlocking {
        val vm = vm()
        settle()
        vm.edit(vm.msg("m-3"), "  Q2 but better  ")
        settle()

        assertEquals(listOf("/api/session/s1/truncate", "/api/chat_stream"), actionRequests())
        assertEquals("m-3", JSONObject(bodies.getValue("/api/session/s1/truncate")).getString("before_msg_id"))
        assertTrue(bodies.getValue("/api/chat_stream").contains("Q2 but better"))
        assertFalse(vm.running)
        // The run's end reloaded the history: m-3 and what followed are gone from the server.
        assertEquals(listOf("m-1", "m-2"), vm.messages.map { it.dbId })
    }

    @Test
    fun resendAndRegenerateCutBeforeTheQuestion() = runBlocking {
        val vm = vm()
        settle()
        vm.regenerate(vm.msg("m-4"))
        settle()
        assertEquals("m-3", JSONObject(bodies.getValue("/api/session/s1/truncate")).getString("before_msg_id"))
        assertTrue(bodies.getValue("/api/chat_stream").contains("Q2"))

        requests.clear()
        vm.resend(vm.msg("m-1"))
        settle()
        assertEquals("m-1", JSONObject(bodies.getValue("/api/session/s1/truncate")).getString("before_msg_id"))
        assertEquals(listOf("/api/session/s1/truncate", "/api/chat_stream"), actionRequests())
    }

    @Test
    fun aRefusedCutSendsNothingAndLeavesTheChatAlone() = runBlocking {
        val vm = vm()
        settle()
        truncate = MockResponse().setResponseCode(404).setBody(fixture("truncate_404.json"))
        val before = vm.messages
        vm.edit(vm.msg("m-3"), "x")
        settle()

        assertEquals(listOf("/api/session/s1/truncate"), actionRequests())
        assertEquals("Message not found", vm.notice())
        assertEquals(before, vm.messages)
        assertNull(vm.error)
        assertFalse(vm.running)
    }

    @Test
    fun aSendRefusedAfterTheCutPutsTheTextBackInTheComposer() = runBlocking {
        val vm = vm()
        settle()
        chatStream = MockResponse().setResponseCode(429).setBody(fixture("chat_stream_429_quota.json"))
        vm.edit(vm.msg("m-3"), "Q2 again")
        settle()
        assertEquals("Q2 again", vm.draft)
        assertEquals(listOf("m-1", "m-2"), vm.messages.map { it.dbId })
    }

    @Test
    fun editingAReplySavesItAndUpdatesItInPlace() = runBlocking {
        val vm = vm()
        settle()
        val a = vm.msg("m-2")
        assertEquals("A1", vm.visibleForEdit(a))
        vm.edit(a, "A1 corrected")
        settle()

        val b = JSONObject(bodies.getValue("/api/session/s1/edit-message"))
        assertEquals("m-2", b.getString("msg_id"))
        assertEquals("A1 corrected", b.getString("content"))
        assertEquals("A1 corrected", vm.msg("m-2").content)
        assertEquals(listOf("m-1", "m-2", "m-3", "m-4"), vm.messages.map { it.dbId })
        // Unchanged text is not sent again.
        requests.clear()
        vm.edit(vm.msg("m-2"), "A1 corrected")
        settle()
        assertEquals(emptyList<String>(), actionRequests())
    }

    // -- fork --------------------------------------------------------------

    @Test
    fun forkKeepsEveryStoredRowThroughTheMessageHiddenAndToolOnesIncluded() = runBlocking {
        val vm = vm()
        settle()
        var opened: String? = null
        // m-2 is stored row 2 (after m-1 and the tool row): keep_count 3.
        vm.fork(vm.msg("m-2")) { opened = it }
        settle()
        assertEquals(3, JSONObject(bodies.getValue("/api/session/s1/fork")).getInt("keep_count"))
        assertEquals("5b0f3c1e-7a44-4d0e-9d52-2f6c1a9e0b11", opened)

        // m-3 is stored row 3, and the hidden row after it must not shift anything.
        vm.fork(vm.msg("m-3")) {}
        settle()
        assertEquals(4, JSONObject(bodies.getValue("/api/session/s1/fork")).getInt("keep_count"))
    }

    @Test
    fun forkOfTheLastReplyKeepsTheWholeHistory() = runBlocking {
        val vm = vm()
        settle()
        vm.fork(vm.msg("m-4")) {}
        settle()
        assertEquals(Int.MAX_VALUE, JSONObject(bodies.getValue("/api/session/s1/fork")).getInt("keep_count"))
    }

    // -- rewrite -----------------------------------------------------------

    @Test
    fun rewriteIsForTheLastReplyOnlyAndSavesOverIt() = runBlocking {
        val vm = vm()
        settle()
        assertFalse(vm.isLastReply(vm.msg("m-2")))
        assertTrue(vm.isLastReply(vm.msg("m-4")))

        vm.rewrite(vm.msg("m-2"), "shorter")
        settle()
        assertEquals(emptyList<String>(), actionRequests())

        vm.rewrite(vm.msg("m-4"), "shorter")
        settle()
        val b = JSONObject(bodies.getValue("/api/rewrite"))
        assertEquals("A2", b.getString("original_text"))
        assertEquals("shorter", b.getString("instruction"))
        assertEquals("Grey things: pebble, ash.", vm.msg("m-4").content)
        assertNull(vm.rewriting)
        assertFalse(vm.running)
    }

    // -- guards ------------------------------------------------------------

    @Test
    fun nothingRunsWhileARunIsGoing() = runBlocking {
        val vm = vm()
        settle()
        vm.draft = "hi"
        vm.send()
        assertTrue(vm.running)
        requests.clear()
        val a = vm.msg("m-4")
        vm.delete(a)
        vm.edit(vm.msg("m-3"), "x")
        vm.resend(vm.msg("m-3"))
        vm.regenerate(a)
        vm.rewrite(a, "shorter")
        vm.fork(a) {}
        settle()
        assertFalse(requests.any { it.endsWith("/truncate") || it.endsWith("/delete-messages") || it.endsWith("/fork") || it == "/api/rewrite" })
    }

    @Test
    fun aMessageWithoutAnIdSendsNothing() = runBlocking {
        val vm = vm()
        settle()
        val unsaved = HistoryMessage("assistant", "just streamed", null, null, emptyList())
        vm.delete(unsaved)
        vm.edit(unsaved, "x")
        vm.resend(unsaved)
        vm.regenerate(unsaved)
        vm.rewrite(unsaved, "shorter")
        vm.fork(unsaved) {}
        settle()
        assertEquals(emptyList<String>(), actionRequests())
    }
}
