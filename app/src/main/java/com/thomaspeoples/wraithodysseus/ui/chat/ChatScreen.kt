package com.thomaspeoples.wraithodysseus.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.chat.HistoryMessage
import com.thomaspeoples.wraithodysseus.chat.FirstSend
import com.thomaspeoples.wraithodysseus.chat.MediaPlayerOutput
import com.thomaspeoples.wraithodysseus.chat.ModelChoice
import com.thomaspeoples.wraithodysseus.chat.groupModels
import com.thomaspeoples.wraithodysseus.chat.label
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.chat.parts.Folds
import com.thomaspeoples.wraithodysseus.ui.chat.parts.LIVE_FOLDS
import com.thomaspeoples.wraithodysseus.ui.chat.parts.LocalFolds
import com.thomaspeoples.wraithodysseus.ui.chat.parts.ResearchJobCards
import com.thomaspeoples.wraithodysseus.ui.common.ConfirmDialog
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The chat/{sid} route, and the app's home: the drawer behind [onMenu]
 * reaches everything else. [canUseBash] is privileges.can_use_bash from
 * /api/auth/status: the Terminal chip only exists for those who have it. */
@Composable
fun ChatScreen(
    api: ChatApi,
    sessionId: String,
    canUseBash: Boolean,
    onMenu: () -> Unit,
    /** Opens another chat by id: where Fork lands once the copy exists. */
    onOpenChat: (String) -> Unit = {},
    /** "+ New" and /new: the empty new chat, on this model if known. Nothing is created yet. */
    onNewChat: (ModelChoice?) -> Unit = {},
    /** The first send of a new chat made the session: open it, carrying the message. */
    onCreated: (String, FirstSend) -> Unit = { id, _ -> onOpenChat(id) },
    /** The message a new chat was made for, handed over once by the screen before it. */
    takeFirst: () -> FirstSend? = { null },
    /** The model a new chat starts on, when it is not the account's default. */
    startModel: ModelChoice? = null,
    /** An archived chat, previewed read-only as the web does (sessions.js
     * _arcPeekOpen): no composer and no message changes, a Restore bar instead. */
    archived: Boolean = false,
    onRestore: () -> Unit = {},
) {
    val context = LocalContext.current
    val cacheDir = context.applicationContext.cacheDir
    val vm = viewModel(key = sessionId) {
        ChatViewModel(api, sessionId, createSavedStateHandle(), speech = MediaPlayerOutput(cacheDir), canUseBash = canUseBash, startModel = startModel)
    }
    vm.canUseBash = canUseBash
    val snackbar = LocalSnackbar.current
    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it) } }
    vm.openChat = onOpenChat
    vm.openNew = onNewChat
    vm.openCreated = onCreated
    // Once: takeFirst gives it up on the first call, so a rotation does not send it again.
    LaunchedEffect(vm) { takeFirst()?.let(vm::sendFirst) }

    // The header's name, the chat's own mode for the switches, and the ring:
    // asked on opening and again each time a run (or another action) ends.
    LaunchedEffect(vm) { vm.loadChatInfo() }
    LaunchedEffect(vm, vm.running) { if (!vm.running) vm.refreshContext() }
    var ringOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Covers screen-off and app switches: a run that dropped (or was started
    // elsewhere) is picked up again, replayed from its start.
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { vm.onPause() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding(),
    ) {
        if (vm.isDraft) {
            DraftHeader(vm, onMenu)
        } else {
            ChatHeader(
                vm,
                onMenu,
                onRing = { ringOpen = true },
                onCopy = {
                    scope.launch {
                        vm.transcript()?.let { text ->
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("chat", text))
                            snackbar.showSnackbar("Chat copied")
                        }
                    }
                },
            )
        }

        val bottomFirst = remember(vm.messages) { vm.messages.asReversed() }
        // Rewrite is offered on the last reply only: the server rewrites its last assistant row.
        val lastReplyId = remember(vm.messages) { vm.messages.lastOrNull { it.role == "assistant" }?.dbId }
        // The run's mode (turn_mode) lands on the message that started it, as in the web.
        val lastUser = remember(vm.messages) { vm.messages.lastOrNull { it.role == "user" } }
        val liveMode = vm.live?.details
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            vm.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            vm.ask?.let { ask -> if (ask.approvalId == null) item { AskChips(ask, enabled = !vm.running, onAnswer = vm::answer) } }
            vm.live?.toolLine?.let { item { Text(it, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
            vm.live?.notices?.asReversed()?.forEach { n -> item { NoticeLine(n) } }
            vm.live?.let { turn ->
                item(key = "live") {
                    CompositionLocalProvider(LocalFolds provides Folds(vm.folds, LIVE_FOLDS)) { LiveBubble(turn, vm.sessionModel) }
                }
            }
            if (vm.jobs.isNotEmpty()) item(key = "jobs") { ResearchJobCards(vm.jobs) }
            itemsIndexed(bottomFirst, key = { i, m -> m.dbId ?: "local-${bottomFirst.size - i}" }) { i, m ->
                Box(Modifier.animateItem(fadeInSpec = tween(300), placementSpec = tween(300))) {
                    val streaming = vm.rewriting?.takeIf { m.dbId != null && m.dbId == lastReplyId }
                    // The rewrite shows in place of the reply as it streams in.
                    val shown = when {
                        streaming != null -> m.copy(content = streaming.ifEmpty { "…" }, raw = streaming, thinking = null)
                        m === lastUser && liveMode?.mode != null ->
                            m.copy(details = m.details.copy(mode = liveMode.mode, autoEscalated = liveMode.autoEscalated))
                        else -> m
                    }
                    CompositionLocalProvider(LocalFolds provides Folds(vm.folds, m.dbId ?: "local-${bottomFirst.size - i}")) {
                        MessageItem(shown, vm::thumbnail, menu = messageMenu(vm, m, lastReplyId, onOpenChat, archived), defaultModel = vm.sessionModel)
                    }
                }
            }
            if (vm.hasOlder) item { TextButton(onClick = { vm.loadOlder() }) { Text("Load older") } }
            if (vm.loading) item { Text("Loading…", color = Muted) }
            else if (vm.messages.isEmpty() && vm.live == null) item { Text(if (vm.isDraft) "Send a message to start the chat." else "No messages yet.", color = Muted) }
        }

        vm.ask?.takeIf { it.approvalId != null }?.let { ApprovalSheet(it, vm::decide) }

        val closed = vm.closed
        if (closed != null) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(closed, color = Muted, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.newChat() }) { Text("New chat") }
                TextButton(onClick = onMenu) { Text("Chats") }
            }
        } else if (archived) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Archived chat. Restore it to reply.", color = Muted, modifier = Modifier.weight(1f))
                TextButton(onClick = onRestore) { Text("Restore") }
            }
        } else {
            Composer(
                draft = vm.draft,
                onDraft = {
                    vm.draft = it
                    vm.noteDraft(it)
                },
                toggles = vm.toggles,
                onToggles = { vm.updateToggles(it) },
                showTerminal = canUseBash,
                attachments = vm.attachments,
                uploading = vm.uploading,
                onPicked = { uri -> context.applicationContext.let { app -> vm.attachFile { readUri(app, uri) } } },
                onRemoveAttachment = vm::removeAttachment,
                running = vm.running,
                canStop = vm.canStop,
                onSend = vm::send,
                onStop = { vm.stop() },
                onNew = vm::newChat,
                queued = vm.queued?.text?.ifBlank { "(attachment)" },
                onCancelQueued = vm::cancelQueued,
                presets = vm.presets,
                onExpand = { scope.launch { vm.loadPresets() } },
                suggestions = vm.suggestions(),
            )
        }
    }

    if (ringOpen) ContextSheet(vm) { ringOpen = false }
    if (vm.confirmDelete) {
        ConfirmDialog(
            title = "Delete this chat?",
            text = "The chat and all its messages will be deleted. This cannot be undone.",
            confirm = "Delete",
            onConfirm = vm::deleteChat,
            onDismiss = { vm.confirmDelete = false },
        )
    }
    if (vm.renaming) RenameDialog(vm.title.orEmpty(), onDismiss = { vm.renaming = false }, onRename = vm::rename)
    vm.helpText?.let { text ->
        AlertDialog(
            onDismissRequest = { vm.helpText = null },
            title = { Text("Commands", style = MaterialTheme.typography.titleMedium) },
            text = { Text(text, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { vm.helpText = null }) { Text("OK") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

/**
 * The header of a chat that does not exist yet: no title, count or actions
 * (there is nothing to rename, copy or delete), only the model the first
 * message will go to, which can be changed before it is sent.
 */
@Composable
private fun DraftHeader(vm: ChatViewModel, onMenu: () -> Unit) {
    var picker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("New chat", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
            IconButton(onClick = onMenu) { Icon(Lucide.Menu, "Menu") }
        }
        Box {
            TextButton(onClick = { picker = true }, enabled = vm.models.isNotEmpty() && !vm.running) {
                Text(vm.selected?.label ?: "Model", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Lucide.ChevronDown, null, Modifier.padding(start = 4.dp))
            }
            DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                groupModels(vm.models).forEach { group ->
                    Text(group.provider, color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    group.models.forEach { m ->
                        DropdownMenuItem(text = { Text(m.label) }, onClick = {
                            vm.selected = m
                            picker = false
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat", style = MaterialTheme.typography.titleMedium) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onRename(name)
            }, enabled = name.isNotBlank()) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

private fun messageMenu(vm: ChatViewModel, m: HistoryMessage, lastReplyId: String?, onOpenChat: (String) -> Unit, archived: Boolean) = MessageMenu(
    canChange = !archived && m.dbId != null && !vm.running,
    lastReply = m.dbId != null && m.dbId == lastReplyId,
    editText = { vm.visibleForEdit(m) },
    deleteText = {
        val doomed = vm.deletionOf(m)
        when {
            doomed.size < 2 -> "This message will be deleted from the chat."
            m.role == "user" -> "This message and the reply after it will be deleted from the chat."
            else -> "This reply and the message it answers will be deleted from the chat."
        }
    },
    onEdit = { vm.edit(m, it) },
    onResend = { vm.resend(m) },
    onRegenerate = { vm.regenerate(m) },
    onRewrite = { vm.rewrite(m, it) },
    onFork = { vm.fork(m, onOpenChat) },
    onDelete = { vm.delete(m) },
    readAloud = vm.ttsEnabled && m.role == "assistant",
    speaking = vm.speaking == vm.speechKey(m),
    onReadAloud = { vm.readAloud(m) },
    onContinue = { vm.continueReply(m) },
    onVariant = { vm.switchVariant(m, it) },
)

/** Name, type and bytes of a picked file. Read whole: the server caps
 * upload size anyway and answers with a readable error past it. */
private suspend fun readUri(context: Context, uri: Uri): Triple<String, String, ByteArray>? = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        Triple(name, mime, bytes)
    }.getOrNull()
}
