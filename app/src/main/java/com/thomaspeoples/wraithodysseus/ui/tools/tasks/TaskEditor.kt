package com.thomaspeoples.wraithodysseus.ui.tools.tasks

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.ui.common.parseServerTime
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What the editor's boxes hold, all as the text the person sees so that a
 * half-typed number survives rotation. Time and date are local; the
 * conversion to the server's UTC happens only in [TasksApi.updateBody].
 */
data class TaskForm(
    val name: String,
    val prompt: String,
    /** One of [SCHEDULES]. */
    val schedule: String,
    /** Local "HH:mm". */
    val time: String,
    /** A weekday 0 (Monday) to 6 for weekly, the day of the month for monthly. */
    val day: String,
    /** Local "YYYY-MM-DD", for "once". */
    val date: String,
    val cron: String,
    /** "Every N occurrences" of an event task. */
    val count: String,
) {
    companion object {
        val SCHEDULES = listOf("daily", "weekly", "monthly", "once", "cron")

        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * The form for [task], filled like the web's (tasks.js _showForm): the
         * stored UTC time shown in local time, an event count of 5 when there
         * is none, 09:00 when no time is stored. A once task's time comes from
         * its date, because that is the moment it runs; the app creates once
         * tasks without a scheduled_time, so the stored one is only a default.
         */
        fun of(task: Task, zone: ZoneId, now: Instant): TaskForm {
            val schedule = task.schedule?.takeIf { it in SCHEDULES } ?: "daily"
            val onceAt = if (schedule == "once") parseServerTime(task.scheduledDate)?.atZone(zone)?.toLocalDateTime() else null
            val time = onceAt?.toLocalTime()
                ?: task.scheduledTime?.let { runCatching { TasksApi.utcTimeToLocal(it, zone, now) }.getOrNull() }
                ?: LocalTime.of(9, 0)
            return TaskForm(
                name = task.name,
                prompt = task.prompt,
                schedule = schedule,
                time = time.format(HHMM),
                day = (task.scheduledDay ?: if (schedule == "monthly") 1 else 0).toString(),
                date = (onceAt?.toLocalDate() ?: now.atZone(zone).toLocalDate()).toString(),
                cron = task.cronExpression.orEmpty(),
                count = (task.triggerCount ?: 5).toString(),
            )
        }

        val Saver = listSaver<TaskForm, String>(
            save = { listOf(it.name, it.prompt, it.schedule, it.time, it.day, it.date, it.cron, it.count) },
            restore = { TaskForm(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7]) },
        )
    }
}

private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val SCHEDULE_LABELS = mapOf("daily" to "Daily", "weekly" to "Weekly", "monthly" to "Monthly", "once" to "Once", "cron" to "Cron")

/**
 * The web's Edit Task form for one task, as a screen. It offers the fields
 * the web does for the kinds this app can show: the name; a prompt or
 * research task's prompt; and, by trigger, the schedule, an event task's
 * count, nothing more for a webhook. Changing a task's type or trigger, the
 * action, model, chain, persona, notifications and output stay in the web UI,
 * and are left as they are.
 */
@Composable
fun TaskEditor(task: Task, saving: Boolean, onSave: (TaskForm) -> Unit, onCancel: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    var form by rememberSaveable(task.id, stateSaver = TaskForm.Saver) { mutableStateOf(TaskForm.of(task, zone, Instant.now())) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    BackHandler(enabled = !saving, onBack = onCancel)

    fun save() {
        problem = TasksApi.editProblem(task, form, zone, Instant.now())
        if (problem == null) onSave(form)
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel, enabled = !saving) { Icon(Lucide.ArrowLeft, "Close") }
            Text("Edit task", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = ::save, enabled = !saving) { Text("Save") }
        }
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { form = form.copy(name = it); problem = null },
                label = { Text("Name") },
                singleLine = true,
                enabled = !saving,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            if (task.editsPrompt) {
                OutlinedTextField(
                    value = form.prompt,
                    onValueChange = { form = form.copy(prompt = it); problem = null },
                    label = { Text(if (task.taskType == "research") "Research question" else "Prompt") },
                    minLines = 4,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("This task runs ${task.action ?: "an action"}. Its action is set in the web UI.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            when (task.triggerType) {
                "schedule" -> ScheduleFields(form, enabled = !saving, context = context) { form = it; problem = null }
                "event" -> {
                    Text(
                        "Runs on every ${(task.triggerEvent ?: "event").replace('_', ' ')}.",
                        color = Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = form.count,
                        onValueChange = { form = form.copy(count = it.filter(Char::isDigit).take(4)); problem = null },
                        label = { Text("Every N occurrences") },
                        singleLine = true,
                        enabled = !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                "webhook" -> Text("Runs when its webhook URL is called. The URL is shown in the web UI.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ScheduleFields(form: TaskForm, enabled: Boolean, context: Context, onChange: (TaskForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Frequency", color = Muted, style = MaterialTheme.typography.labelLarge)
        ChipRow(TaskForm.SCHEDULES.map { SCHEDULE_LABELS.getValue(it) to it }, form.schedule, enabled) { onChange(form.copy(schedule = it)) }
        when (form.schedule) {
            "weekly" -> {
                Text("Day of week", color = Muted, style = MaterialTheme.typography.labelLarge)
                val selected = form.day.trim().toIntOrNull()?.takeIf { it in 0..6 } ?: 0
                ChipRow(DAY_NAMES.mapIndexed { i, d -> d to i.toString() }, selected.toString(), enabled) { onChange(form.copy(day = it)) }
            }
            "monthly" -> OutlinedTextField(
                value = form.day,
                onValueChange = { onChange(form.copy(day = it.filter(Char::isDigit).take(2))) },
                label = { Text("Day of month") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            "once" -> {
                val date = runCatching { LocalDate.parse(form.date) }.getOrElse { LocalDate.now() }
                PickerRow("Date", date.format(DATE_FORMAT), enabled) {
                    pickDate(context, date) { onChange(form.copy(date = it.toString())) }
                }
            }
            "cron" -> {
                OutlinedTextField(
                    value = form.cron,
                    onValueChange = { onChange(form.copy(cron = it)) },
                    label = { Text("Cron expression (UTC)") },
                    placeholder = { Text("*/30 * * * *") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("min hour day month weekday, e.g. \"0 */2 * * *\" is every 2 hours.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (form.schedule != "cron") {
            val time = runCatching { LocalTime.parse(form.time) }.getOrElse { LocalTime.of(9, 0) }
            PickerRow("Time", time.format(TIME_FORMAT), enabled) {
                pickTime(context, time) { onChange(form.copy(time = it.format(DateTimeFormatter.ofPattern("HH:mm")))) }
            }
        }
    }
}

/** A row of single-choice chips, scrolling sideways when they do not fit. */
@Composable
private fun ChipRow(choices: List<Pair<String, String>>, selected: String?, enabled: Boolean, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(choices) { (label, value) ->
            FilterChip(selected = selected == value, onClick = { onPick(value) }, enabled = enabled, label = { Text(label) })
        }
    }
}

@Composable
private fun PickerRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Muted, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(value) }
    }
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun pickDate(context: Context, current: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, current.year, current.monthValue - 1, current.dayOfMonth).show()
}

private fun pickTime(context: Context, current: LocalTime, onPicked: (LocalTime) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPicked(LocalTime.of(h, m)) }, current.hour, current.minute, DateFormat.is24HourFormat(context)).show()
}
