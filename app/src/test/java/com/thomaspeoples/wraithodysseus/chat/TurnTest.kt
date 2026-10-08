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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnTest {

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private fun events(text: String): List<SseEvent> {
        val response = Response.Builder()
            .request(Request.Builder().url("http://192.168.1.2/api/chat_stream").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(text.toResponseBody())
            .build()
        return runBlocking { response.sseFrames().toList() }.map(SseParser::decode)
    }

    private fun replay(name: String) = events(fixture(name)).fold(TurnState(), TurnState::apply)

    // -- think splitter ----------------------------------------------------

    @Test
    fun thinkTagsAreSplitOutOfTheAnswer() {
        assertEquals(Split("Answer.", "reasoning"), splitThinking("<think>reasoning</think>Answer."))
        assertEquals(Split("Before after", "mid"), splitThinking("Before <think>mid</think>after"))
        assertEquals(Split("Plain text", ""), splitThinking("Plain text"))
    }

    @Test
    fun anUnclosedBlockIsStillThinking() {
        assertEquals(Split("", "still going"), splitThinking("<think>still going"))
    }

    @Test
    fun thinkingTagSpellingIsAcceptedToo() {
        assertEquals(Split("Hi", "hm"), splitThinking("<thinking>hm</thinking>Hi"))
    }

    @Test
    fun aTagCutAcrossDeltasStillSplits() {
        val t = listOf("<thi", "nk>Count the letters", " in banana.</th", "ink>Three.")
            .fold(TurnState()) { s, d -> s.apply(SseEvent.Delta(d, thinking = false, replacesTurn = false)) }
        assertEquals("Three.", t.answer)
        assertEquals("Count the letters in banana.", t.thinking)
    }

    // -- assembly, driven by captured streams ------------------------------

    @Test
    fun flaggedThinkingAndTextAssemble() {
        val t = replay("sse_chat_basic.txt")
        assertEquals("Hello there!", t.answer)
        assertEquals("The user wants a short greeting.", t.thinking)
        assertEquals("qwen3-30b-a3b", t.model)
    }

    @Test
    fun inlineThinkTagsFromTheServer() {
        val t = replay("sse_chat_think_tags.txt")
        assertEquals("There are **three** a's.", t.answer)
        assertEquals("Count the letters in banana.", t.thinking)
    }

    @Test
    fun aTurnScopedDeltaRestartsTheAnswer() {
        assertEquals("Final synthesis.", replay("sse_agent_replacement.txt").answer)
    }

    @Test
    fun finalResponseReplacesAndToolsShowOnTheStatusLine() {
        val t = replay("sse_agent_tools.txt")
        assertEquals("The project has one file: README.md.", t.answer)
        assertEquals("bash finished (exit 0)", t.toolLine)

        val running = events(fixture("sse_agent_tools.txt"))
            .takeWhile { !(it is SseEvent.Event && it.type == "tool_progress") }
            .fold(TurnState(), TurnState::apply)
        assertEquals("Running bash: ls -la /tmp/project", running.toolLine)
    }

    @Test
    fun plainAskUserHasLabelledOptions() {
        val ask = checkNotNull(replay("sse_ask_user_options.txt").ask)
        assertEquals("Which city should I check the weather for?", ask.question)
        assertEquals(listOf("Lisbon", "Porto"), ask.options.map { it.label })
        assertNull(ask.approvalId)
        // The ask_user tool itself is not a "running" line.
        assertNull(replay("sse_ask_user_options.txt").toolLine)
    }

    @Test
    fun approvalGateCarriesTheDecisionValues() {
        val ask = checkNotNull(replay("sse_tool_approval.txt").ask)
        assertEquals("apr_5f1c0d9e2b7a4c3d", ask.approvalId)
        assertEquals(listOf("approve_task", "approve", "deny"), ask.options.map { it.value })
        assertEquals("Allow for this task", ask.options[0].label)
    }

    @Test
    fun aResolvedDenyClearsTheGate() {
        val gated = replay("sse_tool_approval.txt")
        val after = events(fixture("sse_tool_approval_denied.txt")).fold(gated, TurnState::apply)
        assertNull(after.ask)
    }

    @Test
    fun errorFramesKeepTheirText() {
        assertEquals("Rate limited by provider. Try again shortly.", replay("sse_error_text_field.txt").error)
        val upstream = replay("sse_error_upstream.txt")
        assertEquals("Cannot reach llm.example.internal:8080", upstream.error)
        assertEquals("Partial", upstream.answer)
    }
}
