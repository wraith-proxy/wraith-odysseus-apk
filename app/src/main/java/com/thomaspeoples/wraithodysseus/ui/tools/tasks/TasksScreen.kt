package com.thomaspeoples.wraithodysseus.ui.tools.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaspeoples.wraithodysseus.ui.common.ActionSheet
import com.thomaspeoples.wraithodysseus.ui.common.ConfirmDialog
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.SheetAction
import com.thomaspeoples.wraithodysseus.ui.common.TopLevelBar
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.tools.ToolEnv
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.thomaspeoples.wraithodysseus.ui.theme.themeBackground

/**
 * The web UI's Tasks panel: the list with pause, resume, run now, run
 * history and delete, and a natural-language "New task" that is parsed first
 * and only created once the person has read what the server understood.
 * Editing a task and the event/webhook/action task forms stay in the web UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(env: ToolEnv) {
    val vm = viewModel { TasksViewModel(TasksApi(env.http)) }
    val api = remember(env.http) { TasksApi(env.http) }
    val snackbar = LocalSnackbar.current
    var sheet by remember { mutableStateOf<Task?>(null) }
    var deleting by remember { mutableStateOf<Task?>(null) }
    var composing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = vm.history != null) { vm.closeHistory() }
    // The server answers 403 to a non-admin saving an action that runs code on the host; with
    // auth switched off everyone is admin (task_action_policy.py owner_has_admin_task_privileges).
    val isAdmin = env.status?.let { it.isAdmin || !it.configured } == true

    Column(Modifier.fillMaxSize().themeBackground(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        val editing = vm.editing
        if (editing != null) {
            TaskEditor(editing, saving = vm.saving, onSave = vm::saveEdit, onCancel = vm::closeEdit, webhookUrl = api.webhookUrl(editing))
            return@Column
        }
        val history = vm.history
        if (history != null) {
            HistoryView(history, onBack = vm::closeHistory)
            return@Column
        }
        TopLevelBar("Tasks", onMenu = env.onMenu, actions = {
            IconButton(onClick = { composing = true }) { Icon(Lucide.Plus, "New task") }
        })
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskFilter.entries.forEach { f ->
                FilterChip(selected = vm.filter == f, onClick = { vm.filter = f }, label = { Text(f.label) })
            }
        }
        PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            val all = vm.tasks
            val rows = vm.visible
            when {
                all == null && vm.loadError != null -> Message(vm.loadError.orEmpty(), retry = vm::refresh)
                all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                rows.isEmpty() -> Message(if (all.isEmpty()) "No tasks yet." else "No matching tasks.")
                else -> {
                    val now = remember(all) { Instant.now() }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { it.id }) { t ->
                            TaskRow(t, now, busy = t.id in vm.busy, onClick = { sheet = t })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }

    sheet?.let { t ->
        val actions = buildList {
            if (t.canRun) add(SheetAction("Run now", Lucide.Play) { vm.runNow(t) })
            if (t.canEdit(isAdmin)) add(SheetAction("Edit", Lucide.Pencil) { vm.edit(t) })
            if (t.status == "active") add(SheetAction("Pause", Lucide.Square) { vm.pause(t) })
            if (t.status == "paused") add(SheetAction("Resume", Lucide.Play) { vm.resume(t) })
            add(SheetAction("History", Lucide.Clock) { vm.openHistory(t) })
            add(SheetAction("Delete", Lucide.Trash2, destructive = true) { deleting = t })
        }
        ActionSheet(actions, onDismiss = { sheet = null }, title = t.name)
    }
    deleting?.let { t ->
        ConfirmDialog(
            title = "Delete \"${t.name}\"?",
            confirm = "Delete",
            text = "Its run history goes with it.",
            onConfirm = { vm.delete(t) },
            onDismiss = { deleting = null },
        )
    }
    if (composing || vm.draft != null) {
        NewTaskDialog(vm, onClose = { composing = false; vm.discardDraft() }, onCreated = { composing = false })
    }
}

@Composable
private fun Message(text: String, retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Muted)
        retry?.let { TextButton(onClick = it) { Text("Retry") } }
    }
}

@Composable
private fun TaskRow(t: Task, now: Instant, busy: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (t.isBuiltin) Text("built-in", style = MaterialTheme.typography.labelSmall, color = Muted)
            if (t.status != "active") {
                Text(t.status, style = MaterialTheme.typography.labelMedium, color = if (t.status == "paused") MaterialTheme.colorScheme.tertiary else Muted)
            }
        }
        val meta = buildList {
            add(TasksApi.scheduleText(t, ZoneId.systemDefault(), now))
            if (t.nextRun != null && t.status == "active") add("Next: " + TasksApi.relativeTime(t.nextRun, now))
            if (t.runCount > 0) add("${t.runCount} run${if (t.runCount != 1) "s" else ""}")
        }
        Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted)
        t.lastRunResult?.takeIf { it.isNotBlank() }?.let {
            val failed = t.lastRunStatus == "error" || t.lastRunStatus == "failed"
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
private fun HistoryView(h: History, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, "Back") }
        Text("${h.task.name} — Run history", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val runs = h.runs
    when {
        runs == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        h.error != null -> Message(h.error)
        runs.isEmpty() -> Message("No runs yet.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(runs, key = { it.id }) { r -> RunRow(r); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
        }
    }
}

@Composable
private fun RunRow(r: TaskRun) {
    var expanded by rememberSaveable(r.id) { mutableStateOf(false) }
    val text = r.text
    val long = text.length > RESULT_PREVIEW
    Column(Modifier.fillMaxWidth().clickable(enabled = long) { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                r.status,
                style = MaterialTheme.typography.labelLarge,
                color = if (r.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            r.model?.let { Text(it.substringAfterLast('/'), style = MaterialTheme.typography.labelMedium, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
            Box(Modifier.weight(1f))
            Text(startedText(r), style = MaterialTheme.typography.labelMedium, color = Muted)
        }
        Text(
            if (long && !expanded) text.take(RESULT_PREVIEW) + "…" else text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private const val RESULT_PREVIEW = 300

private fun startedText(r: TaskRun): String {
    val at = com.thomaspeoples.wraithodysseus.ui.common.parseServerTime(r.startedAt) ?: return ""
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(at.atZone(ZoneId.systemDefault()))
}

/** Describe, then see what the server understood, then confirm. Nothing is created before the last step. */
@Composable
private fun NewTaskDialog(vm: TasksViewModel, onClose: () -> Unit, onCreated: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val draft = vm.draft
    if (draft == null) {
        AlertDialog(
            onDismissRequest = { if (!vm.parsing) onClose() },
            title = { Text("New task", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        text, { text = it }, enabled = !vm.parsing, minLines = 3,
                        placeholder = { Text("Every weekday at 7am, summarise the top AI news") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (vm.parsing) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { vm.parse(text) }, enabled = !vm.parsing && text.isNotBlank()) { Text("Understand") } },
            dismissButton = { TextButton(onClick = onClose, enabled = !vm.parsing) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
        return
    }
    val problem = remember(draft) { TasksApi.onceProblem(draft, java.time.ZoneId.systemDefault(), java.time.Instant.now()) }
    AlertDialog(
        onDismissRequest = { if (!vm.creating) vm.discardDraft() },
        title = { Text("Create this task?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                draft.name?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                Text(draftSummary(draft), color = Muted, style = MaterialTheme.typography.bodyMedium)
                Text(draft.prompt, style = MaterialTheme.typography.bodyMedium)
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (vm.creating) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { vm.confirmDraft(onCreated) }, enabled = !vm.creating && problem == null) { Text("Create") } },
        dismissButton = { TextButton(onClick = vm::discardDraft, enabled = !vm.creating) { Text("Back") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** The draft's schedule as the list will show it once created, plus where the result goes. */
internal fun draftSummary(d: TaskDraft): String {
    val schedule = when (d.schedule) {
        "cron" -> "Cron: ${d.cronExpression ?: "?"} (UTC)"
        "once" -> "Once on ${d.scheduledDate?.replace("T", " at ") ?: "?"}"
        "daily" -> "Daily at ${d.scheduledTime ?: "09:00"}"
        "weekly" -> "Weekly on ${DAY_NAMES.getOrElse(d.scheduledDay ?: 0) { "Monday" }} at ${d.scheduledTime ?: "09:00"}"
        "monthly" -> "Monthly on the ${TasksApi.ordinal(d.scheduledDay ?: 1)} at ${d.scheduledTime ?: "09:00"}"
        else -> d.schedule
    }
    val kind = if (d.taskType == "research") "Research" else "Prompt"
    val out = d.outputTarget?.takeIf { it != "session" }?.let { " · result by $it" }.orEmpty()
    return "$kind · $schedule$out"
}

private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
