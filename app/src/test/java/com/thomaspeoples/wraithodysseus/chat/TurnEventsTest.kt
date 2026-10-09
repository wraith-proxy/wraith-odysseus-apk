package com.thomaspeoples.wraithodysseus.chat

import com.thomaspeoples.wraithodysseus.net.SseEvent
import com.thomaspeoples.wraithodysseus.net.SseParser
import com.thomaspeoples.wraithodysseus.net.sseFrames
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each event the reducer learned for card 2A, replayed from a fixture whose frames cite the server file and line. */
class TurnEventsTest {

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private fun events(name: String): List<SseEvent> {
        val response = Response.Builder()
            .request(Request.Builder().url("http://192.168.1.2/api/chat_stream").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(fixture(name).toResponseBody())
            .build()
        return runBlocking { response.sseFrames().toList() }.map(SseParser::decode)
    }

    private fun replay(name: String) = events(name).fold(TurnState(), TurnState::apply)

    private fun upTo(name: String, type: String) = events(name)
        .takeWhile { !(it is SseEvent.Event && it.type == type) }
        .fold(TurnState(), TurnState::apply)

    // -- sources and memories ----------------------------------------------

    @Test
    fun webSourcesKeepTitleAndLinkAndFallBackToTheHost() {
        val web = replay("sse_events_sources.txt").details.webSources
        assertEquals(Source("Tailscale docs", "https://www.tailscale.com/kb/1017"), web[0])
        assertEquals("tailscale.com", web[0].domain)
        assertEquals("example.org", web[1].title)
    }

    @Test
    fun researchSourcesReplaceWebOnesAndFindingsAreKept() {
        val d = replay("sse_events_sources.txt").details
        assertEquals(listOf(Source("Paper A", "https://arxiv.org/abs/1")), d.researchSources)
        assertEquals("Research sources", d.sourcesLabel)
        assertEquals(d.researchSources, d.sources)
        assertEquals(listOf(Finding("Paper A", "https://arxiv.org/abs/1", "It finds X.")), d.findings)
    }

    @Test
    fun webSourcesLabelWhenNoResearch() {
        val d = upTo("sse_events_sources.txt", "memories_used").details
        assertEquals("Web sources", d.sourcesLabel)
        assertEquals(2, d.sources.size)
    }

    @Test
    fun ragSourcesCarryFilenameSimilarityAndSnippet() {
        assertEquals(listOf(RagSource("notes.md", 0.8123, "Tailscale is used for")), replay("sse_events_sources.txt").details.ragSources)
    }

    @Test
    fun memoriesUsedKeepTheirType() {
        val m = replay("sse_events_sources.txt").details.memories
        assertEquals(listOf("pinned", "recalled", "pinned"), m.map { it.type })
        assertEquals("Lives in Lisbon", m[1].text)
    }

    // -- mode, model, metrics, saved -----------------------------------------

    @Test
    fun turnModeSetsTheAgentChip() {
        val d = replay("sse_events_meta.txt").details
        assertEquals("agent", d.mode)
        assertTrue(d.autoEscalated)
        assertEquals(">_ auto", d.modeChip)
        assertEquals("research", MessageDetails(mode = "research").modeChip)
        assertNull(MessageDetails(mode = "chat").modeChip)
    }

    @Test
    fun modelInfoThenModelActualRoutesTheHeader() {
        val first = upTo("sse_events_meta.txt", "model_actual")
        assertEquals("qwen3-30b-a3b", first.model)
        val t = replay("sse_events_meta.txt")
        assertEquals("org/qwen3-coder-480b-Q4_K_M", t.model)
        assertEquals("qwen3-30b-a3b", t.requestedModel)
        assertEquals("qwen3-30b-a3b -> qwen3-coder-480b", modelLabel(t.requestedModel, t.model))
    }

    @Test
    fun metricsAreParsedAndAnEmptyLaterFrameKeepsThem() {
        val m = checkNotNull(replay("sse_events_meta.txt").details.metrics)
        assertEquals(52.4, m.tokensPerSecond!!, 0.0)
        assertEquals("computed", m.tpsSource)
        assertEquals(1200, m.inputTokens)
        assertTrue(m.realUsage)
        assertEquals(71.5, m.contextPercent!!, 0.0)
        assertEquals(0.00123, m.costUsd!!, 0.0)
        assertEquals(0.41, m.timeToFirstToken!!, 0.0)
        assertEquals(listOf("tool_select" to 0.1, "memory_lookup" to 0.15), m.prepBreakdown)
        assertEquals(12, m.toolSchemaCount)
        assertEquals(3, m.agentRounds)
        assertEquals(2, m.toolCalls)
    }

    @Test
    fun messageSavedGivesTheDatabaseId() {
        assertEquals("m-0207", replay("sse_events_meta.txt").savedId)
    }

    // -- notices ----------------------------------------------------------

    @Test
    fun noticesReadLikeTheWebsToasts() {
        assertEquals(
            listOf(
                "Context compacted — older messages summarized",
                "Context trimmed for this model (12/40 messages sent)",
                "Reached the 25-step limit — not finished.",
                "Tool budget reached (30/30 calls). Agent stopped.",
                "[Agent guard: artifact_recovery_unoffered_tool]",
                "Teacher takeover: escalating to big-model — gave up after 3 tries",
                "Teacher could not solve it: teacher endpoint not resolvable: boom",
                "Fallback: model-a failed — answered by model-b",
            ),
            replay("sse_events_notices.txt").notices,
        )
    }

    @Test
    fun aFallbackRenamesTheHeaderToWhoAnswered() {
        assertEquals("org/model-b", replay("sse_events_notices.txt").model)
    }

    @Test
    fun shortModelDropsPathQuantAndLength() {
        assertEquals("model-a", shortModel("org/model-a-Q8_0"))
        assertEquals("llama.gguf".removeSuffix(".gguf"), shortModel("dir/llama.gguf"))
        assertEquals("Qwen3-Coder-480B", shortModel("Qwen3-Coder-480B-A35B-Instruct-extra"))
        assertEquals("...", shortModel(null))
    }

    // -- tools ------------------------------------------------------------

    @Test
    fun aToolStartsRunningThenProgressesThenFinishes() {
        val started = upTo("sse_events_tools.txt", "tool_progress").details.tools
        assertEquals(1, started.size)
        assertTrue(started[0].running)
        assertEquals("ls -la", started[0].command)

        val progressed = upTo("sse_events_tools.txt", "tool_output").details.tools[0]
        assertEquals("total 8", progressed.tail)
        assertEquals("2.5s", progressed.progress)
        assertTrue(progressed.running)
    }

    @Test
    fun toolOutputClosesTheMatchingCardWithExitDiffAndScreenshot() {
        val tools = replay("sse_events_tools.txt").details.tools
        assertEquals(2, tools.size)
        val ok = tools[0]
        assertFalse(ok.running)
        assertTrue(ok.ok)
        assertEquals("total 8\nREADME.md", ok.output)
        assertEquals(0, ok.exitCode)
        assertEquals("ls -la", ok.command)
        assertNull(ok.tail)

        val failed = tools[1]
        assertFalse(failed.ok)
        assertEquals(1, failed.exitCode)
        assertEquals(ToolDiff("a.txt", "--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new", 1, 1, false), failed.diff)
        assertEquals("data:image/png;base64,AAAA", failed.screenshot)
    }

    @Test
    fun theStatusLineStillWorks() {
        assertEquals("write_file finished (exit 1)", upTo("sse_events_tools.txt", "generated_image").toolLine)
    }

    @Test
    fun anOutputWithNoStartBecomesACardOfItsOwn() {
        val t = TurnState().apply(
            SseEvent.Event("tool_output", JSONObject("""{"type":"tool_output","tool":"bash","command":"pwd","output":"/","exit_code":0}""")),
        )
        assertEquals(1, t.details.tools.size)
        assertFalse(t.details.tools[0].running)
    }

    @Test
    fun askUserNeverBecomesAToolCard() {
        val t = TurnState().apply(SseEvent.Event("tool_start", JSONObject("""{"type":"tool_start","tool":"ask_user","command":"q"}""")))
        assertTrue(t.details.tools.isEmpty())
    }

    @Test
    fun aScreenshotThatIsNotAnInlineImageIsDropped() {
        assertNull(safeScreenshot("https://evil.example/x.png"))
        assertNull(safeScreenshot("data:text/html;base64,AAAA"))
        assertEquals("data:image/jpeg;base64,/9j/4A==", safeScreenshot("data:image/jpeg;base64,/9j/4A=="))
    }

    @Test
    fun generatedImagesAreListedOnce() {
        val images = replay("sse_events_tools.txt").details.images
        assertEquals(listOf(GeneratedImage("/api/generated-image/x.png", "a red boat", "img-1", "1024x1024")), images)
    }

    @Test
    fun agentStepAndPlanUpdateAreKept() {
        val t = replay("sse_events_tools.txt")
        assertEquals(3, t.round)
        assertEquals("1. look\n2. fix", t.plan)
    }

    // -- ignored ----------------------------------------------------------

    @Test
    fun otherPanelsEventsChangeNothing() {
        val t = replay("sse_events_ignored.txt")
        assertEquals(TurnState().copy(startedMs = t.startedMs), t)
    }

    // -- the thinking timer ------------------------------------------------

    private fun delta(text: String, thinking: Boolean = false) = SseEvent.Delta(text, thinking, replacesTurn = false)

    @Test
    fun thinkingIsTimedFromTheFirstThinkingDeltaToTheFirstAnswerDelta() {
        var t = TurnState()
        t = t.apply(SseEvent.Event("model_info", JSONObject("""{"model":"m"}""")), 1_000)
        assertNull(t.thinkStartMs)
        t = t.apply(delta("hm", thinking = true), 2_000)
        t = t.apply(delta("more", thinking = true), 5_000)
        assertNull(t.thinkingSeconds)
        t = t.apply(delta("Answer"), 8_500)
        assertEquals("6.5", t.thinkingSeconds)
        // Later text does not move the end.
        assertEquals("6.5", t.apply(delta(" more"), 20_000).thinkingSeconds)
        assertEquals(1_000L, t.startedMs)
    }

    @Test
    fun inlineThinkTagsAreTimedToo() {
        var t = TurnState().apply(delta("<think>plan"), 1_000)
        t = t.apply(delta("</think>Hi"), 3_000)
        assertEquals("2.0", t.thinkingSeconds)
    }

    @Test
    fun noThinkingMeansNoTime() {
        val t = TurnState().apply(delta("Just an answer"), 1_000)
        assertNull(t.thinkingSeconds)
    }
}
