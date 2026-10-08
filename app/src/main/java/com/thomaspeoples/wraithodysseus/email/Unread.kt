package com.thomaspeoples.wraithodysseus.email

import com.thomaspeoples.wraithodysseus.net.ApiException
import java.io.IOException
import java.net.SocketTimeoutException

// The server has no merged inbox, so "every unread message" is built here:
// one paged pass per account, then a merge.

/** One account's pass. [error] is set if it stopped early; whatever loaded
 * before that is still in [emails]. */
data class UnreadPass(val emails: List<EmailSummary>, val total: Int, val error: String?)

/** The merged inbox. [errors] is one "Account: reason" line per failed pass. */
data class UnreadInbox(val emails: List<EmailSummary>, val count: Int, val errors: List<String>)

const val MAX_PAGES = 40

/**
 * Which passes to make: the enabled accounts, default first. An empty list
 * means the user has no accounts row at all, so one pass goes out with no
 * account_id (null) and the server falls back to its legacy settings or env
 * config. All-disabled is not empty: it means no passes.
 */
fun unreadPasses(accounts: List<EmailAccount>): List<EmailAccount?> =
    if (accounts.isEmpty()) listOf(null)
    else accounts.filter { it.enabled }.sortedByDescending { it.isDefault }

/**
 * Pages through one account's unread list until any of: the server's total
 * is reached, a page comes back empty, a page adds no uid not already seen
 * (the list shifted under us, or the server ignored the offset), or
 * [MAX_PAGES] pages -- so a misbehaving server can never loop us forever.
 */
suspend fun pageUnread(fetch: suspend (offset: Int) -> EmailPage): UnreadPass {
    val seen = LinkedHashMap<String, EmailSummary>()
    var offset = 0
    var total = 0
    repeat(MAX_PAGES) {
        val page = try {
            fetch(offset)
        } catch (e: ApiException) {
            return UnreadPass(seen.values.toList(), total, e.message)
        } catch (e: IOException) {
            return UnreadPass(seen.values.toList(), total, describe(e))
        }
        total = page.total
        val added = page.emails.count { seen.putIfAbsent(it.key, it) == null }
        offset += page.emails.size
        if (page.emails.isEmpty() || added == 0 || offset >= total) return UnreadPass(seen.values.toList(), total, null)
    }
    return UnreadPass(seen.values.toList(), total, null)
}

/** Newest first across all accounts. The count is the sum of the server's
 * per-account totals, so it is right even past the page cap. */
fun mergeUnread(passes: List<Pair<EmailAccount?, UnreadPass>>): UnreadInbox = UnreadInbox(
    emails = passes.flatMap { it.second.emails }.distinctBy { it.key }.sortedByDescending { it.dateEpoch },
    count = passes.sumOf { it.second.total },
    errors = passes.mapNotNull { (account, pass) ->
        pass.error?.let { if (account == null) it else "${account.name}: $it" }
    },
)

/** Human text for a network failure that never got an HTTP answer. */
fun describe(e: IOException): String =
    if (e is SocketTimeoutException) "Timed out" else "Could not reach the server"
