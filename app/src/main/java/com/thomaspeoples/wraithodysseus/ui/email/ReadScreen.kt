package com.thomaspeoples.wraithodysseus.ui.email

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.email.EmailApi
import com.thomaspeoples.wraithodysseus.email.EmailMessage
import com.thomaspeoples.wraithodysseus.email.describe
import com.thomaspeoples.wraithodysseus.email.niceDate
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import java.io.IOException
import kotlinx.coroutines.launch

/**
 * One message as plain text. The server marks it seen as part of the read.
 * Reply opens the composer in place rather than as its own route, so the
 * original it quotes never has to travel through a navigation argument.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadScreen(api: EmailApi, accountId: String?, folder: String, uid: String, onBack: () -> Unit) {
    var message by remember { mutableStateOf<EmailMessage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var replying by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(accountId, folder, uid) {
        try {
            message = api.read(accountId, folder, uid)
        } catch (e: ApiException) {
            error = e.message
        } catch (e: IOException) {
            error = describe(e)
        }
    }

    val loaded = message
    if (replying && loaded != null) {
        BackHandler { replying = false }
        ComposeScreen(api = api, onDone = { replying = false }, reply = loaded, replyAccountId = accountId)
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (loaded != null) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    notice = try {
                                        api.markUnread(accountId, folder, uid)
                                        "Marked unread. Refresh the inbox to see it there again."
                                    } catch (e: ApiException) {
                                        e.message
                                    } catch (e: IOException) {
                                        describe(e)
                                    }
                                    busy = false
                                }
                            },
                        ) { Text("Mark unread") }
                        TextButton(onClick = { replying = true }) { Text("Reply") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                error != null -> Column { Banner(error.orEmpty(), MaterialTheme.colorScheme.error) }
                loaded == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (loaded.markSeenFailed) Banner("Could not mark this message read on the server.", OdyColors.Warn)
                    notice?.let { Banner(it, MaterialTheme.colorScheme.secondary) }
                    Header(loaded)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    SelectionContainer {
                        Text(
                            loaded.body,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(m: EmailMessage) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(m.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.titleMedium)
        val from = if (m.fromName.isBlank()) m.fromAddress else "${m.fromName} <${m.fromAddress}>"
        Text(from, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Meta("To", m.to)
        Meta("Cc", m.cc)
        Text(niceDate(m.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Meta(label: String, value: String) {
    if (value.isBlank()) return
    Text("$label: $value", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
