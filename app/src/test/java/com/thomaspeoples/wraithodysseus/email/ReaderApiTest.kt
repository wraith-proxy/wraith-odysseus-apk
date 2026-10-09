package com.thomaspeoples.wraithodysseus.email

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The calls the HTML reader added to [EmailApi]. */
class ReaderApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private val server = MockWebServer()
    private lateinit var api: EmailApi

    @Before
    fun setUp() {
        server.start()
        api = EmailApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))

    private suspend fun errorOf(block: suspend () -> Unit): ApiException =
        (runCatching { block() }.exceptionOrNull() as? ApiException) ?: throw AssertionError("expected ApiException")

    // -- read -----------------------------------------------------------------

    @Test
    fun readCarriesTheHtmlAndTheAttachmentLists() = runBlocking {
        json(fixture("email_read_html.json"))
        val m = api.read("acc-home", "INBOX", "2001")
        assertTrue(m.bodyHtml.startsWith("<p style="))
        assertEquals(listOf("invoice.pdf", "image001.png"), m.attachments.map { it.filename })
        assertEquals("1900", m.relatedAttachments.single().sourceUid)
        assertFalse(m.attachmentsDeferred)
        assertEquals("alice@example.com, \"Doe, Bob\" <bob@example.com>", m.to)
    }

    @Test
    fun readToleratesMissingOrNullHtml() = runBlocking {
        json("""{"uid":"1","body":"x","body_html":null,"attachments":[],"attachments_deferred":true}""")
        val m = api.read(null, "INBOX", "1")
        assertEquals("", m.bodyHtml)
        assertTrue(m.attachmentsDeferred)
        json("""{"uid":"1","body":"x"}""")
        assertEquals("", api.read(null, "INBOX", "1").bodyHtml)
    }

    @Test
    fun attachmentsAreListedAndErrorsInA200AreShown() = runBlocking {
        json("""{"attachments":[{"index":0,"filename":"a.pdf","content_type":"application/pdf","size":5,"is_inline":false,"content_id":""}],"uid":"1"}""")
        val list = api.attachments("acc", "Archive", "1")
        assertEquals("a.pdf", list.single().filename)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/email/attachments/1", url.encodedPath)
        assertEquals("Archive", url.queryParameter("folder"))
        assertEquals("acc", url.queryParameter("account_id"))

        json("""{"attachments":[],"error":"Mail operation failed"}""")
        assertEquals("Mail operation failed", errorOf { api.attachments(null, "INBOX", "1") }.message)
    }

    // -- attachment download ----------------------------------------------------

    @Test
    fun anAttachmentIsSavedToTheGivenFile() = runBlocking {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(bytes)))
        val dest = File.createTempFile("att", ".bin").also { it.delete() }.resolve("sub/report.pdf")
        try {
            api.saveAttachment("acc", "INBOX", "7", 3, dest)
            assertArrayEquals(bytes, dest.readBytes())
            val url = server.takeRequest().requestUrl!!
            assertEquals("/api/email/attachment/7/3", url.encodedPath)
            assertEquals("INBOX", url.queryParameter("folder"))
            assertEquals("acc", url.queryParameter("account_id"))
        } finally {
            dest.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun aJsonAnswerToAnAttachmentRequestIsAnErrorAndNothingIsSaved() = runBlocking {
        json("""{"error":"Attachment index 9 not found"}""")
        val dest = File.createTempFile("att", ".bin").also { it.delete() }
        val e = errorOf { api.saveAttachment(null, "INBOX", "7", 9, dest) }
        assertEquals("Attachment index 9 not found", e.message)
        assertFalse(dest.exists())

        // The same status with no message in it still reads as a failure.
        json("""{"nope":true}""")
        assertEquals("Could not download the attachment", errorOf { api.saveAttachment(null, "INBOX", "7", 9, dest) }.message)
        assertFalse(dest.exists())
    }

    @Test
    fun anAttachmentHttpErrorIsAnApiException() = runBlocking {
        json("""{"detail":"Not found"}""", 404)
        assertEquals("Not found", errorOf { api.saveAttachment(null, "INBOX", "7", 0, File.createTempFile("att", ".bin")) }.message)
    }

    // -- inline images -----------------------------------------------------------

    @Test
    fun theInlineImageUrlCarriesCidFolderAndAccount() {
        val url = api.inlineImageUrl("acc-home", "Sent Items", "42", "logo@x y")
        assertEquals("/api/email/inline-image/42", url.encodedPath)
        assertEquals("logo@x y", url.queryParameter("cid"))
        assertEquals("Sent Items", url.queryParameter("folder"))
        assertEquals("acc-home", url.queryParameter("account_id"))
        assertEquals("account_id=acc-home&cid=logo%40x%20y&folder=Sent%20Items", url.encodedQuery)
        assertNull(api.inlineImageUrl(null, "INBOX", "1", "c").queryParameter("account_id"))
    }

    @Test
    fun anInlineImageIsFetchedWithTheSessionCookie() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        val image = api.inlineImage("acc", "INBOX", "42", "logo@x")!!
        assertEquals("image/png", image.mime)
        assertArrayEquals(byteArrayOf(1, 2, 3), image.bytes)
        val req = server.takeRequest()
        assertEquals("/api/email/inline-image/42?account_id=acc&cid=logo%40x&folder=INBOX", req.path)
        assertEquals("odysseus_session=abc123", req.getHeader("Cookie"))
    }

    @Test
    fun aMissingInlineImageIsNullNotAnError() = runBlocking {
        json("""{"detail":"Inline image not found"}""", 404)
        assertNull(api.inlineImage(null, "INBOX", "42", "x"))
        json("""{"error":"Mail operation failed"}""")
        assertNull(api.inlineImage(null, "INBOX", "42", "x"))
    }

    // -- moving and deleting ---------------------------------------------------

    @Test
    fun moveToSpamPostsDestJunkWithFolderAndAccount() = runBlocking {
        json("""{"success":true}""")
        api.move("acc", "INBOX", "5", "Junk")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        val url = req.requestUrl!!
        assertEquals("Junk", url.queryParameter("dest"))
        assertEquals("INBOX", url.queryParameter("folder"))
        assertEquals("acc", url.queryParameter("account_id"))

        json("""{"success":false,"error":"Failed to move to Junk"}""")
        assertEquals("Failed to move to Junk", errorOf { api.move("acc", "INBOX", "5", "Junk") }.message)
    }

    @Test
    fun deletePermanentUsesDeleteAndNeedsAnExplicitSuccess() = runBlocking {
        json("""{"success":true}""")
        api.deletePermanent("acc", "Trash", "5")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/email/delete-permanent/5", req.requestUrl!!.encodedPath)
        assertEquals("Trash", req.requestUrl!!.queryParameter("folder"))
        assertEquals("acc", req.requestUrl!!.queryParameter("account_id"))

        json("""{"success":false,"error":"Email not found"}""")
        assertEquals("Email not found", errorOf { api.deletePermanent("acc", "Trash", "5") }.message)
        json("""{}""")
        assertEquals("Request failed", errorOf { api.deletePermanent("acc", "Trash", "5") }.message)
    }

    // -- translate ---------------------------------------------------------------

    @Test
    fun translateSendsTheBodyAndReturnsTheText() = runBlocking {
        json("""{"success":true,"translation":"<<<TRANSLATION>>> Hej värld <<<END>>>","language":"Swedish","model_used":"m"}""")
        val r = api.translate("Hello world", "Subject", "Dana <d@x>", "Swedish")
        assertEquals(TranslateResult.Done("Hej värld", "Swedish"), r)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/email/translate", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("Hello world", body.getString("body"))
        assertEquals("Subject", body.getString("subject"))
        assertEquals("Dana <d@x>", body.getString("from"))
        assertEquals("Swedish", body.getString("target_language"))
        assertFalse(body.getBoolean("auto"))
    }

    @Test
    fun translateReportsSameLanguage() = runBlocking {
        json("""{"success":true,"same_language":true,"language":"English"}""")
        assertEquals(TranslateResult.SameLanguage, api.translate("Hello", "", "", "English"))
    }

    @Test
    fun aTranslate504MeansStillWorkingAndIsNotRetried() = runBlocking {
        json("""<html>Gateway Time-out</html>""", 504)
        assertEquals(TranslateResult.StillWorking, api.translate("Hello", "", "", "French"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun otherTranslateFailuresAreErrors() = runBlocking {
        json("""{"success":false,"error":"No LLM endpoint configured"}""")
        assertEquals("No LLM endpoint configured", errorOf { api.translate("Hello", "", "", "French") }.message)
        json("""{"detail":"Server exploded"}""", 500)
        assertEquals("Server exploded", errorOf { api.translate("Hello", "", "", "French") }.message)
        json("""{"success":true,"translation":"  "}""")
        assertEquals("Empty response from model", errorOf { api.translate("Hello", "", "", "French") }.message)
    }

    // -- contacts and calendar -----------------------------------------------------

    @Test
    fun savingAContactPostsNameAndEmail() = runBlocking {
        json("""{"success":true}""")
        assertEquals(ContactResult.Saved, api.addContact("Dana", "dana@example.com"))
        val req = server.takeRequest()
        assertEquals("/api/contacts/add", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("Dana", body.getString("name"))
        assertEquals("dana@example.com", body.getString("email"))

        json("""{"success":true,"message":"Already exists","contact":{}}""")
        assertEquals(ContactResult.AlreadySaved, api.addContact("Dana", "dana@example.com"))
        json("""{"success":false,"error":"Name, email, phone, or address required"}""")
        assertEquals("Name, email, phone, or address required", errorOf { api.addContact("", "") }.message)
        json("""{"detail":"Admin required"}""", 403)
        assertEquals("Admin required", errorOf { api.addContact("Dana", "d@x") }.message)
    }

    @Test
    fun anIcsFileIsImportedAsMultipartField_file() = runBlocking {
        json("""{"ok":true,"imported":2,"skipped":1,"repaired":0,"calendar":"invite","calendar_id":"c","event_uids":["u1"]}""")
        val r = api.importCalendar("invite.ics", "BEGIN:VCALENDAR\nEND:VCALENDAR\n".toByteArray())
        assertEquals(CalendarImport(2, 1, "invite"), r)
        val req = server.takeRequest()
        assertEquals("/api/calendar/import", req.path)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val sent = req.body.readUtf8()
        assertTrue(sent, sent.contains("""name="file"; filename="invite.ics""""))
        assertTrue(sent.contains("BEGIN:VCALENDAR"))

        json("""{"detail":"Invalid ICS file: x"}""", 400)
        assertEquals("Invalid ICS file: x", errorOf { api.importCalendar("x.ics", ByteArray(1)) }.message)
        json("""{"imported":0}""")
        assertEquals("Import failed", errorOf { api.importCalendar("x.ics", ByteArray(1)) }.message)
    }

    // -- forward -------------------------------------------------------------------

    @Test
    fun anAttachmentIsStagedForForwarding() = runBlocking {
        json("""{"success":true,"token":"abc_report.pdf","filename":"report.pdf","size":10}""")
        assertEquals("abc_report.pdf", api.stageAttachment("acc", "INBOX", "7", 2))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/email/compose-from-attachment/7/2", req.requestUrl!!.encodedPath)
        assertEquals("acc", req.requestUrl!!.queryParameter("account_id"))

        json("""{"success":false,"error":"Attachment index 2 not found"}""")
        assertEquals("Attachment index 2 not found", errorOf { api.stageAttachment("acc", "INBOX", "7", 2) }.message)
        json("""{"success":true}""")
        assertEquals("Could not attach the file", errorOf { api.stageAttachment("acc", "INBOX", "7", 2) }.message)
    }

    @Test
    fun sendJsonCarriesAttachmentTokensOnlyWhenThereAreSome() {
        val plain = EmailApi.sendJson(SendRequest("a@x", "", "S", "B", null))
        assertFalse(plain.has("attachments"))
        val withFiles = EmailApi.sendJson(SendRequest("a@x", "", "S", "B", null, attachments = listOf("t1", "t2")))
        assertEquals(listOf("t1", "t2"), (0 until 2).map { withFiles.getJSONArray("attachments").getString(it) })
    }
}
