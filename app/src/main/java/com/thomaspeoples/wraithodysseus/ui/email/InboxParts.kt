package com.thomaspeoples.wraithodysseus.ui.email

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.email.EmailAccount
import com.thomaspeoples.wraithodysseus.email.EmailSummary
import com.thomaspeoples.wraithodysseus.email.InboxFilter
import com.thomaspeoples.wraithodysseus.email.InboxFilters
import com.thomaspeoples.wraithodysseus.email.SCHEDULED_FOLDER
import com.thomaspeoples.wraithodysseus.email.ScheduledEmail
import com.thomaspeoples.wraithodysseus.email.folderDisplayName
import com.thomaspeoples.wraithodysseus.email.isSentFolder
import com.thomaspeoples.wraithodysseus.email.rowName
import com.thomaspeoples.wraithodysseus.email.senderHue
import com.thomaspeoples.wraithodysseus.ui.common.parseServerTime
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The web's per-sender colour: hue from the name, fixed saturation and lightness. */
internal fun senderColor(name: String): Color = Color.hsl(senderHue(name).toFloat(), 0.55f, 0.65f)

/** The account chips: the virtual "All · Unread", then each account with its
 * INBOX unread count. */
@Composable
internal fun AccountChips(
    accounts: List<EmailAccount>,
    allUnread: Boolean,
    selected: String?,
    unread: Map<String, Int>,
    onAll: () -> Unit,
    onAccount: (String) -> Unit,
) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FilterChip(
                selected = allUnread,
                onClick = onAll,
                label = { Text("All · Unread" + (unread.values.sum().takeIf { it > 0 }?.let { " ($it)" } ?: "")) },
            )
        }
        items(accounts, key = { it.id }) { account ->
            FilterChip(
                selected = !allUnread && selected == account.id,
                onClick = { onAccount(account.id) },
                label = { Text(account.name.ifBlank { account.fromAddress } + (unread[account.id]?.takeIf { it > 0 }?.let { " ($it)" } ?: "")) },
            )
        }
    }
}

/** The folder and filter pickers, side by side. */
@Composable
internal fun Pickers(
    folders: List<String>,
    folder: String,
    filter: String,
    onFolder: (String) -> Unit,
    onFilter: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker(folderDisplayName(folder), Modifier.weight(1f)) { close ->
            (folders + SCHEDULED_FOLDER).forEach { f ->
                DropdownMenuItem(text = { Text(folderDisplayName(f)) }, onClick = { close(); onFolder(f) })
            }
        }
        if (folder != SCHEDULED_FOLDER) {
            Picker(InboxFilters.label(filter), Modifier.weight(1f)) { close ->
                InboxFilters.STATUS.forEach { f -> FilterItem(f, close, onFilter) }
                HorizontalDivider()
                Text("Tags", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                InboxFilters.TAGS.forEach { f -> FilterItem(f, close, onFilter) }
            }
        }
    }
}

@Composable
private fun FilterItem(f: InboxFilter, close: () -> Unit, onPick: (String) -> Unit) =
    DropdownMenuItem(text = { Text(f.label) }, onClick = { close(); onPick(f.value) })

