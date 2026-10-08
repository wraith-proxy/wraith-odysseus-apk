package com.thomaspeoples.wraithodysseus.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private val server = MockWebServer()
    private lateinit var session: FakeSession
    private lateinit var http: Http
    private val future = Long.MAX_VALUE

    @Before
    fun setUp() {
        server.start()
        session = FakeSession(server.url("/"), SessionCookie("abc123", future, secure = false))
        http = Http(session)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun get(path: String) = Request.Builder().url(http.url(path)).build()

    private suspend fun callExpectingError(path: String): ApiException =
        runCatching { http.call(get(path)) }.exceptionOrNull() as? ApiException
            ?: throw AssertionError("expected ApiException")

    // -- error text -------------------------------------------------------

    @Test
    fun errorTextComesFromDetailThenErrorThenMessageThenStatus() {
        assertEquals("Invalid credentials", Http.errorMessage(401, fixture("login_401_invalid_credentials.json"), ""))
        assertEquals("Not authenticated", Http.errorMessage(401, fixture("error_401_middleware_not_authenticated.json"), ""))
        assertEquals("Request exceeded 45s timeout", Http.errorMessage(504, fixture("error_504_timeout.json"), ""))
        assertEquals("Too many requests — try again later", Http.errorMessage(429, fixture("login_429.json"), ""))
        assertEquals("Bad Gateway", Http.errorMessage(502, "<html>nginx</html>", "Bad Gateway"))
        assertEquals("HTTP 502", Http.errorMessage(502, null, ""))
    }

    @Test
    fun anAllCapsErrorCodeDefersToTheMessage() {
        assertEquals(
            "Session not found: 7d3c2f0e-1111-4a2b-9c3d-000000000001",
            Http.errorMessage(404, fixture("error_404_session_not_found_handler.json"), "Not Found"),
        )
        assertEquals("SESSION_NOT_FOUND", Http.errorMessage(404, """{"error":"SESSION_NOT_FOUND"}""", ""))
    }

    @Test
    fun aValidationErrorListIsSummarised() {
        assertEquals("Invalid request", Http.errorMessage(422, fixture("error_422_validation.json"), ""))
    }

    @Test
    fun notAuthenticatedIsRecognisedInBothShapesOnly() {
        assertTrue(Http.isNotAuthenticated(fixture("error_401_middleware_not_authenticated.json")))
        assertTrue(Http.isNotAuthenticated(fixture("error_401_route_not_authenticated.json")))
        assertFalse(Http.isNotAuthenticated(fixture("error_401_setup_required.json")))
        assertFalse(Http.isNotAuthenticated(fixture("error_401_authentication_required.json")))
        assertFalse(Http.isNotAuthenticated(fixture("login_401_invalid_credentials.json")))
        assertFalse(Http.isNotAuthenticated(null))
    }

    // -- cookie rules -----------------------------------------------------

    @Test
    fun cookieGoesOnlyToTheStoredOrigin() {
        val origin = "https://odysseus.example.com/".toHttpUrl()
        val c = SessionCookie("v", 2_000, secure = true)
        assertTrue(Http.cookieAllowed("https://odysseus.example.com/api/sessions".toHttpUrl(), origin, c, 1_000))
        assertFalse(Http.cookieAllowed("https://evil.example.com/api/sessions".toHttpUrl(), origin, c, 1_000))
        assertFalse(Http.cookieAllowed("https://odysseus.example.com:8443/api".toHttpUrl(), origin, c, 1_000))
        assertFalse(Http.cookieAllowed("https://odysseus.example.com/api".toHttpUrl(), null, c, 1_000))
    }

    @Test
    fun anExpiredCookieIsNotSent() {
        val origin = "https://odysseus.example.com/".toHttpUrl()
        assertFalse(Http.cookieAllowed(origin, origin, SessionCookie("v", 1_000, secure = true), 1_000))
    }

    @Test
    fun aSecureCookieIsWithheldOverHttp() = runBlocking {
        session.cookie = SessionCookie("abc123", future, secure = true)
        server.enqueue(MockResponse().setBody("[]"))
        http.call(get("/api/sessions"))
        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun theCookieIsSentAndNoAuthorizationHeaderEver() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals("[]", http.call(get("/api/sessions")))
        val req = server.takeRequest()
        assertEquals("odysseus_session=abc123", req.getHeader("Cookie"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test
    fun theSessionCookieIsReadFromSetCookie() {
        fun loginResponse(vararg setCookies: String): Response {
            val b = Response.Builder()
                .request(Request.Builder().url("https://odysseus.example.com/api/auth/login").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            setCookies.forEach { b.addHeader("Set-Cookie", it) }
            return b.build()
        }
        val c = Http.sessionCookie(
            loginResponse("odysseus_session=${"a".repeat(64)}; HttpOnly; Max-Age=604800; Path=/; SameSite=lax; Secure")
        )
        assertNotNull(c)
        assertEquals("a".repeat(64), c!!.value)
        assertTrue(c.secure)
        assertTrue(c.expiresAt > System.currentTimeMillis() + 6L * 24 * 3600 * 1000)
        assertNull(Http.sessionCookie(loginResponse()))
        assertNull(Http.sessionCookie(loginResponse("odysseus_session=\"\"; Max-Age=0; Path=/")))
    }

    // -- redirects and session expiry --------------------------------------

    @Test
    fun redirectsAreNotFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://203.0.113.9/"))
        assertEquals(302, callExpectingError("/api/sessions").status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun notAuthenticatedOnAnAppRouteClearsTheCookieAndSignals() = runBlocking {
        for (name in listOf("error_401_middleware_not_authenticated.json", "error_401_route_not_authenticated.json")) {
            session.cookie = SessionCookie("abc123", future, secure = false)
            val signalled = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { http.sessionExpired.first() }
            }
            server.enqueue(MockResponse().setResponseCode(401).setBody(fixture(name)))
            assertEquals("Not authenticated", callExpectingError("/api/sessions").message)
            signalled.await()
            assertNull(name, session.cookie)
        }
    }

    @Test
    fun a401OnAnAuthRouteDoesNotExpireTheSession() = runBlocking {
        val signalled = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(500) { http.sessionExpired.first() }
        }
        server.enqueue(MockResponse().setResponseCode(401).setBody(fixture("error_401_route_not_authenticated.json")))
        callExpectingError("/api/auth/login")
        assertNull(signalled.await())
        assertNotNull(session.cookie)
    }

    @Test
    fun setupRequiredDoesNotExpireTheSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody(fixture("error_401_setup_required.json")))
        assertEquals("Setup required", callExpectingError("/api/sessions").message)
        assertNotNull(session.cookie)
    }
}
