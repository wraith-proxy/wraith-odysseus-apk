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
import com.thomaspeoples.wraithodysseus.chat.splitThinking
import com.thomaspeoples.wraithodysseus.chat.stripToolBlocks
import com.thomaspeoples.wraithodysseus.chat.visibleAssistantText
import com.thomaspeoples.wraithodysseus.chat.UploadedFile
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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

    /** Failures (and short confirmations) of message actions, for the
     * screen's snackbar. A channel, not state: each is shown once, and one
     * raised while the screen is away waits for it. */
    private val noticeChannel = Channel<String>(Channel.BUFFERED)
    val notices: Flow<String> = noticeChannel.receiveAsFlow()

    /** The rewrite streaming into the last reply, shown in its place until
     * it is saved; null when none is running. */
    var rewriting by mutableStateOf<String?>(null)
        private set

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
    /** An [attach] still waiting to hear whether a run is going. */
    private var probe: Job? = null

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
        if (running || probe?.isActive == true) return
        start(quiet = true) { api.resume(sessionId) }
    }

    /** One load at a time. The limit stops the page exactly where the shown
     * rows begin (the last page need not start on a multiple of 50). A
     * reload that lands mid-load moves where the shown rows begin, and the
     * page then no longer joins up with them: it is dropped, and the next
     * tap loads from the new start. Rows deleted elsewhere also move where
     * the page ends, so rows already shown are left out of it. */
    fun loadOlder() {
        if (!hasOlder || loadingOlder) return
        loadingOlder = true
        viewModelScope.launch {
            report {
                val until = offset
                val from = maxOf(until - ChatApi.PAGE, 0)
                val page = api.history(sessionId, from, until - from)
                if (offset != until) return@report
                val shown = messages.mapNotNull { it.dbId }.toSet()
                messages = page.messages.filter { it.dbId == null || it.dbId !in shown } + messages
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
        val bubble = HistoryMessage("user", text, null, null, sent)
        messages = messages + bubble
        draft = ""
        attachments = emptyList()
        // Refused before the stream opened (quota, model, a dropped
        // connection): the server saved nothing, so the message goes back
        // into the composer instead of vanishing with a reload.
        start(rejected = {
            messages = messages - bubble
            if (draft.isBlank()) draft = text
            attachments = sent + attachments
        }) { api.chatStream(request(text, sent.map { it.id }, null)) }
    }

    // -- message actions ---------------------------------------------------
    //
    // Everything below needs the message's database id (the server addresses
    // rows by it, never by position) and is refused while a run is going: an
    // action that cuts the history under a live run would corrupt it.

    /** The rows the web UI deletes with [m] (chat.js:7593 deleteMessage): a
     * user message takes the reply that follows it, a reply the message it
     * answers. [m] comes first. */
    fun deletionOf(m: HistoryMessage): List<HistoryMessage> {
        val i = messages.indexOfFirst { it.dbId != null && it.dbId == m.dbId }
        if (i < 0) return listOf(m)
        val other = if (m.role == "user") {
            messages.drop(i + 1).takeWhile { it.role != "user" }.firstOrNull { it.role == "assistant" }
        } else {
            messages.take(i).lastOrNull { it.role == "user" }
        }
        return listOfNotNull(m, other)
    }

    fun delete(m: HistoryMessage) {
        if (m.dbId == null) return
        val ids = deletionOf(m).mapNotNull { it.dbId }
        act {
            api.deleteMessages(sessionId, ids)
            messages = messages.filter { it.dbId !in ids }
            say("Message deleted")
        }
    }

    /** Edit saved. A user message is cut off and sent again as the edited
     * text (the reply it had no longer fits); a reply is edited in place. */
    fun edit(m: HistoryMessage, text: String) {
        val id = m.dbId ?: return
        if (running) return
        if (m.role == "user") {
            replay(id, text.trim(), m.attachments)
            return
        }
        if (text == visibleForEdit(m)) return
        act {
            api.editMessage(sessionId, id, text)
            val inlineThinking = splitThinking(m.raw).thinking
            val split = splitThinking(text)
            val at = messages.indexOfFirst { it.dbId == id }
            if (at >= 0) messages = messages.toMutableList().also { it[at] = m.copy(content = split.answer, raw = text) }
            // Reasoning is shown from the text too: let the server's copy decide.
            if (split.thinking != inlineThinking) reloadHistory()
            say("Message edited")
        }
    }

    /** What an assistant edit starts from: the stored text without tool
     * blocks, think tags kept, as the web's editor does (chat.js:7715). */
    fun visibleForEdit(m: HistoryMessage): String = if (m.role == "user") m.content else stripToolBlocks(m.raw)

    fun resend(m: HistoryMessage) {
        val id = m.dbId ?: return
        replay(id, m.content, m.attachments)
    }

    /** The user message the reply [m] answers, if it is loaded. */
    fun questionOf(m: HistoryMessage): HistoryMessage? {
        val i = messages.indexOfFirst { it.dbId != null && it.dbId == m.dbId }
        return if (i < 0) null else messages.take(i).lastOrNull { it.role == "user" && it.dbId != null }
    }

    fun regenerate(m: HistoryMessage) {
        val q = questionOf(m)
        if (q == null) {
            say("Load earlier messages first: the question is not on screen")
            return
        }
        resend(q)
    }

    /** Rewrite rewrites the server's last assistant row, so only that reply offers it. */
    fun isLastReply(m: HistoryMessage): Boolean =
        m.role == "assistant" && m.dbId != null && messages.lastOrNull { it.role == "assistant" }?.dbId == m.dbId

    fun rewrite(m: HistoryMessage, instruction: String) {
        val id = m.dbId ?: return
        if (!isLastReply(m)) return
        val original = visibleAssistantText(m.raw)
        if (original.isBlank()) {
            say("No text to rewrite")
            return
        }
        act {
            rewriting = ""
            try {
                val text = api.rewrite(sessionId, original, instruction) { rewriting = it }
                val at = messages.indexOfFirst { it.dbId == id }
                if (at >= 0) messages = messages.toMutableList().also { it[at] = m.copy(content = text, raw = text) }
                reloadHistory()
            } finally {
                rewriting = null
            }
        }
    }

    /** Copies the chat up to and including [m] into a new one and hands its
     * id to [onOpen]. */
    fun fork(m: HistoryMessage, onOpen: (String) -> Unit) {
        val id = m.dbId ?: return
        act {
            val keep = if (messages.lastOrNull()?.dbId == id) {
                // The last reply: keep everything, whatever hidden rows follow.
                Int.MAX_VALUE
            } else {
                val shownBefore = messages.takeWhile { it.dbId != id }.count { it.dbId != null }
                api.forkKeepCount(sessionId, id, offset + shownBefore)
            }
            if (keep == null) {
                say("Could not tell where to fork this chat; fork from its last reply instead")
                return@act
            }
            val forked = api.fork(sessionId, keep)
            say("Forked: ${forked.name}")
            onOpen(forked.id)
        }
    }

    /** Cut the chat off before the message [fromId], then send [text] (with
     * [files]) in its place. Used by edit, resend and regenerate, so the
     * toggles and model are the composer's. */
    private fun replay(fromId: String, text: String, files: List<UploadedFile>) {
        if (running || (text.isEmpty() && files.isEmpty())) return
        ask = null
        val bubble = HistoryMessage("user", text, null, null, files)
        // After the cut the server has lost the message for good, so if the
        // stream then fails to open the text goes back to the composer.
        start(rejected = {
            if (draft.isBlank()) draft = text
            viewModelScope.launch { reloadHistory() }
        }) {
            try {
                api.truncate(sessionId, fromId)
            } catch (e: ApiException) {
                notice(e)
                return@start null
            } catch (e: IOException) {
                notice(e)
                return@start null
            } catch (e: JSONException) {
                notice(e)
                return@start null
            }
            val at = messages.indexOfFirst { it.dbId == fromId }
            messages = (if (at >= 0) messages.subList(0, at) else messages) + bubble
            api.chatStream(request(text, files.map { it.id }, null))
        }
    }

    /** One non-streaming action at a time, the composer locked meanwhile. */
    private fun act(block: suspend () -> Unit) {
        if (running) return
        running = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiException) {
                notice(e)
            } catch (e: IOException) {
                notice(e)
            } catch (e: JSONException) {
                notice(e)
            } finally {
                running = false
            }
        }
    }

    private fun notice(e: Exception) = say(userMessage(e))

    private fun say(text: String) {
        noticeChannel.trySend(text)
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
        // Not sent (unlike an expired one): the gate comes back to retry.
        start(expiredOn409 = true, rejected = { if (ask == null && error != EXPIRED) ask = gate }) {
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
        f.stopped = stopped
        f.stopping = stopped
    }

    /** [read] gives the picked file's name, type and bytes, or null if it
     * cannot be read. It runs here rather than in the screen's scope, so a
     * rotation mid-read does not drop the file; it must not hold the Activity. */
    fun attachFile(read: suspend () -> Triple<String, String, ByteArray>?) = viewModelScope.launch {
        uploading = true
        report {
            val (name, mime, bytes) = read() ?: run {
                error = "Could not read that file"
                return@report
            }
            attachments = attachments + api.upload(name, mime, bytes)
        }
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

    /** [rejected], if given, runs instead of the history reload when [open]
     * itself fails, i.e. no stream was ever opened. [quiet] leaves the screen
     * as it is until [open] gives a stream (a resume probe usually gets
     * none); any other start cancels a quiet one still waiting. */
    private fun start(
        expiredOn409: Boolean = false,
        quiet: Boolean = false,
        rejected: (() -> Unit)? = null,
        open: suspend () -> RunStream?,
    ) {
        probe?.cancel()
        if (!quiet) {
            running = true
            error = null
        }
        val job = viewModelScope.launch {
            val f = RunFollower(api, sessionId)
            var mirror: Job? = null
            var opened = false
            val end = try {
                open().also { opened = true }?.let { stream ->
                    running = true
                    error = null
                    follower = f
                    mirror = launch { f.turn.collect { live = it } }
                    canStop = stream.runId != null
                    f.follow(stream)
                }
            } catch (e: ApiException) {
                RunEnd.Failed(if (expiredOn409 && e.status == 409) EXPIRED else e.message)
            } catch (_: IOException) {
                RunEnd.Failed("Could not reach the server")
            } catch (_: JSONException) {
                RunEnd.Failed(UNEXPECTED)
            }
            mirror?.cancel()
            val last = f.turn.value
            if (end != null) {
                // Not opened, nothing was asked: a question still pending stays.
                if (opened) ask = last.ask
                error = (end as? RunEnd.Failed)?.message ?: last.error
                if (!opened && rejected != null) rejected() else reloadHistory()
            }
            live = null
            running = false
            canStop = false
            follower = null
        }
        if (quiet) probe = job
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
        const val EXPIRED = "Approval expired"

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
