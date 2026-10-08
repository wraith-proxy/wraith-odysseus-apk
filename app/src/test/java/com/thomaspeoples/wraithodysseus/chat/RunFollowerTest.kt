package com.thomaspeoples.wraithodysseus.chat

import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RunFollowerTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private val server = MockWebServer()
    private lateinit var api: ChatApi
    private val waits = mutableListOf<Long>()

    @Before
    fun setUp() {
        server.start()
        api = ChatApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun follower() = RunFollower(api, "s1") { waits += it }

    private fun sse(body: String, runId: String) =
        MockResponse().setHeader("Content-Type", "text/event-stream").setHeader("X-Odysseus-Run-Id", runId).setBody(body)

    private fun send(): RunStream = runBlocking {
        api.chatStream(ChatRequest("s1", "hi", Toggles(), null, emptyList(), null))
    }

    /** The first "Hello" of the replay fixture, then the connection dies. */
    private fun dropped() = sse(
        "data: {\"delta\": \"Hello\"}\n\n" + "data: {\"delta\": \" there\"}\n\n".repeat(50),
        "run-1",
    ).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)

    @Test
    fun replayAfterADropIsNotDoubled() = runBlocking {
        server.enqueue(dropped())
        server.enqueue(sse(fixture("sse_resume_replay_heartbeat.txt"), "run-1"))
        val f = follower()

        val end = withTimeout(10_000) { f.follow(send()) }

        assertEquals(RunEnd.Done, end)
        assertEquals("Hello there!", f.turn.value.answer)
        assertEquals("run-1", f.runId)
        server.takeRequest()
        assertEquals("/api/chat/resume/s1", server.takeRequest().path)
        assertEquals(listOf(2_000L), waits)
    }

    @Test
    fun aStreamEndingWithoutDoneAlsoResumes() = runBlocking {
        server.enqueue(sse("data: {\"delta\": \"Hel\"}\n\n", "run-1"))
        server.enqueue(sse(fixture("sse_resume_replay_heartbeat.txt"), "run-1"))
        val f = follower()
        assertEquals(RunEnd.Done, withTimeout(10_000) { f.follow(send()) })
        assertEquals("Hello there!", f.turn.value.answer)
    }

    @Test
    fun aStreamEndingAfterAStopIsTheEndNotADrop() = runBlocking {
        // The server ends a stopped run's stream without [DONE].
        server.enqueue(sse("data: {\"delta\": \"Hel\"}\n\n", "run-1"))
        val f = follower()
        f.stopping = true
        assertEquals(RunEnd.Stopped, withTimeout(10_000) { f.follow(send()) })
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun resume404EndsTheRun() = runBlocking {
        server.enqueue(dropped())
        server.enqueue(MockResponse().setResponseCode(404).setBody(fixture("chat_resume_404.json")))
        assertEquals(RunEnd.Gone, withTimeout(10_000) { follower().follow(send()) })
    }

    @Test
    fun backoffDoublesUpToThirtySeconds() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0..5).map(RunFollower::backoffMs))
    }
}
