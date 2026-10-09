package com.thomaspeoples.wraithodysseus.ui.sessions

import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.ChatSession
import com.thomaspeoples.wraithodysseus.ui.common.ActionSheet
import com.thomaspeoples.wraithodysseus.ui.common.ConfirmDialog
import com.thomaspeoples.wraithodysseus.ui.common.SheetAction
import com.thomaspeoples.wraithodysseus.ui.common.parseServerTime
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import java.time.Instant

/** The web's long press on a chat row (sessions.js, 500 ms). */
private const val LONG_PRESS_MS = 500L

/** The web sidebar's default "Last Active" order: favourites first, then the
 * most recently active. */
fun drawerOrder(sessions: List<ChatSession>): List<ChatSession> =
    sessions.sortedWith(compareByDescending<ChatSession> { it.isImportant }.thenByDescending { lastActive(it) })

/** The chat the app opens on: the most recently active, favourite or not. */
fun mostRecent(sessions: List<ChatSession>): ChatSession? = sessions.maxByOrNull(::lastActive)

private fun lastActive(s: ChatSession): Instant = parseServerTime(s.lastMessageAt) ?: Instant.EPOCH

/** The web UI's row icon (sessions.js), most specific first. A favourite
 * shows its bookmark instead, as it does there. */
fun sessionIcon(s: ChatSession): ImageVector = when {
    s.isImportant -> Lucide.Bookmark
    s.name.startsWith("[GRP]") -> Lucide.User // the set has no "users" glyph
    s.name.startsWith("Fork:") || s.name.startsWith("⫝") -> Lucide.GitFork
    s.hasDocuments -> Lucide.FileText
    s.hasImages -> Lucide.Image
    s.mode == "agent" -> Lucide.Terminal
    s.mode == "research" -> Lucide.Search // exact, as sessions.js: research_pending is not yet research
    else -> Lucide.MessageSquare
}

private val PREFIX = Regex("""^(Fork:|\u2ADD|\[GRP])\s*""")

/** "Title · model", the model by its basename, as the web sidebar labels a
 * chat, with the Fork:, ⫝ and [GRP] markers dropped (the icon says it). */
fun rowLabel(s: ChatSession): String =
    listOfNotNull(s.name.replace(PREFIX, "").ifBlank { "Untitled" }, s.model?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { null })
        .joinToString(" · ")

/** The drawer's chats: group headings, rows and "Show N more". Pressing a
 * row opens it (or ticks it in select mode); holding it for half a second
 * opens its menu. */
fun LazyListScope.chatListItems(
    items: List<ListItem>,
    ctl: ChatListController,
    currentId: String?,
    running: Set<String>,
    onOpen: (String) -> Unit,
) {
    items(items, key = { it.key }) { item ->
        when (item) {
            is GroupHeader -> GroupHeaderRow(item) { ctl.toggleGroup(item.key) }
            is ChatItem -> ChatRow(
                item.session,
                current = item.session.id == currentId,
                running = item.session.id in running,
                selecting = ctl.selecting,
                picked = item.session.id in ctl.selected,
                onClick = { if (ctl.selecting) ctl.toggle(item.session.id) else onOpen(item.session.id) },
                onLongClick = { if (!ctl.selecting) ctl.menuFor = item.session },
            )
            is ShowMore -> TextButton(onClick = { ctl.showAll(item.group) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Show ${item.hidden} more", color = Muted)
            }
        }
    }
}

@Composable
private fun GroupHeaderRow(h: GroupHeader, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (h.collapsed) "Show ${h.label}" else "Hide ${h.label}", onClick = onToggle)
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (h.collapsed) Lucide.ChevronRight else Lucide.ChevronDown, null, tint = Muted, modifier = Modifier.size(14.dp))
        Text(h.label, color = Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text("(${h.count})", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    s: ChatSession,
    current: Boolean,
    running: Boolean,
    selecting: Boolean,
    picked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    // Compose's long press is the system's (400 ms); the web uses 500.
    val base = LocalViewConfiguration.current
    val config = remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long get() = LONG_PRESS_MS
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides config) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClickLabel = "Chat actions",
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                )
                .semantics { if (selecting) selected = picked }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selecting) {
                Icon(
                    if (picked) Lucide.Check else Lucide.Square,
                    contentDescription = if (picked) "Selected" else "Not selected",
                    tint = if (picked) MaterialTheme.colorScheme.primary else Muted,
                    modifier = Modifier.size(18.dp),
                )
            }
            Icon(
                sessionIcon(s),
                contentDescription = null,
                tint = if (s.isImportant) MaterialTheme.colorScheme.primary else Muted,
                modifier = Modifier.size(16.dp),
            )
            Text(rowLabel(s), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (running) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

/** All, Archive, Delete and Cancel, shown while chats are being picked. */
@Composable
fun SelectBar(ctl: ChatListController, shown: List<String>, all: List<ChatSession>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { ctl.toggleAll(shown) }) { Text("All") }
        Text("${ctl.selected.size}", color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = ctl::archiveSelected, enabled = ctl.selected.isNotEmpty()) { Text("Archive") }
        TextButton(onClick = { ctl.askDeleteSelected(all) }, enabled = ctl.selected.isNotEmpty()) {
            Text("Delete", color = if (ctl.selected.isNotEmpty()) MaterialTheme.colorScheme.error else Muted)
        }
        TextButton(onClick = ctl::endSelect) { Text("Cancel") }
    }
}

