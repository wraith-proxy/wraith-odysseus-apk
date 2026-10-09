package com.thomaspeoples.wraithodysseus.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide

/** The model picker on the left; the menu that opens the drawer on the
 * right, where the web UI keeps its hamburger. */
@Composable
internal fun ChatHeader(vm: ChatViewModel, onMenu: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
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
        IconButton(onClick = onMenu) { Icon(Lucide.Menu, "Menu") }
    }
}
