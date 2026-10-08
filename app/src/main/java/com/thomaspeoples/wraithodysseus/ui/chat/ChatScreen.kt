package com.thomaspeoples.wraithodysseus.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaspeoples.wraithodysseus.chat.AskOption
import com.thomaspeoples.wraithodysseus.chat.AskUser
import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.chat.HistoryMessage
import com.thomaspeoples.wraithodysseus.chat.TurnState
import com.thomaspeoples.wraithodysseus.chat.UploadedFile
import com.thomaspeoples.wraithodysseus.ui.common.Markdown
import com.thomaspeoples.wraithodysseus.ui.common.ThinkingBlock
import com.thomaspeoples.wraithodysseus.ui.theme.AiBubbleShape
import com.thomaspeoples.wraithodysseus.ui.theme.BUBBLE_MAX_WIDTH_FRACTION
import com.thomaspeoples.wraithodysseus.ui.theme.BubblePadding
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.theme.SheetShape
import com.thomaspeoples.wraithodysseus.ui.theme.UserBubbleShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The chat/{sid} route. [canUseBash] is privileges.can_use_bash from
 * /api/auth/status: the Terminal chip only exists for those who have it. */
@Composable
fun ChatScreen(api: ChatApi, sessionId: String, canUseBash: Boolean, onBack: () -> Unit) {
    val vm = viewModel(key = sessionId) { ChatViewModel(api, sessionId, createSavedStateHandle()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
        TopBar(vm, onBack)

        val bottomFirst = remember(vm.messages) { vm.messages.asReversed() }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            vm.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            vm.ask?.let { ask -> if (ask.approvalId == null) item { AskChips(ask, enabled = !vm.running, onAnswer = vm::answer) } }
            vm.live?.toolLine?.let { item { Text(it, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
            vm.live?.let { turn -> item(key = "live") { LiveBubble(turn) } }
            itemsIndexed(bottomFirst, key = { i, m -> m.dbId ?: "local-${bottomFirst.size - i}" }) { _, m ->
                Box(Modifier.animateItem(fadeInSpec = tween(300), placementSpec = tween(300))) {
                    MessageBubble(m, vm::thumbnail)
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
            onPicked = { uri -> scope.launch { readUri(context, uri)?.let { (name, mime, bytes) -> vm.attachFile(name, mime, bytes) }
                    ?: run { vm.error = "Could not read that file" } } },
            onRemoveAttachment = vm::removeAttachment,
            running = vm.running,
            canStop = vm.canStop,
            onSend = vm::send,
            onStop = { vm.stop() },
        )
    }
}

@Composable
private fun TopBar(vm: ChatViewModel, onBack: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Box {
            TextButton(onClick = { open = true }, enabled = vm.models.isNotEmpty() && !vm.running) {
                Text(vm.selected?.display ?: vm.sessionModel ?: "Model", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                vm.models.forEach { m ->
                    // The same id can live on several endpoints; say which.
                    val shared = vm.models.count { it.model == m.model } > 1
                    DropdownMenuItem(
                        text = { Text(if (shared) "${m.display} (${m.endpointName})" else m.display) },
                        onClick = {
                            vm.selected = m
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Bubble(user: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        // An 85% lane, with the bubble hugging its speaker's side of it.
        Box(
            Modifier.fillMaxWidth(BUBBLE_MAX_WIDTH_FRACTION),
            contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            val shape = if (user) UserBubbleShape else AiBubbleShape
            Column(
                Modifier
                    .clip(shape)
                    .background(if (user) OdyColors.UserBubble else OdyColors.AiBubble)
                    .then(if (user) Modifier else Modifier.border(1.dp, OdyColors.BubbleBorder, shape))
                    .padding(BubblePadding),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { content() }
        }
    }
}

@Composable
private fun MessageBubble(m: HistoryMessage, thumbnail: suspend (String) -> ByteArray) {
    val user = m.role == "user"
    Bubble(user) {
        if (m.attachments.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { m.attachments.forEach { Thumb(it, thumbnail) } }
        }
        m.thinking?.let { ThinkingBlock(it, active = false) }
        when {
            user -> Text(m.content, style = MaterialTheme.typography.bodyMedium)
            m.content.isNotBlank() -> Markdown(m.content)
        }
    }
}

@Composable
private fun LiveBubble(turn: TurnState) {
    val answer = turn.answer
    Bubble(user = false) {
        if (turn.thinking.isNotEmpty()) ThinkingBlock(turn.thinking, active = answer.isEmpty())
        if (answer.isNotEmpty()) Markdown(answer)
        else if (turn.thinking.isEmpty()) Text("…", color = Muted)
    }
}

@Composable
private fun Thumb(file: UploadedFile, thumbnail: suspend (String) -> ByteArray) {
    if (!file.mime.startsWith("image/")) {
        Text(file.name, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val image by produceState<ImageBitmap?>(null, file.id) {
        value = runCatching {
            val bytes = thumbnail(file.id)
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }.getOrNull()
    }
    image?.let {
        Image(it, file.name, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.small))
    } ?: Box(Modifier.size(72.dp).clip(MaterialTheme.shapes.small).background(OdyColors.CodeBg))
}

/** Plain ask_user: a tap answers, or with [AskUser.multi] the picks are
 * collected and sent together, joined with ", " as the web UI does. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskChips(ask: AskUser, enabled: Boolean, onAnswer: (List<AskOption>) -> Unit) {
    var picked by remember(ask) { mutableStateOf(emptyList<AskOption>()) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ask.options.forEach { o ->
            if (ask.multi) {
                FilterChip(
                    selected = o in picked,
                    onClick = { picked = if (o in picked) picked - o else picked + o },
                    label = { Text(o.label) },
                    enabled = enabled,
                    shape = CircleShape,
                )
            } else {
                AssistChip(onClick = { onAnswer(listOf(o)) }, label = { Text(o.label) }, enabled = enabled, shape = CircleShape)
            }
        }
        if (ask.multi) {
            AssistChip(onClick = { onAnswer(picked) }, label = { Text("Send") }, enabled = enabled && picked.isNotEmpty(), shape = CircleShape)
        }
    }
}

/** The tool-approval gate. Dismissing it folds it into a button rather than
 * answering; an unanswered gate simply expires server-side. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalSheet(ask: AskUser, onDecide: (AskOption) -> Unit) {
    var hidden by remember(ask) { mutableStateOf(false) }
    if (hidden) {
        TextButton(onClick = { hidden = false }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Approval needed: review") }
        return
    }
    ModalBottomSheet(onDismissRequest = { hidden = true }, shape = SheetShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(ask.question, style = MaterialTheme.typography.titleMedium)
            if (ask.description.isNotBlank()) Text(ask.description, color = Muted, style = MaterialTheme.typography.bodySmall)
            ask.options.filter { it.value != null }.forEach { o ->
                OutlinedButton(onClick = { onDecide(o) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(o.label, color = if (o.value == "deny") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        if (o.description.isNotBlank()) Text(o.description, color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

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
