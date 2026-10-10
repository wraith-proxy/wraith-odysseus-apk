package com.thomaspeoples.wraithodysseus.ui.email

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaspeoples.wraithodysseus.email.EmailApi
import com.thomaspeoples.wraithodysseus.email.EmailSummary
import com.thomaspeoples.wraithodysseus.email.ScheduledEmail
import com.thomaspeoples.wraithodysseus.email.groupByDate
import com.thomaspeoples.wraithodysseus.email.isSentFolder
import com.thomaspeoples.wraithodysseus.email.lastSynced
import com.thomaspeoples.wraithodysseus.email.ReminderPreset
import com.thomaspeoples.wraithodysseus.email.reminderPresets
import com.thomaspeoples.wraithodysseus.ui.common.ActionSheet
import com.thomaspeoples.wraithodysseus.ui.common.ConfirmDialog
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.SheetAction
import com.thomaspeoples.wraithodysseus.ui.common.openInBrowser
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.thomaspeoples.wraithodysseus.ui.theme.themeBackground

/** How often the unread counts are asked for while the screen is visible. */
private const val POLL_MS = 60_000L

/** How often "Last: ..." is redrawn, so "just now" does not stay put. */
private const val TICK_MS = 30_000L

/**
 * The web UI's inbox: account chips, folders, filters, date groups, per-row
 * actions, select mode and search. All state lives in [InboxViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(api: EmailApi, onOpen: (EmailSummary) -> Unit, onCompose: () -> Unit, menu: @Composable () -> Unit = {}) {
    val vm = viewModel { InboxViewModel(api) }
    val state = vm.state
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // Rows are not Parcelable: the dialogs and sheets are held by row key so
    // they survive rotation, and looked up in the list again.
    var sheetKey by rememberSaveable { mutableStateOf<String?>(null) }
    var remindKey by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var cancellingId by rememberSaveable { mutableStateOf<String?>(null) }
    var bulkDelete by rememberSaveable { mutableStateOf(false) }
    fun row(k: String?) = k?.let { key -> state.visible.firstOrNull { it.key == key } }
    val sheet = row(sheetKey)
    val remind = row(remindKey)
    val deleting = row(deletingKey)
    val cancelling = state.scheduled.firstOrNull { it.id == cancellingId }
    var now by remember { mutableStateOf(Instant.now()) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Unread counts: once when the screen comes back, then every minute for
    // as long as it stays visible. Leaving it cancels the loop.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, vm) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            synchronized(ReadHandoff.removed) {
                if (ReadHandoff.removed.isNotEmpty()) vm.drop(ReadHandoff.removed.toSet())
                ReadHandoff.removed.clear()
            }
            while (true) {
                vm.poll()
                delay(POLL_MS)
            }
        }
    }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = Instant.now()
                delay(TICK_MS)
            }
        }
    }

    BackHandler(enabled = state.selecting) { vm.stopSelecting() }
    BackHandler(enabled = searchOpen && !state.selecting) {
        searchOpen = false
        vm.closeSearch()
    }

    val searchable = !state.allUnread && !state.isScheduled
    val groups = remember(state.visible, now.epochSecond / 60) { groupByDate(state.visible, now) }
    val sent = isSentFolder(state.folder)

    Scaffold(
        modifier = Modifier.themeBackground(MaterialTheme.colorScheme.background),
        containerColor = Color.Transparent,
        topBar = {
            if (state.selecting) {
                TopAppBar(
                    title = { Text("${state.selected.size} selected") },
                    navigationIcon = { IconButton(onClick = vm::stopSelecting) { Icon(Lucide.X, "Cancel selection") } },
                    actions = { TextButton(onClick = vm::selectAll) { Text("Select all") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            } else {
                TopAppBar(
                    title = {
                        if (searchOpen) {
                            TextField(
                                value = state.query,
                                onValueChange = vm::setQuery,
                                placeholder = { Text("Search mail") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Column {
                                Text(headline(state), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                lastSynced(state.updatedAt, now).takeIf { it.isNotEmpty() && !state.isScheduled }?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = Muted)
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    actions = {
                        if (searchOpen) {
                            SearchMenu(state.options, vm::setOptions, context)
                            IconButton(onClick = { searchOpen = false; vm.closeSearch() }) { Icon(Lucide.X, "Close search") }
                        } else {
                            if (searchable) IconButton(onClick = { searchOpen = true }) { Icon(Lucide.Search, "Search") }
                            IconButton(onClick = onCompose) { Icon(Lucide.Pencil, "Compose") }
                            // Email settings (writing style, auto-reply, cleanup) are
                            // not in the app; the web UI has them.
                            IconButton(onClick = {
                                if (!openInBrowser(context, api.origin())) scope.launch { snackbar.showSnackbar("No browser to open it with") }
                            }) { Icon(Lucide.Settings, "Email settings in the web UI") }
                            menu()
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (state.selecting) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BulkButton("Done", Lucide.Check) { vm.bulk(BulkAction.Done) }
                    BulkButton("Read", Lucide.MailOpen) { vm.bulk(BulkAction.Read) }
                    BulkButton("Unread", Lucide.Mail) { vm.bulk(BulkAction.Unread) }
                    BulkButton("Delete", Lucide.Trash2, destructive = true) { bulkDelete = true }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.searchBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.problem?.let { Banner(it, MaterialTheme.colorScheme.error) }
            state.errors.forEach { Banner(it, MaterialTheme.colorScheme.error) }

            if (!state.noAccounts && (state.accounts.isNotEmpty())) {
                AccountChips(state.accounts, state.allUnread, state.accountId, state.unread, vm::selectAllUnread, vm::selectAccount)
            }
            if (!state.allUnread && !state.noAccounts && !searchOpen) {
                Pickers(state.folders, state.folder, state.filter, vm::selectFolder, vm::selectFilter)
            }

            val empty = if (state.isScheduled) state.scheduled.isEmpty() else state.visible.isEmpty()
            when {
                state.noAccounts -> Centered("No enabled email accounts. Add or enable one in the web UI.")
                state.loaded && state.problem == null && !state.searchBusy && empty -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.reload(pull = true) }, modifier = Modifier.fillMaxSize()) {
                    // Scrollable, so a pull still reaches the box when the list is empty.
                    LazyColumn(Modifier.fillMaxSize()) { item { Box(Modifier.fillParentMaxSize()) { Centered(emptyText(state)) } } }
                }
                else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.reload(pull = true) }, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (state.isScheduled) {
                            items(state.scheduled, key = { it.id }) { item ->
                                ScheduledRow(item, onCancel = { cancellingId = item.id })
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            }
                        } else {
                            groups.forEach { group ->
                                item(key = "group-${group.label}") { DateHeader(group.label) }
                                items(group.emails, key = { it.key }) { email ->
                                    EmailRow(
                                        email = email,
                                        account = if (state.accounts.size > 1 && state.allUnread) state.accountNames[email.accountId] else null,
                                        selecting = state.selecting,
                                        selected = email.key in state.selected,
                                        onClick = {
                                            if (state.selecting) vm.toggleSelected(email)
                                            else {
                                                vm.opened(email)
                                                ReadHandoff.siblings = groups.flatMap { it.emails }
                                                onOpen(email)
                                            }
                                        },
                                        onLongClick = { if (state.selecting) vm.toggleSelected(email) else vm.startSelecting(email) },
                                        onFavorite = { vm.toggleFavorite(email) },
                                        onDone = { vm.toggleDone(email) },
                                        onMore = { sheetKey = email.key },
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                }
                            }
                            if (state.found == null && !state.allUnread && state.emails.size < state.total) {
                                item(key = "more") {
                                    LaunchedEffect(state.emails.size) { vm.loadMore() }
                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    sheet?.let { email ->
        val actions = buildList {
            add(SheetAction("Open", Lucide.MailOpen) { vm.opened(email); ReadHandoff.siblings = groups.flatMap { it.emails }; onOpen(email) })
            add(SheetAction("Remind to reply", Lucide.AlarmClock) { remindKey = email.key })
            if (!sent) add(SheetAction(if (email.isAnswered) "Not done" else "Done", Lucide.Check) { vm.toggleDone(email) })
            add(SheetAction(if (email.isFlagged) "Unfavorite" else "Favorite", Lucide.Bookmark) { vm.toggleFavorite(email) })
            add(SheetAction("Archive", Lucide.Archive) { vm.archive(email) })
            add(SheetAction("Select", Lucide.ListChecks) { vm.startSelecting(email) })
            add(SheetAction("Delete", Lucide.Trash2, destructive = true) { deletingKey = email.key })
        }
        ActionSheet(actions, onDismiss = { sheetKey = null }, title = email.subject.ifBlank { "(no subject)" })
    }

    remind?.let { email ->
        val presets = reminderPresets(LocalDateTime.now(ZoneId.systemDefault()))
        val actions = presets.map { p ->
            SheetAction(presetLabel(p), Lucide.AlarmClock) { vm.remind(email, p.due) }
        } + SheetAction("Pick date…", Lucide.CalendarPlus) {
            pickDateTime(context, presets[1].due) { vm.remind(email, it) }
        }
        ActionSheet(actions, onDismiss = { remindKey = null }, title = "Remind me")
    }

    deleting?.let { email ->
        ConfirmDialog(
            title = "Delete this email?",
            text = email.subject.ifBlank { "(no subject)" },
            confirm = "Delete",
            onConfirm = { vm.delete(email) },
            onDismiss = { deletingKey = null },
        )
    }
    if (bulkDelete) {
        ConfirmDialog(
            title = "Delete ${state.selected.size} emails?",
            text = "They move to Trash.",
            confirm = "Delete",
            onConfirm = { vm.bulk(BulkAction.Delete) },
            onDismiss = { bulkDelete = false },
        )
    }
    cancelling?.let { item ->
        ConfirmDialog(
            title = "Cancel this scheduled email?",
            text = item.subject.ifBlank { "(no subject)" },
            confirm = "Cancel send",
            onConfirm = { vm.cancelScheduled(item) },
            onDismiss = { cancellingId = null },
        )
    }
}

/** "Tomorrow \u00b7 Sat 10 Oct, 8:00 AM"; today's own time needs no date. */
private fun presetLabel(p: ReminderPreset, today: LocalDate = LocalDate.now()): String {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(p.due)
    val day = if (p.due.toLocalDate() == today) "" else DateTimeFormatter.ofPattern("EEE d MMM, ").format(p.due)
    return "${p.label} \u00b7 $day$time"
}

