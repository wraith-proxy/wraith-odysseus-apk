package com.thomaspeoples.wraithodysseus.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.chat.ChatSession
import com.thomaspeoples.wraithodysseus.chat.newChatName
import com.thomaspeoples.wraithodysseus.chat.pickNewChatModel
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** The Chats tab: every session, newest first (important ones pinned on top
 * by the server), refreshed on pull-down and whenever the screen resumes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(api: ChatApi, onOpen: (String) -> Unit, onSearch: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<ChatSession>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun guarded(block: suspend () -> Unit) {
        try {
            error = null
            block()
        } catch (e: ApiException) {
            error = e.message
        } catch (_: IOException) {
            error = "Could not reach the server. Is Tailscale connected?"
        }
    }

    fun refresh() = scope.launch {
        refreshing = true
        guarded { sessions = api.sessions() }
        refreshing = false
    }

    fun newChat() = scope.launch {
        if (creating) return@launch
        creating = true
        guarded {
            val model = pickNewChatModel(api.defaultChat(), api.models())
            if (model == null) {
                error = "No chat model is set up. Add one in the web UI."
            } else {
                val time = LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM))
                onOpen(api.createSession(newChatName(model.model, time), model.endpointId, model.model))
            }
        }
        creating = false
    }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
            IconButton(onClick = { newChat() }, enabled = !creating) { Icon(Icons.Default.Add, "New chat") }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.weight(1f)) {
            val list = sessions
            when {
                list == null -> Box(Modifier.fillMaxSize())
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No chats yet. Tap + to start one.", color = Muted)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { s ->
                        SessionRow(s) { onOpen(s.id) }
                        HorizontalDivider(color = OdyColors.BubbleBorder.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(s: ChatSession, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.name.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(s.model, s.mode?.takeIf { it == "agent" }, s.updatedAt.substringBefore('T')).joinToString(" · "),
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (s.isImportant) Icon(Icons.Default.Star, "Important", tint = OdyColors.Warn)
    }
}
