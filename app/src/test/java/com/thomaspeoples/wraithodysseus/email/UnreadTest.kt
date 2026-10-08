package com.thomaspeoples.wraithodysseus.email

import com.thomaspeoples.wraithodysseus.net.ApiException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnreadTest {

    private fun email(uid: String, epoch: Double = 0.0, account: String? = "a") =
        EmailSummary(account, "INBOX", uid, "<$uid@example.com>", "s$uid", "n", "x@example.com", epoch, false)

    private fun page(total: Int, vararg uids: String) = EmailPage(uids.map { email(it) }, total)

    private fun account(id: String, default: Boolean = false, enabled: Boolean = true) =
        EmailAccount(id, id.uppercase(), default, enabled, "$id@example.com")

    // -- passes -----------------------------------------------------------

    @Test
    fun enabledAccountsOnlyDefaultFirst() {
        val passes = unreadPasses(listOf(account("w"), account("old", enabled = false), account("h", default = true)))
        assertEquals(listOf("h", "w"), passes.map { it?.id })
    }

    @Test
    fun noAccountsMeansOneFallbackPass() {
        assertEquals(listOf<EmailAccount?>(null), unreadPasses(emptyList()))
    }

    @Test
    fun allDisabledMeansNoPasses() {
        assertEquals(emptyList<EmailAccount?>(), unreadPasses(listOf(account("old", enabled = false))))
    }

    // -- paging stop rules ------------------------------------------------

    @Test
    fun stopsWhenTheTotalIsReached() = runBlocking {
        val offsets = mutableListOf<Int>()
        val pass = pageUnread { offset ->
            offsets += offset
            if (offset == 0) page(3, "1", "2") else page(3, "3")
        }
        assertEquals(listOf(0, 2), offsets)
        assertEquals(listOf("1", "2", "3"), pass.emails.map { it.uid })
        assertEquals(3, pass.total)
    }

    @Test
    fun stopsOnAnEmptyPage() = runBlocking {
        var calls = 0
        val pass = pageUnread { offset ->
            calls++
            if (offset == 0) page(10, "1", "2") else page(10)
        }
        assertEquals(2, calls)
        assertEquals(2, pass.emails.size)
        assertEquals(10, pass.total)
    }

    @Test
    fun stopsWhenAPageAddsNothingNew() = runBlocking {
        // A server that ignores the offset keeps returning the first page.
        var calls = 0
        val pass = pageUnread { calls++; page(100, "1", "2") }
        assertEquals(2, calls)
        assertEquals(listOf("1", "2"), pass.emails.map { it.uid })
    }

    @Test
    fun overlappingPagesAreDeduped() = runBlocking {
        val pass = pageUnread { offset ->
            when (offset) {
                0 -> page(4, "1", "2")
                2 -> page(4, "2", "3")
                else -> page(4)
            }
        }
        assertEquals(listOf("1", "2", "3"), pass.emails.map { it.uid })
    }

    @Test
    fun givesUpAfterFortyPages() = runBlocking {
        var calls = 0
        val pass = pageUnread { offset -> calls++; page(Int.MAX_VALUE, "u$offset") }
        assertEquals(MAX_PAGES, calls)
        assertEquals(MAX_PAGES, pass.emails.size)
        assertNull(pass.error)
    }

    @Test
    fun anErrorKeepsWhatAlreadyLoaded() = runBlocking {
        val pass = pageUnread { offset ->
            if (offset == 0) page(5, "1", "2") else throw ApiException(200, "Mail operation failed")
        }
        assertEquals(listOf("1", "2"), pass.emails.map { it.uid })
        assertEquals("Mail operation failed", pass.error)
    }

    @Test
    fun aNetworkFailureIsDescribed() = runBlocking {
        assertEquals("Timed out", pageUnread { throw SocketTimeoutException("timeout") }.error)
        assertEquals("Could not reach the server", pageUnread { throw IOException("refused") }.error)
    }

    // -- merge ------------------------------------------------------------

    @Test
    fun mergeSortsNewestFirstAndSumsTotals() {
        val home = account("h", default = true)
        val work = account("w")
        val inbox = mergeUnread(listOf(
            home to UnreadPass(listOf(email("1", 100.0, "h"), email("2", 300.0, "h")), 120, null),
            work to UnreadPass(listOf(email("1", 200.0, "w")), 1, null),
        ))
        assertEquals(listOf("h/INBOX/2", "w/INBOX/1", "h/INBOX/1"), inbox.emails.map { it.key })
        // From the totals, not the loaded rows: one account had more than fit.
        assertEquals(121, inbox.count)
        assertEquals(emptyList<String>(), inbox.errors)
    }

    @Test
    fun aFailedAccountGetsItsOwnBannerAndTheOthersStillShow() {
        val inbox = mergeUnread(listOf(
            account("h") to UnreadPass(listOf(email("1", account = "h")), 1, null),
            account("w") to UnreadPass(emptyList(), 0, "Mail operation failed: LOGIN failed"),
        ))
        assertEquals(1, inbox.emails.size)
        assertEquals(listOf("W: Mail operation failed: LOGIN failed"), inbox.errors)
    }

    @Test
    fun theFallbackPassErrorHasNoAccountName() {
        val inbox = mergeUnread(listOf(null to UnreadPass(emptyList(), 0, "Folder not found: INBOX")))
        assertEquals(listOf("Folder not found: INBOX"), inbox.errors)
    }
}
