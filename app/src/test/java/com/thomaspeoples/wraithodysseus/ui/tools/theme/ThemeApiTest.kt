package com.thomaspeoples.wraithodysseus.ui.tools.theme

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import com.thomaspeoples.wraithodysseus.ui.settings.PrefsApi
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

class ThemeApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private val server = MockWebServer()
    private lateinit var api: ThemeApi

    @Before
    fun setUp() {
        server.start()
        api = ThemeApi(PrefsApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false)))))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun loadReadsTheStoredTheme() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("theme_stored.json")))
        val t = api.load()!!
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/prefs/theme", req.path)
        assertEquals("odysseus_session=abc123", req.getHeader("Cookie"))
        assertEquals("midnight", t.name)
    }

    @Test
    fun unsetThemeLoadsAsNull() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("theme_unset.json")))
        assertEquals(null, api.load())
    }

    @Test
    fun anErrorBodyOn200Throws() {
        server.enqueue(MockResponse().setBody("""{"error":"nope"}"""))
        val e = runCatching { runBlocking { api.load() } }.exceptionOrNull() as ApiException
        assertEquals("nope", e.message)
    }

    @Test
    fun darkPresetBodyHasNoExtras() {
        val b = ThemeApi.presetBody("dark")
        assertEquals(setOf("name", "colors"), b.keys().asSequence().toSet())
        assertEquals("dark", b.getString("name"))
        assertEquals("#282c34", b.getJSONObject("colors").getString("bg"))
    }

    @Test
    fun presetBodyCarriesTheWebsDefaults() {
        val b = ThemeApi.presetBody("midnight")
        assertEquals("rain", b.getString("bgPattern"))
        assertEquals("#ffffff", b.getString("bgEffectColor"))
        assertEquals(0.5, b.getDouble("bgEffectIntensity"), 0.0)
        assertFalse(b.has("bgEffectSize"))
        assertFalse(b.has("font"))
        assertFalse(b.has("frosted"))
        val o = ThemeApi.presetBody("organs")
        assertEquals(0.75, o.getDouble("bgEffectSize"), 0.0)
        assertEquals(0.05, o.getDouble("bgEffectSpeed"), 0.0)
    }

    @Test
    fun gptPresetKeepsItsAdvancedColours() {
        val adv = ThemeApi.presetBody("gpt").getJSONObject("colors").getJSONObject("advanced")
        assertEquals("#2f2f2f", adv.getString("userBubbleBg"))
    }

    @Test
    fun savingAPresetPutsItWrappedInValue() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"key":"theme","value":{}}"""))
        api.savePreset("forest")
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/prefs/theme", req.path)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        val v = JSONObject(req.body.readUtf8()).getJSONObject("value")
        assertEquals("forest", v.getString("name"))
        assertEquals("petals", v.getString("bgPattern"))
    }

    @Test
    fun customThemesSkipBrokenEntries() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("theme_custom.json")))
        val list = api.customThemes()
        assertEquals("/api/prefs/custom-themes", server.takeRequest().path)
        assertEquals(listOf("mine"), list.map { it.name })
        assertEquals(mapOf("userBubbleBg" to "#223344"), list[0].colors.advanced)
    }

    @Test
    fun customBodyPassesTheEntryAndItsExtras() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("theme_custom.json")))
        val b = ThemeApi.customBody(api.customThemes().single())
        assertEquals("mine", b.getString("name"))
        assertEquals("sans", b.getString("font"))
        assertEquals("compact", b.getString("density"))
        assertEquals("dots", b.getString("bgPattern"))
        assertTrue(b.getBoolean("frosted"))
        assertEquals("#101820", b.getJSONObject("colors").getString("bg"))
    }

    @Test
    fun parseCustomOfNothingIsEmpty() {
        assertTrue(ThemeApi.parseCustom(null).isEmpty())
        assertTrue(ThemeApi.parseCustom("x").isEmpty())
    }
}
