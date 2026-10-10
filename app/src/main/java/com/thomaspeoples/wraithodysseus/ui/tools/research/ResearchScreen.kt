package com.thomaspeoples.wraithodysseus.ui.tools.research

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaspeoples.wraithodysseus.ui.common.ActionSheet
import com.thomaspeoples.wraithodysseus.ui.common.ConfirmDialog
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.Markdown
import com.thomaspeoples.wraithodysseus.ui.common.SheetAction
import com.thomaspeoples.wraithodysseus.ui.common.TopLevelBar
import com.thomaspeoples.wraithodysseus.ui.common.dateBucket
import com.thomaspeoples.wraithodysseus.ui.common.openInBrowser
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.tools.ToolEnv
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.thomaspeoples.wraithodysseus.ui.theme.themeBackground

/**
 * Deep Research, as the web UI's panel: the start form (question, rounds,
 * format, search engine and, for an admin, model), the running jobs with live
 * progress, the history of finished reports, and for a report its sources, the
 * visual HTML report, "chat about this" and delete. Gated on the
 * can_use_research privilege and on the admin's Deep Research switch. The
 * web's client-side queue and bulk select are left to the web UI.
 */
@Composable
fun ResearchScreen(env: ToolEnv) {
    val vm = viewModel {
        ResearchViewModel(
            ResearchApi(env.http),
            allowed = env.status?.allows("can_use_research") ?: true,
            isAdmin = env.status?.isAdmin == true,
        )
    }
    val state = vm.state
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // Follows the running jobs for exactly as long as this screen is on show.
    LaunchedEffect(vm) { vm.watch() }

    val openLink = { url: String ->
        if (!openInBrowser(context, url)) scope.launch { snackbar.showSnackbar("No browser to open the link") }
        Unit
    }

    val report = state.report
    val detail = state.detail
    when {
        report != null -> ReportPage(report, onBack = vm::closeReport, onLink = openLink)
        detail != null -> DetailPage(
            detail = detail,
            spinning = state.spinning,
            onBack = vm::closeDetail,
            onReport = { vm.openReport(detail.item.id, detail.item.query) },
            onChat = { vm.spinoff(detail.item.id, env.openChat) },
            onDelete = { vm.delete(detail.item.id) },
            onLink = openLink,
        )
        else -> MainPage(
            env, vm, state,
            onReport = { vm.openReport(it.id, it.query) },
            onChat = { vm.spinoff(it.id, env.openChat) },
        )
    }
}

@Composable
private fun MainPage(env: ToolEnv, vm: ResearchViewModel, state: ResearchState, onReport: (LibraryItem) -> Unit, onChat: (LibraryItem) -> Unit) {
    Column(Modifier.fillMaxSize().themeBackground(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        TopLevelBar("Deep Research", env.onMenu)
        val closed = state.closed
        when {
            state.checking -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            closed != null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Lucide.ZoomIn, null, tint = Muted, modifier = Modifier.size(40.dp))
                    Text(closed, textAlign = TextAlign.Center)
                }
            }
            else -> {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ResearchTab.entries) { tab ->
                        val count = if (tab == ResearchTab.Active) state.jobs.size.takeIf { it > 0 } else null
                        FilterChip(
                            selected = state.tab == tab,
                            onClick = { vm.setTab(tab) },
                            label = { Text(if (count != null) "${tab.label} $count" else tab.label) },
                        )
                    }
                }
                when (state.tab) {
                    ResearchTab.Research -> StartTab(vm, state)
                    ResearchTab.Active -> ActiveTab(vm, state)
                    ResearchTab.History -> HistoryTab(vm, state, onReport, onChat)
                }
            }
        }
    }
}