/** "N emails" for a list, "N scheduled" for that folder. */
internal fun headline(state: InboxState): String = when {
    state.isScheduled -> "${state.scheduled.size} scheduled"
    !state.loaded && state.found == null -> "Email"
    else -> state.visible.size.let { n -> if (state.found != null) n else maxOf(state.total, n) }.let { "$it ${if (it == 1) "email" else "emails"}" }
}

private fun emptyText(state: InboxState) = when {
    state.found != null -> "No matches"
    state.isScheduled -> "No scheduled emails"
    state.allUnread -> "No unread mail"
    else -> "No emails"
}

@Composable
private fun BulkButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        Icon(icon, null, tint = tint, modifier = Modifier.padding(end = 6.dp))
        Text(label, color = tint)
    }
}

/** A full-width notice strip, tinted with [color]. */
@Composable
internal fun Banner(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(color.copy(alpha = 0.1f), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Time for today, day and month this year, full date before that. */
internal fun listDate(epochSeconds: Double, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val date = Instant.ofEpochMilli((epochSeconds * 1000).toLong()).atZone(zone)
    val today = now.atZone(zone)
    val pattern = when {
        date.toLocalDate() == today.toLocalDate() -> "HH:mm"
        date.year == today.year -> "d MMM"
        else -> "d MMM yyyy"
    }
    return date.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
}
