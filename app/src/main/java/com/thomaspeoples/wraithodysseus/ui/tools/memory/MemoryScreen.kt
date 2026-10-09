package com.thomaspeoples.wraithodysseus.ui.tools.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
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

/** What the editor is open on: [memory] null is a new one. */
private data class Editing(val memory: Memory?)

/**
 * Memory, as the web UI's Memory panel: the memories this user has (pinned
 * first, then newest), a local text search, category chips, add / edit / pin /
 * delete, and the two switches that decide whether memory is used in chats and
 * whether the server extracts new memories by itself. Adding needs the
 * can_manage_memory privilege, which the server checks too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(env: ToolEnv) {
    val vm = viewModel { MemoryViewModel(MemoryApi(env.http)) }
    val state = vm.state
    val snackbar = LocalSnackbar.current
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var sheet by remember { mutableStateOf<Memory?>(null) }
    var deleting by remember { mutableStateOf<Memory?>(null) }
    val canAdd = env.status?.allows("can_manage_memory") ?: true

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val visible = state.visible
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopLevelBar("Memory", env.onMenu) {
                IconButton(onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) vm.setQuery("")
                }) { Icon(Lucide.Search, "Search") }
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    placeholder = { Text("Search memories") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ToggleRow("Use memory in chats", state.memoryEnabled) { vm.setToggle(PREF_MEMORY_ENABLED, it) }
            ToggleRow("Extract memories automatically", state.autoMemory) { vm.setToggle(PREF_AUTO_MEMORY, it) }
            val cats = remember(state.memories) { categoryCounts(state.memories) }
            if (cats.size > 1) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(cats) { (cat, n) ->
                        FilterChip(
                            selected = state.category == cat,
                            onClick = { vm.setCategory(if (state.category == cat) null else cat) },
                            label = { Text("$cat $n") },
                        )
                    }
                }
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.load(pull = true) }, modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    state.problem != null -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                        Text(state.problem, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.load() }) { Text("Try again") }
                    }
                    visible.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(if (state.memories.isEmpty()) "No memories yet" else "No matching memories", color = Muted)
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(visible, key = { it.id }) { m -> MemoryCard(m, onOpen = { sheet = m }) }
                    }
                }
            }
        }
        if (canAdd) {
            FloatingActionButton(
                onClick = { editing = Editing(null) },
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            ) { Icon(Lucide.Plus, "New memory") }
        }
    }

    editing?.let { e ->
        MemoryEditor(
            memory = e.memory,
            saving = vm.saving,
            onSave = { text, cat -> vm.save(e.memory, text, cat) { editing = null } },
            onCancel = { editing = null },
        )
    }
    sheet?.let { m ->
        val actions = listOf(
            SheetAction("Edit", Lucide.Pencil) { editing = Editing(m) },
            SheetAction(if (m.pinned) "Unpin" else "Pin", Lucide.Pin) { vm.togglePin(m) },
            SheetAction("Delete", Lucide.Trash2, destructive = true) { deleting = m },
        )
        ActionSheet(actions, onDismiss = { sheet = null }, title = m.category)
    }
    deleting?.let { m ->
        ConfirmDialog(
            title = "Delete this memory?",
            text = m.text.take(120),
            confirm = "Delete",
            onConfirm = { vm.delete(m) },
            onDismiss = { deleting = null },
        )
    }
}

/** A switch row. Hidden until the pref has been read, so it never shows a guess. */
@Composable
private fun ToggleRow(label: String, on: Boolean?, onChange: (Boolean) -> Unit) {
    if (on == null) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryCard(m: Memory, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape)
            .combinedClickable(onClick = onOpen, onLongClick = onOpen)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(m.text, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (m.pinned) Icon(Lucide.Pin, "Pinned", tint = Muted, modifier = Modifier.size(14.dp))
            Text(m.category, color = Muted, style = MaterialTheme.typography.labelSmall)
            if (m.uses > 0) Text("used ${m.uses}", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MemoryEditor(memory: Memory?, saving: Boolean, onSave: (String, String) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(memory?.text.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(memory?.category ?: "fact") }
    val options = remember { (MEMORY_CATEGORIES + listOfNotNull(memory?.category)).distinct() }
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text(if (memory == null) "New memory" else "Edit memory", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(options) { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, category) }, enabled = !saving && text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
