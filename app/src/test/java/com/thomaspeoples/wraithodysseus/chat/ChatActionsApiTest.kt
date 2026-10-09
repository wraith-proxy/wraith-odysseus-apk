package com.thomaspeoples.wraithodysseus.chat

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The message-action calls: path, JSON body, parse and error of each. */
class ChatActionsApiTest {

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

    private fun sse(name: String) =
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(fixture(name))

    private fun body(r: RecordedRequest) = JSONObject(r.body.readUtf8())

    private fun failure(block: suspend () -> Unit): ApiException = runBlocking {
        try {
            block()
            fail("expected an ApiException")
            error("unreachable")
        } catch (e: ApiException) {
            e
        }
    }

    // -- truncate ----------------------------------------------------------

    @Test
    fun truncateSendsTheMessageIdAndReadsKept() = runBlocking {
        // Shape: routes/history/history_routes.py:420 {"status","kept","truncated"}.
        server.enqueue(json("truncate_ok.json"))
        assertEquals(4, api.truncate("s 1", "m-07"))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/session/s%201/truncate", r.path)
        assertTrue(checkNotNull(r.getHeader("Content-Type")).startsWith("application/json"))
        val b = body(r)
        assertEquals("m-07", b.getString("before_msg_id"))
        // Never a list index: history_routes.py:393 would read it as keep_count.
        assertTrue(!b.has("keep_count"))
    }

    @Test
    fun truncateOfAnUnknownMessageIsTheServersOwnError() {
        // history_routes.py:407 HTTPException(404, "Message not found").
        server.enqueue(json("truncate_404.json", 404))
        assertEquals("Message not found", failure { api.truncate("s1", "gone") }.message)
    }

    @Test
    fun aBlankIdSendsNothing() {
        runCatching { runBlocking { api.truncate("s1", " ") } }.also { assertTrue(it.isFailure) }
        runCatching { runBlocking { api.deleteMessages("s1", emptyList()) } }.also { assertTrue(it.isFailure) }
        runCatching { runBlocking { api.deleteMessages("s1", listOf("")) } }.also { assertTrue(it.isFailure) }
        runCatching { runBlocking { api.editMessage("s1", "", "x") } }.also { assertTrue(it.isFailure) }
        assertEquals(0, server.requestCount)
    }

    // -- delete / edit -----------------------------------------------------

    @Test
    fun deleteSendsTheIds() = runBlocking {
        // history_routes.py:518 {"status":"ok","deleted":N}.
        server.enqueue(json("delete_messages_ok.json"))
        api.deleteMessages("s1", listOf("m-01", "m-02"))
        val r = server.takeRequest()
        assertEquals("/api/session/s1/delete-messages", r.path)
        assertEquals(listOf("m-01", "m-02"), body(r).getJSONArray("msg_ids").let { a -> (0 until a.length()).map(a::getString) })
    }

