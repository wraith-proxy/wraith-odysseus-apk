package com.thomaspeoples.wraithodysseus.demo

import com.thomaspeoples.wraithodysseus.net.SessionCookie
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Demo mode: a fake server on a reserved host, so testers can try the app with no Odysseus
 * install. Nothing leaves the phone and writes are not kept.
 */
object Demo {
    const val HOST = "demo.invalid"
    val origin: HttpUrl = "https://demo.invalid/".toHttpUrl()
    const val USERNAME = "demo"
    val cookie = SessionCookie("demo", Long.MAX_VALUE, secure = true)

    fun isDemo(url: HttpUrl?): Boolean = url?.host == HOST

    private val _writes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires on every write the demo ignores, for the "nothing is saved" toast. */
    val writes: SharedFlow<Unit> = _writes

    private val routes: Map<String, String> by lazy {
        demoChat + demoMail + demoPlanner + demoKnowledge + demoMisc
    }

    /** Returns (body, media type). */
    fun respond(method: String, path: String): Pair<String, String> {
        val hit = routes["$method $path"]
        if (hit != null) {
            val t = hit.trimStart()
            val type = when {
                t.startsWith("data:") || t.startsWith("event:") -> "text/event-stream"
                t.startsWith("<") -> "text/html"
                else -> "application/json"
            }
            return hit to type
        }
        if (method == "GET" || method == "HEAD") return "{}" to "application/json"
        _writes.tryEmit(Unit)
        return "{\"success\":true,\"ok\":true}" to "application/json"
    }
}

/** Answers every request to Demo.HOST from Demo.respond; everything else goes to the network untouched. */
class DemoInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val r = chain.request()
        if (r.url.host != Demo.HOST) return chain.proceed(r)
        val (body, type) = Demo.respond(r.method, r.url.encodedPath)
        return Response.Builder()
            .request(r)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody(type.toMediaType()))
            .build()
    }
}
