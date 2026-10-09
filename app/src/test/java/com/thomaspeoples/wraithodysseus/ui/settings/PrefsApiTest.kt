package com.thomaspeoples.wraithodysseus.ui.settings

import com.thomaspeoples.wraithodysseus.chat.ModelChoice
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
import org.junit.Before
import org.junit.Test

class PrefsApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private val server = MockWebServer()
    private lateinit var api: PrefsApi
    private val choice = ModelChoice(endpointId = "ep1", endpointName = "Local", model = "llama", display = "Llama")

    @Before
    fun setUp() {
        server.start()
        api = PrefsApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun reply(body: String) = server.enqueue(MockResponse().setBody(body))

    @Test
    fun unsetSwitchIsOnAndOnlyFalseIsOff() = runBlocking {
        reply("""{"key":"memory_enabled","value":null}""")
        reply("""{"key":"memory_enabled","value":false}""")
        reply("""{"key":"memory_enabled","value":true}""")
        assertTrue(api.flag("memory_enabled"))
        assertFalse(api.flag("memory_enabled"))
        assertTrue(api.flag("memory_enabled"))
        assertEquals("/api/prefs/memory_enabled", server.takeRequest().path)
    }

    @Test
    fun settingASwitchPutsTheBoolean() = runBlocking {
        reply("""{"key":"auto_memory","value":false}""")
        api.setFlag("auto_memory", false)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/prefs/auto_memory", req.path)
        assertEquals(false, JSONObject(req.body.readUtf8()).getBoolean("value"))
    }

    @Test
    fun anErrorOn200Throws() {
        reply("""{"success":false,"message":"read only"}""")
        val e = runCatching { runBlocking { api.setFlag("skills_enabled", true) } }.exceptionOrNull() as ApiException
        assertEquals("read only", e.message)
    }

    @Test
    fun adminDefaultGoesToTheServerSettings() = runBlocking {
        reply("""{"default_model":"llama"}""")
        api.setDefaultModel(choice, isAdmin = true)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/auth/settings", req.path)
        val b = JSONObject(req.body.readUtf8())
        assertEquals("ep1", b.getString("default_endpoint_id"))
        assertEquals("llama", b.getString("default_model"))
    }

    @Test
    fun userDefaultGoesToTheirOwnPrefs() = runBlocking {
        reply("""{"key":"default_endpoint_id","value":"ep1"}""")
        reply("""{"key":"default_model","value":"llama"}""")
        api.setDefaultModel(choice, isAdmin = false)
        val a = server.takeRequest()
        val b = server.takeRequest()
        assertEquals("/api/prefs/default_endpoint_id", a.path)
        assertEquals("ep1", JSONObject(a.body.readUtf8()).getString("value"))
        assertEquals("/api/prefs/default_model", b.path)
        assertEquals("llama", JSONObject(b.body.readUtf8()).getString("value"))
    }

    @Test
    fun forbiddenAdminSaveIsReported() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Admin only"}"""))
        val e = runCatching { runBlocking { api.setDefaultModel(choice, isAdmin = true) } }.exceptionOrNull() as ApiException
        assertEquals(403, e.status)
        assertEquals("Admin only", e.message)
    }
}