@Composable
private fun Picker(label: String, modifier: Modifier, items: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Lucide.ChevronDown, null, tint = Muted, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

@Composable
internal fun DateHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = Muted,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * One row. A tap opens (or, while selecting, ticks) and a long press starts
 * selecting, as the web does on touch. The bookmark is Favorite and the check
 * is Done; Sent mail has no Done, and shows who it went to.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EmailRow(
    email: EmailSummary,
    account: String?,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavorite: () -> Unit,
    onDone: () -> Unit,
    onMore: () -> Unit,
) {
    val sent = isSentFolder(email.folder)
    val name = rowName(email, sent)
    val color = senderColor(name)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = if (selecting) 16.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            Box(
                Modifier.size(8.dp).background(if (showUnreadDot(email.isRead, email.isAnswered, sent)) color else Color.Transparent, CircleShape)
                    .semantics { if (showUnreadDot(email.isRead, email.isAnswered, sent)) contentDescription = "Unread" },
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (email.isRead) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (email.hasAttachments) {
                    Icon(Lucide.Paperclip, "Has attachments", tint = Muted, modifier = Modifier.padding(start = 6.dp).size(13.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(listDate(email.dateEpoch), style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            Text(email.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            account?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
        }
        if (!selecting) {
            val on = MaterialTheme.colorScheme.primary
            IconButton(onClick = onFavorite, modifier = Modifier.size(48.dp)) {
                Icon(Lucide.Bookmark, if (email.isFlagged) "Unfavorite" else "Favorite", tint = if (email.isFlagged) on else Muted, modifier = Modifier.size(18.dp))
            }
            if (!sent) {
                IconButton(onClick = onDone, modifier = Modifier.size(48.dp)) {
                    Icon(Lucide.Check, if (email.isAnswered) "Mark not done" else "Mark done", tint = if (email.isAnswered) on else Muted, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onMore, modifier = Modifier.size(48.dp)) {
                Icon(Lucide.EllipsisVertical, "More", tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** A scheduled send: not a message on the server yet, so only Cancel applies. */
@Composable
internal fun ScheduledRow(item: ScheduledEmail, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val failed = item.status == "failed"
                Text(if (failed) "FAILED" else "PENDING", style = MaterialTheme.typography.labelSmall, color = if (failed) MaterialTheme.colorScheme.error else Muted)
            }
            Text("To: ${item.to.ifBlank { "(no recipient)" }} · Sends ${sendTime(item.sendAt)}", style = MaterialTheme.typography.labelSmall, color = Muted)
            if (item.error.isNotBlank()) Text(item.error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        IconButton(onClick = onCancel) { Icon(Lucide.X, "Cancel scheduled send", tint = Muted) }
    }
}

private val SEND_TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

/** The server's send_at, which is naive UTC, in the phone's zone. */
internal fun sendTime(sendAt: String, zone: ZoneId = ZoneId.systemDefault()): String =
    parseServerTime(sendAt)?.atZone(zone)?.format(SEND_TIME) ?: sendAt

/** The "..." menu of a search: narrowing the results already on screen. */
@Composable
internal fun SearchMenu(
    options: com.thomaspeoples.wraithodysseus.email.SearchOptions,
    onChange: (com.thomaspeoples.wraithodysseus.email.SearchOptions) -> Unit,
    context: Context,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Lucide.Ellipsis, "Search options", tint = if (options.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (options.notDone) "✓ Not done" else "Not done") },
                onClick = { open = false; onChange(options.copy(notDone = !options.notDone)) },
            )
            DropdownMenuItem(
                text = { Text(if (options.attachments) "✓ Attachments" else "Attachments") },
                onClick = { open = false; onChange(options.copy(attachments = !options.attachments)) },
            )
            DropdownMenuItem(
                text = { Text("From: ${options.from ?: "any"}") },
                onClick = { open = false; pickDate(context, options.from) { onChange(options.copy(from = it)) } },
            )
            DropdownMenuItem(
                text = { Text("To: ${options.to ?: "any"}") },
                onClick = { open = false; pickDate(context, options.to) { onChange(options.copy(to = it)) } },
            )
            if (options.from != null || options.to != null) {
                DropdownMenuItem(text = { Text("Clear dates") }, onClick = { open = false; onChange(options.copy(from = null, to = null)) })
            }
        }
    }
}

internal fun pickDate(context: Context, initial: LocalDate?, onPick: (LocalDate) -> Unit) {
    val d = initial ?: LocalDate.now()
    DatePickerDialog(context, { _, y, m, day -> onPick(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
}

/** The date, then the time: Android has no single date-and-time picker. */
internal fun pickDateTime(context: Context, initial: LocalDateTime, onPick: (LocalDateTime) -> Unit) {
    pickDate(context, initial.toLocalDate()) { date ->
        TimePickerDialog(context, { _, h, min -> onPick(date.atTime(h, min)) }, initial.hour, initial.minute, true).show()
    }
}

/** The web's rule (emailInbox.js): a dot only for mail that is neither read nor marked done, and never in Sent. */
internal fun showUnreadDot(isRead: Boolean, isAnswered: Boolean, sent: Boolean): Boolean = !isRead && !isAnswered && !sent
