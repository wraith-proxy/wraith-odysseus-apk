package com.thomaspeoples.wraithodysseus.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.chat.SearchHit
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import java.io.IOException
import kotlinx.coroutines.delay
import org.json.JSONException
import com.thomaspeoples.wraithodysseus.ui.theme.themeBackground

/** Search across every chat's messages. Typing restarts a 300ms wait, so
 * only a pause in typing reaches the server. */
@Composable
fun SearchScreen(api: ChatApi, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            hits = null
            return@LaunchedEffect
        }
        delay(300)
        error = null
        try {
            hits = api.search(q)
        } catch (e: ApiException) {
            error = e.message
        } catch (_: IOException) {
            error = "Could not reach the server"
        } catch (_: JSONException) {
            error = "Unexpected response from the server"
        }
    }

    Column(Modifier.fillMaxSize().themeBackground(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search chats", color = Muted) },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 8.dp).focusRequester(focus),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        val list = hits
        if (list != null && list.isEmpty()) Text("No matches.", color = Muted, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(list.orEmpty(), key = { it.messageId + it.sessionId }) { hit ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(hit.sessionId) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(hit.sessionName.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${hit.role}: ${hit.snippet}",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(color = OdyColors.BubbleBorder.copy(alpha = 0.4f))
            }
        }
    }
}
