package com.thomaspeoples.wraithodysseus.ui.tools.memory

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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Every call MemoryApi makes, and the search / filter the list applies. */
class MemoryApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private val server = MockWebServer()
    private lateinit var api: MemoryApi

    @Before
    fun setUp() {
        server.start()
        api = MemoryApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private fun reply(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun listParsesEveryFieldAndToleratesNulls() = runBlocking {
        reply(fixture("memory_list.json"))
        val all = api.list()
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/memory", req.requestUrl!!.encodedPath)
        assertEquals("abc123", req.getHeader("Cookie")!!.substringAfter('='))
        assertEquals(4, all.size)
        assertEquals(Memory("m1", "Likes oat milk", "preference", false, 1700000300, 3), all[0])
        assertTrue(all[1].pinned)
        assertEquals(Memory("m4", "", "fact", false, 0, 0), all[3])
    }

    @Test
    fun addPostsJsonNeverRetriesAndReportsADuplicate() = runBlocking {
        reply("""{"ok":true,"count":5}""")
        assertTrue(api.add("Hello", "goal"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/memory/add", req.requestUrl!!.encodedPath)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("Hello", body.getString("text"))
        assertEquals("goal", body.getString("category"))
        reply("""{"ok":true,"count":5,"message":"Memory already exists"}""")
        assertFalse(api.add("Hello", "goal"))
    }

    @Test
    fun updateSendsFormFields() = runBlocking {
        reply("""{"ok":true,"message":"Memory updated successfully"}""")
        api.update("m1", "New text", "fact")
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/memory/m1", req.requestUrl!!.encodedPath)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals("text=New%20text&category=fact", req.body.readUtf8().replace("+", "%20"))
    }

    @Test
    fun pinSendsPinnedAsAForm() = runBlocking {
        reply("""{"ok":true,"pinned":true}""")
        api.pin("m1", true)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/memory/m1/pin", req.requestUrl!!.encodedPath)
        assertEquals("pinned=true", req.body.readUtf8())
    }

    @Test
    fun deleteAndIdsCannotAddAPath() = runBlocking {
        reply("""{"ok":true}""")
        api.delete("a/../b")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/memory/a%2F..%2Fb", req.requestUrl!!.encodedPath)
    }

    @Test
    fun prefIsOnUnlessExplicitlyFalse() = runBlocking {
        reply("""{"key":"memory_enabled","value":false}""")
        assertFalse(api.pref(PREF_MEMORY_ENABLED))
        assertEquals("/api/prefs/memory_enabled", server.takeRequest().requestUrl!!.encodedPath)
        reply("""{"key":"auto_memory","value":null}""")
        assertTrue(api.pref(PREF_AUTO_MEMORY))
        reply("""{"key":"auto_memory","value":true}""")
        assertTrue(api.pref(PREF_AUTO_MEMORY))
    }

    @Test
    fun setPrefPutsValue() = runBlocking {
        reply("""{"key":"auto_memory","value":false}""")
        api.setPref(PREF_AUTO_MEMORY, false)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/prefs/auto_memory", req.requestUrl!!.encodedPath)
        assertEquals(false, JSONObject(req.body.readUtf8()).getBoolean("value"))
    }

    @Test
    fun errorsInA200BodyAndHttpErrorsBecomeApiExceptions() = runBlocking {
        reply("""{"error":"nope"}""")
        try {
            api.list()
            fail()
        } catch (e: ApiException) {
            assertEquals("nope", e.message)
        }
        reply("""{"ok":false}""")
        try {
            api.delete("m1")
            fail()
        } catch (e: ApiException) {
            assertEquals(200, e.status)
        }
        reply("""{"detail":"empty memory"}""", code = 400)
        try {
            api.add(" ", "fact")
            fail()
        } catch (e: ApiException) {
            assertEquals(400, e.status)
            assertEquals("empty memory", e.message)
        }
        reply("not json")
        try {
            api.list()
            fail()
        } catch (e: ApiException) {
            assertEquals("Unexpected response", e.message)
        }
    }

    @Test
    fun searchFiltersTextAndCategoryNewestFirstWithPinnedOnTop() = runBlocking {
        reply(fixture("memory_list.json"))
        val all = api.list()
        assertEquals(listOf("m2", "m1", "m3", "m4"), filterMemories(all, "", null).map { it.id })
        assertEquals(listOf("m3"), filterMemories(all, "  WRAITH ", null).map { it.id })
        assertEquals(listOf("m1"), filterMemories(all, "", "preference").map { it.id })
        assertEquals(emptyList<String>(), filterMemories(all, "wraith", "fact").map { it.id })
    }

    @Test
    fun categoryCountsFollowThePickerOrder() = runBlocking {
        reply(fixture("memory_list.json"))
        assertEquals(listOf("fact" to 2, "preference" to 1, "project" to 1), categoryCounts(api.list()))
    }
}
