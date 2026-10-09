package com.thomaspeoples.wraithodysseus.ui.tools.skills

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaspeoples.wraithodysseus.ui.common.ActionSheet
import com.thomaspeoples.wraithodysseus.ui.common.ConfirmDialog
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.Markdown
import com.thomaspeoples.wraithodysseus.ui.common.SheetAction
import com.thomaspeoples.wraithodysseus.ui.common.TopLevelBar
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.tools.ToolEnv

/**
 * Skills, as the web UI's Skills panel: the user's skills with a text search
 * and a status filter, a reader for a skill's SKILL.md, publish / return to
 * draft, and delete. Tapping a card reads it; its menu has the rest. Editing,
 * audits and test runs stay in the web UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(env: ToolEnv) {
    val vm = viewModel { SkillsViewModel(SkillsApi(env.http)) }
    val state = vm.state
    val snackbar = LocalSnackbar.current
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Skill?>(null) }
    var deleting by remember { mutableStateOf<Skill?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val doc = state.doc
    if (doc != null) {
        BackHandler(onBack = vm::closeDoc)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::closeDoc) { Icon(Lucide.ArrowLeft, "Back") }
                Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                when {
                    doc.problem != null -> Text(doc.problem, color = MaterialTheme.colorScheme.error)
                    doc.markdown == null -> CircularProgressIndicator()
                    doc.markdown.isBlank() -> Text("(empty)", color = Muted)
                    else -> Markdown(doc.markdown)
                }
            }
        }
    } else {
        BackHandler(enabled = searchOpen) {
            searchOpen = false
            vm.setQuery("")
        }
        val visible = state.visible
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
            TopLevelBar("Skills", env.onMenu) {
                IconButton(onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) vm.setQuery("")
                }) { Icon(Lucide.Search, "Search") }
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    placeholder = { Text("Search skills") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SkillFilter.entries) { f ->
                    FilterChip(selected = state.filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) })
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
                        Text(if (state.skills.isEmpty()) "No skills yet" else "No matching skills", color = Muted)
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(visible, key = { it.name }) { s -> SkillCard(s, onOpen = { vm.view(s) }, onMenu = { sheet = s }) }
                    }
                }
            }
        }
    }

    sheet?.let { s ->
        val actions = listOf(
            SheetAction("Read", Lucide.BookOpen) { vm.view(s) },
            SheetAction(if (s.published) "Return to draft" else "Publish", if (s.published) Lucide.X else Lucide.Check) { vm.togglePublished(s) },
            SheetAction("Delete", Lucide.Trash2, destructive = true) { deleting = s },
        )
        ActionSheet(actions, onDismiss = { sheet = null }, title = s.name)
    }
    deleting?.let { s ->
        ConfirmDialog(
            title = "Delete skill \"${s.name}\"?",
            text = "This removes its SKILL.md.",
            confirm = "Delete",
            onConfirm = { vm.delete(s) },
            onDismiss = { deleting = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkillCard(s: Skill, onOpen: () -> Unit, onMenu: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape)
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)
            .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (s.description.isNotBlank()) {
                Text(s.description, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            val meta = listOfNotNull(
                s.status,
                s.category.takeIf { it.isNotBlank() },
                "used ${s.uses}".takeIf { s.uses > 0 },
                "${Math.round(s.confidence * 100)}%".takeIf { s.confidence > 0 },
            ).joinToString(" · ")
            Text(meta, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) {
            Icon(Lucide.Ellipsis, "Skill actions", tint = Muted, modifier = Modifier.size(18.dp))
        }
    }
}
