package com.thomaspeoples.wraithodysseus.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.WraithOdysseusApp
import com.thomaspeoples.wraithodysseus.auth.AuthStatus
import com.thomaspeoples.wraithodysseus.chat.ChatApi
import com.thomaspeoples.wraithodysseus.chat.ModelChoice
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import java.io.IOException
import kotlinx.coroutines.launch
import org.json.JSONException

/** Runs [block] and says what went wrong in the snackbar; false if it failed. */
private suspend fun attempt(say: suspend (String) -> Unit, block: suspend () -> Unit): Boolean = try {
    block()
    true
} catch (e: ApiException) {
    say(userMessage(e)); false
} catch (e: IOException) {
    say(userMessage(e)); false
} catch (e: JSONException) {
    say(userMessage(e)); false
}

/**
 * The server-side settings a phone needs most: which model new chats start
 * on, and whether memory and skills are used. The memory and skills
 * switches follow the web's privilege check (can_manage_memory).
 */
@Composable
fun PrefsSection(status: AuthStatus?) {
    val http = (LocalContext.current.applicationContext as WraithOdysseusApp).http
    val prefs = remember(http) { PrefsApi(http) }
    val chat = remember(http) { ChatApi(http) }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val isAdmin = status?.isAdmin == true
    val memoryAllowed = status?.allows("can_manage_memory") != false

    var current by remember { mutableStateOf<ModelChoice?>(null) }
    var currentLabel by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<ModelChoice>?>(null) }
    var picking by remember { mutableStateOf(false) }
    var flags by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    val flagKeys = listOf(PrefsApi.KEY_MEMORY, PrefsApi.KEY_AUTO_MEMORY, PrefsApi.KEY_SKILLS)

    LaunchedEffect(prefs, memoryAllowed) {
        attempt(snackbar::showSnackbar) {
            val d = chat.defaultChat()
            val all = chat.models().also { models = it }
            current = all.firstOrNull { it.endpointId == d.endpointId && it.model == d.model }
            currentLabel = current?.display ?: d.model
        }
        if (memoryAllowed) {
            attempt(snackbar::showSnackbar) { flags = flagKeys.associateWith { prefs.flag(it) } }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, enabled = models != null) { picking = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Lucide.Brain, contentDescription = null, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text("Default model", style = MaterialTheme.typography.bodyLarge)
            Text(
                currentLabel.ifEmpty { "Not set" }, color = Muted, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (memoryAllowed && flags.isNotEmpty()) {
        listOf(
            PrefsApi.KEY_MEMORY to "Use memory in chats",
            PrefsApi.KEY_AUTO_MEMORY to "Save memories automatically",
            PrefsApi.KEY_SKILLS to "Use skills in chats",
        ).forEach { (key, label) ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = flags[key] == true,
                    onCheckedChange = { on ->
                        val before = flags
                        flags = flags + (key to on)
                        scope.launch {
                            // Back to what it was if the server did not take it, as the web UI does.
                            if (!attempt(snackbar::showSnackbar) { prefs.setFlag(key, on) }) flags = before
                        }
                    },
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

    val list = models
    if (picking && list != null) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Default model", style = MaterialTheme.typography.titleMedium) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.endpointId + "\u0000" + it.model }) { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.RadioButton) {
                                picking = false
                                scope.launch {
                                    if (attempt(snackbar::showSnackbar) { prefs.setDefaultModel(m, isAdmin) }) {
                                        current = m
                                        currentLabel = m.display
                                    }
                                }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(m.display, style = MaterialTheme.typography.bodyMedium)
                                Text(m.endpointName, color = Muted, style = MaterialTheme.typography.labelSmall)
                            }
                            if (m == current) Icon(Lucide.Check, contentDescription = "Current", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}
