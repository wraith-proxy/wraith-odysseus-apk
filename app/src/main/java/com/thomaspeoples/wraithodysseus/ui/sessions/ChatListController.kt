package com.thomaspeoples.wraithodysseus.ui.sessions

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thomaspeoples.wraithodysseus.chat.ChatSession
import com.thomaspeoples.wraithodysseus.chat.SessionsApi
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONException

/**
 * The drawer's chat list: what is open (menus, dialogs, select mode), the
 * choices that are kept (sort, collapsed groups) and every action on a chat.
 * The list is fetched again after each change, whatever the outcome, so it
 * never shows what the server did not do. Failures are said through [say]
 * with the server's own words.
 */
class ChatListController(
    private val api: SessionsApi,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val say: suspend (String) -> Unit,
    /** Fetches the chats again; must not throw. */
    private val reload: suspend () -> Unit,
    /** Puts the text on the clipboard; false when the system refuses it (a huge chat overflows Binder). */
    private val copy: (String) -> Boolean,
    /** Chats that no longer exist or are archived, so the open one can be left. */
    private val onRemoved: (Set<String>) -> Unit,
) {
    var sort by mutableStateOf(SortMode.of(prefs.getString(SORT, null)))
        private set
    var collapsed by mutableStateOf(prefs.getStringSet(COLLAPSED, null).orEmpty().toSet())
        private set
    var expanded by mutableStateOf(emptySet<String>())
        private set

    var sortMenu by mutableStateOf(false)
    var menuFor by mutableStateOf<ChatSession?>(null)
    var renaming by mutableStateOf<ChatSession?>(null)
    var moving by mutableStateOf<ChatSession?>(null)
    var newFolderFor by mutableStateOf<ChatSession?>(null)
    var deleting by mutableStateOf<ChatSession?>(null)

    var selecting by mutableStateOf(false)
        private set
    var selected by mutableStateOf(emptySet<String>())
        private set
    var confirmBulkDelete by mutableStateOf<BulkPlan?>(null)

    fun sortBy(mode: SortMode) {
        sort = mode
        prefs.edit().putString(SORT, mode.key).apply()
    }

    fun toggleGroup(key: String) {
        collapsed = if (key in collapsed) collapsed - key else collapsed + key
        prefs.edit().putStringSet(COLLAPSED, collapsed).apply()
    }

    fun showAll(group: String) {
        expanded = expanded + group
    }

    // -- select mode ---------------------------------------------------------

    fun startSelect(first: String? = null) {
        selecting = true
        selected = setOfNotNull(first)
    }

    fun endSelect() {
        selecting = false
        selected = emptySet()
    }

    fun toggle(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    /** All the rows shown, or none when they are all picked already. */
    fun toggleAll(shown: List<String>) {
        selected = if (selected.containsAll(shown)) emptySet() else shown.toSet()
    }

    fun archiveSelected() {
        val ids = selected.toList()
        if (ids.isEmpty()) return
        endSelect()
        scope.launch {
            val r = bulkRun(ids) { api.archive(it) }
            reload()
            if (r.done.isNotEmpty()) onRemoved(r.done.toSet())
            say(bulkSummary("archived", r, 0))
        }
    }

    /** Asks first, naming how many favourites will be left alone. */
    fun askDeleteSelected(all: List<ChatSession>) {
        val plan = bulkDeletePlan(selected, all)
        if (plan.ids.isEmpty()) {
            scope.launch { say(SessionsApi.UNFAVORITE_FIRST) }
        } else {
            confirmBulkDelete = plan
        }
    }

    fun deleteSelected(plan: BulkPlan) {
        endSelect()
        scope.launch {
            val r = bulkRun(plan.ids) { api.delete(it) }
            reload()
            if (r.done.isNotEmpty()) onRemoved(r.done.toSet())
            say(bulkSummary("deleted", r, plan.skippedStarred))
        }
    }

    // -- one chat --------------------------------------------------------------

    /** Runs [op]; its result, or the failure, is said once the list is fresh. */
    private fun act(onOk: () -> Unit = {}, op: suspend () -> String?) {
        scope.launch {
            var failed = false
            val message = try {
                op()
            } catch (e: ApiException) {
                failed = true
                userMessage(e)
            } catch (e: IOException) {
                failed = true
                userMessage(e)
            } catch (e: JSONException) {
                failed = true
                userMessage(e)
            }
            reload()
            if (!failed) onOk()
            message?.let { say(it) }
        }
    }

    fun rename(s: ChatSession, name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || clean == s.name) return
        act { api.rename(s.id, clean).let { "Renamed" } }
    }

    fun toggleFavorite(s: ChatSession) = act { api.setFavorite(s.id, !s.isImportant).let { if (s.isImportant) "Unfavorited" else "Favorited" } }

    fun move(s: ChatSession, folder: String) {
        val name = folder.trim()
        act {
            api.moveToFolder(s.id, name)
            // As the web does, show the folder so the move is seen.
            if (name.isNotEmpty()) sortBy(SortMode.Folder)
            if (name.isEmpty()) "Removed from folder" else "Moved to $name"
        }
    }

    fun archive(s: ChatSession) = act(onOk = { onRemoved(setOf(s.id)) }) {
        api.archive(s.id)
        "Chat archived"
    }

    /** A favourite is refused before asking, as the web does; the server would answer 403. */
    fun askDelete(s: ChatSession) {
        if (s.isImportant) scope.launch { say(SessionsApi.UNFAVORITE_FIRST) } else deleting = s
    }

    fun delete(s: ChatSession) = act(onOk = { onRemoved(setOf(s.id)) }) {
        api.delete(s.id)
        "Chat deleted"
    }

    fun copyChat(s: ChatSession) = act {
        val text = api.chatText(s.id)
        if (text == null) {
            "No messages to copy"
        } else {
            if (copy(text)) "Chat copied to clipboard" else "Chat too long to copy"
        }
    }

    private companion object {
        const val SORT = "sort"
        const val COLLAPSED = "collapsed"
    }
}
