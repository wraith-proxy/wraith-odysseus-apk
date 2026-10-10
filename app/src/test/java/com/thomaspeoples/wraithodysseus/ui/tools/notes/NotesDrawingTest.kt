package com.thomaspeoples.wraithodysseus.ui.tools.notes

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Which address a drawing note's picture comes from, and the size caps that keep a bad one from crashing. */
class NotesDrawingTest {

    private fun dataUrl(bytes: ByteArray, mime: String = "png") = "data:image/$mime;base64," + Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `a png data url is decoded`() {
        val png = byteArrayOf(1, 2, 3, 4, 5)
        val s = drawingSource(dataUrl(png))
        assertTrue(s is DrawingSource.Inline)
        assertArrayEquals(png, (s as DrawingSource.Inline).bytes)
    }

    @Test
    fun `the data url scheme is case insensitive and jpeg webp gif are accepted`() {
        for (m in listOf("jpeg", "jpg", "webp", "gif")) assertTrue(m, drawingSource(dataUrl(byteArrayOf(9), m)) is DrawingSource.Inline)
        assertTrue(drawingSource("DATA:IMAGE/PNG;BASE64," + Base64.getEncoder().encodeToString(byteArrayOf(9))) is DrawingSource.Inline)
    }

    @Test
    fun `other data urls are refused`() {
        assertEquals(DrawingSource.Unusable, drawingSource("data:image/svg+xml;base64,PHN2Zz4="))
        assertEquals(DrawingSource.Unusable, drawingSource("data:text/html;base64,PGI+"))
        assertEquals(DrawingSource.Unusable, drawingSource("data:image/png,rawtext"))
    }

    @Test
    fun `bad base64 and empty payloads are unusable`() {
        assertEquals(DrawingSource.Unusable, drawingSource("data:image/png;base64,!!!notbase64"))
        assertEquals(DrawingSource.Unusable, drawingSource("data:image/png;base64,"))
    }

    @Test
    fun `an oversized data url is refused before decoding`() {
        assertEquals(DrawingSource.Unusable, drawingSource(dataUrl(ByteArray(100)), maxBytes = 50))
        assertTrue(drawingSource(dataUrl(ByteArray(100)), maxBytes = 100) is DrawingSource.Inline)
    }

    @Test
    fun `server paths and web urls are fetched`() {
        assertEquals(DrawingSource.Remote("/api/gallery/file/abc.png"), drawingSource("  /api/gallery/file/abc.png "))
        assertEquals(DrawingSource.Remote("https://odysseus.example/x.png"), drawingSource("https://odysseus.example/x.png"))
        assertEquals(DrawingSource.Remote("HTTP://10.0.0.2:8080/x.png"), drawingSource("HTTP://10.0.0.2:8080/x.png"))
    }

    @Test
    fun `other hosts disguised as paths and other schemes are refused`() {
        for (v in listOf("//evil.example/x.png", "/\\evil.example/x.png", "/", "file:///sdcard/x.png", "javascript:alert(1)", "content://x/1", "x.png", "./x.png"))
            assertEquals(v, DrawingSource.Unusable, drawingSource(v))
    }

    @Test
    fun `missing blank and control characters are unusable`() {
        assertEquals(DrawingSource.Unusable, drawingSource(null))
        assertEquals(DrawingSource.Unusable, drawingSource("   "))
        assertEquals(DrawingSource.Unusable, drawingSource("/a b.png"))
        assertEquals(DrawingSource.Unusable, drawingSource("/a\nb.png"))
    }

    @Test
    fun `readCapped returns the body under the cap`() {
        val body = Buffer().write(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), readCapped(3, body, max = 3))
    }

    @Test
    fun `readCapped refuses a declared or chunked body over the cap`() {
        assertNull(readCapped(10, Buffer().write(ByteArray(10)), max = 5))
        assertNull(readCapped(-1, Buffer().write(ByteArray(10)), max = 5))
        assertArrayEquals(ByteArray(5), readCapped(-1, Buffer().write(ByteArray(5)), max = 5))
    }
}
