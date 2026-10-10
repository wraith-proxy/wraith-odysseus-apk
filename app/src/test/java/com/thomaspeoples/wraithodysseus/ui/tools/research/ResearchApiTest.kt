package com.thomaspeoples.wraithodysseus.ui.tools.research

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Every call ResearchApi makes, and how each answer is read. */
class ResearchApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private val server = MockWebServer()
    private lateinit var api: ResearchApi

    @Before
    fun setUp() {
        server.start()
        api = ResearchApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private fun reply(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private fun failure(block: suspend () -> Unit): ApiException = try {
        runBlocking { block() }
        throw AssertionError("expected an ApiException")
    } catch (e: ApiException) {
        e
    }

    @Test
    fun featureIsOffOnlyWhenExplicitlyFalse() = runBlocking {
        reply(fixture("research_features_off.json"))
        assertFalse(api.featureEnabled())
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/auth/features", req.requestUrl!!.encodedPath)
        reply(fixture("research_features_on.json"))
        assertTrue(api.featureEnabled())
        reply("""{"web_search":true}""")
        assertTrue(api.featureEnabled())
        reply("not json")
        assertTrue(api.featureEnabled())
        reply("""{"detail":"boom"}""", 500)
        assertTrue(api.featureEnabled())
    }

    @Test
    fun modelsKeepEnabledLlmEndpointsOncePerModelSortedWithEndpointNames() = runBlocking {
        reply(fixture("research_endpoints.json"))
        val models = api.models()
        val req = server.takeRequest()
        assertEquals("/api/model-endpoints", req.requestUrl!!.encodedPath)
        assertEquals("abc123", req.getHeader("Cookie")!!.substringAfter('='))
        assertEquals(listOf("Alpha", "zeta", "beta"), models.map { it.model })
        assertEquals(listOf("ep1", "ep1", "ep2"), models.map { it.endpointId })
        assertEquals("Alpha · Local", models[0].label)
        assertEquals("beta · Cloud", models[2].label)
    }

    @Test
    fun modelsAreAdminOnlyAndTheRefusalSurfaces() {
        reply("""{"detail":"Admin access required"}""", 403)
        val e = failure { api.models() }
        assertEquals(403, e.status)
        assertEquals("Admin access required", e.message)
    }

    @Test
    fun singleEndpointLabelsAreJustTheModel() = runBlocking {
        reply("""[{"id":"e","name":"Only","is_enabled":true,"models":["m1"]}]""")
        assertEquals("m1", api.models().single().label)
    }

    @Test
    fun libraryAsksForRecentAndParsesRowsDroppingThoseWithoutId() = runBlocking {
        reply(fixture("research_library.json"))
        val items = api.library(limit = 50)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/research/library", req.requestUrl!!.encodedPath)
        assertEquals("recent", req.requestUrl!!.queryParameter("sort"))
        assertEquals("50", req.requestUrl!!.queryParameter("limit"))
        assertEquals(listOf("rp-aaa111", "rp-bbb222", "rp-ccc333", "rp-ddd444"), items.map { it.id })
        val first = items[0]
        assertEquals("product", first.category)
        assertEquals(14, first.sourceCount)
        assertEquals("4m 12s", first.duration)
        assertEquals(1760000252.0, first.completedAt, 0.0)
        assertEquals("explain", items[2].mode)
        assertEquals("error", items[3].status)
    }

    @Test
    fun libraryErrorBodyOn200IsAnError() {
        reply("""{"error":"Not authenticated"}""")
        assertEquals("Not authenticated", failure { api.library() }.message)
    }

    @Test
    fun activeParsesRunningJobsWithProgressAndTraces() = runBlocking {
        reply(fixture("research_active.json"))
        val jobs = api.active()
        assertEquals("/api/research/active", server.takeRequest().requestUrl!!.encodedPath)
        val job = jobs.single()
        assertEquals("rp-run111", job.id)
        assertTrue(job.running)
        assertEquals(1760000100250L, job.startedAtMs)
        assertEquals("comparison", job.category)
        assertEquals(Progress(phase = "reading", round = 2, queries = 4, totalSources = 9), job.progress)
        assertEquals("9 sources read", job.sourceState)
        assertEquals(TraceRow("Round 2", "search: ryzen 9 benchmark", "", "searxng · via planner"), job.actionTrace[0])
        assertEquals(TraceRow("Plan", "fetch: https://example.com/x", "skipped", "skipped · paywall"), job.actionTrace[1])
        assertEquals(TraceRow("fetch", "AnandTech review", "ok", "3 results · review · 82/100 · browser"), job.navigationTrace.single())
    }

    @Test
    fun startPostsOnlyWhatWasChosenAndReturnsTheJob() = runBlocking {
        reply("""{"session_id":"rp-new1","status":"running","query":"why","category":"howto","mode":"research"}""")
        val job = api.start("why", ResearchSettings(maxRounds = 5, searchProvider = "brave", endpointId = "ep1", model = "Alpha", category = "howto"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/research/start", req.requestUrl!!.encodedPath)
        assertEquals("application/json", req.getHeader("Content-Type")!!.substringBefore(';'))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("why", body.getString("query"))
        assertEquals(5, body.getInt("max_rounds"))
        assertEquals("brave", body.getString("search_provider"))
        assertEquals("ep1", body.getString("endpoint_id"))
        assertEquals("Alpha", body.getString("model"))
        assertEquals("howto", body.getString("category"))
        assertEquals("rp-new1", job.id)
        assertEquals("howto", job.category)
        assertTrue(job.running)
    }

    @Test
    fun startWithDefaultsSendsJustTheQuery() = runBlocking {
        reply("""{"session_id":"rp-new2","status":"running","query":"q"}""")
        api.start("q", ResearchSettings())
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(setOf("query"), body.keys().asSequence().toSet())
    }

    @Test
    fun startRefusalsComeThroughWithTheServersWords() {
        reply("""{"detail":"Your account is not allowed to can use research."}""", 403)
        assertEquals("Your account is not allowed to can use research.", failure { api.start("q", ResearchSettings()) }.message)
        reply("""{"detail":"No endpoints configured. Add one in Settings first."}""", 400)
        assertEquals("No endpoints configured. Add one in Settings first.", failure { api.start("q", ResearchSettings()) }.message)
    }

    @Test
    fun startWithoutASessionIdIsAnError() {
        reply("""{"status":"running"}""")
        failure { api.start("q", ResearchSettings()) }
    }

    @Test
    fun startIsNeverSentTwiceAfterADroppedConnection() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("""{"session_id":"rp-x"}"""))
        try {
            runBlocking { api.start("q", ResearchSettings()) }
            fail("expected a dropped connection")
        } catch (_: IOException) {
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun streamYieldsEachFrameAndEndsOnTheFinalOne() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(fixture("research_stream.sse")))
        val frames = api.stream("rp-1").toList()
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/research/stream/rp-1", req.requestUrl!!.encodedPath)
        assertEquals("abc123", req.getHeader("Cookie")!!.substringAfter('='))
        assertEquals(4, frames.size)
        assertEquals("probing", frames[0].getString("phase"))
        assertTrue(frames[3].getBoolean("final"))
        assertEquals("done", frames[3].getString("status"))
    }

    @Test
    fun streamOfAnUnknownJobIs404() {
        reply("""{"detail":"No research found for this session"}""", 404)
        val e = failure { api.stream("rp-gone") }
        assertEquals(404, e.status)
    }

    @Test
    fun statusIsTheFallbackFeed() = runBlocking {
        reply(fixture("research_status.json"))
        val json = api.status("rp-1")
        assertEquals("/api/research/status/rp-1", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("running", json.getString("status"))
        assertEquals("analyzing", json.getJSONObject("progress").getString("phase"))
    }

    @Test
    fun cancelPostsAndReportsWhetherItStopped() = runBlocking {
        reply("""{"cancelled":true}""")
        assertTrue(api.cancel("rp-1"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/research/cancel/rp-1", req.requestUrl!!.encodedPath)
        reply("""{"cancelled":false}""")
        assertFalse(api.cancel("rp-1"))
    }

    @Test
    fun resultParsesReportSourcesFindingsAndTraces() = runBlocking {
        reply(fixture("research_result.json"))
        val r = api.result("rp-1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/research/result-peek/rp-1", req.requestUrl!!.encodedPath)
        assertEquals("# Report\n\nThe answer is **42**.", r.result)
        assertEquals(3, r.sources.size)
        assertEquals(Source("Example one", "https://example.com/1", "docs", "fetch", 91), r.sources[0])
        assertNull(r.sources[2].score)
        assertEquals(listOf("Finding A", ""), r.findings.map { it.title })
        assertEquals("3 sources, 2 read", r.sourceState)
        assertEquals("howto", r.category)
        assertEquals("Round 1", r.actionTrace.single().tool)
        assertEquals("91/100", r.navigationTrace.single().meta.substringAfterLast(" · "))
    }

    @Test
    fun result404IsReadable() {
        reply("""{"detail":"No research result available"}""", 404)
        assertEquals("No research result available", failure { api.result("rp-1") }.message)
    }

    @Test
    fun reportIsTheRawHtml() = runBlocking {
        reply("<html><body><h1>Visual</h1></body></html>")
        assertEquals("<html><body><h1>Visual</h1></body></html>", api.report("rp-1"))
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/research/report/rp-1", req.requestUrl!!.encodedPath)
    }

    @Test
    fun reportFailureIsReadable() {
        reply("""{"detail":"No visual report available for this session"}""", 404)
        assertEquals("No visual report available for this session", failure { api.report("rp-1") }.message)
    }

    @Test
    fun deleteUsesTheBareIdPathAndAnIdCannotAddSegments() = runBlocking {
        reply("""{"deleted":true}""")
        api.delete("rp-1")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/research/rp-1", req.requestUrl!!.encodedPath)
        reply("""{"deleted":true}""")
        api.delete("../x")
        assertEquals("/api/research/..%2Fx", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun spinoffReturnsTheNewChatId() = runBlocking {
        reply(fixture("research_spinoff.json"))
        assertEquals("11111111-2222-3333-4444-555555555555", api.spinoff("rp-1"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/research/spinoff/rp-1", req.requestUrl!!.encodedPath)
    }

    @Test
    fun spinoffWithoutAChatIsAnError() {
        reply("""{"name":"x"}""")
        failure { api.spinoff("rp-1") }
        reply("""{"detail":"No research result available for this session"}""", 404)
        assertEquals("No research result available for this session", failure { api.spinoff("rp-1") }.message)
    }
}