@Composable
private fun StartTab(vm: ResearchViewModel, state: ResearchState) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val s = state.settings
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Multi-step web research with an LLM-in-the-loop agent", color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            placeholder = { Text("Set sail on a question. Odysseus will chart the course.") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { settingsOpen = !settingsOpen }) {
            Icon(Lucide.Settings, null, modifier = Modifier.size(16.dp))
            Text("  Settings")
            Icon(if (settingsOpen) Lucide.ChevronUp else Lucide.ChevronDown, null, modifier = Modifier.size(16.dp))
        }
        if (settingsOpen) {
            Picker(
                "Rounds",
                if (s.maxRounds == 0) "Auto" else s.maxRounds.toString(),
                ROUND_CHOICES.map { it to if (it == 0) "Auto" else it.toString() },
                { vm.setSettings(s.copy(maxRounds = it)) },
            )
            Picker(
                "Format",
                CATEGORY_LABELS[s.category] ?: "Auto",
                CATEGORY_LABELS.toList(),
                { vm.setSettings(s.copy(category = it)) },
            )
            Picker(
                "Search engine",
                s.searchProvider.ifBlank { "Default" },
                SEARCH_PROVIDERS.map { it to it.ifBlank { "Default" } },
                { vm.setSettings(s.copy(searchProvider = it)) },
            )
            if (state.models.isNotEmpty()) {
                val choices: List<Pair<ModelChoice?, String>> = listOf<Pair<ModelChoice?, String>>(null to "Default") + state.models.map { it to it.label }
                Picker(
                    "Model",
                    state.models.firstOrNull { it.model == s.model }?.label ?: "Default",
                    choices,
                    { vm.setSettings(s.copy(model = it?.model.orEmpty(), endpointId = it?.endpointId.orEmpty())) },
                )
            }
        }
        Button(onClick = vm::start, enabled = state.query.isNotBlank() && !state.starting, modifier = Modifier.fillMaxWidth()) {
            if (state.starting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  Starting")
            } else {
                Icon(Lucide.Play, null, modifier = Modifier.size(16.dp))
                Text("  Start")
            }
        }
    }
}

@Composable
private fun ActiveTab(vm: ResearchViewModel, state: ResearchState) {
    var cancelling by remember { mutableStateOf<LiveJob?>(null) }
    // Ticks only while this tab is on show.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    if (state.jobs.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Nothing running", color = Muted) }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.jobs, key = { it.id }) { job ->
                JobCard(
                    job, now,
                    onCancel = { cancelling = job },
                    onRetry = { vm.retry(job) },
                    onEdit = { vm.edit(job) },
                    onDismiss = { vm.dismiss(job) },
                )
            }
        }
    }
    cancelling?.let { job ->
        ConfirmDialog(
            title = "Cancel this research?",
            text = job.query.take(120),
            confirm = "Cancel research",
            onConfirm = { vm.cancel(job) },
            onDismiss = { cancelling = null },
        )
    }
}

