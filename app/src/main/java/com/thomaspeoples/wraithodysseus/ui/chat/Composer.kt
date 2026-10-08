package com.thomaspeoples.wraithodysseus.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.Toggles
import com.thomaspeoples.wraithodysseus.chat.UploadedFile
import com.thomaspeoples.wraithodysseus.ui.theme.InputBarShape
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.theme.SendButtonShape

/** The bottom of a chat: capability chips, pending attachments, and the
 * input bar whose send button turns into Stop while a run is live. */
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
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(onPicked) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onPicked) }
    var menu by remember { mutableStateOf(false) }

    Column(modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Agent", toggles.agent, !running) { onToggles(toggles.toggleAgent()) }
            Chip("Web", toggles.web, !running) { onToggles(toggles.toggleWeb()) }
            if (showTerminal) Chip("Terminal", toggles.terminal, !running) { onToggles(toggles.toggleTerminal()) }
            Chip("Research", toggles.research, !running) { onToggles(toggles.toggleResearch()) }
        }

        if (attachments.isNotEmpty() || uploading) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                attachments.forEach { file ->
                    InputChip(
                        selected = false,
                        onClick = { onRemoveAttachment(file) },
                        label = { Text(file.name, maxLines = 1) },
                        trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(14.dp)) },
                        shape = CircleShape,
                    )
                }
                if (uploading) Text("Uploading…", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(InputBarShape)
                .background(OdyColors.InputBg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton(onClick = { menu = true }, enabled = !running && !uploading) {
                    Icon(Icons.Default.Add, "Attach", tint = Muted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Photo") }, onClick = {
                        menu = false
                        photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    DropdownMenuItem(text = { Text("Document") }, onClick = {
                        menu = false
                        document.launch(arrayOf("*/*"))
                    })
                }
            }
            TextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = { Text(if (running) "Running…" else "Message Odysseus…", color = Muted) },
                enabled = !running,
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
            if (running) {
                TextButton(onClick = onStop, modifier = Modifier.padding(end = 4.dp)) {
                    Text("Stop", color = MaterialTheme.colorScheme.error)
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = !uploading && (draft.isNotBlank() || attachments.isNotEmpty()),
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clip(SendButtonShape)
                        .background(OdyColors.SendBtn),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
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
