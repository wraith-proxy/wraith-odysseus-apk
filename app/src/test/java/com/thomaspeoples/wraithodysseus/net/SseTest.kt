package com.thomaspeoples.wraithodysseus.net

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Driven by raw SSE captured from the server's own emitters (synthetic
 * content), so the parser is checked against what is really sent. */
class SseTest {

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private fun parse(text: String): List<SseEvent> {
        val response = Response.Builder()
            .request(Request.Builder().url("http://192.168.1.2/api/chat_stream").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(text.toResponseBody())
            .build()
        return runBlocking { response.sseFrames().toList() }.map(SseParser::decode)
    }

    private fun List<SseEvent>.answer() =
        filterIsInstance<SseEvent.Delta>().filterNot { it.thinking }.joinToString("") { it.text }

    @Test
    fun basicChatSplitsThinkingFromAnswer() {
        val events = parse(fixture("sse_chat_basic.txt"))
        assertEquals("Hello there!", events.answer())
        assertEquals(
            "The user wants a short greeting.",
            events.filterIsInstance<SseEvent.Delta>().filter { it.thinking }.joinToString("") { it.text },
        )
        assertEquals(
            listOf("model_info", "metrics", "message_saved"),
            events.filterIsInstance<SseEvent.Event>().map { it.type },
        )
        assertEquals(SseEvent.Done, events.last())
    }

    @Test
    fun heartbeatCommentsAreSkipped() {
        val events = parse(fixture("sse_resume_replay_heartbeat.txt"))
        assertEquals("Hello there!", events.answer())
        assertTrue(events.none { it is SseEvent.Raw })
    }

    @Test
    fun crlfAndMultiLineDataAreJoined() {
        val events = parse(fixture("sse_crlf_multiline.txt"))
        assertEquals(listOf(SseEvent.Delta("Hi", thinking = false, replacesTurn = false), SseEvent.Done), events)
    }

    @Test
    fun finalResponseIsAnEventNotADelta() {
        val events = parse(fixture("sse_agent_replacement.txt"))
        val final = events.filterIsInstance<SseEvent.Event>().single { it.type == "final_response" }
        assertEquals("Structured snapshot", final.json.getString("content"))
        assertEquals(
            listOf(false, true, false),
            events.filterIsInstance<SseEvent.Delta>().map { it.replacesTurn },
        )
    }

    @Test
    fun errorFramesCarryTheReadableText() {
        assertEquals(
            SseEvent.Error("Cannot reach llm.example.internal:8080", 503),
            parse(fixture("sse_error_upstream.txt")).filterIsInstance<SseEvent.Error>().single(),
        )
        assertEquals(
            SseEvent.Error("Rate limited by provider. Try again shortly.", 429),
            parse(fixture("sse_error_text_field.txt")).filterIsInstance<SseEvent.Error>().single(),
        )
        assertEquals(
            SseEvent.Error("Agent run failed before completion.", 500),
            parse(fixture("sse_error_run_failed.txt")).filterIsInstance<SseEvent.Error>().single(),
        )
    }

    @Test
    fun aStatusOf400OrMoreIsAnErrorEvenWithoutTheEventLine() {
        assertEquals(
            SseEvent.Error("Error 502", 502),
            SseParser.decode(SseFrame(null, """{"status": 502}""")),
        )
        assertEquals(
            SseEvent.Error("boom", 500),
            SseParser.decode(SseFrame(null, """{"status": 500, "error": {"message": "boom"}}""")),
        )
    }

    @Test
    fun nonJsonDataIsRaw() {
        assertEquals(SseEvent.Raw("plain words"), SseParser.decode(SseFrame(null, "plain words")))
    }

    @Test
    fun thinkingDeltasNeverReplaceTheAnswer() {
        val d = SseParser.decode(SseFrame(null, """{"delta":"x","thinking":true,"replacement_scope":"turn"}"""))
        assertEquals(SseEvent.Delta("x", thinking = true, replacesTurn = false), d)
    }

    @Test
    fun parserIgnoresBlankRunsAndUnknownFields() {
        val p = SseParser()
        assertNull(p.feed(""))
        assertNull(p.feed("id: 7"))
        assertNull(p.feed("retry: 1000"))
        assertNull(p.feed("event: error"))
        assertNull(p.feed("data:no-space"))
        assertEquals(SseFrame("error", "no-space"), p.feed(""))
        // The event name does not leak into the next frame.
        assertNull(p.feed("data: x"))
        assertEquals(SseFrame(null, "x"), p.feed(""))
    }

    @Test
    fun anUnterminatedLastFrameIsDropped() {
        assertEquals(listOf(SseEvent.Done), parse("data: [DONE]\n\ndata: {\"delta\":\"cut"))
    }
}
