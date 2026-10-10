package com.thomaspeoples.wraithodysseus.ui.chat

import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.Preset
import com.thomaspeoples.wraithodysseus.chat.SlashSuggestion
import com.thomaspeoples.wraithodysseus.chat.Toggles
import com.thomaspeoples.wraithodysseus.chat.UploadedFile
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.InputBarShape
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.theme.SendButtonShape
import kotlinx.coroutines.delay
import kotlin.math.abs

/** What the big button does: it follows the input and the run, as in the web. */
enum class BigButton { New, Send, Stop }

/**
 * The big button's state. Empty input starts a new chat, text sends, and
 * while a reply runs an empty input offers Stop (text still sends: it is
 * queued behind the reply).
 */
fun bigButton(hasInput: Boolean, running: Boolean): BigButton = when {
    hasInput -> BigButton.Send
    running -> BigButton.Stop
    else -> BigButton.New
}

/**
 * The bottom of a chat, as the web's composer: the Agent | Chat control and
 * its switches, the "^" expander (attach, prompt presets, RAG), the input,
 * and the big button. Swiping sideways across the input toggles plan mode,
 * with the web's mobile hint. A message typed while a reply streams waits as
 * [queued] until it ends. There is no microphone: the keyboard dictates.
 */
@Composable
fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    toggles: Toggles,
    onToggles: (Toggles) -> Unit,
    showTerminal: Boolean,
    attachments: List<UploadedFile>,
    uploading: Boolean,
    onPicked: (Uri) -> Unit,
    onRemoveAttachment: (UploadedFile) -> Unit,
    running: Boolean,
    canStop: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNew: () -> Unit,
    queued: String?,
    onCancelQueued: () -> Unit,
    presets: List<Preset>?,
    onExpand: () -> Unit,
    suggestions: List<SlashSuggestion>,
    modifier: Modifier = Modifier,
) {
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(onPicked) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onPicked) }
    var expanded by remember { mutableStateOf(false) }

    // The web alternates the placeholder with its plan hint every 5 s (app.js:2461).
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(draft.isEmpty(), running) {
        hint = false
        while (draft.isEmpty() && !running) {
            delay(5_000)
            hint = !hint
        }
    }

    val hasInput = draft.isNotBlank() || attachments.isNotEmpty()
    val big = bigButton(hasInput, running)

    Column(modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeControl(agent = toggles.agentMode && !toggles.research, enabled = !running) { onToggles(toggles.withMode(it)) }
            Chip("Web", toggles.web, !running) { onToggles(toggles.toggleWeb()) }
            if (showTerminal && toggles.agentMode) Chip("Terminal", toggles.terminal, !running) { onToggles(toggles.toggleTerminal()) }
            Chip("Research", toggles.research, !running) { onToggles(toggles.toggleResearch()) }
            if (toggles.planMode) Chip("Plan", true, !running) { onToggles(toggles.togglePlan()) }
        }

        queued?.let {
            Row(
                Modifier.fillMaxWidth().clip(CircleShape).background(OdyColors.InputBg).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Queued: $it",
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCancelQueued) { Icon(Lucide.X, "Cancel queued message", Modifier.size(16.dp), tint = Muted) }
            }
        }

        if (attachments.isNotEmpty() || uploading) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                attachments.forEach { file ->
                    InputChip(
                        selected = false,
                        onClick = { onRemoveAttachment(file) },
                        label = { Text(file.name, maxLines = 1) },
                        trailingIcon = { Icon(Lucide.X, "Remove", Modifier.size(14.dp)) },
                        shape = CircleShape,
                    )
                }
                if (uploading) Text("Uploading…", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }

        if (suggestions.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(InputBarShape).background(OdyColors.InputBg)) {
                suggestions.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onDraft(s.token + " ") }.padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(s.token, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        Text(s.help, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (expanded) {
            Column(
                Modifier.fillMaxWidth().clip(InputBarShape).background(OdyColors.InputBg).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Photo", false, !running && !uploading) {
                        photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    Chip("Document", false, !running && !uploading) { document.launch(arrayOf("*/*")) }
                    Chip("RAG", toggles.rag, !running) { onToggles(toggles.toggleRag()) }
                }
                if (!presets.isNullOrEmpty()) {
                    Text("Prompt preset", color = Muted, style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        presets.forEach { p ->
                            Chip(p.name, toggles.preset == p.id, !running) {
                                onToggles(toggles.withPreset(if (toggles.preset == p.id) null else p.id))
                            }
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(InputBarShape)
                .background(OdyColors.InputBg)
                .planSwipe(enabled = !running) { onToggles(toggles.togglePlan()) }
                .semantics {
                    if (!running) customActions = listOf(CustomAccessibilityAction("Toggle plan mode") { onToggles(toggles.togglePlan()); true })
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                expanded = !expanded
                if (expanded) onExpand()
            }) {
                Icon(if (expanded) Lucide.ChevronDown else Lucide.ChevronUp, if (expanded) "Hide options" else "More options", tint = Muted)
            }
            TextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = {
                    Text(
                        when {
                            toggles.planMode -> "Plan mode on - next message proposes a plan only"
                            running -> "Running… type to queue a message"
                            hint -> "Swipe to toggle plan"
                            else -> "Message Odysseus…"
                        },
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            when (big) {
                BigButton.New -> TextButton(
                    onClick = onNew,
                    modifier = Modifier.padding(end = 4.dp).clip(SendButtonShape).background(OdyColors.SendBtn),
                ) {
                    Icon(Lucide.Plus, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    Text(" New", color = MaterialTheme.colorScheme.onPrimary)
                }
                BigButton.Stop -> IconButton(
                    onClick = onStop,
                    enabled = canStop,
                    modifier = Modifier.padding(end = 4.dp).clip(SendButtonShape).background(if (canStop) MaterialTheme.colorScheme.error else Muted.copy(alpha = 0.3f)),
                ) {
                    Icon(Lucide.Square, "Stop", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                BigButton.Send -> IconButton(
                    onClick = onSend,
                    enabled = !uploading && !(running && queued != null),
                    modifier = Modifier.padding(end = 4.dp).clip(SendButtonShape).background(OdyColors.SendBtn),
                ) {
                    Icon(Lucide.ArrowUp, if (running) "Queue" else "Send", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

/**
 * A sideways swipe anywhere on the input row, watched on the way down so the
 * text field keeps its own gestures. Far enough and mostly horizontal, and
 * not after a hold: a hold then drag is the field selecting text.
 */
private fun Modifier.planSwipe(enabled: Boolean, onSwipe: () -> Unit): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var dx = 0f
        var dy = 0f
        var held: Boolean? = null
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            dx += change.positionChange().x
            dy += change.positionChange().y
            if (held == null && maxOf(abs(dx), abs(dy)) > viewConfiguration.touchSlop) {
                held = change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
            }
            if (!change.pressed) break
        }
        if (held == false && abs(dx) > SWIPE_DP.dp.toPx() && abs(dx) > 2 * abs(dy)) onSwipe()
    }
}

private const val SWIPE_DP = 96

/** Agent | Chat, two halves of one pill. */
@Composable
private fun ModeControl(agent: Boolean, enabled: Boolean, onPick: (Boolean) -> Unit) {
    Row(Modifier.clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)) {
        listOf(true to "Agent", false to "Chat").forEach { (isAgent, label) ->
            val on = agent == isAgent
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (on) MaterialTheme.colorScheme.primary else Muted,
                modifier = Modifier
                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .selectable(selected = on, enabled = enabled && !on, role = Role.RadioButton) { onPick(isAgent) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}
