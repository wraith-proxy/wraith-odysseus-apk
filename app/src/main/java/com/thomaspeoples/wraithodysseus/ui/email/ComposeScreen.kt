package com.thomaspeoples.wraithodysseus.ui.email

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.email.EmailAccount
import com.thomaspeoples.wraithodysseus.email.EmailApi
import com.thomaspeoples.wraithodysseus.email.EmailMessage
import com.thomaspeoples.wraithodysseus.email.SendRequest
import com.thomaspeoples.wraithodysseus.email.SendResult
import com.thomaspeoples.wraithodysseus.email.describe
import com.thomaspeoples.wraithodysseus.email.replyDraft
import com.thomaspeoples.wraithodysseus.email.unreadPasses
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.theme.SendButtonShape
import java.io.IOException
import kotlinx.coroutines.launch

/**
 * A new message, or with [reply] set, a reply to it from [replyAccountId].
 * [onDone] is called on Sent and on back; on any other outcome the draft
 * stays on screen. Nothing is ever re-sent automatically: after an
 * unconfirmed send a second tap could deliver the message twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(api: EmailApi, onDone: () -> Unit, reply: EmailMessage? = null, replyAccountId: String? = null) {
    val draft = remember(reply) { reply?.let { replyDraft(it, replyAccountId) } }
    var to by rememberSaveable { mutableStateOf(draft?.to.orEmpty()) }
    var cc by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf(draft?.subject.orEmpty()) }
    var body by rememberSaveable { mutableStateOf(draft?.body.orEmpty()) }

    // A reply always goes out from the account it arrived on; a new message
    // offers the same accounts the inbox lists, default first.
    var senders by remember { mutableStateOf<List<EmailAccount?>?>(if (draft != null) emptyList() else null) }
    var senderIndex by rememberSaveable { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var unconfirmed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (draft != null) return@LaunchedEffect
        try {
            senders = unreadPasses(api.accounts())
        } catch (e: ApiException) {
            problem = e.message
        } catch (e: IOException) {
            problem = describe(e)
        }
    }

    val choices = senders
    val canSend = !sending && to.isNotBlank() && subject.isNotBlank() &&
        (draft != null || !choices.isNullOrEmpty())

    fun send() {
        // The reply headers come from the draft; only the text is edited.
        val request = draft?.copy(to = to.trim(), cc = cc.trim(), subject = subject.trim(), body = body)
            ?: SendRequest(to.trim(), cc.trim(), subject.trim(), body, choices?.getOrNull(senderIndex)?.id)
        sending = true
        problem = null
        unconfirmed = false
        scope.launch {
            when (val result = api.send(request)) {
                SendResult.Sent -> {
                    Toast.makeText(context, "Sent", Toast.LENGTH_SHORT).show()
                    onDone()
                }
                SendResult.Unconfirmed -> unconfirmed = true
                is SendResult.Failed -> problem = result.message
            }
            sending = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (draft != null) "Reply" else "New message") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = ::send,
                        enabled = canSend,
                        shape = SendButtonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = OdyColors.SendBtn),
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(if (sending) "Sending…" else "Send") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (unconfirmed) Banner("Delivery unconfirmed — check Sent in the web UI", OdyColors.Warn)
            problem?.let { Banner(it, MaterialTheme.colorScheme.error) }
            if (choices != null && choices.isEmpty() && draft == null) {
                Banner("No enabled email accounts. Add or enable one in the web UI.", MaterialTheme.colorScheme.error)
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Only worth a choice when there is one to make.
                if (choices != null && choices.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.forEachIndexed { i, account ->
                            FilterChip(
                                selected = i == senderIndex,
                                onClick = { senderIndex = i },
                                label = { Text(account?.name.orEmpty()) },
                            )
                        }
                    }
                }
                Field("To", to, { to = it }, KeyboardType.Email)
                Field("Cc", cc, { cc = it }, KeyboardType.Email)
                Field("Subject", subject, { subject = it }, KeyboardType.Text)
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = OdyColors.InputBg,
    unfocusedContainerColor = OdyColors.InputBg,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
)
