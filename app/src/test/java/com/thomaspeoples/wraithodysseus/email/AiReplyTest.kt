package com.thomaspeoples.wraithodysseus.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the AI reply: frame parsing, the reducer, the request JSON. */
class AiReplyTest {

    private fun preview(t: String) = AiReplyFrame.Preview(t)
    private fun result(ok: Boolean, reply: String? = null, error: String? = null) =
        AiReplyFrame.Result(ok, reply, error, null)

    private fun run(vararg frames: AiReplyFrame) = frames.fold(AiReplyProgress(), ::reduceAiReply)

    @Test
    fun previewsReplaceRatherThanAppend() {
        assertEquals("Hi Sam", run(preview("Hi"), preview("Hi Sam")).text)
    }

    @Test
    fun emptyPreviewResetsTheDraft() {
        assertEquals("", run(preview("Dear"), preview("")).text)
    }

    @Test
    fun resultIsAuthoritativeOverTheCutPreview() {
        val p = run(preview("Hi"), result(true, "Hi <b>there</b>"))
        assertEquals(AiReplyOutcome.Reply("Hi <b>there</b>", null), p.outcome)
    }

    @Test
    fun failureCarriesTheServerSentenceVerbatim() {
        val p = run(result(false, error = "No email body provided"))
        assertEquals(AiReplyOutcome.Failed("No email body provided"), p.outcome)
    }

    @Test
    fun successWithNoReplyIsAFailure() {
        assertTrue(run(result(true, reply = "")).outcome is AiReplyOutcome.Failed)
        assertEquals(AiReplyOutcome.Failed("Failed to generate reply"), run(result(false)).outcome)
    }

    @Test
    fun framesAfterTheResultAreIgnored() {
        val p = run(result(true, "Done"), preview("late"), result(false, error = "x"))
        assertEquals(AiReplyOutcome.Reply("Done", null), p.outcome)
        assertEquals("", p.text)
    }

    @Test
    fun parsesKnownFramesAndSkipsTheRest() {
        assertEquals(preview("a"), parseAiReplyFrame("""{"type":"reply","text":"a"}"""))
        assertEquals(
            AiReplyFrame.Result(true, "r", null, "m"),
            parseAiReplyFrame("""{"type":"result","success":true,"reply":"r","model_used":"m"}"""),
        )
        assertNull(parseAiReplyFrame("""{"type":"status"}"""))
        assertNull(parseAiReplyFrame("not json"))
    }

    @Test
    fun bodyPutsTheDraftAboveTheQuote() {
        assertEquals("Hi\n\n> q", aiReplyBody("Hi", "\n\n> q"))
    }

    @Test
    fun hintIsTrimmedCappedAndOnlySentWhenGiven() {
        val base = AiReplyRequest("a@b", "Re: x", "body", "<m>", "7", "INBOX", null)
        assertTrue(!aiReplyJson(base).has("user_hint"))
        assertTrue(!aiReplyJson(base.copy(userHint = "  \n ")).has("user_hint"))
        assertEquals("yes", aiReplyJson(base.copy(userHint = " yes ")).getString("user_hint"))
        assertEquals(AI_HINT_MAX, aiReplyJson(base.copy(userHint = "x".repeat(3000))).getString("user_hint").length)
    }
}
