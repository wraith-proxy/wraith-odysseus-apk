package com.thomaspeoples.wraithodysseus.ui.chat

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.ModelChoice
import com.thomaspeoples.wraithodysseus.chat.groupModels
import com.thomaspeoples.wraithodysseus.chat.isCurrentModel
import com.thomaspeoples.wraithodysseus.chat.label
import com.thomaspeoples.wraithodysseus.ui.common.SheetAction
import com.thomaspeoples.wraithodysseus.ui.common.ActionSheet
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors

/** Ring colors by use: green below 70 %, orange from 70, red from 85 (chat.js:248). */
fun contextColor(percent: Double): Color = when {
    percent >= 85 -> Color(0xFFFF4444)
    percent >= 70 -> OdyColors.Warn
    else -> Color(0xFF50FA7B)
}

/** "Chat title · 12 msgs", or just the title while the count is unknown. */
fun headerSubtitle(messages: Int?): String? = messages?.let { "· $it msgs" }

/**
 * The chat's title with its message count and context ring, the model
 * picker under it, and the menu that opens the drawer on the right, where
 * the web UI keeps its hamburger. Tapping the title (or its arrow) offers
 * Rename, Compact, Copy Chat and Delete Chat; tapping the ring opens the
 * Chat Context sheet.
 */
@Composable
internal fun ChatHeader(
    vm: ChatViewModel,
    onMenu: () -> Unit,
    onRing: () -> Unit,
    onCopy: () -> Unit,
) {
    var actions by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val context = vm.context
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable { actions = true }.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    vm.title?.ifBlank { null } ?: "Chat",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                headerSubtitle(context?.messages)?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                Icon(Lucide.ChevronDown, "Chat options", Modifier.size(18.dp), tint = Muted)
            }
            if (context != null) {
                IconButton(onClick = onRing) {
                    ContextRing(context.percent, Modifier.semantics { contentDescription = "Chat context, ${context.percent.toInt()} percent" })
                }
            }
            IconButton(onClick = onMenu) { Icon(Lucide.Menu, "Menu") }
        }
        Box {
            TextButton(onClick = { picker = true }, enabled = vm.models.isNotEmpty() && !vm.running) {
                Text(
                    vm.currentChoice()?.label ?: vm.sessionModel ?: "Model",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Lucide.ChevronDown, null, Modifier.size(16.dp))
            }
            ModelMenu(vm.models, vm.sessionModel, vm.selected, vm.sessionEndpoint(), picker, { picker = false }) { vm.switchModel(it) }
        }
    }

    if (actions) {
        ActionSheet(
            title = vm.title,
            onDismiss = { actions = false },
            actions = listOf(
                SheetAction("Rename", Lucide.Pencil) { vm.askRename() },
                SheetAction("Compact", Lucide.Shrink) { vm.compact() },
                SheetAction("Copy Chat", Lucide.Copy) { onCopy() },
                SheetAction("Delete Chat", Lucide.Trash2, destructive = true) { vm.askDelete() },
            ),
        )
    }
}

/** The models grouped by endpoint, each under its provider, labelled "<provider> · <model>". */
@Composable
private fun ModelMenu(
    models: List<ModelChoice>,
    model: String?,
    selected: ModelChoice?,
    endpointId: String?,
    open: Boolean,
    onDismiss: () -> Unit,
    onPick: (ModelChoice) -> Unit,
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        groupModels(models).forEach { group ->
            Text(group.provider, color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            group.models.forEach { m ->
                val current = if (selected != null) selected.model == m.model && selected.endpointId == m.endpointId else isCurrentModel(m, model, endpointId)
                DropdownMenuItem(
                    text = { Text(m.label, color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                    trailingIcon = if (current) ({ Icon(Lucide.Check, null, Modifier.size(16.dp)) }) else null,
                    onClick = {
                        onDismiss()
                        if (!current) onPick(m)
                    },
                )
            }
        }
    }
}

/** A thin ring filled to [percent] of the context window. */
@Composable
internal fun ContextRing(percent: Double, modifier: Modifier = Modifier) {
    val color = contextColor(percent)
    val track = Muted.copy(alpha = 0.3f)
    Canvas(modifier.size(22.dp)) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        drawArc(color, -90f, (360f * (percent / 100.0)).toFloat(), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}
