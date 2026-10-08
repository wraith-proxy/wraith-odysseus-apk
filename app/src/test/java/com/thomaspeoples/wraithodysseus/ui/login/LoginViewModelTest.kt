package com.thomaspeoples.wraithodysseus.ui.login

import com.thomaspeoples.wraithodysseus.auth.AuthRepo
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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

class LoginViewModelTest {

    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { name }.readText()

    private val server = MockWebServer()
    private val session = FakeSession(null, null)
    private var saved: Pair<HttpUrl, String>? = null

    // A literal IP, not server.url(): MockWebServer names itself "localhost",
    // and HostPolicy rightly refuses plain http to any hostname.
    private val origin get() = "http://127.0.0.1:${server.port}/".toHttpUrl()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    /** The VM's coroutines run as children of the test's own runBlocking, so
     * [settle] can wait for them without a main looper. */
    private fun CoroutineScope.vm() = LoginViewModel(
        repo = AuthRepo(Http(session), session),
        initialUrl = "",
        initialUsername = "",
        saveConnection = { origin, username -> saved = origin to username },
        scope = this,
    ).apply {
        onServerUrl(origin.toString())
        onUsername("alice")
        onPassword("hunter2")
    }

    private suspend fun CoroutineScope.settle() = coroutineContext.job.children.forEach { it.join() }

    private fun ok() = MockResponse().setBody(fixture("login_ok.json"))
        .addHeader("Set-Cookie", "odysseus_session=${"ab".repeat(32)}; HttpOnly; Max-Age=604800; Path=/; SameSite=lax")

    @Test
    fun totpThenOkClearsThePasswordAndSavesTheConnection() = runBlocking {
        val vm = vm()
        server.enqueue(MockResponse().setBody(fixture("login_requires_totp.json")))
        vm.submit()
        settle()
        assertTrue(vm.state.value.needsTotp)
        // Still needed: the TOTP POST re-sends it.
        assertEquals("hunter2", vm.state.value.password)

        server.enqueue(ok())
        vm.onTotpCode("12 34-56")
        vm.submit()
        settle()

        val s = vm.state.value
        assertTrue(s.loggedIn)
        assertEquals("", s.password)
        assertEquals("", s.totpCode)
        assertEquals(origin to "alice", saved)
        server.takeRequest()
        assertEquals("123456", JSONObject(server.takeRequest().body.readUtf8()).getString("totp_code"))
    }

    @Test
    fun aFailureIsShownAndNothingIsSaved() = runBlocking {
        val vm = vm()
        server.enqueue(MockResponse().setResponseCode(429).setBody(fixture("login_429.json")))
        vm.submit()
        settle()
        assertEquals(AuthRepo.TOO_MANY, vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.loggedIn)
        assertNull(saved)
    }

    @Test
    fun aRejectedHostNeverReachesTheNetwork() = runBlocking {
        val vm = vm()
        vm.onServerUrl("http://odysseus.example.ts.net")
        vm.submit()
        vm.checkServer()
        settle()
        assertTrue(vm.state.value.serverError!!.startsWith("Plain http"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun serverCheckShowsTheVersionAndFlagsAnUnconfiguredServer() = runBlocking {
        val vm = vm()
        server.enqueue(MockResponse().setBody(fixture("version.json")))
        server.enqueue(MockResponse().setBody(fixture("auth_status_anonymous.json")))
        vm.checkServer()
        settle()
        assertEquals("Odysseus 1.0.3", vm.state.value.serverVersion)
        assertNull(vm.state.value.serverError)

        server.enqueue(MockResponse().setBody(fixture("version.json")))
        server.enqueue(MockResponse().setBody(fixture("auth_status_unconfigured.json")))
        vm.checkServer()
        settle()
        assertEquals(AuthRepo.SETUP_REQUIRED, vm.state.value.serverError)
    }
}
