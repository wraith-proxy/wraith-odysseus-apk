package com.thomaspeoples.wraithodysseus.email

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * POST /api/email/ai-reply against a fake server. The SSE fixtures follow the
 * frames written by routes/email/email_routes.py:6204-6220 (`data: <json>`,
 * blank line, no heartbeat, no [DONE]).
 */
class AiReplyApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private val server = MockWebServer()
    private lateinit var api: EmailApi

    private val request = AiReplyRequest("sam@example.com", "Re: Thursday", "Can you do Thursday?", "<m1@x>", "42", "INBOX", "acc1")

    @Before
    fun setUp() {
        server.start()
        api = EmailApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun stream(name: String) = server.enqueue(
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(fixture(name)),
    )

    private fun generate(r: AiReplyRequest = request): Pair<AiReplyOutcome, List<String>> = runBlocking {
        val previews = mutableListOf<String>()
        api.aiReply(r) { previews += it } to previews
    }

    @Test
    fun sendsExactlyTheWebRequest() {
        stream("email_ai_reply_stream_ok.sse")
        generate()
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/email/ai-reply", recorded.path)
        assertEquals("odysseus_session=abc123", recorded.getHeader("Cookie"))
        val json = JSONObject(recorded.body.readUtf8())
        assertEquals(
            setOf("to", "subject", "original_body", "message_id", "uid", "folder", "account_id", "stream", "fast"),
            json.keys().asSequence().toSet(),
        )
        assertEquals(true, json.get("stream"))
        assertEquals(true, json.get("fast"))
        assertEquals("sam@example.com", json.getString("to"))
        assertEquals("Can you do Thursday?", json.getString("original_body"))
        assertFalse(json.has("model"))
        assertFalse(json.has("session_id"))
        assertFalse(json.has("user_hint"))
    }

    @Test
    fun sendsTheHintWhenGiven() {
        stream("email_ai_reply_stream_ok.sse")
        generate(request.copy(userHint = "Say yes, but afternoon only"))
        assertEquals("Say yes, but afternoon only", JSONObject(server.takeRequest().body.readUtf8()).getString("user_hint"))
    }

    @Test
    fun streamedSuccessEndsWithTheResultText() {
        stream("email_ai_reply_stream_ok.sse")
        val (outcome, previews) = generate()
        assertEquals(listOf("Hi Sam,", "Hi Sam,\n\nThursday works", "Hi Sam,\n\nThursday works for me."), previews)
        assertEquals(AiReplyOutcome.Reply("Hi Sam,\n\nThursday works for me.\n\nThomas", "arthur:latest"), outcome)
    }

    @Test
    fun resetFrameClearsThePreview() {
        stream("email_ai_reply_stream_reset.sse")
        val (outcome, previews) = generate()
        assertEquals(listOf("Dear", "", "Hi Sam, yes."), previews)
        assertEquals("Hi Sam, yes, that is fine with me.", (outcome as AiReplyOutcome.Reply).text)
    }

    @Test
    fun failureResultKeepsTheServerSentence() {
        stream("email_ai_reply_stream_failed.sse")
        val (outcome, _) = generate()
        assertEquals(
            AiReplyOutcome.Failed(
                "AI reply failed (arthur:latest@host.docker.internal:11434): " +
                    "Model returned analysis or an incomplete reply, not a finished email",
            ),
            outcome,
        )
    }

    @Test
    fun emptyBodyResult() {
        stream("email_ai_reply_stream_no_body.sse")
        assertEquals(AiReplyOutcome.Failed("No email body provided"), generate(request.copy(originalBody = "")).first)
    }

    @Test
    fun streamWithoutResultIsAFailure() {
        server.enqueue(MockResponse().setBody("data: {\"type\": \"reply\", \"text\": \"Hi\"}\n\n"))
        val (outcome, previews) = generate()
        assertEquals(listOf("Hi"), previews)
        assertTrue(outcome is AiReplyOutcome.Failed)
    }

    @Test
    fun http401IsAnApiException() {
        server.enqueue(MockResponse().setResponseCode(401).setBody(fixture("error_401_route_not_authenticated.json")))
        val e = runCatching { generate() }.exceptionOrNull() as ApiException
        assertEquals(401, e.status)
    }

    @Test
    fun http504IsAnApiExceptionWithTheServerText() {
        server.enqueue(MockResponse().setResponseCode(504).setBody(fixture("error_504_timeout.json")))
        val e = runCatching { generate() }.exceptionOrNull() as ApiException
        assertEquals(504, e.status)
        assertEquals("Request exceeded 45s timeout", e.message)
    }
}
