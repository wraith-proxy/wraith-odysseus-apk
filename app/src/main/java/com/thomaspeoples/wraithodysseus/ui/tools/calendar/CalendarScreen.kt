package com.thomaspeoples.wraithodysseus.ui.tools.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaspeoples.wraithodysseus.ui.common.ActionSheet
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.SheetAction
import com.thomaspeoples.wraithodysseus.ui.common.TopLevelBar
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.tools.ToolEnv
import java.time.LocalDate
import java.time.format.TextStyle

/** What the editor is open on: [event] null is a new event, [prefill] a quick-add result to adjust. */
private data class Editing(val event: CalEvent?, val day: LocalDate, val prefill: EventDraft? = null)

/** Survives rotation: the event being edited and the quick-add result are rebuilt from plain values. */
private val EditingSaver: Saver<Editing?, Any> = Saver(
    save = { e -> if (e == null) emptyList<Any?>() else listOf(e.event?.pack(), e.day.toString(), e.prefill?.pack()) },
    restore = { v ->
        val l = v as List<*>
        if (l.isEmpty()) null else Editing((l[0] as List<*>?)?.let(::unpackEvent), LocalDate.parse(l[1] as String), (l[2] as List<*>?)?.let(::unpackDraft))
    },
)

/**
 * Calendar, as the web UI's Calendar pane: an agenda of the next three
 * months grouped by day, or a month grid with the chosen day's events under
 * it; calendars can be switched off (remembered on this phone); events are
 * created, edited and deleted, a repeating one deleting either one
 * occurrence or the whole series; and a line of plain text ("lunch with Sara
 * Friday 1pm") is read by the server and shown for confirmation first.
 *
 * Editing a repeating event always changes the series: the server has no
 * per-occurrence edit, so the app does not pretend to offer one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(env: ToolEnv) {
    val context = LocalContext.current
    val vm = viewModel { CalendarViewModel(CalendarApi(env.http), CalendarPrefs(context)) }
    val state = vm.state
    val snackbar = LocalSnackbar.current
    var editing by rememberSaveable(stateSaver = EditingSaver) { mutableStateOf<Editing?>(null) }
    var sheet by remember { mutableStateOf<CalEvent?>(null) }
    var deleting by remember { mutableStateOf<CalEvent?>(null) }
    var filters by remember { mutableStateOf(false) }
    var quickAdd by remember { mutableStateOf(false) }
    var parsed by rememberSaveable(stateSaver = QuickParseSaver) { mutableStateOf<QuickParse?>(null) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val open = editing
    if (open != null) {
        EventEditor(
            event = open.event,
            start = open.day,
            calendars = state.calendars.filter { it.href !in state.hidden }.ifEmpty { state.calendars },
            saving = vm.saving,
            onSave = { draft -> vm.save(open.event, draft) { editing = null } },
            onDelete = open.event?.let { e -> { vm.delete(e, occurrenceOnly = false) { editing = null } } },
            onCancel = { editing = null },
            prefill = open.prefill,
        )
        return
    }

    val today = LocalDate.now()
    val shown = remember(state.events, state.hidden) { visibleEvents(state.events, state.hidden) }
    val edit: (CalEvent) -> Unit = { e -> vm.edit(e) { editing = Editing(it, it.start.toLocalDate()) } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopLevelBar("Calendar", env.onMenu) {
                IconButton(onClick = { quickAdd = true }) { Icon(Lucide.Sparkles, "Quick add") }
                IconButton(onClick = { filters = true }) { Icon(Lucide.ListFilter, "Calendars") }
                IconButton(onClick = { vm.setMode(if (state.mode == CalMode.Agenda) CalMode.Month else CalMode.Agenda) }) {
                    if (state.mode == CalMode.Agenda) Icon(Lucide.Calendar, "Month view") else Icon(Lucide.ListChecks, "Agenda view")
                }
                IconButton(onClick = { menu = true }) { Icon(Lucide.Ellipsis, "More") }
            }
            if (state.mode == CalMode.Month) MonthHeader(state, vm)
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.load(pull = true) }, modifier = Modifier.fillMaxSize()) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    state.problem != null -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                        Text(state.problem, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.load() }) { Text("Try again") }
                    }
                    state.mode == CalMode.Agenda -> Agenda(shown, state, today, edit) { sheet = it }
                    else -> MonthBody(shown, state, today, vm, edit) { sheet = it }
                }
            }
        }
        FloatingActionButton(
            onClick = { editing = Editing(null, if (state.mode == CalMode.Month) state.selected else today) },
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        ) { Icon(Lucide.Plus, "New event") }
    }

    if (menu) {
        ActionSheet(
            actions = listOf(
                SheetAction(if (state.sundayFirst) "Week starts on Monday" else "Week starts on Sunday", Lucide.Calendar) { vm.setSundayFirst(!state.sundayFirst) },
                SheetAction("Refresh", Lucide.RefreshCw) { vm.load(pull = true) },
            ),
            onDismiss = { menu = false },
        )
    }
    sheet?.let { e ->
        val actions = buildList {
            add(SheetAction(if (e.recurring) "Edit series" else "Edit", Lucide.Pencil) { edit(e) })
            add(SheetAction(if (e.recurring) "Delete..." else "Delete", Lucide.Trash2, destructive = true) { deleting = e })
        }
        ActionSheet(actions, onDismiss = { sheet = null }, title = e.summary.ifBlank { "Event" })
    }
    deleting?.let { e -> DeleteDialog(e, onDelete = { once -> vm.delete(e, occurrenceOnly = once) }, onDismiss = { deleting = null }) }
    if (filters) CalendarFilters(state, onToggle = vm::toggleCalendar, onDismiss = { filters = false })
    if (quickAdd) {
        QuickAddDialog(
            parsing = vm.parsing,
            onParse = { text ->
                vm.quickParse(text) { result ->
                    quickAdd = false
                    parsed = result
                }
            },
            onDismiss = { quickAdd = false },
        )
    }
    parsed?.let { p ->
        val draft = remember(p) { p.toDraft(state.calendars.firstOrNull { it.href !in state.hidden }?.href ?: state.calendars.firstOrNull()?.href) }
        QuickConfirmDialog(
            parse = p,
            draft = draft,
            saving = vm.saving,
            onCreate = { d -> vm.save(null, d) { parsed = null } },
            onEdit = { d -> parsed = null; editing = Editing(null, d.start.toLocalDate(), prefill = d) },
            onDismiss = { parsed = null },
            onUnreadable = { vm.notify("Could not understand the date or time"); parsed = null },
        )
    }
}

@Composable
private fun MonthHeader(state: CalendarState, vm: CalendarViewModel) {
    val locale = LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.shiftMonth(-1) }) { Icon(Lucide.ChevronLeft, "Previous month") }
        Text(
            state.month.month.getDisplayName(TextStyle.FULL, locale) + " " + state.month.year,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).clickable { vm.goToday() },
        )
        IconButton(onClick = { vm.shiftMonth(1) }) { Icon(Lucide.ChevronRight, "Next month") }
    }
}

@Composable
private fun dotColor(e: CalEvent, calendars: List<CalCalendar>): Color =
    eventColor(e, calendars)?.let { Color(it) } ?: MaterialTheme.colorScheme.primary

@Composable
private fun Agenda(events: List<CalEvent>, state: CalendarState, today: LocalDate, onOpen: (CalEvent) -> Unit, onMore: (CalEvent) -> Unit) {
    val (from, to) = state.window(today)
    val days = remember(events, today) { agendaDays(events, from, to, today) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
        if (days.all { it.events.isEmpty() }) {
            item { Text("No upcoming events", color = Muted, modifier = Modifier.padding(24.dp)) }
        }
        days.forEach { day ->
            item(key = "d${day.date}") {
                Text(
                    dayLabel(day.date, today),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (day.date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                if (day.events.isEmpty()) Text("No events", color = Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            items(day.events, key = { "e${day.date}${it.uid}" }) { e -> EventRow(e, day.date, state.calendars, onOpen, onMore) }
        }
        if (state.truncated) item { Text("Some repeating events are cut off here.", color = Muted, modifier = Modifier.padding(16.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventRow(e: CalEvent, day: LocalDate, calendars: List<CalCalendar>, onOpen: (CalEvent) -> Unit, onMore: (CalEvent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(e) }, onLongClick = { onMore(e) }).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor(e, calendars)))
        Column(Modifier.weight(1f)) {
            Text(e.summary.ifBlank { "(no title)" }, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val line = timeLabel(e, day) + if (e.location.isNotBlank()) " · ${e.location}" else ""
            Text(line, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { onMore(e) }) { Icon(Lucide.Ellipsis, "More", tint = Muted) }
    }
}

@Composable
private fun MonthBody(
    events: List<CalEvent>,
    state: CalendarState,
    today: LocalDate,
    vm: CalendarViewModel,
    onOpen: (CalEvent) -> Unit,
    onMore: (CalEvent) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val grid = remember(state.month, state.sundayFirst) { monthGrid(state.month, state.sundayFirst) }
    val byDay = remember(events, grid) { eventsByDay(events, grid) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            grid.take(7).forEach { d ->
                Text(
                    d.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    color = Muted,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        grid.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                week.forEach { d ->
                    val inMonth = d.month == state.month.month
                    val picked = d == state.selected
                    Column(
                        Modifier.weight(1f).height(48.dp).padding(1.dp).clip(RoundedCornerShape(6.dp))
                            .then(if (picked) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier)
                            .clickable { vm.select(d) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            d.dayOfMonth.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                d == today -> MaterialTheme.colorScheme.primary
                                inMonth -> MaterialTheme.colorScheme.onBackground
                                else -> Muted
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 2.dp)) {
                            byDay[d].orEmpty().take(3).forEach { e -> Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor(e, state.calendars))) }
                        }
                    }
                }
            }
        }
        val dayEvents = byDay[state.selected].orEmpty()
        Text(dayLabel(state.selected, today), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
            if (dayEvents.isEmpty()) item { Text("No events", color = Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            items(dayEvents, key = { it.uid }) { e -> EventRow(e, state.selected, state.calendars, onOpen, onMore) }
        }
    }
}

/** Deleting: a plain question for a single event, the web's three-way choice for a repeating one. */
@Composable
private fun DeleteDialog(e: CalEvent, onDelete: (occurrenceOnly: Boolean) -> Unit, onDismiss: () -> Unit) {
    val name = if (e.summary.isBlank()) "this event" else "\"${e.summary}\""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (e.recurring) "Delete recurring event" else "Delete event?", style = MaterialTheme.typography.titleMedium) },
        text = { Text(if (e.recurring) "Delete $name?" else "$name will be removed.") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (e.recurring && "::" in e.uid) {
                    TextButton(onClick = { onDismiss(); onDelete(true) }) { Text("This event only", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = { onDismiss(); onDelete(false) }) {
                    Text(if (e.recurring) "All recurring events" else "Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** Per-calendar switches. Off calendars stay off the agenda and the grid, and are remembered. */
@Composable
private fun CalendarFilters(state: CalendarState, onToggle: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calendars", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                if (state.calendars.isEmpty()) Text("No calendars", color = Muted)
                state.calendars.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { onToggle(c.href) }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(parseHexColor(c.color)?.let { Color(it) } ?: MaterialTheme.colorScheme.primary))
                        Text(c.name.ifBlank { "Calendar" }, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        Switch(checked = c.href !in state.hidden, onCheckedChange = { onToggle(c.href) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun QuickAddDialog(parsing: Boolean, onParse: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!parsing) onDismiss() },
        title = { Text("Quick add", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, placeholder = { Text("lunch with Sara Friday 1pm") }, enabled = !parsing, modifier = Modifier.fillMaxWidth())
                if (parsing) CircularProgressIndicator(Modifier.size(20.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onParse(text.trim()) }, enabled = text.isNotBlank() && !parsing) { Text("Parse") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !parsing) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** What the server understood, to confirm before anything is created. */
@Composable
private fun QuickConfirmDialog(
    parse: QuickParse,
    draft: EventDraft?,
    saving: Boolean,
    onCreate: (EventDraft) -> Unit,
    onEdit: (EventDraft) -> Unit,
    onDismiss: () -> Unit,
    onUnreadable: () -> Unit,
) {
    if (draft == null || draft.summary.isBlank()) {
        LaunchedEffect(parse) { onUnreadable() }
        return
    }
    val whenText = remember(draft) {
        val last = draft.end.toLocalDate().let { if (draft.allDay) it.minusDays(1) else it }
        val day = dayLabel(draft.start.toLocalDate(), LocalDate.now())
        val fakeEvent = CalEvent("", "", draft.summary, draft.start, draft.end, draft.allDay, "", "", "", "", "", "", false)
        val time = timeLabel(fakeEvent, draft.start.toLocalDate())
        if (last != draft.start.toLocalDate()) "$day → ${dayLabel(last, LocalDate.now())}" else "$day, $time"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(draft.summary, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(whenText)
                if (draft.location.isNotBlank()) Text(draft.location, color = Muted)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(draft) }, enabled = !saving) { Text("Add") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onEdit(draft) }) { Text("Edit") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
