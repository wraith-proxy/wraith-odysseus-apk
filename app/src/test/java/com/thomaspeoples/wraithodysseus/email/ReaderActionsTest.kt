package com.thomaspeoples.wraithodysseus.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderActionsTest {

    private fun msg(
        from: String = "dana@example.com",
        to: String = "me@example.com",
        cc: String = "",
        subject: String = "Hello",
        body: String = "Body text",
        html: String = "",
    ) = EmailMessage(
        uid = "9", folder = "INBOX", messageId = "<m9@x>", subject = subject, fromName = "Dana", fromAddress = from,
        to = to, cc = cc, date = "2026-10-08T09:30:00+00:00", references = "<r1@x>", body = body, markSeenFailed = false, bodyHtml = html,
    )

    private fun row(uid: String) = EmailSummary("acc", "INBOX", uid, "", "s", "n", "a@x", 0.0, false)

    @Test
    fun addressListsSplitOnlyBetweenAddresses() {
        assertEquals(
            listOf("alice@example.com", "\"Doe, Bob\" <bob@example.com>", "Eve <eve@example.com>"),
            splitAddresses("alice@example.com, \"Doe, Bob\" <bob@example.com>,Eve <eve@example.com>"),
        )
        assertEquals(emptyList<String>(), splitAddresses(" , "))
        assertEquals("bob@example.com", bareAddress("\"Doe, Bob\" <Bob@Example.com>"))
        assertEquals("a@b.c", bareAddress(" A@B.c "))
    }

    @Test
    fun replyAllOnlyAppearsWithMoreThanOneOtherPerson() {
        val me = listOf("me@example.com")
        // Just the sender and me: nothing extra to reply to.
        assertFalse(hasMultipleRecipients(msg(), me))
        // A second recipient.
        assertTrue(hasMultipleRecipients(msg(cc = "carol@example.com"), me))
        // The same person on To and Cc counts once.
        assertFalse(hasMultipleRecipients(msg(to = "me@example.com, dana@example.com", cc = "Dana <DANA@example.com>"), me))
        // An empty own address excludes nothing, rather than everything (the web's issue #360).
        assertTrue(hasMultipleRecipients(msg(), emptyList()))
        // My address in any case is not "another person".
        assertFalse(hasMultipleRecipients(msg(to = "ME@example.com"), me))
    }

    @Test
    fun replyAllFromSomeoneElseCopiesEveryoneButMe() {
        val d = replyAllDraft(msg(to = "me@example.com, \"Doe, Bob\" <bob@example.com>", cc = "carol@example.com"), "acc", listOf("ME@example.com"))
        assertEquals("dana@example.com", d.to)
        assertEquals("\"Doe, Bob\" <bob@example.com>, carol@example.com", d.cc)
        assertEquals("Re: Hello", d.subject)
        assertEquals("<m9@x>", d.inReplyTo)
        assertEquals("<r1@x> <m9@x>", d.references)
        assertEquals("9", d.sourceUid)
        assertEquals("acc", d.accountId)
    }

    @Test
    fun replyAllFromMyselfGoesBackToTheOriginalRecipients() {
        val d = replyAllDraft(
            msg(from = "me@example.com", to = "bob@example.com, carol@example.com", cc = "carol@example.com, dave@example.com"),
            "acc",
            listOf("me@example.com"),
        )
        assertEquals("bob@example.com, carol@example.com", d.to)
        assertEquals("dave@example.com", d.cc)
        // Written only to myself: fall back to the sender rather than an empty To.
        val self = replyAllDraft(msg(from = "me@example.com", to = "me@example.com"), "acc", listOf("me@example.com"))
        assertEquals("me@example.com", self.to)
    }

    @Test
    fun forwardHasNoRecipientNoThreadingAndMarksNothingAnswered() {
        val d = forwardDraft(msg(to = "me@example.com", subject = "Invoice"), "acc")
        assertEquals("", d.to)
        assertEquals("Fwd: Invoice", d.subject)
        assertNull(d.inReplyTo)
        assertNull(d.references)
        assertNull(d.sourceUid)
        assertTrue(d.body.contains("---------- Forwarded message ----------"))
        assertTrue(d.body.contains("From: Dana <dana@example.com>"))
        assertTrue(d.body.contains("Subject: Invoice"))
        assertTrue(d.body.contains("To: me@example.com"))
        assertTrue(d.body.endsWith("\n\nBody text"))
    }

    @Test
    fun forwardSubjectIsNotPrefixedTwice() {
        assertEquals("Fwd: x", forwardSubject("  x "))
        assertEquals("Fwd: x", forwardSubject("Fwd: x"))
        assertEquals("FW: x", forwardSubject("FW: x"))
        assertEquals("Fw: x", forwardSubject("Fw: x"))
    }

    @Test
    fun forwardOfHtmlOnlyMailQuotesItsText() {
        val d = forwardDraft(msg(body = "", html = "<p>Only <b>html</b></p>"), null)
        assertTrue(d.body.endsWith("\n\nOnly html"))
    }

    @Test
    fun anOpenMessageBecomesAnInboxRow() {
        val s = msg().copy(attachments = listOf(EmailAttachment(0, "a.pdf", "application/pdf", 1, false, ""))).toSummary("acc")
        assertEquals("acc/INBOX/9", s.key)
        assertEquals("<m9@x>", s.messageId)
        assertTrue(s.hasAttachments)
        assertEquals(1_791_451_800.0, s.dateEpoch, 0.0)
    }

    @Test
    fun contactsAreSavedUnderTheDisplayNameOrTheLocalPart() {
        assertEquals("Dana", contactName(msg()))
        assertEquals("dana", contactName(msg().copy(fromName = " ")))
    }

    @Test
    fun neighboursAreTheRowsEitherSide() {
        val list = listOf(row("3"), row("2"), row("1"))
        val mid = neighbours(list, "acc/INBOX/2")!!
        assertEquals("3", mid.previous?.uid)
        assertEquals("1", mid.next?.uid)
        assertEquals(2, mid.position)
        assertEquals(3, mid.total)
        assertNull(neighbours(list, "acc/INBOX/3")!!.previous)
        assertNull(neighbours(list, "acc/INBOX/1")!!.next)
        assertNull(neighbours(list, "acc/INBOX/99"))
        assertNull(neighbours(emptyList(), "acc/INBOX/1"))
    }

    @Test
    fun theWebsLanguagesAreOffered() {
        assertEquals(listOf("English", "Swedish", "Japanese", "Spanish", "French", "German"), TRANSLATE_LANGUAGES)
    }
}
