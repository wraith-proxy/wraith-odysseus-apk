package com.thomaspeoples.wraithodysseus.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.TopLevelBar
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import com.thomaspeoples.wraithodysseus.ui.sessions.mostRecent
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import java.io.IOException
import org.json.JSONException

/**
 * Where the app starts after login: straight on into the most recently
 * active chat, as the web UI reopens the last one. Only an account with no
 * chats at all stays here, with a way to start one.
 */
@Composable
fun HomeScreen(api: ChatApi, onOpenChat: (String) -> Unit, onNewChat: () -> Unit, onMenu: () -> Unit) {
    var empty by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        try {
            val latest = mostRecent(api.sessions())
            if (latest != null) onOpenChat(latest.id) else empty = true
        } catch (e: ApiException) {
            error = userMessage(e)
        } catch (e: IOException) {
            error = userMessage(e)
        } catch (e: JSONException) {
            error = userMessage(e)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        TopLevelBar("Odysseus", onMenu = onMenu)
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val failed = error
            when {
                failed != null -> {
                    Text(failed, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    TextButton(onClick = { attempt++ }) { Text("Retry") }
                }
                empty -> {
                    Text("No chats yet.", color = Muted)
                    Button(onClick = onNewChat) { Text("New chat") }
                }
                else -> CircularProgressIndicator()
            }
        }
    }
}
