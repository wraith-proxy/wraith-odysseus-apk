package com.thomaspeoples.wraithodysseus.ui.tools.skills

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

/** Every call SkillsApi makes, and the list filter. */
class SkillsApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private val server = MockWebServer()
    private lateinit var api: SkillsApi

    @Before
    fun setUp() {
        server.start()
        api = SkillsApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private fun reply(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun listParsesUserSkillsDropsBuiltinsAndNamelessOnes() = runBlocking {
        reply(fixture("memory_skills_list.json"))
        val all = api.list()
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/skills", req.requestUrl!!.encodedPath)
        assertEquals("abc123", req.getHeader("Cookie")!!.substringAfter('='))
        assertEquals(listOf("summarise-pr", "draft-email", "old-one", "legacy-id"), all.map { it.name })
        val first = all[0]
        assertEquals(listOf("git", "review"), first.tags)
        assertTrue(first.published)
        assertEquals(0.9, first.confidence, 0.0)
        assertEquals("draft", all[1].status)
    }

    @Test
    fun markdownReadsTheRawText() = runBlocking {
        reply("""{"name":"a b","markdown":"---\nname: a\n---\n# Hi"}""")
        assertEquals("---\nname: a\n---\n# Hi", api.markdown("a b"))
        assertEquals("/api/skills/a%20b/markdown", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun setStatusPutsJustTheStatus() = runBlocking {
        reply("""{"ok":true}""")
        api.setStatus("draft-email", "published")
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/skills/draft-email", req.requestUrl!!.encodedPath)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("published", body.getString("status"))
        assertEquals(1, body.length())
    }

    @Test
    fun deleteSendsDeleteAndSurfacesFailure() = runBlocking {
        reply("""{"ok":true}""")
        api.delete("old-one")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/skills/old-one", req.requestUrl!!.encodedPath)
        reply("""{"detail":"Skill not found"}""", code = 404)
        try {
            api.delete("gone")
            fail()
        } catch (e: ApiException) {
            assertEquals(404, e.status)
            assertEquals("Skill not found", e.message)
        }
    }

    @Test
    fun aBodyWithAnErrorIsAnApiException() = runBlocking {
        reply("""{"error":"broken"}""")
        try {
            api.list()
            fail()
        } catch (e: ApiException) {
            assertEquals("broken", e.message)
        }
        reply("[]")
        try {
            api.markdown("x")
            fail()
        } catch (e: ApiException) {
            assertEquals("Unexpected response", e.message)
        }
    }

    @Test
    fun filterMatchesNameDescriptionCategoryAndTagsAndByStatus() = runBlocking {
        reply(fixture("memory_skills_list.json"))
        val all = api.list()
        // Published first, then most used, then by name; the binned one is hidden from All.
        assertEquals(listOf("summarise-pr", "draft-email", "legacy-id"), filterSkills(all, "", SkillFilter.All).map { it.name })
        assertEquals(listOf("summarise-pr"), filterSkills(all, "REVIEW", SkillFilter.All).map { it.name })
        assertEquals(listOf("draft-email"), filterSkills(all, "polite", SkillFilter.All).map { it.name })
        assertEquals(listOf("draft-email"), filterSkills(all, "writing", SkillFilter.All).map { it.name })
        assertEquals(listOf("summarise-pr"), filterSkills(all, "", SkillFilter.Published).map { it.name })
        assertEquals(listOf("draft-email", "legacy-id"), filterSkills(all, "", SkillFilter.Draft).map { it.name })
        assertEquals(listOf("old-one"), filterSkills(all, "", SkillFilter.Binned).map { it.name })
        assertFalse(filterSkills(all, "zzz", SkillFilter.All).isNotEmpty())
    }

    @Test
    fun frontMatterIsStrippedOnlyWhenItIsClosed() {
        assertEquals("# Hi\n\nBody", stripFrontMatter("---\nname: a\ndescription: b\n---\n\n# Hi\n\nBody"))
        assertEquals("# No front matter", stripFrontMatter("# No front matter"))
        assertEquals("---\nunclosed", stripFrontMatter("---\nunclosed"))
    }
}
