package com.thomaspeoples.wraithodysseus.ui.email

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thomaspeoples.wraithodysseus.email.EmailAccount
import com.thomaspeoples.wraithodysseus.email.EmailApi
import com.thomaspeoples.wraithodysseus.email.EmailSummary
import com.thomaspeoples.wraithodysseus.email.InboxFilters
import com.thomaspeoples.wraithodysseus.email.SCHEDULED_FOLDER
import com.thomaspeoples.wraithodysseus.email.ScheduledEmail
import com.thomaspeoples.wraithodysseus.email.SearchOptions
import com.thomaspeoples.wraithodysseus.email.UnreadInbox
import com.thomaspeoples.wraithodysseus.email.UnreadPass
import com.thomaspeoples.wraithodysseus.email.applySearchOptions
import com.thomaspeoples.wraithodysseus.email.mergeSearch
import com.thomaspeoples.wraithodysseus.email.mergeUnread
import com.thomaspeoples.wraithodysseus.email.pageUnread
import com.thomaspeoples.wraithodysseus.email.reminderNote
import com.thomaspeoples.wraithodysseus.email.sortedFolders
import com.thomaspeoples.wraithodysseus.email.unreadPasses
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONException

data class InboxState(
    /** Enabled accounts, default first. Empty in the no-accounts fallback. */
    val accounts: List<EmailAccount> = emptyList(),
    /** The virtual "All · Unread" chip: every account's unread INBOX merged. */
    val allUnread: Boolean = false,
    /** The selected account; null in the fallback, where the server picks. */
    val accountId: String? = null,
    val folder: String = EmailApi.INBOX,
    val folders: List<String> = listOf(EmailApi.INBOX),
    val filter: String = InboxFilters.ALL,
    val emails: List<EmailSummary> = emptyList(),
    val total: Int = 0,
    /** sync.updated_at of the list on screen, for "Last: ...". */
    val updatedAt: String? = null,
    val scheduled: List<ScheduledEmail> = emptyList(),
    /** False until the first load finishes, so "no mail" is not shown early. */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val problem: String? = null,
    /** One line per account that failed in the all-unread pass. */
    val errors: List<String> = emptyList(),
    /** True when there are accounts but none of them is enabled. */
    val noAccounts: Boolean = false,
    /** Account id ("" for the fallback) -> INBOX unread count. */
    val unread: Map<String, Int> = emptyMap(),
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
    val query: String = "",
    /** Search results; null when no search is showing. */
    val found: List<EmailSummary>? = null,
    val searchBusy: Boolean = false,
    val options: SearchOptions = SearchOptions(),
) {
    val accountNames: Map<String, String> get() = accounts.associate { it.id to it.name }

    /** What the list shows: search results (narrowed by the "..." menu) or the folder. */
    val visible: List<EmailSummary> get() = found?.let { applySearchOptions(it, options) } ?: emails

    val isScheduled get() = !allUnread && folder == SCHEDULED_FOLDER
}

enum class BulkAction { Done, Read, Unread, Delete }

/** Kept per back-stack entry, so coming back from a message does not reload. */
class InboxViewModel(private val api: EmailApi) : ViewModel() {
    var state by mutableStateOf(InboxState())
        private set

    private val say = Channel<String>(Channel.BUFFERED)

    /** Short notices (a failed action) for the screen to put in the snackbar. */
    val messages = say.receiveAsFlow()

