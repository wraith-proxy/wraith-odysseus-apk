package com.thomaspeoples.wraithodysseus.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.ChatSession
import com.thomaspeoples.wraithodysseus.ui.common.parseServerTime
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import java.time.Instant

/** The web sidebar shows this many chats before "Show N more". */
private const val FIRST_ROWS = 10

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
    s.name.startsWith("Fork:") || s.name.startsWith("⫝") -> Lucide.GitFork
    s.hasDocuments -> Lucide.FileText
    s.hasImages -> Lucide.Image
    s.mode == "agent" -> Lucide.Terminal
    s.mode?.startsWith("research") == true -> Lucide.Search
    else -> Lucide.MessageSquare
}

/** "Title · model", the model by its basename, as the web sidebar labels a chat. */
fun rowLabel(s: ChatSession): String =
    listOfNotNull(s.name.ifBlank { "Untitled" }, s.model?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { null })
        .joinToString(" · ")

/** The drawer's Chats section. [currentId] is the chat on screen. */
fun LazyListScope.chatListItems(
    sessions: List<ChatSession>,
    currentId: String?,
    showAll: Boolean,
    onShowAll: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val ordered = drawerOrder(sessions)
    val shown = if (showAll) ordered else ordered.take(FIRST_ROWS)
    items(shown, key = { "chat-" + it.id }) { s -> ChatRow(s, current = s.id == currentId) { onOpen(s.id) } }
    if (shown.size < ordered.size) {
        item(key = "chats-more") {
            TextButton(onClick = onShowAll, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Show ${ordered.size - shown.size} more", color = Muted)
            }
        }
    }
}

@Composable
private fun ChatRow(s: ChatSession, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            sessionIcon(s),
            contentDescription = null,
            tint = if (s.isImportant) MaterialTheme.colorScheme.primary else Muted,
            modifier = Modifier.size(16.dp),
        )
        Text(rowLabel(s), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}
