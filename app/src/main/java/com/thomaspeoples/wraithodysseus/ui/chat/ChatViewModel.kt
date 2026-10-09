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
import com.thomaspeoples.wraithodysseus.chat.ResearchJob
import com.thomaspeoples.wraithodysseus.chat.RunStream
import com.thomaspeoples.wraithodysseus.chat.SpeechOutput
import com.thomaspeoples.wraithodysseus.chat.Variant
import com.thomaspeoples.wraithodysseus.chat.keptVariants
import com.thomaspeoples.wraithodysseus.chat.showVariant
import com.thomaspeoples.wraithodysseus.chat.variantsMeta
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
import kotlinx.coroutines.delay
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
 * coroutines without a main looper. [speech] plays read-aloud audio; without
 * one the Read aloud action is never offered. [jobPollMs] is how often a
 * running background job is asked about (the web polls every 3 s).
 */
@OptIn(SavedStateHandleSaveableApi::class)
class ChatViewModel(
    private val api: ChatApi,
    private val sessionId: String,
    saved: SavedStateHandle,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val speech: SpeechOutput? = null,
    private val jobPollMs: Long = 3_000,
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

    /** Background research jobs of this chat, as last polled. */
    var jobs by mutableStateOf<List<ResearchJob>>(emptyList())
        private set

    /** The server can speak (asked once per screen). */
    var ttsEnabled by mutableStateOf(false)
        private set
    /** The reply being fetched or read aloud, by [speechKey]. */
    var speaking by mutableStateOf<String?>(null)
        private set
    private var speechJob: Job? = null

    private var watch: Job? = null
    private var resumedOnce = false

    private var follower: RunFollower? = null
    /** An [attach] still waiting to hear whether a run is going. */
    private var probe: Job? = null

    init {
        viewModelScope.launch {
            reloadHistory()
            loading = false
        }
        viewModelScope.launch { models = runCatching { api.models() }.getOrDefault(emptyList()) }
        if (speech != null) viewModelScope.launch { ttsEnabled = runCatching { api.ttsEnabled() }.getOrDefault(false) }
    }

    override fun onCleared() {
        speech?.stop()
        super.onCleared()
    }

    /**
     * The screen came to the front. Picks up a run still going, brings in
     * replies delivered while it was away (a finished background research
     * lands in the chat with no stream), and watches the chat's background
     * jobs until none is left. The first call skips the reload: opening the
     * chat has just loaded it.
     */
    fun onResume() {
        attach()
        if (resumedOnce && !running && !loading) viewModelScope.launch { refreshTail() }
        resumedOnce = true
        watchJobs()
    }

    /** The screen was left: stop asking about jobs. They carry on server-side. */
    fun onPause() {
        watch?.cancel()
        watch = null
    }

    /**
     * Polls GET /api/research/chat-jobs every [jobPollMs] while a job is
     * running or its report is being turned into a chat reply, and brings
     * the new reply in once it leaves that state (backgroundToolJobs.js:106).
     * A first answer that fails ends the watch quietly; one that fails after
     * a job was seen carries on.
     */
    private fun watchJobs() {
        if (watch?.isActive == true) return
        watch = viewModelScope.launch {
            var before = emptySet<String>()
            while (true) {
                val list = try {
                    api.researchJobs(sessionId)
                } catch (e: ApiException) {
                    null
                } catch (_: IOException) {
                    null
                } catch (_: JSONException) {
                    null
                }
                if (list == null) {
                    if (before.isEmpty()) break
                    delay(jobPollMs)
                    continue
                }
                jobs = list.filter { it.tool == "research" && it.status != "discarded" }
                val active = jobs.filter { it.active }.map { it.id }.toSet()
                // A run's own end reloads, so a stream going now needs no help.
                if ((before - active).isNotEmpty() && !running) refreshTail()
                before = active
                if (active.isEmpty()) break
                delay(jobPollMs)
            }
        }
    }

    /**
     * The newest page, joined onto the older pages already shown: a reply
     * delivered meanwhile is added without dropping what was scrolled back
     * to. When nothing shown is in the new page it replaces everything.
     */
    private suspend fun refreshTail() = report {
        val page = api.history(sessionId, null)
        val ids = page.messages.mapNotNull { it.dbId }.toSet()
        val at = messages.indexOfFirst { it.dbId != null && it.dbId in ids }
        if (at < 0) {
            messages = page.messages
            offset = page.offset
            hasOlder = page.hasMoreBefore
        } else {
            messages = messages.take(at) + page.messages
        }
        sessionModel = page.model
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
            if (at >= 0) messages = messages.toMutableList().also { it[at] = m.copy(content = split.answer, raw = text, details = m.details.copy(edited = true, variants = emptyList())) }
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

    /**
     * Regenerate keeps the old reply as a version, as the web does (chat.js:7143):
     * the new reply is sent as usual and, once it is saved, the versions go
     * to it with update-last-meta, the new one on show.
     */
    fun regenerate(m: HistoryMessage) {
        val q = questionOf(m)
        if (q == null) {
            say("Load earlier messages first: the question is not on screen")
            return
        }
        val id = q.dbId ?: return
        replay(id, q.content, q.attachments, keptVariants(m.details.variants, m.raw), m.dbId)
    }

    /** Saves [kept] plus the reply that just landed as the newest version. */
    private suspend fun saveVariants(kept: List<Variant>, oldId: String?, end: RunEnd) {
        if (end != RunEnd.Done && end != RunEnd.Stopped) return
        val last = messages.lastOrNull() ?: return
        if (last.role != "assistant" || last.dbId == null || last.dbId == oldId) return
        val all = kept + Variant(last.raw, "regen")
        try {
            api.updateLastMeta(sessionId, variantsMeta(all, all.lastIndex))
        } catch (e: ApiException) {
            say("The earlier reply could not be kept: ${e.message}")
            return
        } catch (_: IOException) {
            say("The earlier reply could not be kept")
            return
        }
        val at = messages.indexOfFirst { it.dbId == last.dbId }
        if (at >= 0) messages = messages.toMutableList().also { it[at] = last.copy(details = last.details.copy(variants = all, variantIndex = all.lastIndex)) }
    }

    /**
     * Shows version [index] of the last reply (chat.js:7313): the choice is
     * saved with update-last-meta so the web shows the same one. The change
     * is made on screen first; a refused save puts it back.
     */
    fun switchVariant(m: HistoryMessage, index: Int) {
        val id = m.dbId ?: return
        if (running || !isLastReply(m) || index !in m.details.variants.indices || index == m.details.shownVariant) return
        val at = messages.indexOfFirst { it.dbId == id }
        if (at < 0) return
        act {
            messages = messages.toMutableList().also { it[at] = showVariant(m, index) }
            try {
                api.updateLastMeta(sessionId, org.json.JSONObject().put("variantIndex", index))
            } catch (e: Exception) {
                val back = messages.indexOfFirst { it.dbId == id }
                if (back >= 0) messages = messages.toMutableList().also { it[back] = m }
                throw e
            }
            // The newest version's own reasoning is on the server's copy only.
            refreshTail()
        }
    }

    /**
     * "Continue" on a stopped last reply, as the web does (chatRenderer.js:3699,
     * chat.js:5496): a plain "Continue from where you left off." turn, no
     * bubble for it, and when it finishes merge-last-assistant joins the two
     * replies with a "(continued)" marker.
     */
    fun continueReply(m: HistoryMessage) {
        if (running || !isLastReply(m) || !m.details.stopped || m.details.cancelled) return
        ask = null
        start(beforeReload = { end ->
            if (end == RunEnd.Done) {
                try {
                    api.mergeLastAssistant(sessionId, CONTINUED)
                } catch (e: ApiException) {
                    notice(e)
                } catch (e: IOException) {
                    notice(e)
                } catch (e: JSONException) {
                    notice(e)
                }
            }
        }) { api.chatStream(request(CONTINUE_TEXT, emptyList(), null)) }
    }

    // -- read aloud ----------------------------------------------------------

    /** What marks a reply as the one being read: its id, or its text when it has none. */
    fun speechKey(m: HistoryMessage): String = m.dbId ?: m.raw

    /** Read aloud, or stop if this reply is the one being read. The visible
     * text goes to the server; the audio plays from a temp file. */
    fun readAloud(m: HistoryMessage) {
        val player = speech ?: return
        val key = speechKey(m)
        if (speaking == key) {
            speechJob?.cancel()
            player.stop()
            speaking = null
            return
        }
        val text = visibleAssistantText(m.raw)
        if (text.isBlank()) {
            say("No text to read")
            return
        }
        speechJob?.cancel()
        player.stop()
        speaking = key
        speechJob = viewModelScope.launch {
            try {
                val audio = api.synthesize(text)
                player.play(audio) { if (speaking == key) speaking = null }
            } catch (e: ApiException) {
                if (speaking == key) speaking = null
                notice(e)
            } catch (e: IOException) {
                if (speaking == key) speaking = null
                notice(e)
            }
        }
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
                // The web keeps the old text beside the rewrite (chat.js:7918).
                val all = keptVariants(m.details.variants, m.raw) + Variant(text, if (instruction == SHORTER) "shorter" else if (instruction == SIMPLER) "simpler" else "regen")
                try {
                    api.updateLastMeta(sessionId, variantsMeta(all, all.lastIndex))
                } catch (e: ApiException) {
                    notice(e)
                } catch (e: IOException) {
                    notice(e)
                }
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
    private fun replay(
        fromId: String,
        text: String,
        files: List<UploadedFile>,
        kept: List<Variant>? = null,
        oldReplyId: String? = null,
    ) {
        if (running || (text.isEmpty() && files.isEmpty())) return
        ask = null
        val bubble = HistoryMessage("user", text, null, null, files)
        // After the cut the server has lost the message for good, so if the
        // stream then fails to open the text goes back to the composer.
        start(
            rejected = {
                if (draft.isBlank()) draft = text
                viewModelScope.launch { reloadHistory() }
            },
            afterReload = kept?.let { v -> { end -> saveVariants(v, oldReplyId, end) } },
        ) {
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
        /** Runs when the stream ended, before the history reload (continue merges here). */
        beforeReload: (suspend (RunEnd) -> Unit)? = null,
        /** Runs after the reload, with the saved reply in [messages] (regenerate keeps versions here). */
        afterReload: (suspend (RunEnd) -> Unit)? = null,
        open: suspend () -> RunStream?,
    ) {
        probe?.cancel()
        if (!quiet) {
            running = true
            error = null
        }
        val job = viewModelScope.launch {
            val f = RunFollower(api, sessionId, resumed = quiet)
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
                if (!opened && rejected != null) {
                    rejected()
                } else {
                    if (opened) beforeReload?.invoke(end)
                    reloadHistory()
                    if (opened) afterReload?.invoke(end)
                }
            }
            live = null
            running = false
            canStop = false
            follower = null
            // A turn may have started a background job (research) whose reply
            // arrives with no stream; the web polls from then on too. A no-op
            // while a watch runs, and it ends itself when no job is active.
            if (end != null) watchJobs()
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
        /** What the web sends to continue (chatRenderer.js:3705); the server recognises it
         * to find the row to merge. Unmerged, it stays in the chat as the web's does. */
        const val CONTINUE_TEXT = "Continue from where you left off."
        const val CONTINUED = "\n\n*(continued)*\n\n"

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
