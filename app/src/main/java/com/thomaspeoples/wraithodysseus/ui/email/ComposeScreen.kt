package com.thomaspeoples.wraithodysseus.ui.email

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import com.thomaspeoples.wraithodysseus.email.AI_HINT_MAX
import com.thomaspeoples.wraithodysseus.email.AiReplyOutcome
import com.thomaspeoples.wraithodysseus.email.EmailMessage
import com.thomaspeoples.wraithodysseus.email.aiReplyBody
import com.thomaspeoples.wraithodysseus.email.aiReplyRequest
import com.thomaspeoples.wraithodysseus.email.quotedBody
import com.thomaspeoples.wraithodysseus.email.SendRequest
import com.thomaspeoples.wraithodysseus.email.SendResult
import com.thomaspeoples.wraithodysseus.email.describe
import com.thomaspeoples.wraithodysseus.email.replyDraft
import com.thomaspeoples.wraithodysseus.email.unreadPasses
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.theme.SendButtonShape
import java.io.IOException
import kotlinx.coroutines.launch

/**
 * A new message, or with [reply] set, a reply to it from [replyAccountId].
 * [onDone] is called on Sent and on back, which is held off while a send
 * is in flight; on any other outcome the draft stays on screen. Nothing is ever re-sent automatically: after an
 * unconfirmed send a second tap could deliver the message twice.
 *
 * In reply mode the AI reply button drafts the text above the quote. When
 * [generateWithHint] is non-null a draft starts as soon as the screen opens,
 * with that text as the optional context. It never sends anything: the text
 * lands in the body and waits for the person to read it and tap Send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(api: EmailApi, onDone: () -> Unit, reply: EmailMessage? = null, replyAccountId: String? = null, generateWithHint: String? = null) {
    val draft = remember(reply) { reply?.let { replyDraft(it, replyAccountId) } }
    var to by rememberSaveable { mutableStateOf(draft?.to.orEmpty()) }
    var cc by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf(draft?.subject.orEmpty()) }
    var body by rememberSaveable { mutableStateOf(draft?.body.orEmpty()) }

    // AI draft. preBody is what the body was before generating, which is put
    // back on failure or Stop. aiRunning is saveable for the same reason
    // `sending` is: a rotation kills the stream, and the restored screen must
    // not show half a draft as if it were finished.
    var hint by rememberSaveable { mutableStateOf(generateWithHint.orEmpty()) }
    var preBody by rememberSaveable { mutableStateOf("") }
    var aiRunning by rememberSaveable { mutableStateOf(false) }
    var aiStarted by rememberSaveable { mutableStateOf(false) }
    var aiError by rememberSaveable { mutableStateOf<String?>(null) }
    var askHint by rememberSaveable { mutableStateOf(false) }
    var aiJob by remember { mutableStateOf<Job?>(null) }

    // A reply always goes out from the account it arrived on; a new message
    // offers the same accounts the inbox lists, default first.
    var senders by remember { mutableStateOf<List<EmailAccount?>?>(if (draft != null) emptyList() else null) }
    var senderIndex by rememberSaveable { mutableIntStateOf(0) }
    // Saveable, because the send dies with this composition (a rotation
    // mid-send cancels it) and whether it reached the server is then unknown:
    // a restored "sending" comes back as "unconfirmed", never as a fresh Send.
    var sending by rememberSaveable { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var unconfirmed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Back waits for the send: leaving would cancel it (and with it the
    // outcome and the draft) while it may already have reached the server.
    BackHandler(enabled = sending) { }

    fun generate(context: String) {
        val original = reply ?: return
        if (aiRunning || sending) return
        val quote = quotedBody(original)
        hint = context
        preBody = body
        aiRunning = true
        aiError = null
        aiJob = scope.launch {
            try {
                val request = aiReplyRequest(original, to.trim(), subject.trim(), replyAccountId, context)
                when (val outcome = api.aiReply(request) { text -> body = aiReplyBody(text, quote) }) {
                    is AiReplyOutcome.Reply -> body = aiReplyBody(outcome.text, quote)
                    is AiReplyOutcome.Failed -> {
                        body = preBody
                        aiError = outcome.message
                    }
                }
            } catch (e: CancellationException) {
                body = preBody
                throw e
            } catch (e: Exception) {
                body = preBody
                aiError = userMessage(e)
            } finally {
                aiRunning = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (aiRunning) {
            aiRunning = false
            body = preBody
            aiError = "The AI reply was interrupted"
        } else if (generateWithHint != null && !aiStarted) {
            aiStarted = true
            generate(generateWithHint)
        }
    }

    LaunchedEffect(Unit) {
        if (sending) {
            sending = false
            unconfirmed = true
        }
    }

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
    val canSend = !sending && !aiRunning && to.isNotBlank() && subject.isNotBlank() &&
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
                    IconButton(onClick = onDone, enabled = !sending) {
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
            // Consumed first, or imePadding adds the navigation bar a second time.
            Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (unconfirmed) Banner("Delivery unconfirmed — check Sent in the web UI", OdyColors.Warn)
            problem?.let { Banner(it, MaterialTheme.colorScheme.error) }
            if (draft != null) {
                aiError?.let { message ->
                    Banner(message, MaterialTheme.colorScheme.error)
                    TextButton(
                        onClick = { generate(hint) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text("Retry") }
                }
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (aiRunning) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Drafting…", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { aiJob?.cancel() }) {
                            Icon(Lucide.Square, null, Modifier.size(16.dp))
                            Text("Stop", Modifier.padding(start = 6.dp))
                        }
                    } else {
                        OutlinedButton(onClick = { askHint = true }, enabled = !sending) {
                            Icon(Lucide.Sparkle, null, Modifier.size(16.dp))
                            Text("AI reply", Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
            if (askHint) {
                AiReplyDialog(
                    initial = hint,
                    onDismiss = { askHint = false },
                    onConfirm = { askHint = false; generate(it) },
                )
            }
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
                    // The stream owns the text until it ends or is stopped.
                    readOnly = aiRunning,
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

/**
 * The web UI's "Context (optional)" box (static/js/emailLibrary/aiReply.js
 * ~145-168): steering text sent as user_hint, which the server caps at
 * [AI_HINT_MAX] characters.
 */
@Composable
internal fun AiReplyDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI reply") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(AI_HINT_MAX) },
                placeholder = { Text("Context (optional)") },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Draft reply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
