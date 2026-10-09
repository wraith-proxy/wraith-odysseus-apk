package com.thomaspeoples.wraithodysseus.ui.sessions

import com.thomaspeoples.wraithodysseus.chat.ArchivedSession
import com.thomaspeoples.wraithodysseus.chat.ChatSession
import com.thomaspeoples.wraithodysseus.chat.SessionsApi
import com.thomaspeoples.wraithodysseus.net.Http
import com.thomaspeoples.wraithodysseus.net.SessionCookie
import com.thomaspeoples.wraithodysseus.net.SessionState
import androidx.compose.runtime.saveable.SaverScope
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

class ReviewLeftoversTest {
    private class FakeSession(override var baseUrl: HttpUrl?, override var cookie: SessionCookie?) : SessionState

    private val server = MockWebServer()
    private lateinit var api: SessionsApi
    private val said = mutableListOf<String>()
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        server.start()
        api = SessionsApi(Http(FakeSession(server.url("/"), SessionCookie("abc123", Long.MAX_VALUE, secure = false))))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun archived(id: String, important: Boolean = false) =
        ArchivedSession(id, id, null, 1, "", "", important)

    private fun list() = ChatListController(
        api, prefs(), scope, { said += it }, {}, { true }, {},
    )

    /** Nothing stored: every read answers null, every write is dropped. */
    private fun prefs(): android.content.SharedPreferences = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(android.content.SharedPreferences::class.java),
    ) { _, m, _ -> if (m.returnType == Boolean::class.javaPrimitiveType) false else null } as android.content.SharedPreferences

    /** The controller loads on the IO threads, so wait for the state to settle. */
    private fun until(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(cond())
    }

    @Test
    fun resetClearsSelectionAndDialogsForTheNextAccount() {
        val c = list()
        c.startSelect("a")
        c.confirmBulkDelete = BulkPlan(listOf("a"), 0)
        c.deleting = ChatSession("a", "a", null, null, null, false, updatedAt = "", lastMessageAt = "")
        c.showAll("g")
        c.reset()
        assertFalse(c.selecting)
        assertTrue(c.selected.isEmpty())
        assertTrue(c.expanded.isEmpty())
        assertNull(c.confirmBulkDelete)
        assertNull(c.deleting)
    }

    @Test
    fun saverKeepsSelectModeAndExpandedGroups() {
        val c = list()
        c.startSelect("a")
        c.toggle("b")
        c.showAll("g")
        val saver = chatListSaver { list() }
        val saved = with(saver) { SaverScope { true }.save(c) }!!
        val back = saver.restore(saved)!!
        assertTrue(back.selecting)
        assertEquals(setOf("a", "b"), back.selected)
        assertEquals(setOf("g"), back.expanded)
    }

    @Test
    fun aFavoriteIsRefusedBeforeTheDeleteDialog() {
        val c = ArchivedController(api, scope) { said += it }
        c.askDelete(archived("s", important = true))
        assertNull(c.deleting)
        assertEquals(listOf(SessionsApi.UNFAVORITE_FIRST), said)
        val plain = archived("t")
        c.askDelete(plain)
        assertEquals(plain, c.deleting)
    }

    @Test
    fun aFailedNextPageOffersRetryInsteadOfSpinningForever() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"sessions":[{"id":"a","name":"a","message_count":1}],"total":3}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        val c = ArchivedController(api, scope) { said += it }
        c.refresh()
        until { !c.loading }
        assertEquals(1, c.rows.size)
        c.loadMore()
        until { c.moreFailed }
        until { !c.loadingMore }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"sessions":[{"id":"b","name":"b","message_count":1}],"total":3}"""))
        c.loadMore()
        until { c.rows.size == 2 }
        assertFalse(c.moreFailed)
        assertEquals(listOf("a", "b"), c.rows.map { it.id })
    }
}
