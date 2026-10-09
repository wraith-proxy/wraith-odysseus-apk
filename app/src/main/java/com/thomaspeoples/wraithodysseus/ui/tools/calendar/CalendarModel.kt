package com.thomaspeoples.wraithodysseus.ui.tools.calendar

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONException

enum class CalMode { Agenda, Month }

/** Months ahead the agenda looks, as the web's (it fetches three months from today). */
const val AGENDA_MONTHS = 3L

/**
 * What stays on this phone: the calendars switched off, the view, and the
 * first day of the week. The web keeps only the last of these; the other two
 * are kept here so the screen opens as it was left.
 */
class CalendarPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("calendar", Context.MODE_PRIVATE)

    fun hidden(): Set<String> = try {
        prefs.getStringSet(KEY_HIDDEN, emptySet())?.toSet() ?: emptySet()
    } catch (_: RuntimeException) {
        emptySet()
    }

    fun saveHidden(hidden: Set<String>) = write { putStringSet(KEY_HIDDEN, hidden) }

    fun mode(): CalMode = try {
        prefs.getString(KEY_MODE, null)?.let { m -> CalMode.entries.firstOrNull { it.name == m } } ?: CalMode.Agenda
    } catch (_: RuntimeException) {
        CalMode.Agenda
    }

    fun saveMode(mode: CalMode) = write { putString(KEY_MODE, mode.name) }

    fun sundayFirst(): Boolean = try {
        prefs.getBoolean(KEY_SUNDAY, false)
    } catch (_: RuntimeException) {
        false
    }

    fun saveSundayFirst(on: Boolean) = write { putBoolean(KEY_SUNDAY, on) }

    private fun write(edit: android.content.SharedPreferences.Editor.() -> Unit) {
        try {
            prefs.edit().apply(edit).apply()
        } catch (_: RuntimeException) {
            // Not remembering is harmless.
        }
    }

    private companion object {
        const val KEY_HIDDEN = "hidden"
        const val KEY_MODE = "mode"
        const val KEY_SUNDAY = "sunday_first"
    }
}

/** [events] are all that were fetched for the current window, before the calendar toggles. */
data class CalendarState(
    val events: List<CalEvent> = emptyList(),
    val calendars: List<CalCalendar> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val mode: CalMode = CalMode.Agenda,
    val sundayFirst: Boolean = false,
    val month: YearMonth = YearMonth.now(),
    val selected: LocalDate = LocalDate.now(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val truncated: Boolean = false,
    val problem: String? = null,
) {
    /** The days the current view shows, up to the exclusive end. */
    fun window(today: LocalDate): Pair<LocalDate, LocalDate> = when (mode) {
        CalMode.Agenda -> today to today.plusMonths(AGENDA_MONTHS)
        CalMode.Month -> monthGrid(month, sundayFirst).let { it.first() to it.last().plusDays(1) }
    }
}

class CalendarViewModel(private val api: CalendarApi, private val prefs: CalendarPrefs) : ViewModel() {
    var state by mutableStateOf(
        CalendarState(hidden = prefs.hidden(), mode = prefs.mode(), sundayFirst = prefs.sundayFirst()),
    )
        private set

    /** True while a save or delete is in flight, so a second tap cannot make a second event. */
    var saving by mutableStateOf(false)
        private set

    /** True while the quick-add text is being read by the server's model. */
    var parsing by mutableStateOf(false)
        private set

    private val say = Channel<String>(Channel.BUFFERED)

    /** Short notices (a failed action) for the screen to put in the snackbar. */
    val messages = say.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    /** Fetch the calendars and the events of the current window. A refresh keeps the old list on screen. */
    fun load(pull: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val today = LocalDate.now()
            val (from, to) = state.window(today)
            state = state.copy(loading = !pull && !state.loaded, refreshing = pull, problem = null)
            try {
                val calendars = api.calendars()
                val page = api.events(from, to)
                state = state.copy(events = page.events, calendars = calendars, truncated = page.truncated, loading = false, refreshing = false, loaded = true)
            } catch (e: ApiException) {
                failed(e)
            } catch (e: IOException) {
                failed(e)
            }
        }
    }

    private fun failed(e: Exception) {
        // A calendar that is already showing is kept; only an empty screen shows the problem.
        val had = state.events.isNotEmpty() || state.calendars.isNotEmpty()
        state = state.copy(loading = false, refreshing = false, loaded = true, problem = if (had) null else userMessage(e))
        if (had) say.trySend(userMessage(e))
    }

    fun setMode(mode: CalMode) {
        if (state.mode == mode) return
        prefs.saveMode(mode)
        state = state.copy(mode = mode, events = emptyList(), loaded = false)
        load()
    }

    fun shiftMonth(by: Long) = goToMonth(state.month.plusMonths(by), null)

    fun goToday() = goToMonth(YearMonth.now(), LocalDate.now())

    private fun goToMonth(month: YearMonth, selected: LocalDate?) {
        state = state.copy(month = month, selected = selected ?: month.atDay(1), events = emptyList(), loaded = false)
        load()
    }

    fun select(day: LocalDate) {
        state = state.copy(selected = day)
    }

    fun toggleCalendar(href: String) {
        val hidden = if (href in state.hidden) state.hidden - href else state.hidden + href
        prefs.saveHidden(hidden)
        state = state.copy(hidden = hidden)
    }

    fun setSundayFirst(on: Boolean) {
        if (state.sundayFirst == on) return
        prefs.saveSundayFirst(on)
        state = state.copy(sundayFirst = on, events = emptyList(), loaded = false)
        load()
    }

    /** Run a call, saying why when it fails. */
    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiException) {
                say.trySend(userMessage(e))
            } catch (e: IOException) {
                say.trySend(userMessage(e))
            } catch (e: JSONException) {
                say.trySend(userMessage(e))
            }
        }
    }

    /**
     * What the editor opens on. The server edits the series whichever
     * occurrence is sent, so a recurring event is re-read as the series: its
     * own start, not the start of the occurrence that was tapped, and saving
     * must not move the whole series to that day.
     */
    fun edit(event: CalEvent, onReady: (CalEvent) -> Unit) = act {
        onReady(if (event.recurring) api.event(event.uid) else event)
    }

    /** Save a new event ([existing] null) or an edit; [onDone] runs once it is saved. */
    fun save(existing: CalEvent?, draft: EventDraft, onDone: () -> Unit) {
        if (saving) return
        saving = true
        act {
            try {
                if (existing == null) api.create(draft) else api.update(existing.uid, draft)
                onDone()
                load(pull = true)
            } finally {
                saving = false
            }
        }
    }

    /** Delete; the list is re-read after, since a series changes many rows. */
    fun delete(event: CalEvent, occurrenceOnly: Boolean, onDone: () -> Unit = {}) {
        if (saving) return
        saving = true
        act {
            try {
                api.delete(event.uid, occurrenceOnly)
                onDone()
                load(pull = true)
            } finally {
                saving = false
            }
        }
    }

    /** Quick add: the server reads [text]; [onResult] gets what it understood. */
    fun quickParse(text: String, onResult: (QuickParse) -> Unit) {
        if (parsing) return
        parsing = true
        act {
            try {
                onResult(api.quickParse(text))
            } finally {
                parsing = false
            }
        }
    }

    /** Tell the user something the model could not do (a result that cannot be read). */
    fun notify(message: String) {
        say.trySend(message)
    }
}
