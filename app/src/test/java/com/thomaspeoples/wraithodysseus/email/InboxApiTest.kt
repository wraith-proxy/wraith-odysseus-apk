package com.thomaspeoples.wraithodysseus.email

import com.thomaspeoples.wraithodysseus.net.ApiException
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

/** The EmailApi calls the web-style inbox added: path, method, query, body,
 * and the 200-with-error answers the server uses for most failures. */
class InboxApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private val server = MockWebServer()
    private lateinit var api: EmailApi

    @Before
    fun setUp() {
        server.start()
        api = EmailApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun reply(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private suspend fun errorOf(block: suspend () -> Unit): String =
        (runCatching { block() }.exceptionOrNull() as? ApiException)?.message ?: throw AssertionError("expected ApiException")

    private val row = """{"uid":"7","message_id":"<m7@x>","subject":"Hi","from_name":"Bo","from_address":"bo@x.com",
        "to":"me@x.com","date_epoch":1791480600.0,"is_read":true,"is_answered":true,"is_flagged":true,"has_attachments":true}"""

    @Test
    fun listPageSendsFolderFilterAndPagingAndReadsFlags() = runBlocking {
        reply("""{"emails":[$row],"total":120,"folder":"Archive","sync":{"updated_at":"2026-10-09T10:00:00Z"}}""")
        val page = api.listPage("acc", "Archive", "tag:reply-soon", 50)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        val url = req.requestUrl!!
        assertEquals("/api/email/list", url.encodedPath)
        assertEquals("Archive", url.queryParameter("folder"))
        assertEquals("tag:reply-soon", url.queryParameter("filter"))
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("50", url.queryParameter("offset"))
        assertEquals("acc", url.queryParameter("account_id"))
        assertNull(url.queryParameter("cached_only"))
        assertNull(url.queryParameter("refresh"))
        assertEquals(120, page.total)
        assertEquals("2026-10-09T10:00:00Z", page.updatedAt)
        val e = page.emails.single()
        assertTrue(e.isRead && e.isAnswered && e.isFlagged && e.hasAttachments)
        assertEquals("me@x.com", e.to)
        assertEquals("acc/Archive/7", e.key)
    }

    @Test
    fun listPageAsksForTheIndexOnlyAndForARefresh() = runBlocking {
        reply("""{"emails":[],"total":0}""")
        api.listPage("acc", "INBOX", "all", 0, cachedOnly = true)
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("cached_only"))
        reply("""{"emails":[],"total":0}""")
        val page = api.listPage("acc", "INBOX", "all", 0, refresh = true)
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("refresh"))
        assertNull(page.updatedAt)
    }

    @Test
    fun listPageSurfacesA200Error() = runBlocking {
        reply("""{"emails":[],"total":0,"error":"Folder not found: Nope"}""")
        assertEquals("Folder not found: Nope", errorOf { api.listPage("acc", "Nope", "all", 0) })
    }

    @Test
    fun unreadStateReadsCountAndMaxUidForTheInbox() = runBlocking {
        reply("""{"unread_count":4,"max_uid":991,"folder":"INBOX"}""")
        val s = api.unreadState("acc")
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/email/unread-state", url.encodedPath)
        assertEquals("INBOX", url.queryParameter("folder"))
        assertEquals("acc", url.queryParameter("account_id"))
        assertEquals(UnreadState(4, 991), s)
        reply("""{"error":"Account not found"}""")
        assertEquals("Account not found", errorOf { api.unreadState("zzz") })
    }

    @Test
    fun foldersAreReadAndATimeoutFallbackStillCounts() = runBlocking {
        reply("""{"folders":["INBOX","Sent","Archive"]}""")
        assertEquals(listOf("INBOX", "Sent", "Archive"), api.folders("acc"))
        assertEquals("acc", server.takeRequest().requestUrl!!.queryParameter("account_id"))
        reply("""{"folders":["INBOX","Sent","Archive"],"error":"Folder list timed out"}""")
        assertEquals(3, api.folders(null).size)
        reply("""{"folders":[],"error":"Mail operation failed"}""")
        assertEquals("Mail operation failed", errorOf { api.folders(null) })
    }

    @Test
    fun scheduledListAndCancel() = runBlocking {
        reply("""{"scheduled":[{"id":"s-1","to":"a@x.com","cc":"","subject":"Later","send_at":"2026-10-10T08:00:00","created_at":"x","status":"failed","error":"SMTP down"},
            {"id":"s-2","to":"b@x.com","cc":"","subject":"Soon","send_at":"2026-10-11T08:00:00","created_at":"x","status":"pending","error":null}]}""")
        val items = api.scheduled()
        assertEquals("/api/email/scheduled", server.takeRequest().path)
        assertEquals(listOf("s-1", "s-2"), items.map { it.id })
        assertEquals("SMTP down", items[0].error)
        assertEquals("", items[1].error)

        reply("""{"success":true}""")
        api.cancelScheduled("s-2")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/email/scheduled/s-2", req.path)

        reply("""{"success":false,"error":"Mail operation failed"}""")
        assertEquals("Mail operation failed", errorOf { api.cancelScheduled("s-2") })
    }

    @Test
    fun searchUnderTwoCharactersSendsNothing() = runBlocking {
        val page = api.search("acc", "INBOX", " a ", "all", localOnly = false)
        assertEquals(0, page.emails.size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun searchSendsQueryScopeAndLocalOnlyAndStampsTheFolderFound() = runBlocking {
        reply("""{"emails":[$row],"total":1,"query":"hi","folder":"[Gmail]/All Mail","source":"index","sync":{"updated_at":"2026-10-09T10:00:00Z"}}""")
        val page = api.search("acc", "INBOX", "  hi ", "all", localOnly = true)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/email/search", url.encodedPath)
        assertEquals("hi", url.queryParameter("q"))
        assertEquals("INBOX", url.queryParameter("folder"))
        assertEquals("all", url.queryParameter("scope"))
        assertEquals("1", url.queryParameter("local_only"))
        assertEquals("acc", url.queryParameter("account_id"))
        assertEquals("[Gmail]/All Mail", page.emails.single().folder)

        reply("""{"emails":[],"total":0}""")
        api.search("acc", "Sent", "hi", "folder", localOnly = false)
        val second = server.takeRequest().requestUrl!!
        assertNull(second.queryParameter("local_only"))
        assertEquals("folder", second.queryParameter("scope"))
    }

    @Test
    fun flagSendsFolderAccountAndOn() = runBlocking {
        reply("""{"success":true,"flagged":true}""")
        api.flag("acc", "Sent", "7", true)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        val url = req.requestUrl!!
        assertEquals("/api/email/flag/7", url.encodedPath)
        assertEquals("Sent", url.queryParameter("folder"))
        assertEquals("acc", url.queryParameter("account_id"))
        assertEquals("true", url.queryParameter("on"))
        reply("""{"success":true,"flagged":false}""")
        api.flag("acc", "Sent", "7", false)
        assertEquals("false", server.takeRequest().requestUrl!!.queryParameter("on"))
        reply("""{"success":false,"error":"Email not found"}""")
        assertEquals("Email not found", errorOf { api.flag("acc", "Sent", "7", true) })
    }

    @Test
    fun doneAndReadAndArchiveArePostsWithFolderAndAccount() = runBlocking {
        fun case(path: String, call: suspend () -> Unit) = path to call
        for ((path, call) in listOf(
            case("/api/email/mark-answered/9") { api.markAnswered("acc", "INBOX", "9") },
            case("/api/email/clear-answered/9") { api.clearAnswered("acc", "INBOX", "9") },
            case("/api/email/mark-read/9") { api.markRead("acc", "INBOX", "9") },
            case("/api/email/mark-unread/9") { api.markUnread("acc", "INBOX", "9") },
            case("/api/email/archive/9") { api.archive("acc", "INBOX", "9") },
        )) {
            reply("""{"success":true}""")
            call()
            val req = server.takeRequest()
            assertEquals(path, "POST", req.method)
            assertEquals(path, req.requestUrl!!.encodedPath)
            assertEquals(path, "INBOX", req.requestUrl!!.queryParameter("folder"))
            assertEquals(path, "acc", req.requestUrl!!.queryParameter("account_id"))
        }
        reply("""{"success":false,"error":"Email not found"}""")
        assertEquals("Email not found", errorOf { api.archive("acc", "INBOX", "9") })
    }

    @Test
    fun deleteSendsMessageIdAndNeedsExplicitSuccess() = runBlocking {
        reply("""{"success":true}""")
        api.delete("acc", "INBOX", "7", "<m7@x>")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        val url = req.requestUrl!!
        assertEquals("/api/email/delete/7", url.encodedPath)
        assertEquals("INBOX", url.queryParameter("folder"))
        assertEquals("acc", url.queryParameter("account_id"))
        assertEquals("<m7@x>", url.queryParameter("message_id"))

        reply("""{"success":true}""")
        api.delete(null, "INBOX", "7", "")
        val bare = server.takeRequest().requestUrl!!
        assertNull(bare.queryParameter("message_id"))
        assertNull(bare.queryParameter("account_id"))

        reply("""{"success":false,"error":"Could not open email folder"}""")
        assertEquals("Could not open email folder", errorOf { api.delete("acc", "INBOX", "7", "<m7@x>") })
        reply("""{}""")
        assertEquals("Request failed", errorOf { api.delete("acc", "INBOX", "7", "<m7@x>") })
    }

    @Test
    fun deleteBulkBodyCarriesUidsFolderAndAccount() = runBlocking {
        reply("""{"success":true,"deleted_uids":["1","2"],"failed_uids":["3"]}""")
        val result = api.deleteBulk("acc", "INBOX", listOf("1", "2", "3"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/email/delete-bulk", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals(listOf("1", "2", "3"), (0 until 3).map { body.getJSONArray("uids").getString(it) })
        assertEquals("INBOX", body.getString("folder"))
        assertEquals("acc", body.getString("account_id"))
        assertEquals(BulkDelete(listOf("1", "2"), listOf("3")), result)

        assertTrue(EmailApi.deleteBulkJson(null, "INBOX", listOf("1")).isNull("account_id"))
        reply("""{"success":false,"error":"No email UIDs provided","deleted_uids":[],"failed_uids":[]}""")
        assertEquals("No email UIDs provided", errorOf { api.deleteBulk("acc", "INBOX", emptyList()) })
    }

    @Test
    fun createNotePostsTheNoteJson() = runBlocking {
        reply("""{"id":"n1"}""")
        api.createNote(JSONObject().put("title", "Reply: Hi"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/notes", req.path)
        assertEquals("Reply: Hi", JSONObject(req.body.readUtf8()).getString("title"))
        reply("""{"error":"nope"}""")
        assertEquals("nope", errorOf { api.createNote(JSONObject()) })
        assertFalse(api.origin().endsWith("/"))
    }
}
