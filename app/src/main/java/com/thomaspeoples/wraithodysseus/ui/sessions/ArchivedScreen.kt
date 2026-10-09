package com.thomaspeoples.wraithodysseus.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.ArchiveSort
import com.thomaspeoples.wraithodysseus.chat.ArchivedSession
import com.thomaspeoples.wraithodysseus.ui.common.ActionSheet
import com.thomaspeoples.wraithodysseus.ui.common.ConfirmDialog
import com.thomaspeoples.wraithodysseus.ui.common.SheetAction
import com.thomaspeoples.wraithodysseus.ui.common.parseServerTime
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay

/**
 * Archived chats, as the web's Archive tab: search by name, sort, and per row
 * Open, Restore or Delete (asked first). Tapping a row opens the chat. The
 * server returns twenty at a time; the rest load as the list is scrolled to
 * its end. Typing restarts a 300 ms wait, so only a pause reaches the server.
 */
@Composable
fun ArchivedScreen(ctl: ArchivedController, onOpen: (String) -> Unit, onBack: () -> Unit) {
    LaunchedEffect(ctl.search, ctl.sort) {
        // The first load, and a sort change, go at once; typing waits.
        if (ctl.search.isNotEmpty()) delay(300)
        ctl.refresh()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            OutlinedTextField(
                value = ctl.search,
                onValueChange = { ctl.search = it },
                placeholder = { Text("Search archived chats", color = Muted) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { ctl.sortMenu = true }) { Icon(Lucide.ListFilter, "Sort", tint = Muted) }
                DropdownMenu(expanded = ctl.sortMenu, onDismissRequest = { ctl.sortMenu = false }) {
                    ArchiveSort.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            trailingIcon = if (mode == ctl.sort) ({ Icon(Lucide.Check, null, Modifier.size(16.dp)) }) else null,
                            onClick = {
                                ctl.sort = mode
                                ctl.sortMenu = false
                            },
                        )
                    }
                }
            }
        }
        if (!ctl.loading && ctl.error == null) {
            Text(
                if (ctl.total == 1) "1 archived chat" else "${ctl.total} archived chats",
                color = Muted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        ctl.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            TextButton(onClick = ctl::refresh, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Retry") }
        }
        if (ctl.loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
        } else if (ctl.error == null && ctl.rows.isEmpty()) {
            Text(
                if (ctl.search.isBlank()) "No archived chats." else "No matches.",
                color = Muted,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(ctl.rows, key = { it.id }) { s ->
                ArchivedRow(s, onClick = { onOpen(s.id) }, onMenu = { ctl.menuFor = s })
                HorizontalDivider(color = OdyColors.BubbleBorder.copy(alpha = 0.4f))
            }
            if (ctl.hasMore) {
                item(key = "more") {
                    // Composed only once the end is reached, which asks for the next page.
                    if (ctl.moreFailed) {
                        TextButton(onClick = ctl::loadMore, modifier = Modifier.fillMaxWidth()) { Text("Retry loading more") }
                    } else {
                        LaunchedEffect(ctl.rows.size) { ctl.loadMore() }
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) }
                    }
                }
            }
        }
    }

    ctl.menuFor?.let { s ->
        ActionSheet(
            title = s.name.ifBlank { "Untitled" },
            onDismiss = { ctl.menuFor = null },
            actions = listOf(
                SheetAction("Open", Lucide.MessageSquare) { onOpen(s.id) },
                SheetAction("Restore", Lucide.ArchiveRestore) { ctl.restore(s) },
                SheetAction("Delete", Lucide.Trash2, destructive = true) { ctl.askDelete(s) },
            ),
        )
    }
    ctl.deleting?.let { s ->
        ConfirmDialog(
            title = "Delete this chat permanently?",
            text = "The chat and all its messages will be deleted. This cannot be undone.",
            confirm = "Delete",
            onConfirm = {
                ctl.deleting = null
                ctl.delete(s)
            },
            onDismiss = { ctl.deleting = null },
        )
    }
}

@Composable
private fun ArchivedRow(s: ArchivedSession, onClick: () -> Unit, onMenu: () -> Unit) {
    val detail = remember(s) { archivedDetail(s) }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(s.name.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMenu) { Icon(Lucide.EllipsisVertical, "Actions", tint = Muted, modifier = Modifier.size(18.dp)) }
    }
}

/** "qwen3 · 14 messages · Oct 7, 2026", leaving out what the server did not send. */
internal fun archivedDetail(s: ArchivedSession, zone: ZoneId = ZoneId.systemDefault()): String = listOfNotNull(
    s.model?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { null },
    "${s.messageCount} ${if (s.messageCount == 1) "message" else "messages"}",
    parseServerTime(s.updatedAt.ifBlank { null })?.atZone(zone)?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
).joinToString(" · ")
