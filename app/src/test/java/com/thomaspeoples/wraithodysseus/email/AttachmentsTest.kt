package com.thomaspeoples.wraithodysseus.email

import com.thomaspeoples.wraithodysseus.net.ApiException
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsTest {

    private fun att(name: String, size: Long = 100_000) = EmailAttachment(0, name, "x/y", size, false, "")

    @Test
    fun signatureFilterFollowsTheWebsRulesExactly() {
        // Outlook placeholders, whatever their size.
        assertTrue(isLikelySignatureImage(att("image001.png", 500_000)))
        assertTrue(isLikelySignatureImage(att("Image12345.JPG", 500_000)))
        // image01 has too few digits, and bmp/webp are not in the placeholder rule.
        assertFalse(isLikelySignatureImage(att("image01.png", 500_000)))
        assertFalse(isLikelySignatureImage(att("image001.bmp", 500_000)))
        // Signature-like names, any size.
        for (n in listOf("signature.png", "logo_2.gif", "sig-3.jpeg", "footer.svg", "banner.jpg", "LOGO.PNG")) {
            assertTrue(n, isLikelySignatureImage(att(n, 500_000)))
        }
        assertFalse(isLikelySignatureImage(att("logo-big.png", 500_000)))
        assertFalse(isLikelySignatureImage(att("logo.webp", 500_000)))
        // Under 30 KB is small enough to be a signature image; 30 KB exactly is not; 0 is unknown.
        assertTrue(isLikelySignatureImage(att("photo.jpg", 30 * 1024 - 1)))
        assertFalse(isLikelySignatureImage(att("photo.jpg", 30 * 1024)))
        assertFalse(isLikelySignatureImage(att("photo.jpg", 0)))
        assertTrue(isLikelySignatureImage(att("thumb.webp", 1)))
        // Only images are ever hidden, however small.
        assertFalse(isLikelySignatureImage(att("notes.pdf", 10)))
        assertFalse(isLikelySignatureImage(att("invite.ics", 10)))
        assertFalse(isLikelySignatureImage(att("", 10)))
    }

    @Test
    fun calendarFilesAreRecognised() {
        for (n in listOf("invite.ics", "INVITE.ICS", "x.ical", "x.calendar")) assertTrue(n, isCalendarFile(n))
        assertFalse(isCalendarFile("x.ics.pdf"))
    }

    @Test
    fun aJsonContentTypeMeansTheServerReportedAnError() {
        assertTrue(isJsonType("application/json"))
        assertTrue(isJsonType("application/json; charset=utf-8"))
        assertTrue(isJsonType("Application/JSON"))
        assertFalse(isJsonType("application/octet-stream"))
        assertFalse(isJsonType("application/pdf"))
        assertFalse(isJsonType(null))
    }

    @Test
    fun theErrorTextIsPulledFromTheJsonBody() {
        assertEquals("Email not found", attachmentError("""{"error":"Email not found"}"""))
        assertEquals("Could not download the attachment", attachmentError("""{"success":true}"""))
        assertEquals("Could not download the attachment", attachmentError("<html>"))
    }

    @Test
    fun fileNamesCannotEscapeTheCacheFolder() {
        assertEquals("_.._passwd", safeFileName("../../passwd"))
        assertEquals("_evil.pdf", safeFileName("../evil.pdf"))
        assertFalse(safeFileName("a/../b").contains('/'))
        assertEquals("a_b_c.txt", safeFileName("a/b\\c.txt"))
        assertEquals("attachment", safeFileName("..."))
        assertEquals("fallback", safeFileName("", "fallback"))
        assertEquals(120, safeFileName("x".repeat(500)).length)
    }

    @Test
    fun mimeFallbacksCoverWhatAndroidDoesNotKnow() {
        assertEquals("message/rfc822", fallbackMime("mail.eml"))
        assertEquals("text/calendar", fallbackMime("a.ics"))
        assertEquals("application/octet-stream", fallbackMime("a.zzz"))
    }

    @Test
    fun sizesAreShortened() {
        assertEquals("900 B", attachmentSize(900))
        assertEquals("50 KB", attachmentSize(51_200))
        assertEquals("1.5 MB", attachmentSize(1_572_864))
    }

    @Test
    fun attachmentListsAreParsedIncludingTheirSource() {
        val list = parseAttachments(JSONArray("""[{"index":2,"filename":"a.pdf","content_type":"application/pdf","size":10,"is_inline":true,"content_id":"c1","source_uid":"1900","source_folder":"Archive"},
            {"index":0,"filename":"b.txt","uid":"77"}]"""))
        assertEquals(EmailAttachment(2, "a.pdf", "application/pdf", 10, true, "c1", "1900", "Archive"), list[0])
        assertEquals("77", list[1].sourceUid)
        assertNull(list[1].sourceFolder)
        assertEquals(emptyList<EmailAttachment>(), parseAttachments(null))
        val e = runCatching { parseAttachments(JSONArray("""[{"filename":"no index"}]""")) }.exceptionOrNull()
        assertTrue(e is ApiException)
    }

    @Test
    fun translationMarkersAreStripped() {
        assertEquals("Hej", cleanTranslation("<<<TRANSLATION>>>\nHej\n<<<END>>>"))
        assertEquals("Hej", cleanTranslation("  Hej "))
    }
}
