package com.thomaspeoples.wraithodysseus.ui.tools.notes

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

/** Every call NotesApi makes: path, method, body, and the answers the server gives. */
class NotesApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private val server = MockWebServer()
    private lateinit var api: NotesApi

    @Before
    fun setUp() {
        server.start()
        api = NotesApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private fun reply(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private val one = """{"id":"n9","title":"T","content":"c","note_type":"note","color":"red","label":"a b","pinned":false,"archived":false,"sort_order":-1}"""

    private val draft = NoteDraft("T", "note", "c", emptyList(), "red", "a b", sortOrder = -1)

    @Test
    fun listAsksForActiveNotesWithNoQueryAndParsesThem() = runBlocking {
        reply(fixture("notes_list.json"))
        val notes = api.list(archived = false)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/notes", req.requestUrl!!.encodedPath)
        assertNull(req.requestUrl!!.queryParameter("archived"))
        assertEquals("abc123", req.getHeader("Cookie")!!.substringAfter('='))
        assertEquals(4, notes.size)
    }

    @Test
    fun listArchivedSendsTheFlag() = runBlocking {
        reply("""{"notes":[]}""")
        api.list(archived = true)
        assertEquals("true", server.takeRequest().requestUrl!!.queryParameter("archived"))
    }

    @Test
    fun createPostsTheNoteWithSourceAndNeverRetries() = runBlocking {
        reply(one)
        val made = api.create(draft)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/notes", req.requestUrl!!.encodedPath)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("T", body.getString("title"))
        assertEquals("note", body.getString("note_type"))
        assertEquals("c", body.getString("content"))
        assertEquals("red", body.getString("color"))
        assertEquals("a b", body.getString("label"))
        assertEquals("user", body.getString("source"))
        assertEquals(-1, body.getInt("sort_order"))
        assertFalse(body.has("due_date"))
        assertFalse(body.has("repeat"))
        assertEquals("n9", made.id)
    }

    @Test
    fun createChecklistSendsItemsWithIdsAndDone() = runBlocking {
        reply(one)
        api.create(NoteDraft("L", "todo", "", listOf(NoteItem("x1", "Milk", true, 1)), "", ""))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("todo", body.getString("note_type"))
        assertFalse(body.has("content"))
        val item = body.getJSONArray("items").getJSONObject(0)
        assertEquals("x1", item.getString("id"))
        assertEquals("Milk", item.getString("text"))
        assertTrue(item.getBoolean("done"))
        assertEquals(1, item.getInt("indent"))
        assertEquals("", body.getString("label"))
        assertFalse(body.has("sort_order"))
    }

    @Test
    fun updatePutsToTheIdAndLeavesDueDateAlone() = runBlocking {
        reply(one)
        val note = NotesApi.parseNote(JSONObject(one))
        api.update(note, draft)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/notes/n9", req.requestUrl!!.encodedPath)
        val body = JSONObject(req.body.readUtf8())
        assertFalse(body.has("due_date"))
        assertFalse(body.has("source"))
        assertFalse(body.has("items"))
    }

    @Test
    fun turningAChecklistIntoANoteEmptiesItsItems() = runBlocking {
        reply(one)
        val checklist = NotesApi.parseNote(JSONObject("""{"id":"n9","note_type":"todo"}"""))
        api.update(checklist, draft)
        assertEquals(0, JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("items").length())
    }

    @Test
    fun deleteUsesDelete() = runBlocking {
        reply("""{"ok":true}""")
        api.delete("n9")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/notes/n9", req.requestUrl!!.encodedPath)
    }

    @Test
    fun pinAndArchiveReturnTheNewState() = runBlocking {
        reply("""{"ok":true,"pinned":true}""")
        reply("""{"ok":true,"archived":true}""")
        assertTrue(api.togglePin("n9"))
        assertTrue(api.toggleArchive("n9"))
        val pin = server.takeRequest()
        assertEquals("POST", pin.method)
        assertEquals("/api/notes/n9/pin", pin.requestUrl!!.encodedPath)
        assertEquals("/api/notes/n9/archive", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun toggleItemPostsTheIndexAndReturnsTheWholeChecklist() = runBlocking {
        reply(fixture("notes_toggle.json"))
        val items = api.toggleItem("n1", 1)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/notes/n1/items/1/toggle", req.requestUrl!!.encodedPath)
        assertEquals(listOf(false, true), items.map { it.done })
        assertEquals(1, items[1].indent)
    }

    @Test
    fun reorderPostsTheIdsInOrder() = runBlocking {
        reply("""{"ok":true,"count":3}""")
        api.reorder(listOf("c", "a", "b"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/notes/reorder", req.requestUrl!!.encodedPath)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("""["c","a","b"]""", body.getJSONArray("ids").toString())
        assertEquals(listOf("ids"), body.keys().asSequence().toList())
    }

    @Test
    fun anIdCannotAddAPathSegment() = runBlocking {
        reply("""{"ok":true}""")
        api.delete("../x")
        assertEquals("/api/notes/..%2Fx", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun errorBodiesInA200BecomeExceptions() {
        reply("""{"error":"Nope"}""")
        assertEquals("Nope", runBlocking { runCatching { api.togglePin("n") }.exceptionOrNull() }!!.message)
        reply("""{"ok":false}""")
        assertTrue(runBlocking { runCatching { api.delete("n") }.exceptionOrNull() } is ApiException)
        reply("not json")
        assertEquals("Unexpected response", runBlocking { runCatching { api.list(false) }.exceptionOrNull() }!!.message)
    }

    @Test
    fun notFoundUsesTheServersDetail() {
        reply("""{"detail":"Note not found"}""", code = 404)
        val e = runBlocking { runCatching { api.delete("gone") }.exceptionOrNull() } as ApiException
        assertEquals(404, e.status)
        assertEquals("Note not found", e.message)
    }

    @Test
    fun aListRowWithoutAnIdFailsTheCall() {
        reply("""{"notes":[{"title":"no id"}]}""")
        assertEquals("Unexpected response", runBlocking { runCatching { api.list(false) }.exceptionOrNull() }!!.message)
    }
}