    @Test
    fun deleteFailureCarriesTheStatus() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail": "database is locked"}"""))
        val e = failure { api.deleteMessages("s1", listOf("m-01")) }
        assertEquals(500, e.status)
        assertEquals("database is locked", e.message)
    }

    @Test
    fun editSendsIdAndContent() = runBlocking {
        // history_routes.py:571 {"status":"ok"}.
        server.enqueue(json("edit_message_ok.json"))
        api.editMessage("s1", "m-02", "New text\nwith lines")
        val r = server.takeRequest()
        assertEquals("/api/session/s1/edit-message", r.path)
        val b = body(r)
        assertEquals("m-02", b.getString("msg_id"))
        assertEquals("New text\nwith lines", b.getString("content"))
    }

    @Test
    fun editRefusedByTheServerIsReported() {
        // history_routes.py:536 HTTPException(400, "msg_id and content are required").
        server.enqueue(json("edit_message_400.json", 400))
        val e = failure { api.editMessage("s1", "m-02", "x") }
        assertEquals(400, e.status)
        assertEquals("msg_id and content are required", e.message)
    }

    // -- fork --------------------------------------------------------------

    @Test
    fun forkSendsKeepCountAndParsesTheNewChat() = runBlocking {
        // history_routes.py:830-835 {"status","id","name","kept"}; the name starts with U+2ADD.
        server.enqueue(json("fork_ok.json"))
        val forked = api.fork("s1", 5)
        assertEquals(ForkResult("5b0f3c1e-7a44-4d0e-9d52-2f6c1a9e0b11", "⫝ Trip planning", 5), forked)
        val r = server.takeRequest()
        assertEquals("/api/session/s1/fork", r.path)
        assertEquals(5, body(r).getInt("keep_count"))
    }

    @Test
    fun forkFailureIsReported() {
        server.enqueue(json("error_404_session_not_found_handler.json", 404))
        assertEquals(404, failure { api.fork("s1", 5) }.status)
    }

    /**
     * The server slices session.history (history_routes.py:817), which holds
     * EVERY stored row in order (core/session_manager.py:165-195), while
     * /api/history (history_routes.py:312-316) drops the hidden ones and the
     * app drops tool/system rows. This stand-in pages the same way (offset,
     * limit clamped to 1..100, hidden rows filtered after paging) over rows
     * that include hidden and tool ones.
     */
    private class Row(val id: String, val role: String, val hidden: Boolean = false)

    private fun serve(rows: List<Row>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = checkNotNull(request.requestUrl)
                val limit = url.queryParameter("limit")!!.toInt().coerceIn(1, 100)
                val offset = (url.queryParameter("offset")?.toInt() ?: maxOf(rows.size - limit, 0)).coerceIn(0, rows.size)
                val page = rows.subList(offset, minOf(offset + limit, rows.size)).filter { !it.hidden }.map {
                    JSONObject().put("role", it.role).put("content", "text ${it.id}").put("metadata", JSONObject().put("_db_id", it.id))
                }
                return MockResponse().setBody(
                    JSONObject().put("history", JSONArray(page)).put("offset", offset).put("total", rows.size).toString(),
                )
            }
        }
    }

    private fun messyRows() = (0 until 140).map { i ->
        when {
            i % 7 == 3 -> Row("r$i", "tool")
            i % 5 == 4 -> Row("r$i", if (i % 2 == 0) "user" else "assistant", hidden = true)
            i % 2 == 0 -> Row("r$i", "user")
            else -> Row("r$i", "assistant")
        }
    }

    @Test
    fun forkKeepCountIsTheRowsThroughTheMessage() = runBlocking {
        val rows = messyRows()
        serve(rows)
        val shown = rows.filter { !it.hidden && (it.role == "user" || it.role == "assistant") }
        shown.forEachIndexed { k, row ->
            // What the screen can know: the first shown row is at offset 0, and k
            // chat rows precede this one -- a lower bound on its real position.
            val expected = rows.indexOf(row) + 1
            assertEquals(row.id, expected, api.forkKeepCount("s1", row.id, lowerBound = k))
        }
    }

    @Test
    fun forkKeepCountWorksFromAnyLowerBoundThatIsNotPastTheMessage() = runBlocking {
        val rows = messyRows()
        serve(rows)
        val target = rows[120].also { check(!it.hidden && it.role != "tool") }
        for (lower in listOf(120, 100, 77, 21)) {
            assertEquals(121, api.forkKeepCount("s1", target.id, lower))
        }
    }

    @Test
    fun forkKeepCountSaysUnknownWhenTheMessageIsOutOfReach() = runBlocking {
        val rows = messyRows()
        serve(rows)
        val target = rows[130].also { check(!it.hidden && it.role != "tool") }
        // 130 rows past the bound: more than one request can see.
        assertNull(api.forkKeepCount("s1", target.id, lowerBound = 0))
        // Past the message: it is not in the window either.
        assertNull(api.forkKeepCount("s1", target.id, lowerBound = 135))
    }

    // -- rewrite -----------------------------------------------------------

    @Test
    fun rewriteStreamsTextAndSkipsReasoning() = runBlocking {
        // Frames: src/llm_core.py:3721-3743 ({"delta"} / {"delta","thinking":true}) forwarded
        // by routes/chat_routes.py:5080-5095.
        server.enqueue(sse("sse_rewrite.txt"))
        val seen = mutableListOf<String>()
        val text = api.rewrite("s1", "Grey things: pebble, ash, smoke.", "Be shorter") { seen += it }
        assertEquals("Grey things: pebble, ash.", text)
        assertEquals(listOf("Grey things:", "Grey things: pebble, ash."), seen)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/rewrite", r.path)
        val b = body(r)
        assertEquals("s1", b.getString("session_id"))
        assertEquals("Grey things: pebble, ash, smoke.", b.getString("original_text"))
        assertEquals("Be shorter", b.getString("instruction"))
    }

    @Test
    fun rewriteStripsInlineThinkTags() = runBlocking {
        server.enqueue(sse("sse_rewrite_think_tags.txt"))
        assertEquals("Pebble, ash.", api.rewrite("s1", "x", "y") {})
    }

    @Test
    fun rewriteErrorFrameBecomesAnException() {
        // routes/chat_routes.py:5129 `event: error` / {"error","status"}.
        server.enqueue(sse("sse_rewrite_error.txt"))
        val e = failure { api.rewrite("s1", "x", "y") {} }
        assertEquals(500, e.status)
        assertEquals("Cannot reach llm.example.internal:8080", e.message)
    }

    @Test
    fun rewriteRefusedUpFrontIsReported() {
        // routes/chat_routes.py:5040 HTTPException(400, "...are required").
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail": "session_id, original_text, and instruction are required"}"""))
        assertEquals("session_id, original_text, and instruction are required", failure { api.rewrite("s1", "x", "y") {} }.message)
    }

    @Test
    fun anEmptyRewriteIsAFailureNotABlankReply() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
        assertNotNull(failure { api.rewrite("s1", "x", "y") {} }.message)
    }
}
