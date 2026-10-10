package com.thomaspeoples.wraithodysseus.ui.email

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.thomaspeoples.wraithodysseus.email.InlineImage
import com.thomaspeoples.wraithodysseus.email.RequestDecision
import com.thomaspeoples.wraithodysseus.email.decideRequest
import java.io.ByteArrayInputStream
import kotlin.math.abs

private const val BASE_URL = "http://wraith.invalid/"

/**
 * A message's HTML in a locked-down WebView. JavaScript is off, file and
 * content access are off, and every sub-resource goes through
 * [decideRequest]: nothing loads from the network unless the user tapped
 * Load images ([allowImages]) and then only images, while `cid:` images are
 * answered from [fetchCid], which uses the app's own authenticated client so
 * the WebView never holds the session cookie. A tapped link never navigates
 * this view: it goes to [onLink] (the browser). The page height follows its
 * content, so the surrounding screen is the one that scrolls; a horizontal
 * fling that the page did not need is reported to [onSwipe] (+1 towards the
 * next message, -1 the previous).
 *
 * [fetchCid] runs on the WebView's own loader thread, where blocking is fine.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun HtmlBody(
    document: String,
    allowImages: Boolean,
    fetchCid: (String) -> InlineImage?,
    onLink: (String) -> Unit,
    onSwipe: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allow by rememberUpdatedState(allowImages)
    val cid by rememberUpdatedState(fetchCid)
    val link by rememberUpdatedState(onLink)
    val swipe by rememberUpdatedState(onSwipe)
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                overScrollMode = WebView.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    databaseEnabled = false
                    setGeolocationEnabled(false)
                    setSupportMultipleWindows(false)
                    setSupportZoom(false)
                    builtInZoomControls = false
                    javaScriptCanOpenWindowsAutomatically = false
                    mediaPlaybackRequiresUserGesture = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    textZoom = 100
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.isForMainFrame) return null
                        return when (val d = decideRequest(request.url.toString(), request.requestHeaders["Accept"], allow)) {
                            RequestDecision.Allow -> null
                            RequestDecision.Block -> emptyResponse()
                            is RequestDecision.Cid -> cid(d.cid)?.let { WebResourceResponse(it.mime, null, ByteArrayInputStream(it.bytes)) }
                                ?: emptyResponse(404)
                        }
                    }

                    // Any navigation after the first load: a link tap, a meta
                    // refresh, a script-less redirect. None may happen in here.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        link(request.url.toString())
                        return true
                    }
                }
                val detector = GestureDetector(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                            val dx = e2.x - (e1?.x ?: return false)
                            if (abs(vx) > 800 && abs(vx) > abs(vy) * 2 && abs(dx) > 160 && !canScrollHorizontally(if (dx < 0) 1 else -1)) {
                                swipe(if (dx < 0) 1 else -1)
                            }
                            return false
                        }
                    },
                )
                setOnTouchListener { _, event -> detector.onTouchEvent(event); false }
            }
        },
        update = { view ->
            // Reload only when the page or the image choice changed, not on
            // every recomposition, which would reset the scroll position.
            val key = document to allowImages
            if (view.tag != key) {
                view.tag = key
                view.settings.blockNetworkImage = !allowImages
                view.loadDataWithBaseURL(BASE_URL, document, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}

private fun emptyResponse(status: Int = 200) =
    WebResourceResponse("text/plain", "utf-8", status, if (status == 200) "OK" else "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
