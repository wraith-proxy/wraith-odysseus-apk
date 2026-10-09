package com.thomaspeoples.wraithodysseus.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderHtmlTest {

    private val imageAccept = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"

    // -- the request interception decision --------------------------------

    @Test
    fun remoteLoadsAreBlockedByDefault() {
        for (url in listOf("http://tracker.example/p.gif", "https://cdn.example/logo.png")) {
            assertEquals(url, RequestDecision.Block, decideRequest(url, imageAccept, allowImages = false))
        }
    }

    @Test
    fun cidImagesAreAlwaysServedEvenWithImagesOff() {
        assertEquals(RequestDecision.Cid("logo@x"), decideRequest("cid:logo@x", imageAccept, false))
        assertEquals(RequestDecision.Cid("logo@x"), decideRequest("CID:logo@x", null, true))
        assertEquals(RequestDecision.Block, decideRequest("cid:", imageAccept, false))
    }

    @Test
    fun loadImagesAllowsOnlyImageRequests() {
        assertEquals(RequestDecision.Allow, decideRequest("https://cdn.example/a.png", imageAccept, true))
        assertEquals(RequestDecision.Allow, decideRequest("http://cdn.example/a.png", "image/*", true))
        // A stylesheet, a font, a script and a request that says nothing are still blocked.
        assertEquals(RequestDecision.Block, decideRequest("https://cdn.example/a.css", "text/css,*/*;q=0.1", true))
        assertEquals(RequestDecision.Block, decideRequest("https://cdn.example/a.woff2", "*/*", true))
        assertEquals(RequestDecision.Block, decideRequest("https://cdn.example/a.js", "*/*", true))
        assertEquals(RequestDecision.Block, decideRequest("https://cdn.example/a.png", null, true))
    }

    @Test
    fun otherSchemesAreBlocked() {
        for (url in listOf("file:///data/data/app/x", "content://provider/x", "ftp://h/x", "about:blank", "javascript:alert(1)", "intent://x")) {
            assertEquals(url, RequestDecision.Block, decideRequest(url, imageAccept, true))
        }
        assertEquals(RequestDecision.Allow, decideRequest("data:image/png;base64,AAAA", imageAccept, false))
    }

    @Test
    fun cidIsDecodedAndTrimmed() {
        assertEquals("a b@x", cidOf("cid:a%20b@x"))
        assertEquals("image+1@x", cidOf("cid:image+1@x"))
        assertEquals("logo@x", cidOf("cid:<logo@x>"))
        assertNull(cidOf("https://x/y"))
        assertNull(cidOf("cid:  "))
    }

    // -- dark styling -------------------------------------------------------

    @Test
    fun darkCssThrowsAwayTheSendersColoursAndFonts() {
        val css = darkCss("#E6E6E6", "#58A6FF", "#333333")
        assertTrue(css.contains("color:#E6E6E6!important"))
        assertTrue(css.contains("body *{color:inherit!important;background:transparent!important"))
        assertTrue(css.contains("font-family:inherit!important"))
        assertTrue(css.contains("position:static!important"))
        assertTrue(css.contains("body a,body a *{color:#58A6FF!important"))
        assertTrue(css.contains("border-left:3px solid #333333"))
    }

    @Test
    fun readerDocumentInjectsTheCssAndKeepsTheBody() {
        val doc = readerDocument("<p>Hello</p>", "#EEEEEE", "#0088FF", "#444444")
        assertTrue(doc.startsWith("<!doctype html>"))
        assertTrue(doc.contains("<style>"))
        assertTrue(doc.contains("#EEEEEE"))
        assertTrue(doc.contains("<body><p>Hello</p></body>"))
    }

    @Test
    fun cleaningDropsDangerousElementsAndStyleBlocks() {
        val html = """<style>p{color:red}</style><script>alert(1)</script><meta http-equiv="refresh" content="0;url=x">
            <link rel="stylesheet" href="http://x/a.css"><iframe src="http://x"></iframe><svg><circle/></svg><form><input></form><p>kept</p>""".trimIndent()
        val out = cleanMailHtml(html)
        for (gone in listOf("<style", "<script", "<meta", "<link", "<iframe", "<svg", "<form", "alert(1)")) {
            assertFalse(gone, out.contains(gone, ignoreCase = true))
        }
        assertTrue(out.contains("<p>kept</p>"))
    }

    @Test
    fun cleaningStripsTheWebsInlineStyleProperties() {
        val out = cleanMailHtml("""<td style="color:#000; background-color:#fff; font-family:Arial; width:600px; position:absolute; padding:4px" bgcolor="#fff"><font color="red" face="Arial">x</font></td>""")
        assertTrue(out, out.contains("""style="width:600px; padding:4px""""))
        assertFalse(out.contains("bgcolor"))
        assertFalse(out.contains("face="))
        assertFalse(out.contains("color=\"red\""))
    }

    @Test
    fun cleaningDropsStylesWithScriptOrDataUrlsAndEmptiedStyles() {
        val out = cleanMailHtml("""<div style="width:1px;background:url(data:image/png;base64,AAAA)" ></div><p style="color:red"></p>""")
        assertFalse(out.contains("data:"))
        assertFalse(out.contains("color:red"))
        assertFalse(out.contains("style=\"\""))
        assertTrue(out.contains("""style="width:1px""""))
    }

    @Test
    fun remoteImagesAreDetected() {
        assertTrue(hasRemoteImages("""<img src="https://t.example/p.gif">"""))
        assertTrue(hasRemoteImages("""<IMG alt=x src='http://t.example/p.gif'>"""))
        assertTrue(hasRemoteImages("""<div style="background:url('https://t.example/b.png')"></div>"""))
        assertFalse(hasRemoteImages("""<img src="cid:logo@x"><img src="data:image/png;base64,AA">"""))
    }

    @Test
    fun htmlOnlyMailGetsAPlainText() {
        assertEquals("Hi\n\nA & B <ok>", htmlToText("<style>p{}</style><p>Hi</p>A &amp; B &lt;ok&gt;"))
    }
}