    private var listJob: Job? = null
    private var searchJob: Job? = null
    private var knownMaxUid = mutableMapOf<String, Long>()

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        state = state.copy(loading = true, problem = null)
        val accounts = try {
            api.accounts()
        } catch (e: ApiException) {
            state = state.copy(loading = false, loaded = true, problem = e.message)
            return
        } catch (e: IOException) {
            state = state.copy(loading = false, loaded = true, problem = userMessage(e))
            return
        }
        val enabled = accounts.filter { it.enabled }.sortedByDescending { it.isDefault }
        state = state.copy(
            accounts = enabled,
            accountId = enabled.firstOrNull()?.id,
            noAccounts = accounts.isNotEmpty() && enabled.isEmpty(),
        )
        if (state.noAccounts) {
            state = state.copy(loading = false, loaded = true)
            return
        }
        viewModelScope.launch { loadFolders() }
        viewModelScope.launch { poll() }
        reload()
    }

    // -- views ------------------------------------------------------------

    fun selectAllUnread() = switchView { it.copy(allUnread = true, folder = EmailApi.INBOX, filter = InboxFilters.ALL) }

    fun selectAccount(id: String) {
        if (!state.allUnread && state.accountId == id) return
        switchView { it.copy(allUnread = false, accountId = id, folder = EmailApi.INBOX, filter = InboxFilters.ALL) }
        viewModelScope.launch { loadFolders() }
    }

    fun selectFolder(folder: String) = switchView { it.copy(folder = folder, filter = InboxFilters.ALL) }

    fun selectFilter(filter: String) = switchView { it.copy(filter = filter) }

    private fun switchView(change: (InboxState) -> InboxState) {
        searchJob?.cancel()
        state = change(state).copy(
            emails = emptyList(), total = 0, scheduled = emptyList(), updatedAt = null, loaded = false,
            selecting = false, selected = emptySet(), query = "", found = null, searchBusy = false,
            options = SearchOptions(), errors = emptyList(),
        )
        reload()
    }

    private suspend fun loadFolders() {
        val names = try {
            api.folders(state.accountId)
        } catch (_: ApiException) {
            return
        } catch (_: IOException) {
            return
        }
        if (names.isNotEmpty()) state = state.copy(folders = sortedFolders(names))
    }

    // -- the list ---------------------------------------------------------

    /** [pull] is the user's pull-to-refresh: it skips the cached paint and
     * sends refresh=1, so the server re-asks the mail provider. */
    fun reload(pull: Boolean = false) {
        listJob?.cancel()
        if (state.query.trim().length >= EmailApi.MIN_SEARCH) {
            search(state.query.trim())
            if (!pull) return
        }
        listJob = viewModelScope.launch {
            state = state.copy(loading = !pull, refreshing = pull, problem = null, errors = emptyList())
            try {
                val s = state
                when {
                    s.allUnread -> loadAllUnread()
                    s.folder == SCHEDULED_FOLDER -> state = state.copy(scheduled = api.scheduled(), updatedAt = null)
                    else -> loadFolder(pull)
                }
            } catch (e: ApiException) {
                state = state.copy(problem = e.message)
            } catch (e: IOException) {
                state = state.copy(problem = userMessage(e))
            } catch (e: JSONException) {
                state = state.copy(problem = userMessage(e))
            } finally {
                state = state.copy(loading = false, refreshing = false, loaded = true)
            }
        }
    }

    private suspend fun loadFolder(pull: Boolean) {
        val s = state
        if (!pull) {
            // Paint the server's index straight away; it may be old or empty,
            // so the real list follows. A failure here is not worth showing.
            val cached = try {
                api.listPage(s.accountId, s.folder, s.filter, 0, cachedOnly = true)
            } catch (_: ApiException) {
                null
            } catch (_: IOException) {
                null
            }
            if (cached != null && cached.emails.isNotEmpty()) {
                state = state.copy(emails = cached.emails, total = cached.total, updatedAt = cached.updatedAt, loaded = true)
            }
        }
        val page = api.listPage(s.accountId, s.folder, s.filter, 0, refresh = pull)
        state = state.copy(emails = page.emails, total = page.total, updatedAt = page.updatedAt ?: state.updatedAt)
    }

    private suspend fun loadAllUnread() {
        val passes = unreadPasses(state.accounts)
        val done = mutableListOf<Pair<EmailAccount?, UnreadPass>>()
        // One account at a time, showing each as it lands: the first
        // screenful should not wait for the slowest mailbox.
        for (account in passes) {
            done += account to pageUnread { offset -> api.unreadPage(account?.id, offset) }
            publish(mergeUnread(done))
        }
    }

    private fun publish(inbox: UnreadInbox) {
        state = state.copy(emails = inbox.emails, total = inbox.count, errors = inbox.errors, updatedAt = null)
    }

    fun loadMore() {
        val s = state
        if (s.loadingMore || s.allUnread || s.isScheduled || s.found != null || s.emails.size >= s.total) return
        viewModelScope.launch {
            state = state.copy(loadingMore = true)
            try {
                val page = api.listPage(s.accountId, s.folder, s.filter, s.emails.size)
                state = state.copy(emails = (state.emails + page.emails).distinctBy { it.key }, total = page.total)
            } catch (e: ApiException) {
                state = state.copy(problem = e.message)
            } catch (e: IOException) {
                state = state.copy(problem = userMessage(e))
            } finally {
                state = state.copy(loadingMore = false)
            }
        }
    }

    // -- unread counts ----------------------------------------------------

    /** The chips' counts, and a reload when new mail has reached the shown
     * INBOX. Called on resume and then every minute while visible. */
    suspend fun poll() {
        val ids = state.accounts.map { it.id }.ifEmpty { listOf(null) }
        val counts = mutableMapOf<String, Int>()
        var arrived = false
        coroutineScope {
            ids.map { id ->
                async {
                    try {
                        id to api.unreadState(id)
                    } catch (_: ApiException) {
                        null
                    } catch (_: IOException) {
                        null
                    }
                }
            }.awaitAll().filterNotNull().forEach { (id, u) ->
                counts[id.orEmpty()] = u.count
                val before = knownMaxUid.put(id.orEmpty(), u.maxUid)
                if (before != null && u.maxUid > before && (state.allUnread || id == state.accountId)) arrived = true
            }
        }
        state = state.copy(unread = state.unread + counts)
        val s = state
        val watching = s.folder == EmailApi.INBOX && !s.isScheduled && !s.selecting && s.found == null
        if (arrived && watching && !s.loading) reload()
    }

    // -- one message ------------------------------------------------------

    private fun update(key: String, change: (EmailSummary) -> EmailSummary) {
        fun List<EmailSummary>.apply() = map { if (it.key == key) change(it) else it }
        state = state.copy(emails = state.emails.apply(), found = state.found?.apply())
    }

    private fun drop(keys: Set<String>) {
        val gone = state.emails.count { it.key in keys }
        state = state.copy(
            emails = state.emails.filterNot { it.key in keys },
            found = state.found?.filterNot { it.key in keys },
            total = (state.total - gone).coerceAtLeast(0),
            selected = state.selected - keys,
        )
    }

    private suspend fun attempt(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: ApiException) {
        userMessage(e)
    } catch (e: IOException) {
        userMessage(e)
    } catch (e: JSONException) {
        userMessage(e)
    }

    /** Opening a message marks it seen on the server, so it leaves an unread
     * view here too without a reload. */
    fun opened(email: EmailSummary) {
        if (state.allUnread || state.filter == InboxFilters.UNREAD) drop(setOf(email.key))
        else update(email.key) { it.copy(isRead = true) }
    }

    fun toggleFavorite(email: EmailSummary) {
        val on = !email.isFlagged
        // The Favorites view is a snapshot of what is flagged: unflagging
        // takes the row out of it at once, as the web does.
        val leaves = state.filter == InboxFilters.FAVORITES && !on && state.found == null
        if (leaves) drop(setOf(email.key)) else update(email.key) { it.copy(isFlagged = on) }
        viewModelScope.launch {
            val failed = attempt { api.flag(email.accountId, email.folder, email.uid, on) }
            if (failed != null) {
                say.send("Could not ${if (on) "favorite" else "unfavorite"}: $failed")
                if (leaves) reload() else update(email.key) { it.copy(isFlagged = !on) }
            }
        }
    }

    fun toggleDone(email: EmailSummary) {
        val done = !email.isAnswered
        val leaves = done && state.allUnread
        if (leaves) drop(setOf(email.key)) else update(email.key) { it.copy(isAnswered = done, isRead = email.isRead || done) }
        viewModelScope.launch {
            val failed = attempt {
                if (done) {
                    api.markAnswered(email.accountId, email.folder, email.uid)
                    api.markRead(email.accountId, email.folder, email.uid)
                } else {
                    api.clearAnswered(email.accountId, email.folder, email.uid)
                }
            }
            if (failed != null) {
                say.send("Could not mark ${if (done) "done" else "not done"}: $failed")
                if (leaves) reload() else update(email.key) { it.copy(isAnswered = email.isAnswered, isRead = email.isRead) }
            }
        }
    }

    fun archive(email: EmailSummary) {
        drop(setOf(email.key))
        viewModelScope.launch {
            attempt { api.archive(email.accountId, email.folder, email.uid) }?.let {
                say.send("Could not archive: $it")
                reload()
            }
        }
    }

    fun delete(email: EmailSummary) {
        drop(setOf(email.key))
        viewModelScope.launch {
            attempt { api.delete(email.accountId, email.folder, email.uid, email.messageId) }?.let {
                say.send("Could not delete: $it")
                reload()
            }
        }
    }

    /** "Remind to reply": a note, not a mail-server feature. [due] null saves
     * a note with no time, so nothing fires. */
    fun remind(email: EmailSummary, due: LocalDateTime?) {
        viewModelScope.launch {
            val failed = attempt { api.createNote(reminderNote(email, due, api.origin())) }
            say.send(if (failed != null) "Could not create the reminder: $failed" else if (due == null) "Reply note saved" else "Reminder set")
        }
    }

    fun cancelScheduled(item: ScheduledEmail) {
        state = state.copy(scheduled = state.scheduled.filterNot { it.id == item.id })
        viewModelScope.launch {
            attempt { api.cancelScheduled(item.id) }?.let {
                say.send("Could not cancel: $it")
                reload()
            }
        }
    }

    // -- selection --------------------------------------------------------

    fun startSelecting(email: EmailSummary) {
        state = state.copy(selecting = true, selected = setOf(email.key))
    }

    fun toggleSelected(email: EmailSummary) {
        val next = if (email.key in state.selected) state.selected - email.key else state.selected + email.key
        state = state.copy(selected = next, selecting = next.isNotEmpty())
    }

    fun selectAll() {
        state = state.copy(selected = state.visible.map { it.key }.toSet())
    }

    fun stopSelecting() {
        state = state.copy(selecting = false, selected = emptySet())
    }

    /** Runs [action] on the selected rows. Delete goes as one request per
     * account and folder; the rest are one request per message, six at a
     * time, as the web does. Anything that failed is reported and the list
     * reloaded to show what the server really has. */
    fun bulk(action: BulkAction) {
        val targets = state.visible.filter { it.key in state.selected }
        stopSelecting()
        if (targets.isEmpty()) return
        val keys = targets.map { it.key }.toSet()
        when (action) {
            BulkAction.Delete -> drop(keys)
            BulkAction.Done -> if (state.allUnread) drop(keys) else targets.forEach { t -> update(t.key) { it.copy(isAnswered = true, isRead = true) } }
            BulkAction.Read -> if (state.allUnread || state.filter == InboxFilters.UNREAD) drop(keys) else targets.forEach { t -> update(t.key) { it.copy(isRead = true) } }
            BulkAction.Unread -> targets.forEach { t -> update(t.key) { it.copy(isRead = false) } }
        }
        viewModelScope.launch {
            val failures = if (action == BulkAction.Delete) {
                targets.groupBy { it.accountId to it.folder }.map { (where, rows) ->
                    var failed = 0
                    val error = attempt {
                        val result = api.deleteBulk(where.first, where.second, rows.map { it.uid })
                        failed = result.failed.size
                    }
                    if (error != null) rows.size else failed
                }.sum()
            } else {
                val gate = Semaphore(BULK_PARALLEL)
                coroutineScope {
                    targets.map { t ->
                        async {
                            gate.withPermit {
                                attempt {
                                    when (action) {
                                        BulkAction.Done -> {
                                            api.markAnswered(t.accountId, t.folder, t.uid)
                                            api.markRead(t.accountId, t.folder, t.uid)
                                        }
                                        BulkAction.Read -> api.markRead(t.accountId, t.folder, t.uid)
                                        else -> api.markUnread(t.accountId, t.folder, t.uid)
                                    }
                                }
                            }
                        }
                    }.awaitAll().count { it != null }
                }
            }
            if (failures > 0) {
                say.send("$failures of ${targets.size} could not be changed")
                reload()
            }
        }
    }

    // -- search -----------------------------------------------------------

    fun setQuery(query: String) {
        state = state.copy(query = query)
        searchJob?.cancel()
        if (query.trim().length < EmailApi.MIN_SEARCH) {
            state = state.copy(found = null, searchBusy = false)
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch(query.trim())
        }
    }

    fun setOptions(options: SearchOptions) {
        state = state.copy(options = options)
    }

    fun closeSearch() {
        searchJob?.cancel()
        state = state.copy(query = "", found = null, searchBusy = false, options = SearchOptions())
    }

    private fun search(q: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(q) }
    }

    /**
     * What the web does: the local index answers first and is painted, then
     * the full search (which also asks the mail provider) is merged in
     * behind it. In the INBOX the search covers the whole account; in any
     * other folder it stays there.
     */
    private suspend fun runSearch(q: String) {
        val s = state
        val (folder, scope) = if (s.folder == EmailApi.INBOX) EmailApi.INBOX to "all" else s.folder to "folder"
        state = state.copy(searchBusy = true, problem = null)
        try {
            coroutineScope {
                val full = async { api.search(s.accountId, folder, q, scope, localOnly = false) }
                val local = try {
                    api.search(s.accountId, folder, q, scope, localOnly = true)
                } catch (_: ApiException) {
                    null
                } catch (_: IOException) {
                    null
                }
                if (local != null && local.emails.isNotEmpty()) state = state.copy(found = local.emails, updatedAt = local.updatedAt ?: state.updatedAt)
                val page = full.await()
                state = state.copy(found = mergeSearch(local?.emails.orEmpty(), page.emails), updatedAt = page.updatedAt ?: state.updatedAt)
            }
        } catch (e: ApiException) {
            state = state.copy(problem = e.message, found = state.found ?: emptyList())
        } catch (e: IOException) {
            state = state.copy(problem = userMessage(e), found = state.found ?: emptyList())
        } finally {
            state = state.copy(searchBusy = false)
        }
    }

    private companion object {
        const val BULK_PARALLEL = 6
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
