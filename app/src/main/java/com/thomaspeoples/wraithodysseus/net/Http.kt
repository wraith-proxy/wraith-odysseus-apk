package com.thomaspeoples.wraithodysseus.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** A non-2xx answer from the server, with the human-readable text already
 * pulled out of whichever error shape it used. */
class ApiException(val status: Int, override val message: String) : Exception(message)

/**
 * The one OkHttp client, and the only place the session cookie is attached.
 *
 * Redirects are off entirely, so nothing can bounce a request (and its
 * cookie) from https to http or to another host. Nothing here ever sets an
 * Authorization header: the server rejects any Bearer token that is not one
 * of its own ody_ API tokens, and this app authenticates by cookie only.
 */
class Http(private val session: SessionState) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(JSON_TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when the server says the stored cookie is no longer valid. The
     * cookie is already cleared by then; the UI only has to go to login. */
    val sessionExpired: SharedFlow<Unit> = expired

    /** A path (and optional query) on the stored server. */
    fun url(path: String): HttpUrl =
        checkNotNull(session.baseUrl) { "No server set" }.resolve(path)
            ?: throw IllegalArgumentException("Bad path: $path")

    /** For JSON calls: the body of a 2xx response, or ApiException. [retry]
     * false stops OkHttp silently re-sending the request after a connection
     * failure -- for a POST that must never go out twice. */
    suspend fun call(request: Request, readTimeoutSeconds: Long = JSON_TIMEOUT_S, retry: Boolean = true): String =
        withContext(Dispatchers.IO) {
            open(request, readTimeoutSeconds, retry).use { it.body?.string().orEmpty() }
        }

    /**
     * For streams: a 2xx response the caller must close, or ApiException.
     * The response is handed back straight from [await], whose cancellation
     * handler closes it; returning it out of a withContext would let a cancel
     * that lands right then drop it unclosed, with its socket still open.
     */
    suspend fun open(request: Request, readTimeoutSeconds: Long, retry: Boolean = true): Response {
        val call = withContext(Dispatchers.IO) {
            val c = if (readTimeoutSeconds == JSON_TIMEOUT_S && retry) client
            else client.newBuilder()
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .retryOnConnectionFailure(retry)
                .build()
            c.newCall(withCookie(request))
        }
        val response = call.await()
        if (response.isSuccessful) return response

        val body = response.use { withContext(Dispatchers.IO) { it.body?.string() } }
        if (response.code == 401 &&
            !request.url.encodedPath.startsWith("/api/auth/") &&
            isNotAuthenticated(body)
        ) {
            session.cookie = null
            expired.tryEmit(Unit)
        }
        throw ApiException(response.code, errorMessage(response.code, body, response.message))
    }

    private fun withCookie(request: Request): Request {
        val cookie = session.cookie
        if (cookie == null || !cookieAllowed(request.url, session.baseUrl, cookie, System.currentTimeMillis())) {
            return request
        }
        return request.newBuilder().header("Cookie", "$COOKIE_NAME=${cookie.value}").build()
    }

    companion object {
        const val COOKIE_NAME = "odysseus_session"
        const val JSON_TIMEOUT_S = 30L
        const val SEND_TIMEOUT_S = 60L
        /** Covers the server's pre-header gating on chat_stream, and is far
         * above the 10s heartbeat once the stream is flowing. */
        const val STREAM_TIMEOUT_S = 120L

        /** Same origin as the stored server, unexpired, and never a Secure
         * cookie over plain http. */
        internal fun cookieAllowed(url: HttpUrl, origin: HttpUrl?, cookie: SessionCookie, now: Long): Boolean =
            origin != null &&
                url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port &&
                now < cookie.expiresAt &&
                (!cookie.secure || url.isHttps)

        /** The odysseus_session cookie from a login response's Set-Cookie, or
         * null if the server set none -- including the `=""; Max-Age=0` it
         * sends to delete one. */
        fun sessionCookie(response: Response, now: Long = System.currentTimeMillis()): SessionCookie? =
            Cookie.parseAll(response.request.url, response.headers)
                .lastOrNull { it.name == COOKIE_NAME }
                ?.takeIf { it.value.isNotEmpty() && it.value != "\"\"" && it.expiresAt > now }
                ?.let { SessionCookie(it.value, it.expiresAt, it.secure) }

        /**
         * The server uses several error shapes: route errors put the text in
         * `detail` (a list of field errors on a 422), the auth middleware uses
         * `error`, and a few handlers put a code like SESSION_NOT_FOUND in
         * `error` with the readable text in `message`.
         */
        internal fun errorMessage(status: Int, body: String?, statusText: String): String {
            val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (json != null) {
                when (val detail = json.opt("detail")) {
                    is String -> if (detail.isNotBlank()) return detail
                    is JSONArray -> return "Invalid request"
                }
                val error = (json.opt("error") as? String)?.takeIf { it.isNotBlank() }
                val message = (json.opt("message") as? String)?.takeIf { it.isNotBlank() }
                if (error != null) {
                    val isCode = error == error.uppercase() && error.any { it.isLetter() }
                    return if (isCode && message != null) message else error
                }
                if (message != null) return message
            }
            return statusText.ifBlank { "HTTP $status" }
        }

        /** The middleware says it with `error`, route checks with `detail`. */
        internal fun isNotAuthenticated(body: String?): Boolean {
            val json = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return false
            return json.opt("error") == NOT_AUTHENTICATED || json.opt("detail") == NOT_AUTHENTICATED
        }

        private const val NOT_AUTHENTICATED = "Not authenticated"
    }
}

/** Enqueue rather than execute, so cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) =
            cont.resume(response) { _, r, _ -> r.close() }
    })
}
