package com.thomaspeoples.wraithodysseus.ui.chat.parts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.MemoryUsed
import com.thomaspeoples.wraithodysseus.chat.MessageDetails
import com.thomaspeoples.wraithodysseus.chat.MessageMetrics
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted

private val RingColors = mapOf(
    RingLevel.Ok to Color(0xFF98C379),
    RingLevel.Warn to Color(0xFFFF9900),
    RingLevel.High to Color(0xFFE06C75),
)

private enum class Popup { None, Stats, Context, Memories }

/**
 * Under a reply, beside its action icons: the speed (or cost, or time), the
 * context ring and the memory chip. Each opens its detail on a tap. Draws
 * nothing when there is nothing to say, as the web does.
 */
@Composable
internal fun DetailsFooter(details: MessageDetails) {
    val metrics = details.metrics
    val label = metrics?.let(::footerLabel)
    val ring = metrics?.let(::ringPercent)
    val chip = memoryChip(details.memories)
    if (label == null && chip.isEmpty()) return
    var popup by rememberSaveable { mutableStateOf(Popup.None) }

    Row(Modifier.fillMaxWidth().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (label != null) Tap("Message stats", { popup = Popup.Stats }) {
            Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        if (ring != null) Tap("Context ${Math.round(ring)}% used", { popup = Popup.Context }) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Ring(ring)
                Text("${Math.round(ring)}%", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (chip.isNotEmpty()) Tap("Memories used: $chip", { popup = Popup.Memories }) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Brain, contentDescription = null, tint = Muted, modifier = Modifier.size(12.dp))
                Text(chip, color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    val close = { popup = Popup.None }
    when (popup) {
        Popup.None -> Unit
        Popup.Stats -> metrics?.let { StatsDialog(it, close) }
        Popup.Context -> metrics?.let { ContextDialog(it, close) }
        Popup.Memories -> MemoriesDialog(details.memories, close)
    }
}

/** A footer figure with a touch target of its own. */
@Composable
private fun Tap(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** The 14dp ring: a faint track and an arc of the used share, coloured by [ringLevel]. */
@Composable
private fun Ring(percent: Double) {
    val color = RingColors.getValue(ringLevel(percent))
    Canvas(Modifier.size(14.dp).clearAndSetSemantics {}) {
        val stroke = 1.5.dp.toPx()
        val inset = stroke / 2
        val box = Size(size.width - stroke, size.height - stroke)
        drawArc(Muted.copy(alpha = 0.3f), 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
        drawArc(color, -90f, (360 * percent.coerceIn(0.0, 100.0) / 100).toFloat(), false, Offset(inset, inset), box, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

@Composable
private fun Rows(rows: List<Pair<String, String>>) {
    rows.forEach { (k, v) ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(k, color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(v, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatsDialog(m: MessageMetrics, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message stats", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                statsSections(m).forEach { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Rows(it) } }
                if (!m.realUsage) Text("~ estimated token count", color = Muted, style = MaterialTheme.typography.labelSmall)
                if (m.toolSchemaNames.isNotEmpty()) {
                    Text("Available tools", color = Muted, style = MaterialTheme.typography.labelSmall)
                    Text(m.toolSchemaNames.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun ContextDialog(m: MessageMetrics, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Context window", style = MaterialTheme.typography.titleMedium) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Rows(contextRows(m)) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** What the reply drew on: a dot for a pinned memory, a loop for one recalled (chatRenderer.js:2454). */
@Composable
private fun MemoriesDialog(memories: List<MemoryUsed>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Memories used", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                memories.forEach { m ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (m.type == "pinned") "●" else "↻", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Text(m.text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
