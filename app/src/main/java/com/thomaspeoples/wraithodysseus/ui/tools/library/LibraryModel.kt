package com.thomaspeoples.wraithodysseus.ui.tools.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf

/** The document being edited. [text] and [title] are the edit buffers; [doc] is what the server holds. */
class Editing(val doc: LibraryDoc, text: String = doc.content, title: String = doc.title) {
    var text by mutableStateOf(text)
    var title by mutableStateOf(title)
    var preview by mutableStateOf(false)

    val dirty: Boolean get() = text != doc.content || title.trim().ifEmpty { "Untitled" } != doc.title
    val readOnly: Boolean get() = doc.isPdf
}

/**
 * The Library screen's state, so it survives rotation. The list is re-fetched
 * from the start after every change, since a save moves a document to the top
 * and an archive or delete removes a row.
 */
class LibraryViewModel(private val api: LibraryApi) : ViewModel() {
    var items by mutableStateOf<List<LibraryItem>?>(null); private set
    var total by mutableIntStateOf(0); private set
    var loadError by mutableStateOf<String?>(null); private set
    var refreshing by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    var search by mutableStateOf("")
    var archivedView by mutableStateOf(false); private set
    var editing by mutableStateOf<Editing?>(null); private set
    var opening by mutableStateOf<String?>(null); private set
    var saving by mutableStateOf(false); private set
    var busy by mutableStateOf(emptySet<String>()); private set

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Only the newest list request may write, so a slow older search cannot overwrite a newer one. */
    private var listJob: Job? = null

    init { refresh() }

    val hasMore: Boolean get() = (items?.size ?: 0) < total

    fun setArchived(value: Boolean) {
        if (archivedView == value) return
        archivedView = value
        items = null
        refresh()
    }

    fun refresh() {
        listJob?.cancel()
        refreshing = true
        loadingMore = false
        listJob = viewModelScope.launch {
            try {
                val page = api.list(search, archivedView)
                items = page.items
                total = page.total
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

    fun loadMore() {
        val current = items ?: return
        if (loadingMore || refreshing || !hasMore) return
        loadingMore = true
        listJob = viewModelScope.launch {
            try {
                val page = api.list(search, archivedView, offset = current.size)
                val seen = current.mapTo(HashSet()) { it.id }
                items = current + page.items.filter { it.id !in seen }
                total = page.total
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(userMessage(e))
            } finally {
                loadingMore = false
            }
        }
    }

    fun open(item: LibraryItem) {
        if (opening != null) return
        opening = item.id
        viewModelScope.launch {
            try {
                editing = Editing(api.get(item.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(userMessage(e))
            } finally {
                opening = null
            }
        }
    }

    fun close() {
        editing = null
        refresh()
    }

    /** Saves the content, then the title if it changed; the buffers stay as typed if either fails. */
    fun save() {
        val e = editing ?: return
        if (saving || e.readOnly || !e.dirty) return
        saving = true
        viewModelScope.launch {
            try {
                var doc = e.doc
                if (e.text != doc.content) doc = api.save(doc.id, e.text)
                val title = e.title.trim().ifEmpty { "Untitled" }
                if (title != doc.title) doc = api.rename(doc.id, title)
                // The server may change the content (an email draft is merged), so show what it holds.
                val fresh = Editing(doc)
                fresh.preview = e.preview
                if (editing === e) editing = fresh
                messages.tryEmit("Saved")
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                messages.tryEmit(userMessage(ex))
            } finally {
                saving = false
            }
        }
    }

    fun create(title: String) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                editing = Editing(api.create(title))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(userMessage(e))
            } finally {
                saving = false
            }
        }
    }

    fun archive(item: LibraryItem) = act(item, if (archivedView) "Restored" else "Archived") { api.archive(item.id, !archivedView) }

    fun delete(item: LibraryItem) = act(item, "Deleted") { api.delete(item.id) }

    private fun act(item: LibraryItem, done: String, call: suspend () -> Unit) {
        if (item.id in busy) return
        busy = busy + item.id
        viewModelScope.launch {
            try {
                call()
                messages.tryEmit("$done: ${item.title}")
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.tryEmit(userMessage(e))
            } finally {
                busy = busy - item.id
            }
        }
    }
}
