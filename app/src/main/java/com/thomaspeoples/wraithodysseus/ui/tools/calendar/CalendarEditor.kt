package com.thomaspeoples.wraithodysseus.ui.tools.calendar

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.ui.common.ConfirmDialog
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A new or edited event, as the web's form: title, calendar (new events
 * only; the server cannot move one), all-day, start and end through the
 * platform's date and time pickers, repeat, place and notes.
 *
 * For an all-day event the end shown is the last day, and the saved end is
 * the day after it, which is how the server stores it. [event] is null for a
 * new event; [start] seeds the date of a new one. A recurring [event] is the
 * series row: saving changes every occurrence, and the screen says so.
 */
@Composable
fun EventEditor(
    event: CalEvent?,
    start: LocalDate,
    calendars: List<CalCalendar>,
    saving: Boolean,
    onSave: (EventDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
    prefill: EventDraft? = null,
) {
    val seed = prefill
    val initialStart = event?.start ?: seed?.start ?: LocalDateTime.of(start, LocalTime.of(9, 0))
    val initialEnd = event?.end ?: seed?.end ?: initialStart.plusHours(1)
    var title by rememberSaveable { mutableStateOf(event?.summary ?: seed?.summary ?: "") }
    var allDay by rememberSaveable { mutableStateOf(event?.allDay ?: seed?.allDay ?: false) }
    var from by rememberSaveable(stateSaver = LocalDateTimeSaver) { mutableStateOf(initialStart) }
    // The end the pickers show: for all-day the last day, not the exclusive end.
    var to by rememberSaveable(stateSaver = LocalDateTimeSaver) {
        mutableStateOf(if (allDay) initialEnd.minusDays(1).let { if (it.isBefore(initialStart)) initialStart else it } else initialEnd)
    }
    var calendarHref by rememberSaveable { mutableStateOf(event?.calendarHref ?: seed?.calendarHref ?: calendars.firstOrNull()?.href) }
    var rrule by rememberSaveable { mutableStateOf(event?.rrule ?: seed?.rrule ?: "") }
    var location by rememberSaveable { mutableStateOf(event?.location ?: seed?.location ?: "") }
    var description by rememberSaveable { mutableStateOf(event?.description ?: seed?.description ?: "") }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    BackHandler(onBack = onCancel)

    fun save() {
        val end = if (allDay) to.toLocalDate().plusDays(1).atStartOfDay() else to
        val begin = if (allDay) from.toLocalDate().atStartOfDay() else from
        when {
            title.isBlank() -> problem = "Title required"
            allDay && to.toLocalDate().isBefore(from.toLocalDate()) -> problem = "End date must not be before the start"
            !allDay && !end.isAfter(begin) -> problem = "End time must be after start time"
            else -> onSave(EventDraft(title.trim(), allDay, begin, end, description, location, rrule, calendarHref))
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Lucide.ArrowLeft, "Close") }
            Text(if (event == null) "New event" else "Edit event", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = ::save, enabled = !saving) { Text(if (event == null) "Create" else "Save") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; problem = null },
                label = { Text("Title") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (event?.recurring == true) {
                Text("This is a repeating event. Changes apply to every event in the series.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (event == null) {
                if (calendars.size > 1) {
                    ChipRow(calendars.map { it.name.ifBlank { "Calendar" } to it.href }, calendarHref) { calendarHref = it }
                }
            } else if (event.calendarName.isNotBlank()) {
                Text(event.calendarName, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("All day", Modifier.weight(1f))
                Switch(checked = allDay, onCheckedChange = { allDay = it; problem = null })
            }
            WhenRow("Starts", from, allDay) { picked ->
                // Moving the start keeps the length, as the web's form does.
                val length = Duration.between(from, to)
                from = picked
                to = if (allDay) maxOf(picked, to) else picked.plus(length.takeIf { !it.isNegative && !it.isZero } ?: Duration.ofHours(1))
                problem = null
            }
            WhenRow("Ends", to, allDay) { to = it; problem = null }
            Text("Repeat", color = Muted, style = MaterialTheme.typography.labelLarge)
            val repeatChoices = REPEAT_OPTIONS.let { base ->
                if (base.none { it.second == rrule }) base + (repeatLabel(rrule) to rrule) else base
            }
            ChipRow(repeatChoices, rrule) { rrule = it }
            OutlinedTextField(location, { location = it }, label = { Text("Location") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("Notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            if (onDelete != null) {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(bottom = 24.dp)) {
                    Icon(Lucide.Trash2, null, tint = MaterialTheme.colorScheme.error)
                    Text("  Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = if (event?.recurring == true) "Delete this repeating event?" else "Delete this event?",
            text = if (event?.recurring == true) "Every event in the series will be removed. To remove one, use the menu on the calendar." else (event?.summary?.ifBlank { "This event" } ?: "This event") + " will be removed.",
            confirm = "Delete",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}

/** A row of single-choice chips, scrolling sideways when they do not fit. */
@Composable
private fun ChipRow(choices: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(choices) { (label, value) ->
            FilterChip(selected = selected == value, onClick = { onPick(value) }, label = { Text(label) })
        }
    }
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** A label with a date button and, for a timed event, a time button; both open the platform pickers. */
@Composable
private fun WhenRow(label: String, value: LocalDateTime, allDay: Boolean, onChange: (LocalDateTime) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Muted, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { pickDate(context, value.toLocalDate()) { onChange(LocalDateTime.of(it, value.toLocalTime())) } }) {
            Text(value.format(DATE_FORMAT))
        }
        if (!allDay) {
            OutlinedButton(onClick = { pickTime(context, value.toLocalTime()) { onChange(LocalDateTime.of(value.toLocalDate(), it)) } }) {
                Text(value.format(TIME_FORMAT))
            }
        }
    }
}

private fun pickDate(context: Context, current: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, current.year, current.monthValue - 1, current.dayOfMonth).show()
}

private fun pickTime(context: Context, current: LocalTime, onPicked: (LocalTime) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPicked(LocalTime.of(h, m)) }, current.hour, current.minute, DateFormat.is24HourFormat(context)).show()
}
