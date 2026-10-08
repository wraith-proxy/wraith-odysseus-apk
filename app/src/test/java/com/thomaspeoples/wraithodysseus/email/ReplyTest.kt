package com.thomaspeoples.wraithodysseus.email

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyTest {

    private val original = EmailMessage(
        uid = "1042",
        folder = "INBOX",
        messageId = "<a1@example.com>",
        subject = "Dinner on Friday?",
        fromName = "Carol Sample",
        fromAddress = "carol@example.com",
        to = "alice@example.com",
        cc = "",
        date = "2026-10-07T17:30:00+00:00",
        references = "<a00@example.com> <a0@example.com>",
        body = "Hi Alice,\n\nCarol",
        markSeenFailed = false,
    )

    @Test
    fun referencesAppendTheOriginalMessageId() {
        assertEquals("<a00@example.com> <a0@example.com> <a1@example.com>", replyReferences(original))
    }

    @Test
    fun referencesAreJustTheMessageIdWhenTheOriginalHadNone() {
        assertEquals("<a1@example.com>", replyReferences(original.copy(references = "")))
    }

    @Test
    fun subjectIsPrefixedOnce() {
        assertEquals("Re: Dinner on Friday?", replySubject("Dinner on Friday?"))
        assertEquals("Re: Dinner", replySubject("Re: Dinner"))
        assertEquals("RE:Dinner", replySubject("RE:Dinner"))
        assertEquals("re : Dinner", replySubject("  re : Dinner "))
        assertEquals("Re: ", replySubject(""))
    }

    @Test
    fun theBodyQuotesEveryLineUnderAnAttribution() {
        assertEquals(
            "\n\n---------- Previous message ----------\n" +
                "On Wed, 7 Oct 2026 17:30, Carol Sample <carol@example.com> wrote:\n" +
                "> Hi Alice,\n> \n> Carol",
            quotedBody(original, ZoneOffset.UTC),
        )
    }

    @Test
    fun anUnparseableDateIsShownAsIs() {
        assertEquals("yesterday", niceDate("yesterday"))
    }

    @Test
    fun theDraftCarriesThreadingAndSourceFields() {
        val d = replyDraft(original, "acc-home")
        assertEquals("carol@example.com", d.to)
        assertEquals("Re: Dinner on Friday?", d.subject)
        assertEquals("acc-home", d.accountId)
        assertEquals("<a1@example.com>", d.inReplyTo)
        assertEquals("<a00@example.com> <a0@example.com> <a1@example.com>", d.references)
        assertEquals("1042", d.sourceUid)
        assertEquals("INBOX", d.sourceFolder)

        val json = EmailApi.sendJson(d)
        assertEquals("<a1@example.com>", json.get("in_reply_to"))
        assertEquals("1042", json.get("source_uid"))
        assertEquals("INBOX", json.get("source_folder"))
    }

    @Test
    fun withoutAMessageIdThereAreNoThreadingHeaders() {
        val d = replyDraft(original.copy(messageId = "", references = ""), null)
        assertNull(d.inReplyTo)
        assertNull(d.references)
        assertNull(d.accountId)
        assertEquals("1042", d.sourceUid)
    }
}
