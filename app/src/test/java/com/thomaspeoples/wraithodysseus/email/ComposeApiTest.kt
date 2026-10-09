package com.thomaspeoples.wraithodysseus.email

import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComposeApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private val server = MockWebServer()
    private lateinit var api: EmailApi

    @Before
    fun setUp() {
        server.start()
        api = EmailApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))), sendTimeoutSeconds = 1)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private val request = SendRequest(
        to = "a@x.com", cc = "c@x.com", subject = "Hi", body = "Hello", accountId = "acc",
        inReplyTo = "<m1@x>", references = "<r@x> <m1@x>", sourceUid = "42", sourceFolder = "INBOX",
        bcc = "b@x.com", attachments = listOf("tok_a.pdf"),
    )

    @Test
    fun uploadSendsTheFileAsMultipartFieldFile() = runBlocking {
        json("""{"success":true,"token":"abc_report.pdf","filename":"report.pdf","size":5}""")
        val staged = api.uploadAttachment("report.pdf", "application/pdf") { "hello".byteInputStream() }
        assertEquals(StagedFile("abc_report.pdf", "report.pdf", 5), staged)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/email/compose-upload", sent.path)
        assertTrue(sent.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = sent.body.readUtf8()
        assertTrue(body, body.contains("name=\"file\"; filename=\"report.pdf\""))
        assertTrue(body.contains("Content-Type: application/pdf"))
        assertTrue(body.contains("hello"))
    }

    @Test
    fun anUploadTheServerRefusesIsAnError() = runBlocking {
        json("""{"success":false,"error":"Mail operation failed"}""")
        val failure = runCatching { api.uploadAttachment("a.bin", "x/y") { ByteArray(1).inputStream() } }.exceptionOrNull()
        assertEquals("Mail operation failed", failure?.message)
        json("""{"detail":"Attachment too large"}""", 413)
        assertTrue(runCatching { api.uploadAttachment("a.bin", "x/y") { ByteArray(1).inputStream() } }.isFailure)
    }

    @Test
    fun removingAnUploadDeletesItsToken() = runBlocking {
        json("""{"success":true}""")
        api.removeAttachment("abc_my report.pdf")
        val sent = server.takeRequest()
        assertEquals("DELETE", sent.method)
        assertEquals("/api/email/compose-upload/abc_my%20report.pdf", sent.path)
    }

    @Test
    fun draftThenSendSavesTheCopyFirstAndPassesItsIds() = runBlocking {
        json("""{"success":true,"message":"Draft saved","draft_folder":"Drafts","draft_uid":"77"}""")
        json("""{"success":true,"sent_uid":"5"}""")
        val (result, draft) = api.sendWithRecovery(request, null)
        assertEquals(SendResult.Sent, result)
        assertEquals(DraftRef("77", "Drafts"), draft)

        val saved = server.takeRequest()
        assertEquals("/api/email/draft", saved.path)
        val d = JSONObject(saved.body.readUtf8())
        assertEquals("a@x.com", d.getString("to"))
        assertEquals("c@x.com", d.getString("cc"))
        assertEquals("b@x.com", d.getString("bcc"))
        assertEquals("<m1@x>", d.getString("in_reply_to"))
        assertEquals("tok_a.pdf", d.getJSONArray("attachments").getString(0))
        assertFalse(d.has("draft_uid"))
        assertFalse(d.has("source_uid"))

        val sent = server.takeRequest()
        assertEquals("/api/email/send", sent.path)
        val s = JSONObject(sent.body.readUtf8())
        assertEquals("77", s.getString("draft_uid"))
        assertEquals("Drafts", s.getString("draft_folder"))
        assertEquals("42", s.getString("source_uid"))
        assertEquals("INBOX", s.getString("source_folder"))
        assertEquals("acc", s.getString("account_id"))
        assertEquals("b@x.com", s.getString("bcc"))
        assertEquals("<r@x> <m1@x>", s.getString("references"))
        assertTrue(s.getBoolean("wait_for_delivery"))
    }

    @Test
    fun aFailedSendKeepsTheDraftAndTheNextTryReplacesIt() = runBlocking {
        json("""{"success":true,"draft_folder":"Drafts","draft_uid":"77"}""")
        json("""{"success":false,"error":"(535, b'Authentication failed')"}""")
        val (result, draft) = api.sendWithRecovery(request, null)
        assertEquals(SendResult.Failed("(535, b'Authentication failed')"), result)
        assertEquals("77", draft?.uid)
        server.takeRequest(); server.takeRequest()

        json("""{"success":true,"draft_folder":"Drafts","draft_uid":"78"}""")
        json("""{"success":true}""")
        api.sendWithRecovery(request, draft)
        val again = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("77", again.getString("draft_uid"))
        assertEquals("Drafts", again.getString("draft_folder"))
        assertEquals("78", JSONObject(server.takeRequest().body.readUtf8()).getString("draft_uid"))
    }

    @Test
    fun nothingIsSentWhenTheRecoveryDraftCannotBeSaved() = runBlocking {
        json("""{"success":false,"error":"IMAP APPEND failed: NO"}""")
        val (result, draft) = api.sendWithRecovery(request, null)
        assertTrue(result is SendResult.Failed)
        assertTrue((result as SendResult.Failed).message.contains("IMAP APPEND failed: NO"))
        assertNull(draft)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aDraftWithNoIdsSendsWithoutDraftFields() = runBlocking {
        json("""{"success":true,"message":"Draft saved","draft_folder":"Drafts","draft_uid":null}""")
        json("""{"success":true}""")
        api.sendWithRecovery(request, null)
        server.takeRequest()
        val s = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(s.has("draft_uid"))
        assertFalse(s.has("draft_folder"))
    }

    @Test
    fun scheduleSendsTheMailWithUtcTime() = runBlocking {
        json("""{"success":true,"id":"abc","send_at":"2026-10-10T07:00:00"}""")
        api.schedule(request, "2026-10-10T07:00:00Z")
        val sent = server.takeRequest()
        assertEquals("/api/email/schedule", sent.path)
        val s = JSONObject(sent.body.readUtf8())
        assertEquals("2026-10-10T07:00:00Z", s.getString("send_at"))
        assertEquals("a@x.com", s.getString("to"))
        assertEquals("c@x.com", s.getString("cc"))
        assertEquals("b@x.com", s.getString("bcc"))
        assertEquals("Hi", s.getString("subject"))
        assertEquals("Hello", s.getString("body"))
        assertEquals("acc", s.getString("account_id"))
        assertEquals("<m1@x>", s.getString("in_reply_to"))
        assertEquals("tok_a.pdf", s.getJSONArray("attachments").getString(0))
        assertFalse(s.has("wait_for_delivery"))
    }

    @Test
    fun aRefusedScheduleIsAnError() = runBlocking {
        json("""{"success":false,"error":"send_at must be in the future"}""")
        val failure = runCatching { api.schedule(request, "2020-01-01T00:00:00Z") }.exceptionOrNull()
        assertEquals("send_at must be in the future", failure?.message)
    }

    // -- autocomplete -------------------------------------------------------

    @Test
    fun anAdminSearchesTheAddressBook() = runBlocking {
        json("""{"results":[{"name":"Bob Doe","emails":["bob@x.com","bob@y.com"]},{"name":"Eve","emails":[]}]}""")
        val found = api.contacts("bo", admin = true)
        assertEquals(listOf(Contact("Bob Doe", "bob@x.com"), Contact("Bob Doe", "bob@y.com")), found)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/contacts/search", url.encodedPath)
        assertEquals("bo", url.queryParameter("q"))
    }

    @Test
    fun anyoneElseUsesTheSendersList() = runBlocking {
        json("""{"contacts":[{"name":"Carol","address":"carol@x.com"}]}""")
        assertEquals(listOf(Contact("Carol", "carol@x.com")), api.contacts("ca", admin = false))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/email/contacts", url.encodedPath)
        assertEquals("ca", url.queryParameter("q"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRefusedAddressBookFallsBackToTheSendersList() = runBlocking {
        json("""{"detail":"Admin required"}""", 403)
        json("""{"contacts":[{"name":"Carol","address":"carol@x.com"}]}""")
        assertEquals(listOf(Contact("Carol", "carol@x.com")), api.contacts("ca", admin = true))
        assertEquals("/api/contacts/search", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/api/email/contacts", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun aFailedLookupGivesNoSuggestionsAndABlankQueryAsksNothing() = runBlocking {
        json("""{"contacts":[],"error":"Mail operation failed"}""")
        assertEquals(emptyList<Contact>(), api.contacts("x", admin = false))
        assertEquals(emptyList<Contact>(), api.contacts("  ", admin = true))
        assertEquals(1, server.requestCount)
    }
}
