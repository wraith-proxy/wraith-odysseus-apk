package com.thomaspeoples.wraithodysseus.ui.tools.notes

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaspeoples.wraithodysseus.ui.common.ActionSheet
import com.thomaspeoples.wraithodysseus.ui.common.ConfirmDialog
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.SheetAction
import com.thomaspeoples.wraithodysseus.ui.common.TopLevelBar
import com.thomaspeoples.wraithodysseus.ui.common.openInBrowser
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.tools.ToolEnv
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

private const val PREFS = "notes"
private const val KEY_GRID = "grid"
private val CardText = Color(0xFF222222)

/** What the editor is open on: [note] null is a new note. */
private data class Editing(val note: Note?)

/**
 * Notes, as the web UI's Notes pane: a list or a grid (remembered on this
 * phone), pinned notes first, tags as filter chips, an archived view, and a
 * checklist that ticks in place on its card. Due dates are shown but nothing
 * is scheduled here: the server already sends its own reminders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(env: ToolEnv) {
    val vm = viewModel { NotesViewModel(NotesApi(env.http)) }
    val state = vm.state
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    var grid by remember { mutableStateOf(readGrid(context)) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // Kept as an id so it survives rotation: "" is a new note, null is no editor.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let { id -> if (id.isEmpty()) Editing(null) else state.notes.firstOrNull { it.id == id }?.let(::Editing) }
    // Held by id so an open sheet or delete confirm survives rotation.
    var sheetKey by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingKey by rememberSaveable { mutableStateOf<String?>(null) }
    // A drawing note open in the read-only viewer, by id for the same reason.
    var viewingId by rememberSaveable { mutableStateOf<String?>(null) }
    val viewing = state.notes.firstOrNull { it.id == viewingId && it.type == "draw" }
    val scope = rememberCoroutineScope()
    // The web UI is the only place a drawing can be drawn on.
    val editInWeb = {
        val url = env.baseUrl
        if (url == null || !openInBrowser(context, url)) scope.launch { snackbar.showSnackbar("No browser to open it with") }
        Unit
    }
    val api = remember(env.http) { NotesApi(env.http) }
    val sheet = state.notes.firstOrNull { it.id == sheetKey }
    val deleting = state.notes.firstOrNull { it.id == deletingKey }
    var now by remember { mutableStateOf(Instant.now()) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // A drag cut short (rotation, leaving the screen) must not leave the list half-reordered.
    DisposableEffect(vm) { onDispose { vm.dragEnd(drop = false) } }
    LaunchedEffect(state.notes) { now = Instant.now() }

    val open = editing
    if (viewing != null && open == null) {
        DrawingViewer(
            note = viewing,
            api = api,
            onEditInWeb = { editInWeb() },
            onBack = { viewingId = null },
        )
    } else if (open != null) {
        NoteEditor(
            note = open.note,
            saving = vm.saving,
            onSave = { draft -> vm.save(open.note, draft) { editingId = null } },
            onCancel = { editingId = null },
        )
    } else {
        BackHandler(enabled = searchOpen) {
            searchOpen = false
            vm.setQuery("")
        }
        val sorted = remember(state.notes, state.label, state.query, state.archivedView, now.epochSecond / 60) { state.visible(now) }
        // While a card is dragged the list follows the drag, not the server's order.
        val visible = vm.dragOrder?.let { ids ->
            val byId = sorted.associateBy { it.id }
            ids.mapNotNull(byId::get)
        } ?: sorted
        val listState = rememberLazyListState()
        val drag = remember { DragState() }
        val tags = remember(state.notes) { allTags(state.notes.filterNot(::isCalendarNote)) }
        val openNote: (Note) -> Unit = { n ->
            if (n.type == "draw") {
                // A drawing is drawn on a canvas by the web; here it is only viewed.
                viewingId = n.id
            } else {
                editingId = n.id
            }
        }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                TopLevelBar(if (state.archivedView) "Archived notes" else "Notes", env.onMenu) {
                    IconButton(onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) vm.setQuery("")
                    }) { Icon(Lucide.Search, "Search") }
                    IconButton(onClick = {
                        grid = !grid
                        saveGrid(context, grid)
                    }) { if (grid) Icon(Lucide.Square, "List view") else Icon(Lucide.Columns2, "Grid view") }
                    IconButton(onClick = { vm.showArchived(!state.archivedView) }) {
                        if (state.archivedView) Icon(Lucide.StickyNote, "Notes") else Icon(Lucide.Archive, "Archived")
                    }
                }
                if (searchOpen) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = vm::setQuery,
                        placeholder = { Text("Search notes") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (tags.isNotEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(tags) { tag ->
                            FilterChip(
                                selected = state.label == tag,
                                onClick = { vm.setLabel(if (state.label == tag) null else tag) },
                                label = { Text(tag) },
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
                            Text(if (state.archivedView) "No archived notes" else "No notes", color = Muted)
                        }
                        grid -> LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(2),
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalItemSpacing = 8.dp,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(visible, key = { it.id }) { n ->
                                NoteCard(n, now, openNote, { sheetKey = n.id }, vm::toggleItem)
                            }
                        }
                        else -> LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(visible, key = { it.id }) { n ->
                                val dragging = drag.id == n.id
                                NoteCard(
                                    n, now, openNote, { sheetKey = n.id }, vm::toggleItem,
                                    modifier = if (dragging) {
                                        // Held under the finger: draw it where the finger put it, whatever its slot.
                                        Modifier.zIndex(1f).graphicsLayer {
                                            val top = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == n.id }?.offset
                                            translationY = if (top == null) 0f else drag.startTop + drag.travel - top
                                            scaleX = 1.02f
                                            scaleY = 1.02f
                                        }
                                    } else {
                                        Modifier.animateItem()
                                    },
                                    handle = if (state.canReorder && visible.size > 1) {
                                        Modifier.pointerInput(n.id) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    val top = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == n.id }?.offset
                                                    if (top != null) {
                                                        drag.begin(n.id, top)
                                                        vm.dragBegin(now)
                                                    }
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    drag.travel += amount.y
                                                    dragStep(drag, listState, vm)
                                                },
                                                onDragEnd = {
                                                    drag.end()
                                                    vm.dragEnd(drop = true)
                                                },
                                                onDragCancel = {
                                                    drag.end()
                                                    vm.dragEnd(drop = false)
                                                },
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (!state.archivedView) {
                FloatingActionButton(
                    onClick = { editingId = "" },
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                ) { Icon(Lucide.Plus, "New note") }
            }
        }

        sheet?.let { n ->
            val order = state.visible(now).map { it.id }
            val actions = buildList {
                if (n.type != "draw") add(SheetAction("Edit", Lucide.Pencil) { editingId = n.id })
                else add(SheetAction("Edit in web UI", Lucide.ExternalLink) { editInWeb() })
                if (!n.archived) add(SheetAction(if (n.pinned) "Unpin" else "Pin", Lucide.Pin) { vm.togglePin(n) })
                add(SheetAction(if (n.archived) "Restore" else "Archive", if (n.archived) Lucide.ArchiveRestore else Lucide.Archive) { vm.toggleArchive(n) })
                if (state.canReorder && order.indexOf(n.id) > 0) add(SheetAction("Move up", Lucide.ChevronUp) { vm.move(n, -1, now) })
                if (state.canReorder && order.indexOf(n.id) in 0 until order.size - 1) add(SheetAction("Move down", Lucide.ChevronDown) { vm.move(n, 1, now) })
                add(SheetAction("Delete", Lucide.Trash2, destructive = true) { deletingKey = n.id })
            }
            ActionSheet(actions, onDismiss = { sheetKey = null }, title = n.title.ifBlank { "Note" })
        }
        deleting?.let { n ->
            ConfirmDialog(
                title = "Delete this note?",
                text = n.title.ifBlank { "This note" } + " will be removed for good.",
                confirm = "Delete",
                onConfirm = { vm.delete(n) },
                onDismiss = { deletingKey = null },
            )
        }
    }
}

private fun readGrid(context: Context): Boolean = try {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GRID, false)
} catch (_: RuntimeException) {
    false
}

private fun saveGrid(context: Context, grid: Boolean) {
    try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_GRID, grid).apply()
    } catch (_: RuntimeException) {
        // Only the remembered choice is lost.
    }
}

/**
 * The card being dragged: where its slot started ([startTop], px in the list) and how far the
 * finger has gone since ([travel]). The card is drawn at startTop + travel whatever slot it holds,
 * so it stays under the finger while the others move aside.
 */
