package com.thomaspeoples.wraithodysseus.ui.tools.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** The three status filters the web's list has; "completed" one-offs sit under All. */
enum class TaskFilter(val label: String) { All("All"), Active("Active"), Paused("Paused") }

/** The run history shown for one task. [runs] is null while loading. */
data class History(val task: Task, val runs: List<TaskRun>?, val error: String? = null)

/**
 * All of the Tasks screen's state, so it survives rotation. Every server
 * change is followed by a re-list rather than a local edit: pausing or
 * resuming changes next_run, and a run changes run_count and last_run.
 */
class TasksViewModel(private val api: TasksApi) : ViewModel() {
    var tasks by mutableStateOf<List<Task>?>(null); private set
    var loadError by mutableStateOf<String?>(null); private set
    var refreshing by mutableStateOf(false); private set
    var filter by mutableStateOf(TaskFilter.All)
    var history by mutableStateOf<History?>(null); private set

    /** Ids with a request in flight, so a double tap cannot send it twice. */
    var busy by mutableStateOf(emptySet<String>()); private set

    var parsing by mutableStateOf(false); private set
    var draft by mutableStateOf<TaskDraft?>(null); private set
    var creating by mutableStateOf(false); private set

    /** One-shot snackbar texts. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    init { refresh() }

    val visible: List<Task>
        get() = tasks.orEmpty().filter {
            when (filter) {
                TaskFilter.All -> true
                TaskFilter.Active -> it.status == "active"
                TaskFilter.Paused -> it.status == "paused"
            }
        }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                tasks = api.list()
                loadError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = userMessage(e)
            } finally {
                refreshing = false
            }
        }
    }

    fun pause(t: Task) = act(t, "Paused") { api.pause(t.id) }

    fun resume(t: Task) = act(t, "Resumed") { api.resume(t.id) }

    fun runNow(t: Task) = act(t, "Started") { api.runNow(t.id) }

    fun delete(t: Task) = act(t, "Deleted") { api.delete(t.id) }

    private fun act(t: Task, done: String, call: suspend () -> Unit) {
        if (t.id in busy) return
        busy = busy + t.id
        viewModelScope.launch {
            try {
                call()
                messages.tryEmit("$done: ${t.name}")
                tasks = api.list()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(userMessage(e))
            } finally {
                busy = busy - t.id
            }
        }
    }

    fun openHistory(t: Task) {
        history = History(t, null)
        viewModelScope.launch {
            try {
                val runs = api.runs(t.id)
                history = history?.takeIf { it.task.id == t.id }?.copy(runs = runs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                history = history?.takeIf { it.task.id == t.id }?.copy(runs = emptyList(), error = userMessage(e))
            }
        }
    }

    fun closeHistory() { history = null }

    /** Sends the description for parsing; the result is [draft], to be confirmed. */
    fun parse(description: String) {
        if (parsing || description.isBlank()) return
        parsing = true
        viewModelScope.launch {
            try {
                draft = api.parse(description.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(userMessage(e))
            } finally {
                parsing = false
            }
        }
    }

    fun discardDraft() { draft = null }

    /** [onCreated] lets the screen close its dialog: the draft going away alone would bring the first step back. */
    fun confirmDraft(onCreated: () -> Unit = {}) {
        val d = draft ?: return
        if (creating) return
        creating = true
        viewModelScope.launch {
            try {
                val created = api.create(d)
                draft = null
                onCreated()
                messages.tryEmit("Created: ${created.name}")
                tasks = api.list()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(userMessage(e))
            } finally {
                creating = false
            }
        }
    }
}
