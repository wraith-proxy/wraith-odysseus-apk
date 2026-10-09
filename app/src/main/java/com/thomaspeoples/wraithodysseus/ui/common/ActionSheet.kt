package com.thomaspeoples.wraithodysseus.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.SheetShape

/** One row of an [ActionSheet]. */
data class SheetAction(
    val label: String,
    val icon: ImageVector? = null,
    /** Shown in red: it deletes something or cannot be undone. */
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The app's one long-press menu, for messages, chats and mail alike: a
 * bottom sheet of actions, as the web UI's phone menus are. Picking a row
 * closes the sheet before running it, so an action that opens a dialog or
 * another screen never ends up underneath it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(actions: List<SheetAction>, onDismiss: () -> Unit, title: String? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = SheetShape, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            title?.let {
                Text(
                    it,
                    color = Muted,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            actions.forEach { action ->
                val color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            onDismiss()
                            action.onClick()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    action.icon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) }
                    Text(action.label, color = color, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** A yes/no question before something that cannot be undone. */
@Composable
fun ConfirmDialog(
    title: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    text: String? = null,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = text?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
