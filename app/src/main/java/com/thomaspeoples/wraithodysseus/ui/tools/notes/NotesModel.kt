package com.thomaspeoples.wraithodysseus.ui.tools.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONException

/** [notes] is the server's list for the current view, unsorted and unfiltered. */
data class NotesState(
    val notes: List<Note> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val problem: String? = null,
    val archivedView: Boolean = false,
    val label: String? = null,
    val query: String = "",
) {
    /** What the list shows, in the web's order. */
    fun visible(now: Instant): List<Note> = sortNotes(filterNotes(notes, label, query), archivedView, now)

    /** Reordering is only meaningful on the whole active list. */
    val canReorder: Boolean get() = !archivedView && label == null && query.isBlank()
}

class NotesViewModel(private val api: NotesApi) : ViewModel() {
    var state by mutableStateOf(NotesState())
        private set

    /** True while a save is in flight, so a second tap cannot make a second note. */
    var saving by mutableStateOf(false)
        private set

    private val say = Channel<String>(Channel.BUFFERED)

    /** Short notices (a failed action) for the screen to put in the snackbar. */
    val messages = say.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    /** Fetch the current view. A refresh keeps the old list on screen. */
    fun load(pull: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val archived = state.archivedView
            state = state.copy(loading = !pull && !state.loaded, refreshing = pull, problem = null)
            try {
                val notes = api.list(archived)
                state = state.copy(notes = notes, loading = false, refreshing = false, loaded = true)
            } catch (e: ApiException) {
                failed(e)
            } catch (e: IOException) {
                failed(e)
            }
        }
    }

    private fun failed(e: Exception) {
        // A list that is already showing is kept; only an empty screen shows the problem.
        state = state.copy(loading = false, refreshing = false, loaded = true, problem = if (state.notes.isEmpty()) userMessage(e) else null)
        if (state.notes.isNotEmpty()) say.trySend(userMessage(e))
    }

    fun showArchived(on: Boolean) {
        if (state.archivedView == on) return
        state = state.copy(archivedView = on, notes = emptyList(), loaded = false, label = null)
        load()
    }

    fun setLabel(label: String?) {
        state = state.copy(label = label)
    }

    fun setQuery(query: String) {
        state = state.copy(query = query)
    }

    private fun replace(note: Note) {
        state = state.copy(notes = state.notes.map { if (it.id == note.id) note else it })
    }

    /** Run a call, saying why when it fails. */
    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiException) {
                say.trySend(userMessage(e))
            } catch (e: IOException) {
                say.trySend(userMessage(e))
            } catch (e: JSONException) {
                say.trySend(userMessage(e))
            }
        }
    }

    fun toggleItem(note: Note, index: Int) = act {
        val items = api.toggleItem(note.id, index)
        state.notes.firstOrNull { it.id == note.id }?.let { replace(it.copy(items = items)) }
    }

    fun togglePin(note: Note) = act {
        val pinned = api.togglePin(note.id)
        state.notes.firstOrNull { it.id == note.id }?.let { replace(it.copy(pinned = pinned)) }
    }

    /** Archiving (or restoring) takes the note out of the view it is in. */
    fun toggleArchive(note: Note) = act {
        api.toggleArchive(note.id)
        state = state.copy(notes = state.notes.filter { it.id != note.id })
    }

    fun delete(note: Note) = act {
        api.delete(note.id)
        state = state.copy(notes = state.notes.filter { it.id != note.id })
    }

    /** Save a new note ([existing] null) or an edit; [onDone] runs once it is saved. */
    fun save(existing: Note?, draft: NoteDraft, onDone: () -> Unit) {
        if (saving) return
        saving = true
        act {
            try {
                persist(existing, draft)
                onDone()
            } finally {
                saving = false
            }
        }
    }

    private suspend fun persist(existing: Note?, draft: NoteDraft) {
        val pinned = existing?.pinned ?: false
        val withOrder = if (pinned) draft else draft.copy(sortOrder = topSortOrder(state.notes, existing?.id))
        val saved = if (existing == null) api.create(withOrder) else api.update(existing, withOrder)
        state = if (existing == null) state.copy(notes = state.notes + saved) else state.copy(notes = state.notes.map { if (it.id == saved.id) saved else it })
    }

    /** Move a note one place up (-1) or down (+1) in the whole active list. */
    fun move(note: Note, by: Int, now: Instant) {
        if (!state.canReorder) return
        val order = state.visible(now).map { it.id }
        commitOrder(order, moveId(order, note.id, by))
    }

    /** The order on screen while a card is being dragged, or null when none is. */
    var dragOrder by mutableStateOf<List<String>?>(null)
        private set

    private var dragFrom: List<String> = emptyList()
    private var dragNow: Instant = Instant.EPOCH

    /** A card's drag handle was grabbed. Only the whole active list can be reordered. */
    fun dragBegin(now: Instant) {
        if (!state.canReorder) return
        dragFrom = state.visible(now).map { it.id }
        dragNow = now
        dragOrder = dragFrom
    }

    /** The dragged card [id] has moved over [overId]: reorder live, within its block. */
    fun dragOver(id: String, overId: String) {
        val current = dragOrder ?: return
        val blocks = state.notes.associate { it.id to orderBlock(it, dragNow) }
        dragOrder = dragMove(current, { blocks[it] ?: 2 }, id, overId)
    }

    /** The card was dropped ([drop]) or the drag abandoned: one POST on a drop that changed the order. */
    fun dragEnd(drop: Boolean) {
        val moved = dragOrder ?: return
        dragOrder = null
        if (drop) commitOrder(dragFrom, moved)
    }

    /** Show [moved] at once and send it; on failure put [before] back and say why. */
    private fun commitOrder(before: List<String>, moved: List<String>) {
        if (moved == before) return
        val oldState = state
        val position = moved.withIndex().associate { it.value to it.index }
        state = state.copy(notes = state.notes.map { n -> position[n.id]?.let { n.copy(sortOrder = it) } ?: n })
        viewModelScope.launch {
            try {
                api.reorder(moved)
            } catch (e: ApiException) {
                state = oldState
                say.trySend(userMessage(e))
            } catch (e: IOException) {
                state = oldState
                say.trySend(userMessage(e))
            }
        }
    }
}
