package com.thomaspeoples.wraithodysseus.demo

import com.thomaspeoples.wraithodysseus.net.SessionCookie
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

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

    // ponytail: in memory until the app closes; only so a reply survives the chat's reload.
    private val sent = ConcurrentHashMap<String, MutableList<JSONObject>>()

    /** Keeps [message] and the canned reply in [session]'s history. */
    fun sent(session: String, message: String) {
        val rows = sent.getOrPut(session) { mutableListOf() }
        val now = Instant.now().toString()
        fun row(role: String, text: String) = JSONObject().put("role", role).put("content", text)
            .put("metadata", JSONObject().put("_db_id", "$session-sent-${rows.size}").put("timestamp", now).put("model", "demo-llm"))
        synchronized(rows) {
            rows += row("user", message)
            rows += row("assistant", DEMO_REPLY)
        }
    }

    /** Returns (body, media type). */
    fun respond(method: String, path: String): Pair<String, String> {
        val hit = routes["$method $path"]
        val extra = if (method == "GET") sent[path.removePrefix("/api/history/")] else null
        if (extra != null && path.startsWith("/api/history/")) {
            val o = JSONObject(hit ?: """{"history":[],"offset":0,"has_more_before":false}""")
            val arr = o.optJSONArray("history") ?: JSONArray().also { o.put("history", it) }
            synchronized(extra) { extra.forEach(arr::put) }
            return o.put("total", arr.length()).toString() to "application/json"
        }
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
        if (r.method == "POST" && r.url.encodedPath == "/api/chat_stream") (r.body as? MultipartBody)?.let { b ->
            val f = b.parts.associate { p ->
                p.headers?.get("Content-Disposition")?.substringAfter("name=\"")?.substringBefore('"') to
                    Buffer().also(p.body::writeTo).readUtf8()
            }
            Demo.sent(f["session"] ?: return@let, f["message"] ?: return@let)
        }
        val (body, type) = Demo.respond(r.method, r.url.encodedPath)
        // 404 is how the server says no run is live; a 200 would look like one.
        val code = if (r.url.encodedPath.startsWith("/api/chat/resume/")) 404 else 200
        return Response.Builder()
            .request(r)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody(type.toMediaType()))
            .build()
    }
}
