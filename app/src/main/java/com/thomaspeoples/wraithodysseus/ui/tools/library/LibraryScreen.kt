package com.thomaspeoples.wraithodysseus.ui.tools.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
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
import com.thomaspeoples.wraithodysseus.ui.common.parseServerTime
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.tools.ToolEnv
import java.time.Instant

/**
 * The web UI's document library: search, open a document and edit it as text
 * (with a preview for markdown), save, make a new one, archive and delete.
 * PDF documents open read-only: the web edits those in a form pane, and
 * their text carries the link to the uploaded file. Importing PDF/Word
 * files, version history, export and the AI tools stay in the web UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(env: ToolEnv) {
    val vm = viewModel { LibraryViewModel(LibraryApi(env.http)) }
    val snackbar = LocalSnackbar.current
    var sheet by remember { mutableStateOf<LibraryItem?>(null) }
    var deleting by remember { mutableStateOf<LibraryItem?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val editing = vm.editing
    BackHandler(enabled = editing != null) { if (editing?.dirty == true) discarding = true else vm.close() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        if (editing != null) {
            Editor(editing, saving = vm.saving, onBack = { if (editing.dirty) discarding = true else vm.close() }, onSave = vm::save)
            return@Column
        }
        TopLevelBar("Library", onMenu = env.onMenu, actions = {
            IconButton(onClick = { creating = true }) { Icon(Lucide.Plus, "New document") }
        })
        OutlinedTextField(
            vm.search, { vm.search = it },
            placeholder = { Text("Search documents") },
            leadingIcon = { Icon(Lucide.Search, null) },
            trailingIcon = {
                if (vm.search.isNotEmpty()) IconButton(onClick = { vm.search = ""; vm.refresh() }) { Icon(Lucide.X, "Clear") }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.refresh() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !vm.archivedView, onClick = { vm.setArchived(false) }, label = { Text("Documents") })
            FilterChip(selected = vm.archivedView, onClick = { vm.setArchived(true) }, label = { Text("Archived") })
        }
        if (vm.opening != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            val rows = vm.items
            when {
                rows == null && vm.loadError != null -> Message(vm.loadError.orEmpty(), retry = vm::refresh)
                rows == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                rows.isEmpty() -> Message(
                    when {
                        vm.search.isNotBlank() -> "No matching documents."
                        vm.archivedView -> "Nothing archived."
                        else -> "No documents yet."
                    },
                )
                else -> DocList(vm, rows, onOpen = vm::open, onMenu = { sheet = it })
            }
        }
    }

    sheet?.let { item ->
        val actions = listOf(
            SheetAction("Open", Lucide.FileText) { vm.open(item) },
            if (vm.archivedView) SheetAction("Restore", Lucide.ArchiveRestore) { vm.archive(item) }
            else SheetAction("Archive", Lucide.Archive) { vm.archive(item) },
            SheetAction("Delete", Lucide.Trash2, destructive = true) { deleting = item },
        )
        ActionSheet(actions, onDismiss = { sheet = null }, title = item.title)
    }
    deleting?.let { item ->
        ConfirmDialog(
            title = "Delete \"${item.title}\"?",
            confirm = "Delete",
            text = "It is removed from the library.",
            onConfirm = { vm.delete(item) },
            onDismiss = { deleting = null },
        )
    }
    if (discarding) {
        ConfirmDialog(
            title = "Discard your changes?",
            confirm = "Discard",
            onConfirm = { vm.close() },
            onDismiss = { discarding = false },
        )
    }
    if (creating) NewDocDialog(onCreate = { vm.create(it) }, onDismiss = { creating = false })
}

@Composable
private fun Message(text: String, retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Muted)
        retry?.let { TextButton(onClick = it) { Text("Retry") } }
    }
}

@Composable
private fun DocList(vm: LibraryViewModel, rows: List<LibraryItem>, onOpen: (LibraryItem) -> Unit, onMenu: (LibraryItem) -> Unit) {
    val now = remember(rows) { Instant.now() }
    val state = rememberLazyListState()
    // Nearing the end asks for the next 50, as the web's scroll does.
    val nearEnd by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, rows.size) { if (nearEnd) vm.loadMore() }
    var lastBucket: String? = null
    LazyColumn(Modifier.fillMaxSize(), state = state) {
        rows.forEach { item ->
            val bucket = dateBucket(parseServerTime(item.updatedAt), now)
            if (bucket != lastBucket) {
                lastBucket = bucket
                item(key = "h-$bucket") {
                    Text(bucket, style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
                }
            }
            item(key = item.id) {
                ItemRow(item, busy = item.id in vm.busy, onClick = { onOpen(item) }, onMenu = { onMenu(item) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (vm.loadingMore) item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun ItemRow(item: LibraryItem, busy: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(item.language, item.sessionName).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.labelMedium, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val preview = item.preview.trim().replace(Regex("\\s+"), " ")
            if (preview.isNotEmpty() && item.language != "pdf") {
                Text(preview, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        IconButton(onClick = onMenu) { Icon(Lucide.Pencil, "Actions") }
    }
}

@Composable
private fun Editor(e: Editing, saving: Boolean, onBack: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Lucide.ArrowLeft, "Back") }
            Text(if (e.dirty) "Edited" else "Saved", style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.weight(1f))
            if (e.doc.isMarkdown) {
                IconButton(onClick = { e.preview = !e.preview }) {
                    Icon(if (e.preview) Lucide.Pencil else Lucide.Eye, if (e.preview) "Edit" else "Preview")
                }
            }
            if (!e.readOnly) IconButton(onClick = onSave, enabled = e.dirty && !saving) { Icon(Lucide.Check, "Save") }
        }
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(
            e.title, { e.title = it },
            singleLine = true, readOnly = e.readOnly,
            label = { Text("Title") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        if (e.readOnly) {
            Text("This PDF opens read-only here. Fill and annotate it in the web UI.", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(12.dp))
        }
        if (e.preview) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { Markdown(e.text) }
        } else {
            OutlinedTextField(
                e.text, { e.text = it },
                readOnly = e.readOnly,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (e.doc.isMarkdown || e.doc.language == null || e.doc.language == "text") FontFamily.Default else FontFamily.Monospace),
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
        }
    }
}

@Composable
private fun NewDocDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New document", style = MaterialTheme.typography.titleMedium) },
        text = { OutlinedTextField(title, { title = it }, singleLine = true, placeholder = { Text("Untitled") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onDismiss(); onCreate(title.trim()) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