/** A history row: [header] is set on the first row of a day. */
private class HistoryRow(val header: String?, val item: LibraryItem)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryTab(vm: ResearchViewModel, state: ResearchState, onReport: (LibraryItem) -> Unit, onChat: (LibraryItem) -> Unit) {
    var sheet by remember { mutableStateOf<LibraryItem?>(null) }
    var deleting by remember { mutableStateOf<LibraryItem?>(null) }
    val visible = state.history
    val categories = remember(state.library) { historyCategories(state.library.filter { it.status == "done" }) }
    // Grouped by day, as the web's lists are, when the order is by time.
    val rows = remember(visible, state.sort) {
        if (state.sort != HistorySort.Recent) {
            visible.map { HistoryRow(null, it) }
        } else {
            val now = Instant.now()
            var last: String? = null
            visible.map { item ->
                val bucket = dateBucket(item.completedAt.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it.toLong()) }, now)
                HistoryRow(bucket.takeIf { it != last }, item).also { last = bucket }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.search,
            onValueChange = vm::setSearch,
            placeholder = { Text("Search research") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            item {
                Picker("Sort", state.sort.label, HistorySort.entries.map { it to it.label }, vm::setSort, Modifier.padding(end = 4.dp))
            }
            if (categories.size > 1) {
                item { FilterChip(selected = state.category == null, onClick = { vm.setCategory(null) }, label = { Text("All ${categories.sumOf { it.second }}") }) }
                items(categories) { (cat, n) ->
                    FilterChip(selected = state.category == cat, onClick = { vm.setCategory(if (state.category == cat) null else cat) }, label = { Text("${categoryName(cat)} $n") })
                }
            }
        }
        PullToRefreshBox(isRefreshing = state.libraryRefreshing, onRefresh = { vm.loadLibrary(pull = true) }, modifier = Modifier.fillMaxSize()) {
            val problem = state.libraryProblem
            when {
                state.libraryLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                problem != null -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text(problem, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.loadLibrary() }) { Text("Try again") }
                }
                visible.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(if (state.library.isEmpty()) "No research yet" else "No matching research", color = Muted)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    rows.forEach { row ->
                        row.header?.let { h ->
                            item(key = "h-${row.item.id}") { Text(h, color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp)) }
                        }
                        item(key = row.item.id) { HistoryCard(row.item, onOpen = { vm.open(row.item) }, onMenu = { sheet = row.item }) }
                    }
                }
            }
        }
    }

    sheet?.let { item ->
        val actions = listOf(
            SheetAction("Read", Lucide.FileText) { vm.open(item) },
            SheetAction("Visual report", Lucide.ExternalLink) { onReport(item) },
            SheetAction("Chat about this", Lucide.MessageSquare) { onChat(item) },
            SheetAction("Delete from server", Lucide.Trash2, destructive = true) { deleting = item },
        )
        ActionSheet(actions, onDismiss = { sheet = null }, title = item.query.take(80))
    }
    deleting?.let { item ->
        ConfirmDialog(
            title = "Delete this research?",
            text = "This permanently removes it from the server.",
            confirm = "Delete",
            onConfirm = { vm.delete(item.id) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun DetailPage(
    detail: DetailState,
    spinning: Boolean,
    onBack: () -> Unit,
    onReport: () -> Unit,
    onChat: () -> Unit,
    onDelete: () -> Unit,
    onLink: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var allSources by rememberSaveable { mutableStateOf(false) }
    val result = detail.result
    val problem = detail.problem

    Column(Modifier.fillMaxSize().themeBackground(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, "Back") }
            Text(detail.item.query, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { confirmDelete = true }) { Icon(Lucide.Trash2, "Delete", tint = Muted) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onReport) { Text("Visual report") }
            OutlinedButton(onClick = onChat, enabled = !spinning) {
                if (spinning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Lucide.MessageSquare, null, modifier = Modifier.size(16.dp))
                Text("  Chat")
            }
            if (result != null) {
                IconButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("research", copyText(detail.item.query, result)))
                    scope.launch { snackbar.showSnackbar("Copied") }
                }) { Icon(Lucide.Copy, "Copy report", tint = Muted) }
            }
        }
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            when {
                problem != null -> Text(problem, color = MaterialTheme.colorScheme.error)
                result == null -> CircularProgressIndicator()
                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val category = result.category.ifBlank { detail.item.category }
                    if (category.isNotBlank()) Text(categoryName(category), color = Muted, style = MaterialTheme.typography.labelMedium)
                    if (result.sources.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Sources", style = MaterialTheme.typography.titleSmall)
                            (if (allSources) result.sources else result.sources.take(SOURCES_SHOWN)).forEach { SourceLine(it, onLink) }
                            if (result.sources.size > SOURCES_SHOWN) {
                                TextButton(onClick = { allSources = !allSources }) {
                                    Text(if (allSources) "Show fewer" else "+${result.sources.size - SOURCES_SHOWN} more")
                                }
                            }
                        }
                    }
                    if (result.sourceState.isNotBlank()) Text(result.sourceState, color = Muted, style = MaterialTheme.typography.bodySmall)
                    TraceList(result.actionTrace, result.navigationTrace)
                    if (result.result.isBlank()) Text("(empty)", color = Muted) else Markdown(result.result)
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this research?",
            text = "This permanently removes it from the server.",
            confirm = "Delete",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}

private const val SOURCES_SHOWN = 10

@Composable
private fun SourceLine(s: Source, onLink: (String) -> Unit) {
    val href = safeHref(s.url)
    val title = s.title.ifBlank { s.url }
    Column(Modifier.fillMaxWidth().let { if (href != null) it.clickable { onLink(href) } else it }.padding(vertical = 2.dp)) {
        Text(
            title,
            color = if (href != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (href != null) TextDecoration.Underline else null,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        sourceBadges(s).takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun ReportPage(report: ReportState, onBack: () -> Unit, onLink: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val html = report.html
    val problem = report.problem
    Column(Modifier.fillMaxSize().themeBackground(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, "Back") }
            Text(report.query, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            problem != null -> Box(Modifier.fillMaxSize().padding(16.dp)) { Text(problem, color = MaterialTheme.colorScheme.error) }
            html == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            else -> ReportView(html, onLink, Modifier.fillMaxSize())
        }
    }
}
