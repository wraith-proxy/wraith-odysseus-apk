package com.thomaspeoples.wraithodysseus.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import com.thomaspeoples.wraithodysseus.chat.AskOption
import com.thomaspeoples.wraithodysseus.chat.AskUser
import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.chat.ChatRequest
import com.thomaspeoples.wraithodysseus.chat.HistoryMessage
import com.thomaspeoples.wraithodysseus.chat.ModelChoice
import com.thomaspeoples.wraithodysseus.chat.RunEnd
import com.thomaspeoples.wraithodysseus.chat.RunFollower
import com.thomaspeoples.wraithodysseus.chat.RunStream
import com.thomaspeoples.wraithodysseus.chat.Toggles
import com.thomaspeoples.wraithodysseus.chat.ToolApproval
import com.thomaspeoples.wraithodysseus.chat.TurnState
import com.thomaspeoples.wraithodysseus.chat.UploadedFile
import com.thomaspeoples.wraithodysseus.net.ApiException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONException

/**
 * One open chat. Lives across rotation; leaving the screen only detaches --
 * the run carries on server-side and is picked up again by [attach]. What
 * the user has typed, picked and uploaded is in [saved], so it also survives
 * the process being killed behind the document picker.
 *
 * [scope] is a constructor argument only so a JVM test can drive the
 * coroutines without a main looper.
 */
