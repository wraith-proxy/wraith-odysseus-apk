package com.thomaspeoples.wraithodysseus.ui.tools.library

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

class LibraryApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private val server = MockWebServer()
    private lateinit var api: LibraryApi

    @Before
    fun setUp() {
        server.start()
        api = LibraryApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun reply(name: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(fixture(name)))

    private fun errorOf(block: suspend () -> Unit): ApiException =
        runBlocking { runCatching { block() }.exceptionOrNull() } as? ApiException ?: throw AssertionError("expected ApiException")

    @Test
    fun listSendsSearchAndPagingAndParses() = runBlocking {
        reply("library_list.json")
        val page = api.list("machine learning", archived = false, offset = 50)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/documents/library?sort=recent&offset=50&limit=50&search=machine%20learning", req.path)
        assertEquals("odysseus_session=abc123", req.getHeader("Cookie"))
        assertEquals(75, page.total)
        // The row without an id is dropped.
        assertEquals(listOf("d1", "d2"), page.items.map { it.id })
        assertEquals("Trip planning", page.items[0].sessionName)
        assertEquals(4, page.items[0].versionCount)
        assertEquals("Untitled", page.items[1].title)
        assertNull(page.items[1].sessionName)
        assertNull(page.items[1].updatedAt)
    }

    @Test
    fun archivedListAddsTheFlagAndBlankSearchIsOmitted() = runBlocking {
        reply("library_list.json")
        api.list("  ", archived = true)
        assertEquals("/api/documents/library?sort=recent&offset=0&limit=50&archived=true", server.takeRequest().path)
    }

    @Test
    fun getParsesTheDocument() = runBlocking {
        reply("library_doc.json")
        val doc = api.get("d1")
        assertEquals("/api/document/d1", server.takeRequest().path)
        assertEquals("# Day 1\nArrive", doc.content)
        assertEquals("markdown", doc.language)
        assertTrue(doc.isMarkdown)
        assertFalse(doc.isPdf)
        assertFalse(doc.archived)
    }

    @Test
    fun aPdfWrapperIsRecognised() = runBlocking {
        reply("library_doc_pdf.json")
        val doc = api.get("d2")
        assertTrue(doc.isPdf)
        assertFalse(doc.isMarkdown)
        assertTrue(doc.archived)
    }

    @Test
    fun saveSendsOnlyTheContent() = runBlocking {
        reply("library_doc.json")
        val doc = api.save("d1", "new \"text\"\n")
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/document/d1", req.path)
        assertTrue(req.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals(setOf("content"), body.keys().asSequence().toSet())
        assertEquals("new \"text\"\n", body.getString("content"))
        assertEquals("d1", doc.id)
    }

    @Test
    fun renameIsAMetadataPatch() = runBlocking {
        reply("library_doc.json")
        api.rename("d1", "New name")
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/document/d1", req.path)
        assertEquals("{\"title\":\"New name\"}", req.body.readUtf8())
    }

    @Test
    fun createIsSessionLessMarkdownAndDefaultsTheTitle() = runBlocking {
        reply("library_doc.json")
        api.create("  ")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/document", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("Untitled", body.getString("title"))
        assertEquals("markdown", body.getString("language"))
        assertEquals("", body.getString("content"))
        assertFalse(body.has("session_id"))
    }

    @Test
    fun archiveAndRestoreUseTheQueryFlag() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"ok\":true,\"id\":\"d1\",\"archived\":true}"))
        server.enqueue(MockResponse().setBody("{\"ok\":true,\"id\":\"d1\",\"archived\":false}"))
        api.archive("d1", true)
        api.archive("d1", false)
        val a = server.takeRequest()
        assertEquals("POST", a.method)
        assertEquals("/api/document/d1/archive?archived=true", a.path)
        assertEquals("/api/document/d1/archive?archived=false", server.takeRequest().path)
    }

    @Test
    fun deleteCallsDelete() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"status\":\"deleted\",\"id\":\"d1\"}"))
        api.delete("d1")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/document/d1", req.path)
    }

    @Test
    fun errorBodiesOn200AreFailures() {
        reply("library_error_200.json")
        assertEquals("Something broke", errorOf { api.save("d1", "x") }.message)
    }

    @Test
    fun httpErrorsCarryTheDetail() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{\"detail\":\"Document not found\"}"))
        val e = errorOf { api.get("nope") }
        assertEquals(404, e.status)
        assertEquals("Document not found", e.message)
    }
}
