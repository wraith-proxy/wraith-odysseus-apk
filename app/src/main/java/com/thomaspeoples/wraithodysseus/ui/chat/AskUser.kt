package com.thomaspeoples.wraithodysseus.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.AskOption
import com.thomaspeoples.wraithodysseus.chat.AskUser
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.theme.SheetShape

/** Plain ask_user: a tap answers, or with [AskUser.multi] the picks are
 * collected and sent together, joined with ", " as the web UI does. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskChips(ask: AskUser, enabled: Boolean, onAnswer: (List<AskOption>) -> Unit) {
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
internal fun ApprovalSheet(ask: AskUser, onDecide: (AskOption) -> Unit) {
    var hidden by remember(ask) { mutableStateOf(false) }
    if (hidden) {
        TextButton(onClick = { hidden = false }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Approval needed: review") }
        return
    }
    ModalBottomSheet(onDismissRequest = { hidden = true }, shape = SheetShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(ask.question, style = MaterialTheme.typography.titleMedium)
            if (ask.description.isNotBlank()) Text(ask.description, color = Muted, style = MaterialTheme.typography.bodySmall)
            // What will actually run: without it the approval is blind.
            ask.action?.let { action ->
                Text(
                    action.lines().joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(OdyColors.CodeBg)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            }
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
