package com.thomaspeoples.wraithodysseus.ui.tools.gallery

import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GalleryApiTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private val server = MockWebServer()
    private lateinit var api: GalleryApi

    @Before
    fun setUp() {
        server.start()
        api = GalleryApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun reply(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private fun errorOf(block: suspend () -> Unit): ApiException =
        runBlocking { runCatching { block() }.exceptionOrNull() } as? ApiException ?: throw AssertionError("expected ApiException")

    @Test
    fun libraryParsesItemsAndSkipsRowsWithoutAnId() = runBlocking {
        reply(fixture("gallery_library.json"))
        val page = api.library(0)
        assertEquals(2, page.items.size)
        assertEquals(130, page.total)
        val a = page.items[0]
        assertEquals("a1", a.id)
        assertTrue(a.favorite)
        assertEquals("alb1", a.albumId)
        assertEquals(4000, a.width)
        assertEquals(2048000L, a.fileSize)
        assertEquals("2026-10-01T09:30:00", a.takenAt)
        assertFalse(a.isVideo)
        val b = page.items[1]
        assertTrue(b.isVideo)
        assertNull(b.albumId)
        assertNull(b.width)
        assertNull(b.createdAt)
    }

    @Test
    fun libraryPagesByOffsetWithFiltersAndCookie() = runBlocking {
        reply(fixture("gallery_library.json"))
        reply(fixture("gallery_library.json"))
        api.library(60)
        val first = server.takeRequest()
        assertEquals("GET", first.method)
        assertEquals("/api/gallery/library?sort=recent&offset=60&limit=60", first.path)
        assertEquals("odysseus_session=abc123", first.getHeader("Cookie"))
        api.library(0, album = "alb1", favoritesOnly = true)
        assertEquals("/api/gallery/library?sort=recent&offset=0&limit=60&album=alb1&favorites=true", server.takeRequest().path)
    }

    @Test
    fun libraryErrorBodyIsAnError() {
        reply("""{"error":"Failed to fetch"}""")
        assertEquals("Failed to fetch", errorOf { api.library(0) }.message)
    }

    @Test
    fun albumsSkipRowsWithoutAnId() = runBlocking {
        reply(fixture("gallery_albums.json"))
        val albums = api.albums()
        assertEquals("/api/gallery/albums", server.takeRequest().path)
        assertEquals(listOf(Album("alb1", "Trip", 12)), albums)
    }

    @Test
    fun setFavoriteSendsAnExplicitPatch() = runBlocking {
        reply(fixture("gallery_patch.json"))
        assertTrue(api.setFavorite("a1", true))
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/gallery/a1", req.path)
        assertEquals("""{"favorite":true}""", req.body.readUtf8())
    }

    @Test
    fun deleteCallsDeleteAndSurfaces404() {
        reply("""{"status":"deleted"}""")
        runBlocking { api.delete("a1") }
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/gallery/a1", req.path)
        reply("""{"detail":"Image not found"}""", 404)
        assertEquals("Image not found", errorOf { api.delete("a1") }.message)
    }

    @Test
    fun uploadSendsTheWebsMultipartFields() = runBlocking {
        reply(fixture("gallery_upload_ok.json"))
        val result = api.upload(UploadSource("holiday", "image/jpeg") { "bytes".byteInputStream() }, "alb1")
        assertEquals(UploadResult.Imported, result)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/gallery/upload", req.path)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("name=\"file\"; filename=\"holiday.jpg\""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        assertTrue(body.contains("bytes"))
        assertTrue(body.contains("name=\"album_id\""))
        assertTrue(body.contains("alb1"))
    }

    @Test
    fun uploadWithoutAlbumOmitsTheField() = runBlocking {
        reply(fixture("gallery_upload_ok.json"))
        api.upload(UploadSource("a.png", "image/png") { "x".byteInputStream() }, null)
        assertFalse(server.takeRequest().body.readUtf8().contains("album_id"))
    }

    @Test
    fun duplicateIsNotAnError() = runBlocking {
        reply(fixture("gallery_upload_duplicate.json"))
        assertEquals(UploadResult.Duplicate, api.upload(UploadSource("a.png", "image/png") { "x".byteInputStream() }, null))
    }

    @Test
    fun uploadRefusalIsAnError() {
        reply("""{"detail":"Unsupported file type: .heic"}""", 400)
        assertEquals("Unsupported file type: .heic", errorOf { api.upload(UploadSource("a.heic", null) { "x".byteInputStream() }, null) }.message)
        reply("""{"ok":false,"message":"Nope"}""")
        assertEquals("Nope", errorOf { api.upload(UploadSource("a.png", "image/png") { "x".byteInputStream() }, null) }.message)
    }

    @Test
    fun imageBytesFetchWithTheCookieOnTheServersOwnOrigin() = runBlocking {
        server.enqueue(MockResponse().setBody("PNGDATA"))
        val bytes = api.imageBytes("/api/generated-image/0123456789ab.jpg")
        assertEquals("PNGDATA", String(bytes))
        val req = server.takeRequest()
        assertEquals("/api/generated-image/0123456789ab.jpg", req.path)
        assertEquals("odysseus_session=abc123", req.getHeader("Cookie"))
    }

    @Test
    fun imageBytesFromAnotherHostCarryNoCookie() = runBlocking {
        val other = MockWebServer().apply { start() }
        try {
            other.enqueue(MockResponse().setBody("x"))
            api.imageBytes(other.url("/pic.png").toString())
            assertNull(other.takeRequest().getHeader("Cookie"))
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun uploadNameGetsAnExtensionFromTheMimeType() {
        assertEquals("a.jpg", GalleryApi.uploadName("a", "image/jpeg"))
        assertEquals("a.png", GalleryApi.uploadName("a.png", "image/png"))
        assertEquals("a.mp4", GalleryApi.uploadName("a.mp4", null))
        assertEquals("a", GalleryApi.uploadName("a", "image/heic"))
    }

    @Test
    fun pagingStopsWhenEverythingIsLoaded() {
        val p = GalleryApi.parsePage(fixture("gallery_library.json"))
        assertTrue(p.items.size < p.total)
        assertEquals(0, GalleryApi.parsePage("""{"items":[],"total":0}""").total)
    }

    @Test
    fun sampleSizeIsThePowerOfTwoKeepingTheTarget() {
        assertEquals(1, sampleSize(300, 200, 360))
        assertEquals(8, sampleSize(4000, 3000, 360))
        assertEquals(1, sampleSize(4000, 3000, 2048))
        assertEquals(1, sampleSize(0, 0, 360))
    }

    @Test
    fun uploadSummaryMatchesTheWeb() {
        assertEquals("3 imported", uploadSummary(3, 0, emptyList()))
        assertEquals("1 imported, 2 duplicates skipped, 1 error: boom", uploadSummary(1, 2, listOf("boom")))
    }

    @Test
    fun humanSizes() {
        assertEquals("2.0 MB", humanSize(2L shl 20))
        assertEquals("5 KB", humanSize(5120))
    }
}
