package com.thomaspeoples.wraithodysseus.chat

import com.thomaspeoples.wraithodysseus.net.SseFrame
import com.thomaspeoples.wraithodysseus.net.SseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a tool-approval gate shows the person: the sealed action. */
class ApprovalActionTest {

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private fun gate(name: String): AskUser {
        val event = SseParser.decode(SseFrame(null, fixture(name).trim()))
        return checkNotNull(TurnState().apply(event).ask)
    }

    @Test
    fun theActionIsParsedFromTheGate() {
        // Payload: src/tool_approvals.py:196-249 public_payload ("action": tool, content, digest,
        // effects, workspace, document_id, document_version), sent as the "data" of the
        // ask_user event (src/agent_loop.py:34813).
        val ask = gate("tool_approval_ask_user.json")
        assertEquals("apr_77aa10c2d9e84b15", ask.approvalId)
        assertEquals(
            ApprovalAction(
                tool = "bash",
                content = "rm -rf /srv/data/old-builds\nls /srv/data",
                effects = listOf("filesystem_write", "process_exec"),
                workspace = "/srv/data",
                documentId = "doc_91",
                documentVersion = 4,
                digest = "0123456789abcdef",
            ),
            ask.action,
        )
    }

    @Test
    fun theLinesAreTheWebCardsInTheSameOrder() {
        // static/js/chatRenderer.js:3047-3057.
        assertEquals(
            listOf(
                "bash",
                "rm -rf /srv/data/old-builds\nls /srv/data",
                "Effects: filesystem_write, process_exec",
                "Workspace: /srv/data",
                "Document: doc_91",
                "Document version: 4",
                "Approval fingerprint: 0123456789abcdef",
            ),
            checkNotNull(gate("tool_approval_ask_user.json").action).lines(),
        )
    }

    @Test
    fun nullFieldsAreLeftOut() {
        // The recorded gate (sse_tool_approval.txt, shaped from tool_approvals.py:238-246) has
        // workspace, document_id and document_version as JSON null.
        val turn = events("sse_tool_approval.txt").fold(TurnState(), TurnState::apply)
        val action = checkNotNull(checkNotNull(turn.ask).action)
        assertEquals("send_email", action.tool)
        assertNull(action.workspace)
        assertNull(action.documentId)
        assertNull(action.documentVersion)
        assertEquals(
            listOf("send_email", "{\"to\": \"carol@example.com\", \"subject\": \"Re: Dinner\"}", "Effects: external_send", "Approval fingerprint: 9c1d2e3f4a5b6c7d"),
            action.lines(),
        )
    }

    @Test
    fun aPlainQuestionHasNoAction() {
        val turn = events("sse_ask_user_options.txt").fold(TurnState(), TurnState::apply)
        assertNull(checkNotNull(turn.ask).action)
    }

    private fun events(name: String) = fixture(name).trim().split("\n\n").filter { it.isNotBlank() }.map { block ->
        val event = block.lineSequence().firstOrNull { it.startsWith("event:") }?.removePrefix("event:")?.trim()
        SseParser.decode(SseFrame(event, block.lineSequence().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trim() }))
    }
}