private class DragState {
    var id by mutableStateOf<String?>(null)
        private set
    var startTop = 0
        private set
    var travel by mutableFloatStateOf(0f)

    fun begin(id: String, top: Int) {
        this.id = id
        startTop = top
        travel = 0f
    }

    fun end() {
        id = null
    }
}

/** How close to the list's edge, in px, the dragged card must get for the list to scroll. */
private const val EDGE_SCROLL_ZONE = 80

/**
 * After the finger moved: swap with the neighbour the dragged card has passed the middle of
 * (its leading edge past the other's midpoint, so a swap never flips straight back), and scroll
 * when it nears the top or bottom edge.
 */
private fun dragStep(drag: DragState, listState: LazyListState, vm: NotesViewModel) {
    val id = drag.id ?: return
    val info = listState.layoutInfo
    val me = info.visibleItemsInfo.firstOrNull { it.key == id } ?: return
    val top = drag.startTop + drag.travel
    val bottom = top + me.size
    val over = info.visibleItemsInfo
        .filter { it.key != id }
        .filter { if (it.offset > me.offset) bottom > it.offset + it.size / 2f else top < it.offset + it.size / 2f }
        .minByOrNull { kotlin.math.abs(it.offset - me.offset) }
    (over?.key as? String)?.let { vm.dragOver(id, it) }
    when {
        top < info.viewportStartOffset + EDGE_SCROLL_ZONE -> listState.dispatchRawDelta(-EDGE_SCROLL_STEP)
        bottom > info.viewportEndOffset - EDGE_SCROLL_ZONE -> listState.dispatchRawDelta(EDGE_SCROLL_STEP)
    }
}

