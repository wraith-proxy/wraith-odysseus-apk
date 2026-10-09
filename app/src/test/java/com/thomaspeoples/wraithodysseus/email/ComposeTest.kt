package com.thomaspeoples.wraithodysseus.email

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeTest {

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

    // -- reply all, prefixes, references ----------------------------------

    @Test
    fun replyAllCcIsEveryoneButAllMyAddressesAndTheSender() {
        val d = replyAllDraft(
            msg(to = "me@example.com, Bob <bob@example.com>, work@corp.com", cc = "dana@example.com, Carol <carol@example.com>, BOB@example.com"),
            "acc",
            listOf("ME@example.com", "work@corp.com"),
        )
        assertEquals("dana@example.com", d.to)
        assertEquals("Bob <bob@example.com>, Carol <carol@example.com>", d.cc)
    }

    @Test
    fun prefixesAreNeverDoubled() {
        assertEquals("Re: Plan", replySubject("Re: Plan"))
        assertEquals("Re: Plan", replySubject("Plan"))
        assertEquals("Fwd: Plan", forwardSubject("Fwd: Plan"))
        assertEquals("Fw: Plan", forwardSubject("Fw: Plan"))
        assertEquals("Fwd: Re: Plan", forwardSubject("Re: Plan"))
        // The reply-all and forward drafts use the same rules.
        assertEquals("Re: Hello", replyAllDraft(msg(subject = "Re: Hello"), null, emptyList()).subject)
        assertEquals("FWD: Hello", forwardDraft(msg(subject = "FWD: Hello"), null).subject)
    }

    @Test
    fun referencesAreCarriedOverOnReplyAll() {
        assertEquals("<r1@x> <m9@x>", replyAllDraft(msg(), "acc", emptyList()).references)
        assertEquals("<m9@x>", replyAllDraft(msg().copy(references = ""), "acc", emptyList()).references)
    }

    // -- quoting HTML ------------------------------------------------------

    private val styled = "<html><head><title>Hi there</title><style type=\"text/css\">\n.x { color: red; }\n</style></head>" +
        "<body><!-- note to self --><p>Hello <b>Alice</b></p><script>var a = 1;</script><div>Bye</div></body></html>"

    @Test
    fun htmlToTextDropsStyleScriptHeadAndComments() {
        assertEquals("Hello Alice\n\nBye", htmlToText(styled))
    }

    @Test
    fun aQuoteOfHtmlMailHasNoStyleRulesAtTheTop() {
        // What the server sends as `body` for this mail: tags deleted, contents kept.
        val serverBody = "Hi there\n\n.x { color: red; }\n\nHello Alice\nvar a = 1;Bye"
        val original = msg(body = serverBody, html = styled)
        val quote = quotedBody(original, ZoneId.of("UTC").let { java.time.ZoneOffset.UTC })
        assertFalse(quote, quote.contains("color: red"))
        assertFalse(quote.contains("var a"))
        assertTrue(quote, quote.contains("> Hello Alice"))
        assertFalse(forwardDraft(original, null).body.contains("color: red"))
    }

    @Test
    fun aRealPlainTextPartIsQuotedAsSent() {
        val original = msg(body = "Plain words", html = styled)
        assertEquals("Plain words", quoteText(original))
        assertEquals("Plain words", quoteText(msg(body = "Plain words")))
        assertEquals("Hello Alice\n\nBye", quoteText(msg(body = "", html = styled)))
    }

    // -- attachment warning --------------------------------------------------

    @Test
    fun mentionsAttachmentUsesTheWebsWordList() {
        assertTrue(mentionsAttachment("Please find attached the report"))
        assertTrue(mentionsAttachment("See the Attachment."))
        assertTrue(mentionsAttachment("Anbei die Datei"))
        assertTrue(mentionsAttachment("PFA"))
        assertFalse(mentionsAttachment("Thanks, see you Friday"))
        assertFalse(mentionsAttachment("detached house"))
        assertFalse(mentionsAttachment(""))
    }

    @Test
    fun theQuotedOriginalDoesNotCount() {
        val body = "Sounds good.\n\n---------- Previous message ----------\nOn Wed, 7 Oct 2026 17:30, Carol wrote:\n> I attached the file"
        assertFalse(mentionsAttachment(body))
        assertFalse(mentionsAttachment("Sounds good.\n> the attachment\n"))
        assertTrue(mentionsAttachment("Attached is the file.\n> quoted\n"))
        assertFalse(mentionsAttachment("Fyi\n\n---------- Forwarded message ----------\nSubject: attachment"))
    }

    // -- schedule send ------------------------------------------------------

    // Friday 9 Oct 2026, 15:42:17 in Stockholm (UTC+2).
    private val friday = ZonedDateTime.of(2026, 10, 9, 15, 42, 17, 0, ZoneId.of("Europe/Stockholm"))

    @Test
    fun presetsCountFromTheClock() {
        assertEquals("2026-10-09T14:42:00Z", sendAtIso(presetTime(SchedulePreset.InAnHour, friday)))
        assertEquals("2026-10-09T16:42:00Z", sendAtIso(presetTime(SchedulePreset.InThreeHours, friday)))
        assertEquals("2026-10-10T07:00:00Z", sendAtIso(presetTime(SchedulePreset.TomorrowMorning, friday)))
        assertEquals("2026-10-12T07:00:00Z", sendAtIso(presetTime(SchedulePreset.MondayMorning, friday)))
    }

    @Test
    fun mondayIsNeverToday() {
        val monday = friday.plusDays(3).withHour(8)
        assertEquals("2026-10-19T07:00:00Z", sendAtIso(presetTime(SchedulePreset.MondayMorning, monday)))
        val sunday = friday.plusDays(2)
        assertEquals("2026-10-12T07:00:00Z", sendAtIso(presetTime(SchedulePreset.MondayMorning, sunday)))
    }

    @Test
    fun tomorrowNineFollowsTheClockChange() {
        // Stockholm leaves summer time on 25 Oct 2026: 9am the day after is UTC+1.
        val saturday = ZonedDateTime.of(2026, 10, 24, 20, 0, 0, 0, ZoneId.of("Europe/Stockholm"))
        assertEquals("2026-10-25T08:00:00Z", sendAtIso(presetTime(SchedulePreset.TomorrowMorning, saturday)))
    }

    @Test
    fun aPassedTimeIsRefused() {
        val now = friday.toInstant()
        assertTrue(isFuture(friday.plusMinutes(1), now))
        assertFalse(isFuture(friday, now))
        assertFalse(isFuture(friday.minusHours(1), Instant.from(friday)))
    }

    // -- recipients ---------------------------------------------------------

    @Test
    fun recipientsKeepNamesAndSkipDuplicates() {
        assertEquals("\"Doe, Bob\" <bob@x.com>", formatAddress("Doe, Bob", "bob@x.com"))
        assertEquals("Eve <eve@x.com>", formatAddress("Eve", "eve@x.com"))
        assertEquals("eve@x.com", formatAddress("", "eve@x.com"))
        assertEquals("eve@x.com", formatAddress("EVE@x.com", "eve@x.com"))
        assertEquals("Doe, Bob", chipLabel("\"Doe, Bob\" <bob@x.com>"))
        assertEquals("a@b.c", chipLabel("a@b.c"))
        assertEquals(listOf("A <a@x>", "b@x"), addRecipients(listOf("A <a@x>"), listOf("a@X", "b@x", " ", "B@x")))
        assertEquals("a@x, b@x, c@x", recipientHeader(listOf("a@x", "b@x"), "b@x; c@x"))
        assertEquals("", recipientHeader(emptyList(), "  "))
    }
}
