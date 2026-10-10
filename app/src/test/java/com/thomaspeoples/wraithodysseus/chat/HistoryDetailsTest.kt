package com.thomaspeoples.wraithodysseus.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a saved row's metadata gives the message (routes/chat_helpers.py:1520 save_assistant_response, :762 user rows). */
class HistoryDetailsTest {

    private fun history(vararg rows: String) =
        ChatApi.parseHistory(JSONObject("""{"history":[${rows.joinToString(",")}],"total":${rows.size}}""")).messages

    private val assistant = """
        {"role":"assistant","content":"Done.","metadata":{
          "_db_id":"m1","timestamp":"2026-10-09T12:34:56.789012Z",
          "model":"org/qwen3-coder-480b","requested_model":"qwen3-30b-a3b",
          "thinking":"hm","thinking_time":"4.2",
          "web_sources":[{"title":"Docs","url":"https://docs.example.com/x"}],
          "research_findings":[{"url":"https://a.example/p","title":"P","summary":"s"}],
          "rag_sources":[{"filename":"n.md","similarity":0.5,"snippet":"snip"}],
          "memories_used":[{"type":"pinned","text":"likes tea"}],
          "response_time":2.5,"input_tokens":100,"output_tokens":50,"context_percent":88,"context_length":4096,
          "usage_source":"estimated","cost_usd":0.5,
          "tool_events":[
            {"round":1,"tool":"bash","command":"ls","output":"a\nb","exit_code":0},
            {"round":1,"tool":"write_file","command":"w","output":"x","exit_code":2,
             "diff":{"file":"f.txt","text":"+a","added":1,"removed":0,"new_file":true}},
            {"round":2,"tool":"ask_user","command":"q","output":""},
            {"round":2,"tool":"generate_image","command":"c","output":"{\"image_url\":\"/api/generated-image/g.png\",\"image_prompt\":\"cat\"}","exit_code":0}
          ]}}
    """.trimIndent()

    @Test
    fun metadataBecomesDetails() {
        val d = history(assistant)[0].details
        assertEquals("org/qwen3-coder-480b", d.model)
        assertEquals("qwen3-30b-a3b", d.requestedModel)
        assertEquals("qwen3-30b-a3b -> qwen3-coder-480b", modelLabel(d.requestedModel, d.model))
        assertEquals(java.time.Instant.parse("2026-10-09T12:34:56.789Z").toEpochMilli(), d.timeMs)
        assertEquals("4.2", d.thinkingSeconds)
        assertEquals(listOf(Source("Docs", "https://docs.example.com/x")), d.webSources)
        assertEquals("Web sources", d.sourcesLabel)
        assertEquals("P", d.findings[0].title)
        assertEquals("n.md", d.ragSources[0].filename)
        assertEquals(listOf(MemoryUsed("pinned", "likes tea")), d.memories)
    }

    @Test
    fun metricsAreReadFlatFromTheMetadata() {
        val m = checkNotNull(history(assistant)[0].details.metrics)
        assertEquals(2.5, m.responseTime!!, 0.0)
        assertEquals(100, m.inputTokens)
        assertEquals(88.0, m.contextPercent!!, 0.0)
        assertEquals(4096, m.contextLength)
        assertFalse(m.realUsage)
        assertEquals(0.5, m.costUsd!!, 0.0)
    }

    @Test
    fun toolEventsBecomeCardsWithoutTheAskUserOne() {
        val tools = history(assistant)[0].details.tools
        assertEquals(listOf("bash", "write_file", "generate_image"), tools.map { it.tool })
        assertTrue(tools[0].ok)
        assertEquals("a\nb", tools[0].output)
        assertFalse(tools[1].ok)
        assertEquals(2, tools[1].exitCode)
        assertEquals(ToolDiff("f.txt", "+a", 1, 0, true), tools[1].diff)
        assertTrue(tools.none { it.running })
    }

    @Test
    fun anImageToolsJsonOutputGivesTheImage() {
        assertEquals(listOf(GeneratedImage("/api/generated-image/g.png", "cat", null, null)), history(assistant)[0].details.images)
    }

    @Test
    fun aFailedImageCallHasNoImage() {
        val row = """{"role":"assistant","content":"x","metadata":{"tool_events":[{"tool":"generate_image","output":"{\"image_url\":\"/u\"}","exit_code":1}]}}"""
        assertTrue(history(row)[0].details.images.isEmpty())
    }

    @Test
    fun aUserRowsModeComesFromInteractionModeAndAutoEscalated() {
        val row = """{"role":"user","content":"do it","metadata":{"interaction_mode":"agent","auto_escalated":true}}"""
        val d = history(row)[0].details
        assertEquals("agent", d.mode)
        assertTrue(d.autoEscalated)
        assertEquals(">_ auto", d.modeChip)
    }

    @Test
    fun aChatModeRowGetsNoChip() {
        val d = history("""{"role":"user","content":"hi","metadata":{"interaction_mode":"chat"}}""")[0].details
        assertNull(d.modeChip)
    }

    @Test
    fun thinkingTimeMayBeANumber() {
        val d = history("""{"role":"assistant","content":"x","metadata":{"thinking":"t","thinking_time":12.34}}""")[0].details
        assertEquals("12.3", d.thinkingSeconds)
    }

    @Test
    fun aRowWithoutMetadataHasEmptyDetails() {
        assertEquals(MessageDetails(), history("""{"role":"assistant","content":"plain"}""")[0].details)
    }

    @Test
    fun aBadTimestampIsNoTime() {
        assertNull(history("""{"role":"assistant","content":"x","metadata":{"timestamp":"yesterday"}}""")[0].details.timeMs)
    }
}