private const val EDGE_SCROLL_STEP = 24f

/** How many checklist rows a card shows before "+N more". */
private const val CARD_ROWS = 8

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: Note,
    now: Instant,
    onOpen: (Note) -> Unit,
    onMenu: () -> Unit,
    onToggle: (Note, Int) -> Unit,
    modifier: Modifier = Modifier,
    /** The drag handle's gesture, or null where the card cannot be dragged. */
    handle: Modifier? = null,
) {
    val fill = noteColorRgb(note.color)?.let { Color(0xFF000000.toInt() or it) }
    val ink = if (fill != null) CardText else MaterialTheme.colorScheme.onSurface
    val soft = if (fill != null) CardText.copy(alpha = 0.65f) else Muted
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill ?: MaterialTheme.colorScheme.surface)
            .then(if (fill == null) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier)
            .combinedClickable(onClick = { onOpen(note) }, onLongClick = onMenu)
            .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (note.pinned) Icon(Lucide.Pin, "Pinned", tint = soft, modifier = Modifier.padding(top = 3.dp, end = 6.dp).size(14.dp))
            Text(
                note.title.ifBlank { if (note.type == "draw") "Drawing" else "" },
                color = ink,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(top = 2.dp),
            )
            if (handle != null) {
                Box(Modifier.size(28.dp).then(handle), Alignment.Center) {
                    Icon(Lucide.Menu, "Drag to reorder", tint = soft, modifier = Modifier.size(18.dp))
                }
            }
            Box(Modifier.size(28.dp).clickable(onClick = onMenu), Alignment.Center) {
                Icon(Lucide.Ellipsis, "Note actions", tint = soft, modifier = Modifier.size(18.dp))
            }
        }
        when {
            note.type == "draw" -> Text("Drawing: tap to view it", color = soft, style = MaterialTheme.typography.bodySmall)
            note.isChecklist -> {
                if (note.type == "goal" && note.content.isNotBlank()) {
                    Text(note.content, color = soft, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                note.items.take(CARD_ROWS).forEachIndexed { i, item ->
                    Row(Modifier.padding(start = (item.indent * 12).dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.done, onCheckedChange = { onToggle(note, i) }, modifier = Modifier.size(32.dp))
                        Text(
                            item.text,
                            color = if (item.done) soft else ink,
                            style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (item.done) TextDecoration.LineThrough else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (note.items.size > CARD_ROWS) Text("+${note.items.size - CARD_ROWS} more", color = soft, style = MaterialTheme.typography.bodySmall)
            }
            note.content.isNotBlank() -> Text(note.content, color = ink, style = MaterialTheme.typography.bodyMedium, maxLines = 10, overflow = TextOverflow.Ellipsis)
        }
        val due = dueText(note.dueDate, now)
        val repeat = repeatText(note.repeat)
        val progress = progress(note)
        val tags = noteTags(note).filter { it != "reminder" }
        if (due != null || progress.isNotEmpty() || tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (due != null) {
                    Icon(Lucide.AlarmClock, null, tint = if (due.overdue) MaterialTheme.colorScheme.error else soft, modifier = Modifier.size(14.dp))
                    Text(
                        due.text + if (repeat.isNotEmpty()) ", $repeat" else "",
                        color = if (due.overdue) MaterialTheme.colorScheme.error else soft,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (progress.isNotEmpty()) Text(progress, color = soft, style = MaterialTheme.typography.labelSmall)
                if (tags.isNotEmpty()) Text(tags.joinToString(" ") { "#$it" }, color = soft, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A checklist row being edited. */
private data class EditRow(val id: String, val text: String, val done: Boolean, val indent: Int)

private val rowsSaver = listSaver<SnapshotStateList<EditRow>, List<Any>>(
    save = { l -> l.map { listOf(it.id, it.text, it.done, it.indent) } },
    restore = { l -> l.map { EditRow(it[0] as String, it[1] as String, it[2] as Boolean, it[3] as Int) }.toMutableStateList() },
)

private fun newId() = UUID.randomUUID().toString().take(8)

/**
 * The editor for a new or existing note: title, text or checklist, colour
 * and tags. Due date and repeat are not editable here (they are kept as they
 * are on the server). Leaving with changes asks first.
 */
@Composable
private fun NoteEditor(note: Note?, saving: Boolean, onSave: (NoteDraft) -> Unit, onCancel: () -> Unit) {
    // A goal keeps its type; a drawing never reaches here.
    val fixedType = note?.type?.takeIf { it == "goal" }
    // Saveable so a rotation does not lose what was typed.
    val startContent = if (note?.isChecklist == true && note.type != "goal") "" else note?.content.orEmpty()
    var checklist by rememberSaveable { mutableStateOf(note?.isChecklist ?: false) }
    var title by rememberSaveable { mutableStateOf(note?.title.orEmpty()) }
    var content by rememberSaveable { mutableStateOf(startContent) }
    var color by rememberSaveable { mutableStateOf(note?.color.orEmpty()) }
    var label by rememberSaveable { mutableStateOf(note?.label.orEmpty()) }
    val startRows = remember(note) {
        buildList {
            note?.items?.forEach { add(EditRow(it.id, it.text, it.done, it.indent)) }
            if (isEmpty() && note?.isChecklist == true) add(EditRow(newId(), "", false, 0))
        }
    }
    val rows = rememberSaveable(saver = rowsSaver) { startRows.toMutableStateList() }
    var asking by rememberSaveable { mutableStateOf(false) }

    fun build(c: Boolean, t: String, body: String, col: String, lab: String, r: List<EditRow>): NoteDraft {
        val type = fixedType ?: if (!c) "note" else if (note?.isChecklist == true) note.type else "todo"
        return NoteDraft(
            title = t.trim(),
            type = type,
            content = body,
            items = r.filter { it.text.isNotBlank() }.map { NoteItem(it.id, it.text.trim(), it.done, it.indent) },
            color = col,
            label = normaliseLabel(lab),
        )
    }

    fun draft() = build(checklist, title, content, color, label, rows)

    fun empty(d: NoteDraft) = d.title.isEmpty() && d.content.isBlank() && d.items.isEmpty()
    // From the note, not the restored fields, so a restored edit still counts as a change.
    val initial = remember(note) { build(note?.isChecklist ?: false, note?.title.orEmpty(), startContent, note?.color.orEmpty(), note?.label.orEmpty(), startRows) }
    val dirty = draft() != initial
    val leave = { if (dirty) asking = true else onCancel() }
    BackHandler(onBack = leave)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = leave) { Icon(Lucide.ArrowLeft, "Back") }
            Text(if (note == null) "New note" else "Edit note", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onSave(draft()) }, enabled = !saving && !empty(draft()) && (note == null || dirty)) { Text("Save") }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Title") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            if (fixedType == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !checklist, onClick = {
                        if (checklist) {
                            content = itemsToText(rows.filter { it.text.isNotBlank() }.map { NoteItem(it.id, it.text, it.done) })
                            checklist = false
                        }
                    }, label = { Text("Note") })
                    FilterChip(selected = checklist, onClick = {
                        if (!checklist) {
                            rows.clear()
                            textToItems(content, ::newId).forEach { rows.add(EditRow(it.id, it.text, false, 0)) }
                            if (rows.isEmpty()) rows.add(EditRow(newId(), "", false, 0))
                            checklist = true
                        }
                    }, label = { Text("Checklist") })
                }
            }
            if (fixedType == "goal") {
                OutlinedTextField(content, { content = it }, placeholder = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
            if (!checklist) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = { Text("Note") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                rows.forEachIndexed { i, row ->
                    Row(Modifier.padding(start = (row.indent * 16).dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = row.done, onCheckedChange = { rows[i] = row.copy(done = it) })
                        OutlinedTextField(
                            value = row.text,
                            onValueChange = { rows[i] = row.copy(text = it) },
                            placeholder = { Text("List item") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { rows.removeAt(i) }) { Icon(Lucide.X, "Remove item") }
                    }
                }
                TextButton(onClick = { rows.add(EditRow(newId(), "", false, 0)) }) {
                    Icon(Lucide.Plus, null, modifier = Modifier.size(16.dp))
                    Text("Add item", modifier = Modifier.padding(start = 6.dp))
                }
            }
            Text("Colour", color = Muted, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                (listOf("") + NOTE_COLORS).forEach { c ->
                    val swatch = noteColorRgb(c)?.let { Color(0xFF000000.toInt() or it) }
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(swatch ?: Color.Transparent)
                            .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { color = c },
                        Alignment.Center,
                    ) { if (swatch == null) Icon(Lucide.X, "No colour", tint = Muted, modifier = Modifier.size(14.dp)) }
                }
            }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Labels (separated by spaces)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (note?.dueDate != null) {
                Text("Due ${note.dueDate.replace('T', ' ')}. Change it in the web UI.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (asking) {
        ConfirmDialog(
            title = "Discard changes?",
            confirm = "Discard",
            onConfirm = onCancel,
            onDismiss = { asking = false },
        )
    }
}
