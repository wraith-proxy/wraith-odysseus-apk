package com.thomaspeoples.wraithodysseus.ui.email

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.email.Contact
import com.thomaspeoples.wraithodysseus.email.EmailApi
import com.thomaspeoples.wraithodysseus.email.SchedulePreset
import com.thomaspeoples.wraithodysseus.email.StagedFile
import com.thomaspeoples.wraithodysseus.email.addRecipients
import com.thomaspeoples.wraithodysseus.email.bareAddress
import com.thomaspeoples.wraithodysseus.email.chipLabel
import com.thomaspeoples.wraithodysseus.email.formatAddress
import com.thomaspeoples.wraithodysseus.email.splitAddresses
import com.thomaspeoples.wraithodysseus.ui.common.ActionSheet
import com.thomaspeoples.wraithodysseus.ui.common.SheetAction
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import java.time.ZonedDateTime
import kotlinx.coroutines.delay

/** Saves a list of strings across a rotation. */
internal val StringList = listSaver<List<String>, String>(save = { it }, restore = { it })

/** Saves staged attachments across a rotation; the server keeps the files. */
internal val StagedList = listSaver<List<StagedFile>, String>(
    save = { files -> files.map { "${it.size}\u0001${it.token}\u0001${it.filename}" } },
    restore = { rows ->
        rows.mapNotNull { row ->
            row.split('\u0001', limit = 3).takeIf { it.size == 3 }?.let { StagedFile(it[1], it[2], it[0].toLongOrNull() ?: 0) }
        }
    },
)

/**
 * To, Cc or Bcc: the people already added are chips, and what is being typed
 * is committed as a chip on comma, semicolon, space after an address, the
 * keyboard's Next, or leaving the field. Suggestions come from
 * [EmailApi.contacts] a moment after typing stops, and tapping one adds it.
 * Text still in the box when Send is tapped is counted by the caller.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecipientField(
    label: String,
    api: EmailApi,
    admin: Boolean,
    chips: List<String>,
    onChips: (List<String>) -> Unit,
    text: String,
    onText: (String) -> Unit,
    enabled: Boolean = true,
) {
    var suggestions by remember { mutableStateOf<List<Contact>>(emptyList()) }

    fun commit(raw: String) {
        val added = splitAddresses(raw.replace(';', ','))
        if (added.isNotEmpty()) onChips(addRecipients(chips, added))
        onText("")
    }

    LaunchedEffect(text, admin) {
        // The fragment after the last separator is what is being looked up.
        val fragment = text.substringAfterLast(',').trim()
        if (fragment.isEmpty() || '<' in fragment) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        val have = chips.map(::bareAddress).toSet()
        suggestions = api.contacts(fragment, admin).filter { it.address.lowercase() !in have }.distinctBy { it.address.lowercase() }.take(6)
    }

    Column {
        if (chips.isNotEmpty()) {
            FlowRow(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { entry ->
                    InputChip(
                        selected = false,
                        enabled = enabled,
                        onClick = { onChips(chips - entry) },
                        label = { Text(chipLabel(entry), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(Lucide.X, "Remove ${chipLabel(entry)}", Modifier.size(14.dp)) },
                        shape = CircleShape,
                    )
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                // A comma or semicolon closes everything before it; so does a space after an address.
                val sep = v.indexOfLast { it == ',' || it == ';' }
                when {
                    sep >= 0 -> { commit(v.substring(0, sep)); onText(v.substring(sep + 1).trimStart()) }
                    v.endsWith(" ") && '@' in v && '<' !in v -> commit(v)
                    else -> onText(v)
                }
            },
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = androidx.compose.ui.text.input.ImeAction.Next),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onNext = { commit(text) }),
            colors = composeFieldColors(),
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && text.isNotBlank()) commit(text) },
        )
        if (suggestions.isNotEmpty() && text.isNotBlank()) {
            Column(Modifier.fillMaxWidth().background(OdyColors.InputBg, MaterialTheme.shapes.small)) {
                suggestions.forEach { c ->
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            onChips(addRecipients(chips, listOf(formatAddress(c.name, c.address))))
                            onText("")
                            suggestions = emptyList()
                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        if (c.name.isNotBlank() && !c.name.equals(c.address, ignoreCase = true)) {
                            Text(c.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(c.address, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The files going out with the message; tapping a chip removes it (and DELETEs the upload). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttachmentRow(files: List<StagedFile>, busy: Int, onRemove: (StagedFile) -> Unit, enabled: Boolean) {
    if (files.isEmpty() && busy == 0) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        files.forEach { f ->
            InputChip(
                selected = false,
                enabled = enabled,
                onClick = { onRemove(f) },
                label = { Text("${f.filename} · ${sizeLabel(f.size)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Lucide.Paperclip, null, Modifier.size(14.dp)) },
                trailingIcon = { Icon(Lucide.X, "Remove ${f.filename}", Modifier.size(14.dp)) },
                shape = CircleShape,
            )
        }
        if (busy > 0) Text("Uploading ${busy}…", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** The web's Schedule Send box: four presets or a picked date and time. [onPick] gets the local time chosen. */
@Composable
internal fun ScheduleSheet(context: Context, now: () -> ZonedDateTime, onPick: (ZonedDateTime) -> Unit, onDismiss: () -> Unit) {
    val actions = SchedulePreset.entries.map { p ->
        SheetAction(p.label, Lucide.Clock) { onPick(com.thomaspeoples.wraithodysseus.email.presetTime(p, now())) }
    } + SheetAction("Pick date and time…", Lucide.Calendar) { pickDateTime(context, now(), onPick) }
    ActionSheet(actions, onDismiss, title = "Schedule send")
}

/** Date, then time, with the framework's own pickers; starts an hour ahead like the web's box. */
private fun pickDateTime(context: Context, now: ZonedDateTime, onPick: (ZonedDateTime) -> Unit) {
    val start = now.plusHours(1)
    DatePickerDialog(
        context,
        { _, y, m, d ->
            TimePickerDialog(
                context,
                { _, h, min -> onPick(ZonedDateTime.of(y, m + 1, d, h, min, 0, 0, now.zone)) },
                start.hour,
                start.minute,
                android.text.format.DateFormat.is24HourFormat(context),
            ).show()
        },
        start.year,
        start.monthValue - 1,
        start.dayOfMonth,
    ).apply { datePicker.minDate = System.currentTimeMillis() - 1000 }.show()
}

@Composable
internal fun composeFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = OdyColors.InputBg,
    unfocusedContainerColor = OdyColors.InputBg,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
)