@OptIn(SavedStateHandleSaveableApi::class)
class ChatViewModel(
    private val api: ChatApi,
    private val sessionId: String,
    saved: SavedStateHandle,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {

    var messages by mutableStateOf<List<HistoryMessage>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var hasOlder by mutableStateOf(false)
        private set
    private var offset = 0
    private var loadingOlder = false

    /** The turn being streamed, or null when no run is being followed. */
    var live by mutableStateOf<TurnState?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    /** False until the run id is known: a stop before then has nothing to name. */
    var canStop by mutableStateOf(false)
        private set
    /** A question (or approval gate) the last run ended on. */
    var ask by mutableStateOf<AskUser?>(null)
        private set
    var error by mutableStateOf<String?>(null)

    var models by mutableStateOf<List<ModelChoice>>(emptyList())
        private set
    /** The session's model as history reports it, until the user picks one. */
    var sessionModel by mutableStateOf<String?>(null)
        private set
    var selected by saved.saveable("selected", stateSaver = ModelSaver) { mutableStateOf<ModelChoice?>(null) }

    var toggles by saved.saveable("toggles", stateSaver = TogglesSaver) { mutableStateOf(Toggles()) }
    var draft by saved.saveable("draft", stateSaver = autoSaver()) { mutableStateOf("") }
    var attachments by saved.saveable("attachments", stateSaver = FilesSaver) { mutableStateOf<List<UploadedFile>>(emptyList()) }
        private set
    var uploading by mutableStateOf(false)
        private set

    private var follower: RunFollower? = null

    init {
        viewModelScope.launch {
            reloadHistory()
            loading = false
        }
        viewModelScope.launch { models = runCatching { api.models() }.getOrDefault(emptyList()) }
    }

    /** On (re)entering the screen: pick up a run that is still going, e.g.
     * one started before the screen was left or from the web UI. */
    fun attach() {
        if (running) return
        start { api.resume(sessionId) }
    }

    /** One load at a time. The limit stops the page exactly where the shown
     * rows begin (the last page need not start on a multiple of 50), and
     * anything already shown is dropped anyway: a reload that lands mid-load
     * moves the offset, and a repeated dbId would crash the list. */
    fun loadOlder() {
        if (!hasOlder || loadingOlder) return
        loadingOlder = true
        viewModelScope.launch {
            report {
                val from = maxOf(offset - ChatApi.PAGE, 0)
                val page = api.history(sessionId, from, offset - from)
                val shown = messages.mapNotNullTo(HashSet()) { it.dbId }
                messages = page.messages.filter { it.dbId !in shown } + messages
                offset = page.offset
                hasOlder = page.hasMoreBefore
            }
            loadingOlder = false
        }
    }

    fun send() {
        val text = draft.trim()
        if (running || (text.isEmpty() && attachments.isEmpty())) return
        val sent = attachments
        // Sending a normal message retires any pending approval server-side.
        ask = null
        messages = messages + HistoryMessage("user", text, null, null, sent)
        draft = ""
        attachments = emptyList()
        start { api.chatStream(request(text, sent.map { it.id }, null)) }
    }

    /** A plain ask_user answer: the picked labels, sent as a message. */
    fun answer(picked: List<AskOption>) {
        if (running || picked.isEmpty()) return
        draft = picked.joinToString(", ") { it.label }
        send()
    }

    fun decide(option: AskOption) {
        val gate = ask ?: return
        val approvalId = gate.approvalId ?: return
        val value = option.value ?: return
        if (running) return
        ask = null
        start(expiredOn409 = true) {
            api.chatStream(request("", emptyList(), ToolApproval(approvalId, value)))
        }
    }

    /** Marked on the follower before asking, because the stream can end
     * before stop's own answer arrives; unmarked if the server refused. */
    fun stop() = viewModelScope.launch {
        val f = follower ?: return@launch
        val runId = f.runId ?: return@launch
        f.stopping = true
        var stopped = false
        report { stopped = api.stop(sessionId, runId) }
        f.stopping = stopped
    }

    fun attachFile(name: String, mime: String, bytes: ByteArray) = viewModelScope.launch {
        uploading = true
        report { attachments = attachments + api.upload(name, mime, bytes) }
        uploading = false
    }

    fun removeAttachment(file: UploadedFile) {
        attachments = attachments - file
    }

    suspend fun thumbnail(fileId: String): ByteArray = api.thumbnail(fileId)

    private fun request(message: String, ids: List<String>, approval: ToolApproval?) = ChatRequest(
        sessionId = sessionId,
        message = message,
        toggles = toggles,
        selected = selected,
        attachmentIds = ids,
        approval = approval,
    )

    private fun start(expiredOn409: Boolean = false, open: suspend () -> RunStream?) {
        running = true
        error = null
        viewModelScope.launch {
            val f = RunFollower(api, sessionId).also { follower = it }
            val mirror = launch { f.turn.collect { live = it } }
            val end = try {
                open()?.let { stream ->
                    canStop = stream.runId != null
                    f.follow(stream)
                }
            } catch (e: ApiException) {
                RunEnd.Failed(if (expiredOn409 && e.status == 409) "Approval expired" else e.message)
            } catch (_: IOException) {
                RunEnd.Failed("Could not reach the server")
            } catch (_: JSONException) {
                RunEnd.Failed(UNEXPECTED)
            }
            mirror.cancel()
            val last = f.turn.value
            if (end != null) {
                ask = last.ask
                error = (end as? RunEnd.Failed)?.message ?: last.error
                reloadHistory()
            }
            live = null
            running = false
            canStop = false
            follower = null
        }
    }

    /** Back to the last page: the finished reply is saved there. */
    private suspend fun reloadHistory() = report {
        val page = api.history(sessionId, null)
        messages = page.messages
        offset = page.offset
        hasOlder = page.hasMoreBefore
        sessionModel = page.model
    }

    private suspend fun report(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiException) {
            error = e.message
        } catch (_: IOException) {
            error = "Could not reach the server"
        } catch (_: JSONException) {
            // A 2xx that is not the JSON expected, e.g. a proxy's HTML page.
            error = UNEXPECTED
        }
    }

    internal companion object {
        const val UNEXPECTED = "Unexpected response from the server"

        // Flat lists of strings and booleans: all a saved-state Bundle holds.
        val TogglesSaver = listSaver<Toggles, Boolean>(
            save = { listOf(it.agent, it.web, it.terminal, it.research) },
            restore = { Toggles(it[0], it[1], it[2], it[3]) },
        )
        val ModelSaver = listSaver<ModelChoice?, String>(
            save = { m -> m?.let { listOf(it.endpointId, it.endpointName, it.model, it.display) }.orEmpty() },
            restore = { if (it.size == 4) ModelChoice(it[0], it[1], it[2], it[3]) else null },
        )
        val FilesSaver = listSaver<List<UploadedFile>, String>(
            save = { files -> files.flatMap { listOf(it.id, it.name, it.mime) } },
            restore = { flat -> flat.chunked(3).map { UploadedFile(it[0], it[1], it[2]) } },
        )
    }
}
