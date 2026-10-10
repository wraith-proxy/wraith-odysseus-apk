package com.thomaspeoples.wraithodysseus.auth

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthRepoTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private val server = MockWebServer()
    private lateinit var origin: HttpUrl
    private lateinit var session: FakeSession
    private lateinit var repo: AuthRepo

    private val token = "0123456789abcdef".repeat(4)
    private val sessionSetCookie = "odysseus_session=$token; HttpOnly; Max-Age=604800; Path=/; SameSite=lax"

    @Before
    fun setUp() {
        server.start()
        origin = server.url("/")
        session = FakeSession(origin, null)
        repo = AuthRepo(Http(session), session)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(code: Int, fixture: String) = MockResponse().setResponseCode(code).setBody(fixture(fixture))

    // -- parsing ----------------------------------------------------------

    @Test
    fun versionAndStatusParse() {
        assertEquals(ServerVersion("1.0.3", "0.20.19"), AuthRepo.parseVersion(fixture("version.json")))

        val admin = AuthRepo.parseStatus(fixture("auth_status_authenticated_admin.json"))
        assertEquals(AuthStatus(configured = true, authenticated = true, username = "alice", isAdmin = true, canUseBash = true), admin)

        val user = AuthRepo.parseStatus(fixture("auth_status_authenticated_user.json"))
        assertFalse(user.isAdmin)
        assertFalse(user.canUseBash)
        // Only can_* keys set to false count; allowed_models_restricted: false
        // is a setting, not a denial.
        assertEquals(setOf("can_use_bash"), user.denied)
        assertTrue(user.allows("can_use_research"))
        assertFalse(user.allows("can_use_bash"))
        assertFalse(AuthRepo.parseStatus("""{"privileges": {"can_manage_memory": false}}""").allows("can_manage_memory"))

        val anon = AuthRepo.parseStatus(fixture("auth_status_anonymous.json"))
        assertFalse(anon.authenticated)
        assertNull(anon.username)
        assertFalse(anon.canUseBash)

        assertFalse(AuthRepo.parseStatus(fixture("auth_status_unconfigured.json")).configured)
    }

    // -- login ------------------------------------------------------------

    @Test
    fun loginOkCapturesTheCookie() = runBlocking {
        server.enqueue(json(200, "login_ok.json").addHeader("Set-Cookie", sessionSetCookie))
        val before = System.currentTimeMillis()

        assertEquals(LoginResult.Ok("alice"), repo.login(origin, "Alice", "hunter2", null))

        val cookie = checkNotNull(session.cookie)
        assertEquals(token, cookie.value)
        assertFalse(cookie.secure)
        assertTrue(cookie.expiresAt >= before + 604_800_000L - 1_000)

        val req = server.takeRequest()
        assertEquals("/api/auth/login", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("Alice", body.getString("username"))
        assertEquals("hunter2", body.getString("password"))
        assertTrue(body.getBoolean("remember"))
        assertFalse(body.has("totp_code"))
    }

    @Test
    fun requiresTotpStoresNothingAndTheRePostCarriesThePasswordAndCode() = runBlocking {
        server.enqueue(json(200, "login_requires_totp.json"))
        assertEquals(LoginResult.TotpRequired, repo.login(origin, "alice", "hunter2", null))
        assertNull(session.cookie)

        server.enqueue(json(200, "login_ok.json").addHeader("Set-Cookie", sessionSetCookie))
        assertEquals(LoginResult.Ok("alice"), repo.login(origin, "alice", "hunter2", "123456"))
        server.takeRequest()
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("hunter2", body.getString("password"))
        assertEquals("123456", body.getString("totp_code"))
        assertTrue(body.getBoolean("remember"))
        assertEquals(token, session.cookie?.value)
    }

    @Test
    fun loginFailuresAreShownInline() = runBlocking {
        server.enqueue(json(401, "login_401_invalid_credentials.json"))
        assertEquals(LoginResult.Failed("Invalid credentials"), repo.login(origin, "alice", "wrong", null))

        server.enqueue(json(401, "login_401_invalid_totp.json"))
        assertEquals(LoginResult.Failed("Invalid 2FA code"), repo.login(origin, "alice", "hunter2", "000000"))

        server.enqueue(json(429, "login_429.json"))
        assertEquals(LoginResult.Failed(AuthRepo.TOO_MANY), repo.login(origin, "alice", "hunter2", null))

        server.enqueue(json(401, "error_401_setup_required.json"))
        assertEquals(LoginResult.Failed(AuthRepo.SETUP_REQUIRED), repo.login(origin, "alice", "hunter2", null))

        assertNull(session.cookie)
        // One request each: a 429 is never retried behind the user's back.
        assertEquals(4, server.requestCount)
    }

    @Test
    fun aLoginDropsTheCookieItReplaces() = runBlocking {
        session.cookie = SessionCookie("old", Long.MAX_VALUE, secure = false)
        server.enqueue(json(401, "login_401_invalid_credentials.json"))
        repo.login(origin, "alice", "wrong", null)
        assertNull(session.cookie)
    }

    @Test
    fun aSecureCookieOverHttpIsRefused() = runBlocking {
        server.enqueue(json(200, "login_ok.json").addHeader("Set-Cookie", "$sessionSetCookie; Secure"))
        assertEquals(LoginResult.Failed(AuthRepo.NEEDS_HTTPS), repo.login(origin, "alice", "hunter2", null))
        assertNull(session.cookie)
    }

    @Test
    fun anOkWithoutACookieIsAFailure() = runBlocking {
        server.enqueue(json(200, "login_ok.json"))
        assertEquals(LoginResult.Failed(AuthRepo.NO_COOKIE), repo.login(origin, "alice", "hunter2", null))
    }

    @Test
    fun aLoginToAnotherServerNeverSendsItsCookieToTheOldOne() = runBlocking {
        val other = MockWebServer().apply { start() }
        try {
            val otherOrigin = other.url("/")
            other.enqueue(json(200, "login_ok.json").addHeader("Set-Cookie", sessionSetCookie))
            assertEquals(LoginResult.Ok("alice"), repo.login(otherOrigin, "alice", "hunter2", null))
            // Stored together: the cookie is never filed under the old origin.
            assertEquals(otherOrigin, session.baseUrl)

            server.enqueue(json(200, "version.json"))
            repo.version(origin)
            assertNull(server.takeRequest().getHeader("Cookie"))
        } finally {
            other.shutdown()
        }
    }

    // -- logout -----------------------------------------------------------

    @Test
    fun logoutPostsAndClearsTheCookie() = runBlocking {
        session.cookie = SessionCookie(token, Long.MAX_VALUE, secure = false)
        server.enqueue(json(200, "logout_ok.json").addHeader("Set-Cookie", "odysseus_session=\"\"; Max-Age=0; Path=/"))
        repo.logout()
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/auth/logout", req.path)
        assertEquals("odysseus_session=$token", req.getHeader("Cookie"))
        assertNull(session.cookie)
    }

    @Test
    fun logoutClearsTheCookieEvenWhenTheServerIsGone() = runBlocking {
        session.cookie = SessionCookie(token, Long.MAX_VALUE, secure = false)
        server.shutdown()
        repo.logout()
        assertNull(session.cookie)
    }

    // -- account ----------------------------------------------------------

    @Test
    fun changePasswordPostsJsonWithBothPasswords() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok": true}"""))
        repo.changePassword("old pw", "new pw 123")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/auth/change-password", req.path)
        assertTrue(req.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("old pw", body.getString("current_password"))
        assertEquals("new pw 123", body.getString("new_password"))
    }

    @Test
    fun changePasswordShowsTheServersOwnWords() = runBlocking {
        server.enqueue(json(400, "change_password_400_wrong.json"))
        server.enqueue(json(400, "change_password_400_short.json"))
        val wrong = runCatching { repo.changePassword("x", "longenough") }.exceptionOrNull() as com.thomaspeoples.wraithodysseus.net.ApiException
        assertEquals("Current password is incorrect", wrong.message)
        val short = runCatching { repo.changePassword("x", "abc") }.exceptionOrNull() as com.thomaspeoples.wraithodysseus.net.ApiException
        assertEquals("Password must be at least 8 characters", short.message)
        // Never sent twice behind the user's back.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun totpStatusIsReadFromTheStatusEndpoint() = runBlocking {
        server.enqueue(json(200, "totp_status_on.json"))
        server.enqueue(MockResponse().setBody("""{"enabled": false}"""))
        assertTrue(repo.totpEnabled())
        assertEquals("/api/auth/2fa/status", server.takeRequest().path)
        assertFalse(repo.totpEnabled())
        assertTrue(AuthRepo.parseTotpStatus(fixture("totp_status_on.json")))
        assertFalse(AuthRepo.parseTotpStatus("{}"))
    }

    @Test
    fun theFormChecksOnlyWhatTheServerCannotSay() {
        assertEquals("Enter your current password", ChangePasswordForm("", "a", "a").problem())
        assertEquals("Enter a new password", ChangePasswordForm("x", "", "").problem())
        assertEquals("The new passwords do not match", ChangePasswordForm("x", "abc", "abd").problem())
        assertNull(ChangePasswordForm("x", "abc", "abc").problem())
    }
}
