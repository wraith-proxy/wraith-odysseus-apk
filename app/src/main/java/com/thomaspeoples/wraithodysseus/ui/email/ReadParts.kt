package com.thomaspeoples.wraithodysseus.ui.email

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.email.EmailApi
import com.thomaspeoples.wraithodysseus.email.EmailAttachment
import com.thomaspeoples.wraithodysseus.email.EmailMessage
import com.thomaspeoples.wraithodysseus.email.InlineImage
import com.thomaspeoples.wraithodysseus.email.attachmentSize
import com.thomaspeoples.wraithodysseus.email.isCalendarFile
import com.thomaspeoples.wraithodysseus.email.isLikelySignatureImage
import com.thomaspeoples.wraithodysseus.email.niceDate
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import kotlinx.coroutines.runBlocking

/** A colour as the "#RRGGBB" the reader's stylesheet takes. */
internal fun cssColor(c: Color): String = "#%06X".format(0xFFFFFF and c.toArgb())

/**
 * Serves `cid:` images to the reader's WebView from the message's own MIME
 * parts, remembering each answer. Runs on the WebView loader thread, so it
 * blocks. A failed call (offline) is not remembered, so a retry can succeed.
 */
class CidFetcher(private val api: EmailApi, private val accountId: String?, private val folder: String, private val uid: String) {
    private val done = HashMap<String, InlineImage?>()

    fun fetch(cid: String): InlineImage? {
        synchronized(done) { if (done.containsKey(cid)) return done[cid] }
        val image = try {
            runBlocking { api.inlineImage(accountId, folder, uid, cid) }
        } catch (_: Exception) {
            return null
        }
        synchronized(done) { done[cid] = image }
        return image
    }
}

/** Where a translation of the open message stands. */
sealed interface TranslationState {
    data object Idle : TranslationState
    data class Busy(val language: String) : TranslationState
    data class Done(val language: String, val text: String) : TranslationState
    data class Same(val language: String) : TranslationState
    /** The server answered 504: the model may well still be working. */
    data class Working(val language: String) : TranslationState
    data class Failed(val language: String, val message: String) : TranslationState
}

/**
 * Subject, the sender chip and the date. The chip opens a popover with the
 * name and address, Copy and Save to contacts; the arrow beside it reveals
 * the To and Cc lines.
 */
