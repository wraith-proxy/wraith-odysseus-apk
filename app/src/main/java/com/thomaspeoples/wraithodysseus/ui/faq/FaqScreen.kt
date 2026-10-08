package com.thomaspeoples.wraithodysseus.ui.faq

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.R

private val faqEntries: List<Pair<String, String>> = listOf(
    "What is this app?" to
        "An unofficial Android client for a self-hosted Odysseus AI server: your chats and your " +
            "unread email, on the phone. It is not made by or affiliated with the Odysseus project.",
    "Does my server need changing?" to
        "No. The app uses only the routes the server's own web UI uses, and was tested against " +
            "Odysseus 1.0.3.",
    "Why can't it reach my server?" to
        "The phone has to reach the server the same way your browser does. If the server is on " +
            "your tailnet, check that Tailscale is connected on the phone, and use the same https " +
            "address that works in the phone's browser.",
    "Why does it refuse my http:// address?" to
        "Plain http is accepted only for private network addresses (a LAN or a Tailscale 100.x " +
            "IP). Anything else must be https, so your session cookie never crosses the internet " +
            "unencrypted. A server that marks its cookie Secure needs https even on a private address.",
    "What does the app store?" to
        "The server address, your username, and the session cookie, which is encrypted with a key " +
            "that never leaves the device. Your password is never stored.",
    "Why was I sent back to the login screen?" to
        "The server ends every session 7 days after login, and can end one sooner. Log in again " +
            "as the same account and the app returns to the screen you were on.",
    "It says to finish setup in the web UI." to
        "The server has not been set up yet. Open the web UI in a browser, finish the first-run " +
            "setup there, then log in here.",
    "Why is there no Terminal toggle?" to
        "It only appears for accounts the server allows to run shell commands, the same as in " +
            "the web UI.",
    "Does a reply stop if I switch apps or the screen turns off?" to
        "No. The reply keeps running on the server. When you come back, the app picks the stream " +
            "up again and rebuilds it from the start, so nothing is lost or doubled.",
    "Which email does it show?" to
        "Every unread message in the inbox of every enabled account, merged and newest first. " +
            "Opening a message marks it read on the server; Mark unread puts it back.",
    "It said delivery is unconfirmed. Was my email sent?" to
        "Maybe. The server took longer than its time limit to confirm. Check the Sent folder in " +
            "the web UI before sending again, so it does not go twice.",
    "How do I get updates?" to
        "It is not on the Play Store. Obtainium can track this project's Gitea releases, or you " +
            "can download the APK directly. Direct installs also get a dialog on launch when a " +
            "newer release exists.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaqScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_faq)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            faqEntries.forEach { (question, answer) -> FaqItem(question, answer) }
        }
    }
}

@Composable
private fun FaqItem(question: String, answer: String) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            if (expanded) {
                Text(answer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
