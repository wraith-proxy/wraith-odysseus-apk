package com.thomaspeoples.wraithodysseus.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleTest {

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    // -- user rows ---------------------------------------------------------

    @Test
    fun injectedFileAndImageBlocksAreStrippedWhenThereAreAttachments() {
        // Shapes from src/document_processor.py:95-115 (the "=== File:" header,
        // "[Type: ...]" line and fenced body) and src/chat_handler.py:280 ("[Image: name]"
        // then the vision model's description), as chat_handler appends them to the typed text.
        val raw = "Summarise this\n\n=== File: notes.py ===\n[Type: python, Lines: 2, Size: 31 bytes]\n\n" +
            "```python\nprint('hi')\nprint('bye')\n```\n\n[Image: whiteboard.png]\nA whiteboard with three boxes."
        assertEquals("Summarise this", visibleUserText(raw, hasAttachments = true))
    }

    @Test
    fun plainFileBlocksAndPdfTextGoToo() {
        // src/document_processor.py:95-106 (non-code body) and :160 ("\n\n[PDF content]:" + text).
        val raw = "Read these\n\n=== File: a.txt ===\n[Type: text, Lines: 1, Size: 5 bytes]\n\nhello" +
            "\n\n[PDF content]:\n\n[Page 1 text]:\nto the board\n[Image attached: scan.png]"
        assertEquals("Read these", visibleUserText(raw, hasAttachments = true))
    }

    @Test
    fun visionDescriptionsGoEvenWithoutAttachmentMetadata() {
        // chatRenderer.js:3569 strips them for every user row: the cached attachments
        // are gone after a browser restart but the text stays.
        assertEquals("What is this?", visibleUserText("What is this?\n\n[Image: cat.png]\nA grey cat.", hasAttachments = false))
    }

    @Test
    fun aTypedFileHeaderIsLeftAloneWithoutAttachments() {
        val typed = "Look at\n=== File: not-an-upload ==="
        assertEquals(typed, visibleUserText(typed, hasAttachments = false))
    }

    @Test
    fun endOfTextBehavesLikeJavascriptNotJava() {
        // Java's $ also matches before a final newline; the ported patterns use \z.
        assertEquals("q", visibleUserText("q\n[Image: a.png]\ndescription\n", hasAttachments = false))
    }

    // -- hidden rows -------------------------------------------------------

    @Test
    fun resumePromptsAndRewriteInstructionsAreHidden() {
        // static/js/sessions.js:223-225.
        fun hidden(text: String) = isHiddenTurn("user", text, wakeCheckIn = false, hiddenFromUserView = false)
        assertTrue(hidden("Continue where you left off"))
        assertTrue(hidden("  Continue where you left off  "))
        assertTrue(hidden("Your message was cut off. Please continue."))
        assertTrue(hidden("Your previous response was interrupted. Continue exactly where you left off."))
        assertTrue(hidden("[Instruction: Rewrite shorter]\nbody"))
        assertTrue(hidden("x [Instruction: Explain simpler]"))
        assertFalse(hidden("Please continue where you left off"))
        assertFalse(hidden("Did you rewrite it?"))
    }

    @Test
    fun theseRulesApplyToUserRowsOnly() {
        assertFalse(isHiddenTurn("assistant", "Continue where you left off", false, false))
    }

    @Test
    fun wakeChecksAreHiddenOnEitherRole() {
        // chatRenderer.js:3480-3487.
        assertTrue(isHiddenTurn("user", "Did you finish?", wakeCheckIn = true, hiddenFromUserView = false))
        assertTrue(isHiddenTurn("assistant", "ok", wakeCheckIn = false, hiddenFromUserView = true))
        assertTrue(isHiddenTurn("user", "  [Task] Self-check: abc", false, false))
        assertFalse(isHiddenTurn("user", "A [Task] Self-check: in the middle", false, false))
    }

    @Test
    fun historyParsingAppliesBothRules() {
        // History entry shape: routes/history/history_routes.py:266-279 (_db_history_entry);
        // the injected text: src/document_processor.py:95 and src/chat_handler.py:280.
        val page = ChatApi.parseHistory(JSONObject(fixture("history_injected_rows.json")))
        assertEquals(listOf("m-01", "m-02", "m-06", "m-07"), page.messages.map { it.dbId })
        assertEquals("Summarise this", page.messages[0].content)
        assertEquals("Summarise this", page.messages[0].raw)
        // No attachments, so the typed header stays.
        assertEquals("A line a person typed:\n=== File: not-an-upload ===", page.messages[2].content)
    }

    // -- assistant text ----------------------------------------------------

    @Test
    fun copyDropsToolBlocksAndReasoning() {
        // Patterns from static/js/chatRenderer.js:811-891; copy rule at :1583.
        val raw = "<think>plan</think>Here you go.\n[TOOL_CALL]{tool => \"bash\"}[/TOOL_CALL]\n" +
            "<tool_call>{\"name\":\"x\"}</tool_call>\nDone."
        assertEquals("Here you go.\n\nDone.", visibleAssistantText(raw))
    }

    @Test
    fun copyFallsBackToTheRawTextWhenNothingIsLeft() {
        val raw = "<think>cut off while thinking"
        assertEquals(raw, visibleAssistantText(raw))
    }

    @Test
    fun toolNarrationIsDropped() {
        assertEquals("All set.", stripToolBlocks("All set.\nThe output shows: stdout: hello\n"))
    }

    // -- rewrite -----------------------------------------------------------

    @Test
    fun rewriteReasoningIsPeeledOff() {
        // chat.js:7898-7905.
        assertEquals("Short.", stripRewriteThinking("<think>a</think>Short."))
        assertEquals("Short.", stripRewriteThinking("reasoning only</think>Short."))
        assertEquals("Short.", stripRewriteThinking("<think>Short."))
        assertEquals("Short.", stripRewriteThinking("  Short.  "))
    }
}