/** The menus and questions the list opens: a chat's menu, the folder picker,
 * the sort choices and the dialogs behind Rename, New folder and Delete. */
@Composable
fun ChatListDialogs(ctl: ChatListController, all: List<ChatSession>) {
    ctl.menuFor?.let { s ->
        ActionSheet(
            title = rowLabel(s),
            onDismiss = { ctl.menuFor = null },
            actions = listOf(
                SheetAction("Select", Lucide.ListChecks) { ctl.startSelect(s.id) },
                SheetAction("Rename", Lucide.Pencil) { ctl.renaming = s },
                SheetAction(if (s.isImportant) "Unfavorite" else "Favorite", Lucide.Bookmark) { ctl.toggleFavorite(s) },
                SheetAction("Copy Chat", Lucide.Copy) { ctl.copyChat(s) },
                SheetAction("Move to folder", Lucide.FolderInput) { ctl.moving = s },
                SheetAction("Archive", Lucide.Archive) { ctl.archive(s) },
                SheetAction("Delete", Lucide.Trash2, destructive = true) { ctl.askDelete(s) },
                SheetAction("Cancel", Lucide.X) {},
            ),
        )
    }
    ctl.moving?.let { s ->
        ActionSheet(
            title = "Move to folder",
            onDismiss = { ctl.moving = null },
            actions = buildList {
                if (!s.folder.isNullOrBlank()) add(SheetAction("(No folder)", Lucide.X) { ctl.move(s, "") })
                folderNames(all).filter { it != s.folder }.forEach { name -> add(SheetAction(name, Lucide.Folder) { ctl.move(s, name) }) }
                add(SheetAction("New folder…", Lucide.Plus) { ctl.newFolderFor = s })
            },
        )
    }
    if (ctl.sortMenu) {
        ActionSheet(
            title = "Sort chats",
            onDismiss = { ctl.sortMenu = false },
            actions = buildList {
                SortMode.entries.forEach { mode ->
                    add(SheetAction(mode.label, if (mode == ctl.sort) Lucide.Check else null) { ctl.sortBy(mode) })
                }
                add(SheetAction("Select", Lucide.ListChecks) { ctl.startSelect() })
            },
        )
    }
    ctl.renaming?.let { s ->
        NamePrompt("Rename chat", "Rename", s.name, onDismiss = { ctl.renaming = null }) { ctl.rename(s, it) }
    }
    ctl.newFolderFor?.let { s ->
        NamePrompt("New folder", "Create", "", onDismiss = { ctl.newFolderFor = null }) { ctl.move(s, it) }
    }
    ctl.deleting?.let { s ->
        ConfirmDialog(
            title = "Delete this chat?",
            text = "The chat and all its messages will be deleted. This cannot be undone.",
            confirm = "Delete",
            onConfirm = { ctl.delete(s) },
            onDismiss = { ctl.deleting = null },
        )
    }
    ctl.confirmBulkDelete?.let { plan ->
        val n = plan.ids.size
        ConfirmDialog(
            title = "Delete $n ${if (n == 1) "chat" else "chats"}?",
            text = "This cannot be undone." +
                if (plan.skippedStarred > 0) {
                    val k = plan.skippedStarred
                    " $k ${if (k == 1) "favorite" else "favorites"} will be skipped: unfavorite them first."
                } else "",
            confirm = "Delete",
            onConfirm = { ctl.deleteSelected(plan) },
            onDismiss = { ctl.confirmBulkDelete = null },
        )
    }
}

@Composable
private fun NamePrompt(title: String, confirm: String, initial: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onSubmit(name)
            }, enabled = name.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
