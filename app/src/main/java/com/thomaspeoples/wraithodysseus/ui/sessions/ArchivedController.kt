package com.thomaspeoples.wraithodysseus.ui.sessions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thomaspeoples.wraithodysseus.chat.ArchiveSort
import com.thomaspeoples.wraithodysseus.chat.ArchivedSession
import com.thomaspeoples.wraithodysseus.chat.SessionsApi
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONException
import androidx.compose.runtime.mutableIntStateOf

/**
 * The archived-chats view (the web's Archive tab): a searchable, sortable
 * list the server pages, with Restore and Delete on each row. The list is
 * the server's alone -- /api/sessions never includes archived chats -- so a
 * change is applied to the rows held here only once the server has done it.
 * Failures are said through [say] in the server's own words.
 */
class ArchivedController(
    private val api: SessionsApi,
    private val scope: CoroutineScope,
    private val say: suspend (String) -> Unit,
) {
    var rows by mutableStateOf<List<ArchivedSession>>(emptyList())
        private set
    var total by mutableIntStateOf(0)
        private set
    /** True until the first page (or the first page of a new search) arrives. */
    var loading by mutableStateOf(true)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** The next page failed to load: the list shows Retry instead of asking again by itself. */
    var moreFailed by mutableStateOf(false)
        private set
    var search by mutableStateOf("")
    var sort by mutableStateOf(ArchiveSort.Recent)

    var menuFor by mutableStateOf<ArchivedSession?>(null)
    var deleting by mutableStateOf<ArchivedSession?>(null)
    var sortMenu by mutableStateOf(false)

    private var job: Job? = null

    val hasMore get() = rows.size < total

    /** Fetches the first page of the current search and sort; a load still going is dropped. */
    fun refresh() {
        job?.cancel()
        loading = true
        error = null
        moreFailed = false
        job = scope.launch {
            try {
                val page = api.archived(search, 0, sort = sort)
                rows = page.sessions
                total = page.total
            } catch (e: ApiException) {
                error = userMessage(e)
            } catch (e: IOException) {
                error = userMessage(e)
            } catch (e: JSONException) {
                error = userMessage(e)
            }
            loading = false
        }
    }

    /** The next page, from as many rows as are held. */
    fun loadMore() {
        if (loading || loadingMore || !hasMore) return
        loadingMore = true
        moreFailed = false
        val query = search
        val order = sort
        scope.launch {
            try {
                val page = api.archived(query, rows.size, sort = order)
                // A search or sort changed meanwhile: this page belongs to the old one.
                if (query == search && order == sort) {
                    val have = rows.map { it.id }.toSet()
                    rows = rows + page.sessions.filter { it.id !in have }
                    total = page.total
                }
            } catch (e: ApiException) {
                moreFailed = true
                say(userMessage(e))
            } catch (e: IOException) {
                moreFailed = true
                say(userMessage(e))
            } catch (e: JSONException) {
                moreFailed = true
                say(userMessage(e))
            }
            loadingMore = false
        }
    }

    fun restore(s: ArchivedSession) = change(s, "Chat restored") { api.unarchive(s.id) }

    /** A favorite is refused before asking, as the web does; the server would answer 403. */
    fun askDelete(s: ArchivedSession) {
        if (s.isImportant) scope.launch { say(SessionsApi.UNFAVORITE_FIRST) } else deleting = s
    }

    fun delete(s: ArchivedSession) = change(s, "Chat deleted") { api.delete(s.id) }

    private fun change(s: ArchivedSession, done: String, op: suspend () -> Unit) {
        scope.launch {
            try {
                op()
                rows = rows.filter { it.id != s.id }
                total = maxOf(total - 1, rows.size)
                say(done)
            } catch (e: ApiException) {
                say(userMessage(e))
            } catch (e: IOException) {
                say(userMessage(e))
            } catch (e: JSONException) {
                say(userMessage(e))
            }
        }
    }
}
