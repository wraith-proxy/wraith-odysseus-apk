package com.thomaspeoples.wraithodysseus.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.chat.HistoryMessage
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
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
) {
    val vm = viewModel(key = sessionId) { ChatViewModel(api, sessionId, createSavedStateHandle()) }
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it) } }

    // Covers screen-off and app switches: a run that dropped (or was started
    // elsewhere) is picked up again, replayed from its start.
    LifecycleResumeEffect(vm) {
        vm.attach()
        onPauseOrDispose { }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding(),
    ) {
        ChatHeader(vm, onMenu)

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
            vm.live?.let { turn -> item(key = "live") { LiveBubble(turn, vm.sessionModel) } }
            itemsIndexed(bottomFirst, key = { i, m -> m.dbId ?: "local-${bottomFirst.size - i}" }) { _, m ->
                Box(Modifier.animateItem(fadeInSpec = tween(300), placementSpec = tween(300))) {
                    val streaming = vm.rewriting?.takeIf { m.dbId != null && m.dbId == lastReplyId }
                    // The rewrite shows in place of the reply as it streams in.
                    val shown = when {
                        streaming != null -> m.copy(content = streaming.ifEmpty { "…" }, raw = streaming, thinking = null)
                        m === lastUser && liveMode?.mode != null ->
                            m.copy(details = m.details.copy(mode = liveMode.mode, autoEscalated = liveMode.autoEscalated))
                        else -> m
                    }
                    MessageItem(shown, vm::thumbnail, menu = messageMenu(vm, m, lastReplyId, onOpenChat), defaultModel = vm.sessionModel)
                }
            }
            if (vm.hasOlder) item { TextButton(onClick = { vm.loadOlder() }) { Text("Load older") } }
            if (vm.loading) item { Text("Loading…", color = Muted) }
            else if (vm.messages.isEmpty() && vm.live == null) item { Text("No messages yet.", color = Muted) }
        }

        vm.ask?.takeIf { it.approvalId != null }?.let { ApprovalSheet(it, vm::decide) }

        Composer(
            draft = vm.draft,
            onDraft = { vm.draft = it },
            toggles = vm.toggles,
            onToggles = { vm.toggles = it },
            showTerminal = canUseBash,
            attachments = vm.attachments,
            uploading = vm.uploading,
            onPicked = { uri -> context.applicationContext.let { app -> vm.attachFile { readUri(app, uri) } } },
            onRemoveAttachment = vm::removeAttachment,
            running = vm.running,
            canStop = vm.canStop,
            onSend = vm::send,
            onStop = { vm.stop() },
        )
    }
}

private fun messageMenu(vm: ChatViewModel, m: HistoryMessage, lastReplyId: String?, onOpenChat: (String) -> Unit) = MessageMenu(
    canChange = m.dbId != null && !vm.running,
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