@Composable
internal fun ReadHeader(m: EmailMessage, onCopy: () -> Unit, onSave: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var recipients by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(m.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { menu = true }) {
                    Text(
                        m.fromName.ifBlank { m.fromAddress },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(m.fromName.ifBlank { m.fromAddress }, fontWeight = FontWeight.SemiBold)
                        Text(m.fromAddress, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Copy address") },
                        leadingIcon = { Icon(Lucide.Copy, null, Modifier.size(18.dp)) },
                        onClick = { menu = false; onCopy() },
                    )
                    DropdownMenuItem(
                        text = { Text("Save to contacts") },
                        leadingIcon = { Icon(Lucide.UserPlus, null, Modifier.size(18.dp)) },
                        onClick = { menu = false; onSave() },
                    )
                }
            }
            if (m.to.isNotBlank() || m.cc.isNotBlank()) {
                IconButton(onClick = { recipients = !recipients }) {
                    Icon(if (recipients) Lucide.ChevronUp else Lucide.ChevronDown, if (recipients) "Hide recipients" else "Show To and Cc")
                }
            }
        }
        if (recipients) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RecipientLine("To", m.to)
                    RecipientLine("Cc", m.cc)
                }
            }
        }
        Text(niceDate(m.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecipientLine(label: String, value: String) {
    if (value.isBlank()) return
    Text("$label: $value", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The five reader buttons; Reply All only when [replyAll] says it differs from Reply. */
@Composable
internal fun ReadActions(
    replyAll: Boolean,
    forwardEnabled: Boolean,
    onAi: () -> Unit,
    onReply: () -> Unit,
    onReplyAll: () -> Unit,
    onForward: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton("AI reply", Lucide.Sparkle, onAi)
        ActionButton("Reply", Lucide.Reply, onReply)
        if (replyAll) ActionButton("Reply all", Lucide.ReplyAll, onReplyAll)
        ActionButton("Forward", Lucide.Forward, onForward, enabled = forwardEnabled)
        OutlinedButton(onClick = onMore) {
            Icon(Lucide.Ellipsis, null, Modifier.size(16.dp))
            Text("More", Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled) {
        Icon(icon, null, Modifier.size(16.dp))
        Text(label, Modifier.padding(start = 6.dp))
    }
}

/** The translation under the buttons, in whichever state it is. */
@Composable
internal fun TranslationPanel(state: TranslationState, onRetry: (String) -> Unit, onDismiss: () -> Unit) {
    when (state) {
        TranslationState.Idle -> Unit
        is TranslationState.Busy -> Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Translating to ${state.language}…", style = MaterialTheme.typography.bodySmall)
        }
        is TranslationState.Done -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Languages, null, Modifier.size(14.dp), tint = Muted)
                Text("Translation · ${state.language}", style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(start = 6.dp).weight(1f))
                IconButton(onClick = onDismiss) { Icon(Lucide.X, "Close translation", Modifier.size(18.dp)) }
            }
            SelectionContainer { Text(state.text, style = MaterialTheme.typography.bodyMedium) }
        }
        is TranslationState.Same -> Row(verticalAlignment = Alignment.CenterVertically) {
            Banner("Already in ${state.language}", MaterialTheme.colorScheme.secondary)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
        is TranslationState.Working -> Column {
            Banner("The server is still translating this and did not answer in time. Try again in a moment; a finished translation is kept.", com.thomaspeoples.wraithodysseus.ui.theme.OdyColors.Warn)
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { onRetry(state.language) }) { Text("Retry") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
        is TranslationState.Failed -> Column {
            Banner("Could not translate: ${state.message}", MaterialTheme.colorScheme.error)
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { onRetry(state.language) }) { Text("Retry") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/**
 * Attachments as the web lists them: real files first, "from earlier in this
 * thread" next, and the signature-like images (see [isLikelySignatureImage])
 * folded away unless asked for. A calendar invite also gets an add-to-calendar
 * button.
 */
@Composable
internal fun AttachmentsBlock(
    attachments: List<EmailAttachment>,
    related: List<EmailAttachment>,
    loading: Boolean,
    busyKey: String?,
    onOpen: (EmailAttachment) -> Unit,
    onCalendar: (EmailAttachment) -> Unit,
    keyOf: (EmailAttachment) -> String,
) {
    val visible = attachments.filterNot(::isLikelySignatureImage)
    val hidden = attachments.filter(::isLikelySignatureImage)
    val earlier = related.filterNot(::isLikelySignatureImage)
    var showHidden by rememberSaveable { mutableStateOf(false) }
    if (loading) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text("Checking attachments…", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(start = 8.dp))
        }
        return
    }
    if (visible.isEmpty() && earlier.isEmpty() && hidden.isEmpty()) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (visible.isNotEmpty()) {
            SectionLabel("Attachments (${visible.size})")
            visible.forEach { AttachmentRow(it, keyOf(it) == busyKey, onOpen, onCalendar) }
        }
        if (earlier.isNotEmpty()) {
            SectionLabel("From earlier in this thread")
            earlier.forEach { AttachmentRow(it, keyOf(it) == busyKey, onOpen, onCalendar) }
        }
        if (hidden.isNotEmpty()) {
            Row(Modifier.clickable { showHidden = !showHidden }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Filtered inline images / signature files (${hidden.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
                Icon(if (showHidden) Lucide.ChevronUp else Lucide.ChevronDown, null, Modifier.padding(start = 4.dp).size(14.dp), tint = Muted)
            }
            if (showHidden) hidden.forEach { AttachmentRow(it, keyOf(it) == busyKey, onOpen, onCalendar) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.Paperclip, null, Modifier.size(14.dp), tint = Muted)
        Text(text, style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun AttachmentRow(a: EmailAttachment, busy: Boolean, onOpen: (EmailAttachment) -> Unit, onCalendar: (EmailAttachment) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !busy) { onOpen(a) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.filename, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(attachmentSize(a.size), style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            if (isCalendarFile(a.filename)) {
                IconButton(onClick = { onCalendar(a) }) { Icon(Lucide.CalendarPlus, "Add to calendar", Modifier.size(20.dp)) }
            }
            Icon(Lucide.Download, "Open", Modifier.size(18.dp), tint = Muted)
        }
    }
}
