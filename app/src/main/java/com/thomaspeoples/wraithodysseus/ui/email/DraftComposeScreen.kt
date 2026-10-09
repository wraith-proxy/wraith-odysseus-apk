package com.thomaspeoples.wraithodysseus.ui.email

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.email.EmailApi
import com.thomaspeoples.wraithodysseus.email.EmailAttachment
import com.thomaspeoples.wraithodysseus.email.SendRequest
import com.thomaspeoples.wraithodysseus.email.SendResult
import com.thomaspeoples.wraithodysseus.email.describe
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.theme.SendButtonShape
import java.io.IOException
import kotlinx.coroutines.launch

/** The message a Forward takes its attachments from. */
data class ForwardSource(val accountId: String?, val folder: String, val uid: String, val attachments: List<EmailAttachment>)

/**
 * The composer for Reply All and Forward, which [ComposeScreen] cannot do
 * (it takes only a To and starts Cc empty). [draft] is prefilled; with a
 * [forward] source its attachments are staged on the server first and sent as
 * real attachments, as the web's Forward does. Sending follows the same rule
 * as [ComposeScreen]: nothing is ever re-sent automatically, and an
 * unconfirmed delivery keeps the draft with a warning to check Sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftComposeScreen(api: EmailApi, draft: SendRequest, title: String, onDone: () -> Unit, forward: ForwardSource? = null) {
    var to by rememberSaveable { mutableStateOf(draft.to) }
    var cc by rememberSaveable { mutableStateOf(draft.cc) }
    var subject by rememberSaveable { mutableStateOf(draft.subject) }
    var body by rememberSaveable { mutableStateOf(draft.body) }
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var unconfirmed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    BackHandler(enabled = sending) { }

    fun send() {
        sending = true
        problem = null
        unconfirmed = false
        scope.launch {
            val tokens = try {
                forward?.attachments.orEmpty().map { api.stageAttachment(forward?.accountId, forward?.folder.orEmpty(), forward?.uid.orEmpty(), it.index) }
            } catch (e: ApiException) {
                problem = "Could not attach the original files: ${e.message}"
                sending = false
                return@launch
            } catch (e: IOException) {
                problem = describe(e)
                sending = false
                return@launch
            }
            when (val result = api.send(draft.copy(to = to.trim(), cc = cc.trim(), subject = subject.trim(), body = body, attachments = tokens))) {
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
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onDone, enabled = !sending) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = ::send,
                        enabled = !sending && to.isNotBlank() && subject.isNotBlank(),
                        shape = SendButtonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = OdyColors.SendBtn),
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(if (sending) "Sending…" else "Send") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (unconfirmed) Banner("Delivery unconfirmed — check Sent in the web UI", OdyColors.Warn)
            problem?.let { Banner(it, MaterialTheme.colorScheme.error) }
            if (forward != null && forward.attachments.isNotEmpty()) {
                val n = forward.attachments.size
                Banner("Forwarding $n attachment${if (n == 1) "" else "s"} with it", MaterialTheme.colorScheme.secondary)
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DraftField("To", to, { to = it }, KeyboardType.Email)
                DraftField("Cc", cc, { cc = it }, KeyboardType.Email)
                DraftField("Subject", subject, { subject = it }, KeyboardType.Text)
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = draftColors(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                )
            }
        }
    }
}

@Composable
private fun DraftField(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        colors = draftColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun draftColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = OdyColors.InputBg,
    unfocusedContainerColor = OdyColors.InputBg,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
)
