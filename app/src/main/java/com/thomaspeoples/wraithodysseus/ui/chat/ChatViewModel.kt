package com.thomaspeoples.wraithodysseus.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch

/**
 * One open chat. Lives across rotation; leaving the screen only detaches --
 * the run carries on server-side and is picked up again by [attach].
 */
class ChatViewModel(private val api: ChatApi, private val sessionId: String) : ViewModel() {

    var messages by mutableStateOf<List<HistoryMessage>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var hasOlder by mutableStateOf(false)
        private set
    private var offset = 0

    /** The turn being streamed, or null when no run is being followed. */
    var live by mutableStateOf<TurnState?>(null)
        private set
    var running by mutableStateOf(false)
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
    var selected by mutableStateOf<ModelChoice?>(null)

    var toggles by mutableStateOf(Toggles())
    var draft by mutableStateOf("")
    var attachments by mutableStateOf<List<UploadedFile>>(emptyList())
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

    fun loadOlder() = viewModelScope.launch {
        if (!hasOlder) return@launch
        report {
            val page = api.history(sessionId, maxOf(offset - ChatApi.PAGE, 0))
            messages = page.messages + messages
            offset = page.offset
            hasOlder = page.hasMoreBefore
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

    fun stop() = viewModelScope.launch {
        val runId = follower?.runId ?: return@launch
        report { api.stop(sessionId, runId) }
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
                open()?.let { f.follow(it) }
            } catch (e: ApiException) {
                RunEnd.Failed(if (expiredOn409 && e.status == 409) "Approval expired" else e.message)
            } catch (_: IOException) {
                RunEnd.Failed("Could not reach the server")
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
        }
    }
}
