package com.thomaspeoples.wraithodysseus.ui.tools.memory

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONException

/** [memories] is the server's list, unfiltered. A toggle is null until its pref has been read. */
data class MemoryState(
    val memories: List<Memory> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val problem: String? = null,
    val query: String = "",
    val category: String? = null,
    val memoryEnabled: Boolean? = null,
    val autoMemory: Boolean? = null,
) {
    val visible: List<Memory> get() = filterMemories(memories, query, category)
}

class MemoryViewModel(private val api: MemoryApi) : ViewModel() {
    var state by mutableStateOf(MemoryState())
        private set

    /** True while an add or edit is in flight, so a second tap cannot make a second memory. */
    var saving by mutableStateOf(false)
        private set

    private val say = Channel<String>(Channel.BUFFERED)

    /** Short notices for the screen to put in the snackbar. */
    val messages = say.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        load()
        loadToggles()
    }

    fun load(pull: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            state = state.copy(loading = !pull && !state.loaded, refreshing = pull, problem = null)
            try {
                state = state.copy(memories = api.list(), loading = false, refreshing = false, loaded = true)
            } catch (e: ApiException) {
                failed(e)
            } catch (e: IOException) {
                failed(e)
            }
        }
    }

    private fun failed(e: Exception) {
        // A list already showing is kept; only an empty screen shows the problem.
        state = state.copy(loading = false, refreshing = false, loaded = true, problem = if (state.memories.isEmpty()) userMessage(e) else null)
        if (state.memories.isNotEmpty()) say.trySend(userMessage(e))
    }

    private fun loadToggles() {
        viewModelScope.launch {
            try {
                state = state.copy(memoryEnabled = api.pref(PREF_MEMORY_ENABLED))
                state = state.copy(autoMemory = api.pref(PREF_AUTO_MEMORY))
            } catch (_: ApiException) {
                // The switches just stay hidden; the list is the point of the screen.
            } catch (_: IOException) {
            }
        }
    }

    fun setQuery(query: String) {
        state = state.copy(query = query)
    }

    fun setCategory(category: String?) {
        state = state.copy(category = category)
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

    /** Switch a pref optimistically, putting it back if the server refuses. */
    fun setToggle(key: String, on: Boolean) = act {
        val before = state
        state = if (key == PREF_MEMORY_ENABLED) state.copy(memoryEnabled = on) else state.copy(autoMemory = on)
        try {
            api.setPref(key, on)
        } catch (e: Exception) {
            state = if (key == PREF_MEMORY_ENABLED) state.copy(memoryEnabled = before.memoryEnabled) else state.copy(autoMemory = before.autoMemory)
            throw e
        }
    }

    fun togglePin(m: Memory) = act {
        api.pin(m.id, !m.pinned)
        state = state.copy(memories = state.memories.map { if (it.id == m.id) it.copy(pinned = !m.pinned) else it })
    }

    fun delete(m: Memory) = act {
        api.delete(m.id)
        state = state.copy(memories = state.memories.filter { it.id != m.id })
    }

    /** Save a new memory ([existing] null) or an edit; [onDone] runs once it is saved. */
    fun save(existing: Memory?, text: String, category: String, onDone: () -> Unit) {
        val clean = text.trim()
        if (saving || clean.isEmpty()) return
        saving = true
        act {
            try {
                if (existing == null) {
                    val added = api.add(clean, category)
                    if (!added) say.trySend("Memory already exists")
                    state = state.copy(memories = api.list())
                } else {
                    api.update(existing.id, clean, category)
                    state = state.copy(memories = api.list())
                }
                onDone()
            } finally {
                saving = false
            }
        }
    }
}
