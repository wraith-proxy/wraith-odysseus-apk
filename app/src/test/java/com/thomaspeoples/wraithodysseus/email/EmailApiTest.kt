package com.thomaspeoples.wraithodysseus.email

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmailApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private val server = MockWebServer()
    private lateinit var api: EmailApi

    @Before
    fun setUp() {
        server.start()
        val http = Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false)))
        api = EmailApi(http, sendTimeoutSeconds = 1)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun reply(name: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(fixture(name)))

    private suspend fun errorOf(block: suspend () -> Unit): String =
        (runCatching { block() }.exceptionOrNull() as? ApiException)?.message
            ?: throw AssertionError("expected ApiException")

    // -- checkBody --------------------------------------------------------

    @Test
    fun checkBodyTurnsHttp200FailuresIntoErrors() {
        for ((name, text) in listOf(
            "email_list_error.json" to "Mail operation failed: LOGIN failed",
            "email_list_folder_error.json" to "Folder not found: INBOX",
            "email_read_error.json" to "Email UID 1042 not found",
            "email_mark_unread_fail.json" to "Email not found",
            "email_send_fail.json" to "(535, b'Authentication failed')",
        )) {
            val e = runCatching { EmailApi.checkBody(fixture(name)) }.exceptionOrNull() as ApiException
            assertEquals(name, text, e.message)
            assertEquals(200, e.status)
        }
        assertEquals("Request failed", (runCatching { EmailApi.checkBody("""{"success":false}""") }.exceptionOrNull() as ApiException).message)
        assertEquals("Unexpected response", (runCatching { EmailApi.checkBody("<html>") }.exceptionOrNull() as ApiException).message)
    }

    @Test
    fun checkBodyPassesSuccessAndPlainBodies() {
        EmailApi.checkBody(fixture("email_mark_unread_ok.json"))
        EmailApi.checkBody(fixture("email_list_empty.json"))
        EmailApi.checkBody(fixture("email_send_ok_no_sent_folder.json"))
        // An empty error string is not an error.
        EmailApi.checkBody("""{"emails":[],"total":0,"error":""}""")
    }

    // -- accounts and list ------------------------------------------------

    @Test
    fun accountsAreUnwrapped() = runBlocking {
        reply("email_accounts.json")
        val accounts = api.accounts()
        assertEquals(listOf("acc-home", "acc-work", "acc-old"), accounts.map { it.id })
        assertTrue(accounts[0].isDefault)
        assertFalse(accounts[2].enabled)
        assertEquals("Work", accounts[1].name)
        assertEquals("/api/email/accounts", server.takeRequest().path)

        reply("email_accounts_empty.json")
        assertEquals(emptyList<EmailAccount>(), api.accounts())
    }

    @Test
    fun unreadPageAsksForUnreadInboxAndStampsTheAccount() = runBlocking {
        reply("email_list_unread_home_p0.json")
        val page = api.unreadPage("acc-home", 0)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/email/list", url.encodedPath)
        assertEquals("INBOX", url.queryParameter("folder"))
        assertEquals("unread", url.queryParameter("filter"))
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("offset"))
        assertEquals("acc-home", url.queryParameter("account_id"))

        assertEquals(3, page.total)
        assertEquals(listOf("1042", "1039"), page.emails.map { it.uid })
        val first = page.emails[0]
        assertEquals("acc-home", first.accountId)
        assertEquals("INBOX", first.folder)
        assertEquals("Carol Sample", first.fromName)
        assertEquals("<a1@example.com>", first.messageId)
        assertEquals(1791480600.0, first.dateEpoch, 0.0)
        assertEquals("acc-home/INBOX/1042", first.key)
    }

    @Test
    fun theFallbackPassSendsNoAccountId() = runBlocking {
        reply("email_list_empty.json")
        val page = api.unreadPage(null, 0)
        assertNull(server.takeRequest().requestUrl!!.queryParameter("account_id"))
        assertEquals(0, page.emails.size)
    }

    @Test
    fun aListErrorInsideHttp200Throws() = runBlocking {
        reply("email_list_error.json")
        assertEquals("Mail operation failed: LOGIN failed", errorOf { api.unreadPage("acc-work", 0) })
    }

    @Test
    fun aMalformedEntryFailsTheCallInsteadOfCrashing() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"accounts":[{"name":"Home"}]}"""))
        assertEquals("Unexpected response", errorOf { api.accounts() })
        server.enqueue(MockResponse().setBody("""{"emails":["not an object"],"total":1}"""))
        assertEquals("Unexpected response", errorOf { api.unreadPage("acc-home", 0) })
    }

    @Test
    fun aForeignAccountIs404() = runBlocking {
        reply("email_account_404.json", 404)
        assertEquals("Account not found", errorOf { api.unreadPage("acc-other", 0) })
    }

    @Test
    fun pagingTheFixturesStopsAtTheTotal() = runBlocking {
        reply("email_list_unread_home_p0.json")
        reply("email_list_unread_home_p2.json")
        val pass = pageUnread { api.unreadPage("acc-home", it) }
        assertEquals(listOf("1042", "1039", "1001"), pass.emails.map { it.uid })
        assertEquals(3, pass.total)
        assertNull(pass.error)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("2", server.takeRequest().requestUrl!!.queryParameter("offset"))
    }

    // -- read and mark unread ---------------------------------------------

    @Test
    fun readReturnsTheTextBody() = runBlocking {
        reply("email_read.json")
        val m = api.read("acc-home", "INBOX", "1042")
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/email/read/1042", url.encodedPath)
        assertEquals("INBOX", url.queryParameter("folder"))
        assertEquals("acc-home", url.queryParameter("account_id"))
        assertEquals("Hi Alice,\n\nAre you free for dinner on Friday?\n\nCarol", m.body)
        assertEquals("<a00@example.com> <a0@example.com>", m.references)
        assertFalse(m.markSeenFailed)

        reply("email_read_seen_failed.json")
        assertTrue(api.read("acc-home", "INBOX", "1042").markSeenFailed)

        reply("email_read_error.json")
        assertEquals("Email UID 1042 not found", errorOf { api.read("acc-home", "INBOX", "1042") })
    }

    @Test
    fun markUnreadPostsAndChecksTheBody() = runBlocking {
        reply("email_mark_unread_ok.json")
        api.markUnread(null, "INBOX", "1042")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/email/mark-unread/1042?folder=INBOX", req.path)

        reply("email_mark_unread_fail.json")
        assertEquals("Email not found", errorOf { api.markUnread(null, "INBOX", "1042") })
    }

    // -- send outcomes ----------------------------------------------------

    private val message = SendRequest("carol@example.com", "", "Hello", "Body", "acc-home")

    @Test
    fun sendWaitsForDeliveryAndReportsSent() = runBlocking {
        reply("email_send_ok.json")
        assertEquals(SendResult.Sent, api.send(message))
        val req = server.takeRequest()
        assertEquals("/api/email/send", req.path)
        val json = JSONObject(req.body.readUtf8())
        assertEquals(true, json.get("wait_for_delivery"))
        assertEquals("acc-home", json.get("account_id"))

        // A null sent_folder is still a successful send.
        reply("email_send_ok_no_sent_folder.json")
        assertEquals(SendResult.Sent, api.send(message))
    }

    @Test
    fun sendFailuresInsideHttp200AreShown() = runBlocking {
        reply("email_send_fail.json")
        assertEquals(SendResult.Failed("(535, b'Authentication failed')"), api.send(message))
        reply("email_send_no_account.json")
        assertEquals(SendResult.Failed("No SMTP-capable email account configured"), api.send(message))
    }

    @Test
    fun aGatewayTimeoutIsUnconfirmedAndNotRetried() = runBlocking {
        reply("email_send_504.json", 504)
        assertEquals(SendResult.Unconfirmed, api.send(message))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aReadTimeoutIsUnconfirmedAndNotRetried() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(SendResult.Unconfirmed, api.send(message))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aConnectionCutAfterTheBodyIsUnconfirmed() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(SendResult.Unconfirmed, api.send(message))
        // And never re-sent behind the user's back.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun noServerToConnectToFailsRatherThanCrashing() = runBlocking {
        // A port that was free a moment ago: the connect is refused.
        val port = ServerSocket(0).use { it.localPort }
        val origin = "http://127.0.0.1:$port/".toHttpUrl()
        val offline = EmailApi(Http(FakeSession(origin, SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
        assertEquals(SendResult.Failed("Could not reach the server"), offline.send(message))
    }

    @Test
    fun otherHttpErrorsFail() = runBlocking {
        reply("email_account_404.json", 404)
        assertEquals(SendResult.Failed("Account not found"), api.send(message))
    }

    @Test
    fun sendJsonLeavesOutWhatIsNotSet() {
        val json = EmailApi.sendJson(SendRequest("a@example.com", " ", "S", "B", accountId = null))
        assertFalse(json.has("account_id"))
        assertFalse(json.has("cc"))
        assertFalse(json.has("in_reply_to"))
        assertFalse(json.has("references"))
        assertFalse(json.has("source_uid"))
        assertEquals(true, json.get("wait_for_delivery"))
    }
}
