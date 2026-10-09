package com.thomaspeoples.wraithodysseus.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.R
import com.thomaspeoples.wraithodysseus.auth.AuthStatus
import com.thomaspeoples.wraithodysseus.chat.ChatSession
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.sessions.ChatItem
import com.thomaspeoples.wraithodysseus.ui.sessions.ChatListController
import com.thomaspeoples.wraithodysseus.ui.sessions.ChatListDialogs
import com.thomaspeoples.wraithodysseus.ui.sessions.SelectBar
import com.thomaspeoples.wraithodysseus.ui.sessions.buildChatList
import com.thomaspeoples.wraithodysseus.ui.sessions.chatListItems
import com.thomaspeoples.wraithodysseus.ui.settings.Avatar
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.tools.Tool
import java.time.Instant

/**
 * The web UI's sidebar: New Chat, Search, the chats, Email, the Tools and,
 * at the foot, who is logged in with the settings gear. [sessions] is null
 * until the first load; [error] says why a load failed.
 */
@Composable
fun AppDrawer(
    sessions: List<ChatSession>?,
    error: String?,
    currentChatId: String?,
    chatList: ChatListController,
    running: Set<String>,
    status: AuthStatus?,
    username: String?,
    onNewChat: () -> Unit,
    onSearch: () -> Unit,
    onOpenChat: (String) -> Unit,
    onEmail: () -> Unit,
    onCompose: () -> Unit,
    onTool: (Tool) -> Unit,
    onArchived: () -> Unit,
    onSettings: () -> Unit,
) {
    var toolsOpen by rememberSaveable { mutableStateOf(true) }
    val now = remember(sessions) { Instant.now() }
    val items = remember(sessions, chatList.sort, chatList.collapsed, chatList.expanded) {
        buildChatList(sessions.orEmpty(), chatList.sort, now, chatList.collapsed, chatList.expanded)
    }
    // The drawer comes in from the right, so its inner edge is the start.
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerShape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
    ) {
        LazyColumn(Modifier.weight(1f)) {
            item(key = "brand") {
                Text(
                    "Odysseus",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                )
            }
            item(key = "new") { DrawerRow(Lucide.Plus, "New Chat", onNewChat) }
            item(key = "search") { DrawerRow(Lucide.Search, "Search", onSearch) }
            item(key = "chats") {
                DrawerRow(Lucide.MessageSquare, stringResource(R.string.nav_chats), onClick = null) {
                    IconButton(onClick = { chatList.sortMenu = true }) { Icon(Lucide.ListFilter, "Sort and select", tint = Muted, modifier = Modifier.size(18.dp)) }
                }
            }
            when {
                sessions != null -> chatListItems(items, chatList, currentChatId, running, onOpenChat)
                error != null -> item(key = "chats-error") {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                else -> item(key = "chats-loading") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) }
                }
            }
            item(key = "archived") { DrawerRow(Lucide.Archive, "Archived chats", onArchived) }
            item(key = "email") {
                DrawerRow(Lucide.Mail, stringResource(R.string.nav_email), onEmail) {
                    IconButton(onClick = onCompose) { Icon(Lucide.Plus, "New email", tint = Muted, modifier = Modifier.size(18.dp)) }
                }
            }
            item(key = "tools") {
                DrawerRow(Lucide.Wrench, "Tools", { toolsOpen = !toolsOpen }) {
                    Icon(
                        if (toolsOpen) Lucide.ChevronUp else Lucide.ChevronDown,
                        contentDescription = if (toolsOpen) "Collapse" else "Expand",
                        tint = Muted,
                        modifier = Modifier.padding(end = 12.dp).size(18.dp),
                    )
                }
            }
            if (toolsOpen) {
                items(Tool.entries.filter { it.visibleTo(status) }, key = { "tool-" + it.id }) { tool ->
                    DrawerRow(tool.icon, tool.label, { onTool(tool) }, indent = true) {
                        if (tool.webOnly) {
                            Icon(Lucide.ExternalLink, "Opens in browser", tint = Muted, modifier = Modifier.padding(end = 12.dp).size(14.dp))
                        }
                    }
                }
            }
        }
        // The app's snackbar sits under the drawer's scrim; this one is seen.
        SnackbarHost(LocalSnackbar.current)
        if (chatList.selecting) {
            SelectBar(chatList, items.filterIsInstance<ChatItem>().map { it.session.id }, sessions.orEmpty())
        }
        ChatListDialogs(chatList, sessions.orEmpty())
        HorizontalDivider(color = OdyColors.BubbleBorder.copy(alpha = 0.4f))
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onSettings).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(username)
            Text(username.orEmpty(), color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Lucide.Settings, "Settings", tint = Muted, modifier = Modifier.size(20.dp))
        }
    }
}

/** A sidebar row: the web UI's red icon and light label. A null [onClick]
 * makes it a section heading. */
@Composable
private fun DrawerRow(
    icon: ImageVector,
    label: String,
    onClick: (() -> Unit)?,
    indent: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = if (indent) 32.dp else 20.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        )
        trailing()
    }
}
