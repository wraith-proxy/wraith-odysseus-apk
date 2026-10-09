package com.thomaspeoples.wraithodysseus.ui.tools.research

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException

enum class ResearchTab(val label: String) { Research("Research"), Active("Active"), History("History") }

/** A finished report open on its own page; [result] is null until it is read. */
data class DetailState(val item: LibraryItem, val result: ResearchResult? = null, val problem: String? = null)

/** The visual report; [html] is null until it is fetched. */
data class ReportState(val id: String, val query: String, val html: String? = null, val problem: String? = null)

data class ResearchState(
    /** Why the tool cannot be used (no privilege, switched off), or null when it can. */
    val closed: String? = null,
    val checking: Boolean = true,
    val tab: ResearchTab = ResearchTab.Research,
    val query: String = "",
    val settings: ResearchSettings = ResearchSettings(),
    val models: List<ModelChoice> = emptyList(),
    val starting: Boolean = false,
    val jobs: List<LiveJob> = emptyList(),
    val library: List<LibraryItem> = emptyList(),
    val libraryLoading: Boolean = false,
    val libraryRefreshing: Boolean = false,
    val libraryLoaded: Boolean = false,
    val libraryProblem: String? = null,
    val search: String = "",
    val sort: HistorySort = HistorySort.Recent,
    val category: String? = null,
    val detail: DetailState? = null,
    val report: ReportState? = null,
    val spinning: Boolean = false,
) {
    val history: List<LibraryItem> get() = visibleHistory(library, search, sort, category)
    val running: List<LiveJob> get() = jobs.filter { it.running }
}

/**
 * Deep research, as the web UI's panel. The jobs it follows are the server's:
 * [watch] lists them (the web does so every 20 s, to adopt a job started
 * elsewhere) and follows each running one over the SSE feed the web uses,
 * falling back to polling the status route when that breaks. It runs for as
 * long as the screen is composed and stops with it.
 */
class ResearchViewModel(
    private val api: ResearchApi,
    private val allowed: Boolean,
    private val isAdmin: Boolean,
) : ViewModel() {
    var state by mutableStateOf(ResearchState())
        private set

    private val say = Channel<String>(Channel.BUFFERED)

    /** Short notices for the screen to put in the snackbar. */
    val messages = say.receiveAsFlow()

    /** Wakes [watch] early, when a job has just been started. */
    private val kick = Channel<Unit>(Channel.CONFLATED)

    private var libraryJob: Job? = null
    private var detailJob: Job? = null
    private var reportJob: Job? = null

    init {
        viewModelScope.launch {
            if (!allowed) {
                state = state.copy(checking = false, closed = NOT_ALLOWED)
                return@launch
            }
            if (!api.featureEnabled()) {
                state = state.copy(checking = false, closed = SWITCHED_OFF)
                return@launch
            }
            state = state.copy(checking = false)
            kick.trySend(Unit)
            loadLibrary()
            if (isAdmin) {
                try {
                    state = state.copy(models = api.models())
                } catch (_: ApiException) {
                    // The picker stays at Default, as the web's does.
                } catch (_: IOException) {
                }
            }
        }
    }

    // ---- following the server's jobs ----

    /**
     * Keeps the list of running jobs current and each one's progress live.
     * Suspends until cancelled; the screen runs it in a LaunchedEffect so it
     * ends when the screen leaves.
     */
    suspend fun watch() {
        if (!allowed) return
        coroutineScope {
            val following = HashSet<String>()
            while (isActive) {
                if (state.closed == null && !state.checking) {
                    refreshActive()
                    state.jobs.filter { it.running && it.id !in following }.forEach { job ->
                        following += job.id
                        launch {
                            try {
                                follow(job.id)
                            } finally {
                                following -= job.id
                            }
                        }
                    }
                }
                withTimeoutOrNull(ACTIVE_POLL_MS) { kick.receive() }
            }
        }
    }

    private suspend fun refreshActive() {
        try {
            state = state.copy(jobs = mergeActive(state.jobs, api.active()))
        } catch (_: ApiException) {
            // The next round tries again; the jobs on show are still right.
        } catch (_: IOException) {
        }
    }

    private fun job(id: String) = state.jobs.firstOrNull { it.id == id }

    private fun updateJob(id: String, f: (LiveJob) -> LiveJob) {
        state = state.copy(jobs = state.jobs.map { if (it.id == id) f(it) else it })
    }

    private suspend fun follow(id: String) {
        try {
            api.stream(id).collect { frame -> updateJob(id) { applyFrame(it, frame) } }
        } catch (_: ApiException) {
            // Poll instead, as the web does when its EventSource errors.
        } catch (_: IOException) {
        }
        if (job(id)?.running == true) poll(id)
        val done = job(id) ?: return
        if (done.status == "done") {
            state = state.copy(jobs = state.jobs.filter { it.id != id })
            say.trySend("Research finished: ${done.query.take(60)}")
            loadLibrary(quiet = true)
        }
    }

    /** The web's _pollFallback, except a dropped connection waits and tries again rather than failing the job. */
    private suspend fun poll(id: String) {
        delay(POLL_FIRST_MS)
        while (job(id)?.running == true) {
            try {
                val status = api.status(id)
                updateJob(id) { applyStatus(it, status) }
            } catch (e: ApiException) {
                updateJob(id) { it.copy(status = "error", error = e.message) }
                return
            } catch (_: IOException) {
                delay(POLL_OFFLINE_MS)
                continue
            }
            if (job(id)?.running == true) delay(POLL_MS)
        }
    }

    // ---- the start form ----

    fun setQuery(query: String) {
        state = state.copy(query = query)
    }

    fun setSettings(settings: ResearchSettings) {
        state = state.copy(settings = settings)
    }

    fun setTab(tab: ResearchTab) {
        state = state.copy(tab = tab)
    }

    /** Start the typed question. On failure the text stays for a corrected retry, as in the web UI. */
    fun start() {
        val query = state.query.trim()
        if (query.isEmpty() || state.starting) return
        launchStart(query, state.settings, clearForm = true)
    }

    private fun launchStart(query: String, settings: ResearchSettings, clearForm: Boolean) {
        viewModelScope.launch {
            state = state.copy(starting = true)
            try {
                val job = api.start(query, settings)
                state = state.copy(
                    jobs = state.jobs.filter { it.id != job.id } + job,
                    query = if (clearForm) "" else state.query,
                    tab = ResearchTab.Active,
                )
                kick.trySend(Unit)
            } catch (e: ApiException) {
                say.trySend("Research did not start: ${e.message}")
            } catch (e: IOException) {
                say.trySend("Research did not start: ${userMessage(e)}")
            } catch (e: JSONException) {
                say.trySend("Research did not start: ${userMessage(e)}")
            } finally {
                state = state.copy(starting = false)
            }
        }
    }

    fun retry(job: LiveJob) {
        state = state.copy(jobs = state.jobs.filter { it.id != job.id })
        launchStart(job.query, job.settings ?: ResearchSettings(), clearForm = false)
    }

    /** Move a failed job back into the form to change and start again. */
    fun edit(job: LiveJob) {
        state = state.copy(
            jobs = state.jobs.filter { it.id != job.id },
            query = job.query,
            settings = job.settings ?: state.settings,
            tab = ResearchTab.Research,
        )
    }

    fun dismiss(job: LiveJob) {
        state = state.copy(jobs = state.jobs.filter { it.id != job.id })
    }

    /** Stop a running job. The server may say it had already finished; either way the lists catch up. */
    fun cancel(job: LiveJob) {
        viewModelScope.launch {
            try {
                if (api.cancel(job.id)) {
                    updateJob(job.id) { it.copy(status = "cancelled", error = null) }
                } else {
                    say.trySend("It had already finished")
                    kick.trySend(Unit)
                    loadLibrary(quiet = true)
                }
            } catch (e: ApiException) {
                say.trySend(userMessage(e))
            } catch (e: IOException) {
                say.trySend(userMessage(e))
            } catch (e: JSONException) {
                say.trySend(userMessage(e))
            }
        }
    }

    // ---- history ----

    fun loadLibrary(pull: Boolean = false, quiet: Boolean = false) {
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            state = state.copy(libraryLoading = !pull && !quiet && !state.libraryLoaded, libraryRefreshing = pull, libraryProblem = null)
            try {
                state = state.copy(library = api.library(), libraryLoading = false, libraryRefreshing = false, libraryLoaded = true)
            } catch (e: Exception) {
                if (e !is ApiException && e !is IOException) throw e
                state = state.copy(
                    libraryLoading = false,
                    libraryRefreshing = false,
                    libraryLoaded = true,
                    libraryProblem = if (state.library.isEmpty()) userMessage(e) else null,
                )
                if (state.library.isNotEmpty() && !quiet) say.trySend(userMessage(e))
            }
        }
    }

    fun setSearch(search: String) {
        state = state.copy(search = search)
    }

    fun setSort(sort: HistorySort) {
        state = state.copy(sort = sort)
    }

    fun setCategory(category: String?) {
        state = state.copy(category = category)
    }

    fun open(item: LibraryItem) {
        detailJob?.cancel()
        state = state.copy(detail = DetailState(item))
        detailJob = viewModelScope.launch {
            val next = try {
                DetailState(item, result = api.result(item.id))
            } catch (e: ApiException) {
                DetailState(item, problem = userMessage(e))
            } catch (e: IOException) {
                DetailState(item, problem = userMessage(e))
            }
            if (state.detail?.item?.id == item.id) state = state.copy(detail = next)
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        state = state.copy(detail = null)
    }

    /** The visual report, fetched with the app's own authenticated client and shown without network access. */
    fun openReport(id: String, query: String) {
        reportJob?.cancel()
        state = state.copy(report = ReportState(id, query))
        reportJob = viewModelScope.launch {
            val next = try {
                ReportState(id, query, html = api.report(id))
            } catch (e: ApiException) {
                ReportState(id, query, problem = userMessage(e))
            } catch (e: IOException) {
                ReportState(id, query, problem = userMessage(e))
            }
            if (state.report?.id == id) state = state.copy(report = next)
        }
    }

    fun closeReport() {
        reportJob?.cancel()
        state = state.copy(report = null)
    }

    /** "Chat about this research": a new chat the server primes with the report, opened through [onChat]. */
    fun spinoff(id: String, onChat: (String) -> Unit) {
        if (state.spinning) return
        viewModelScope.launch {
            state = state.copy(spinning = true)
            try {
                onChat(api.spinoff(id))
            } catch (e: ApiException) {
                say.trySend("Could not start follow-up chat: ${e.message}")
            } catch (e: IOException) {
                say.trySend("Could not start follow-up chat: ${userMessage(e)}")
            } catch (e: JSONException) {
                say.trySend("Could not start follow-up chat: ${userMessage(e)}")
            } finally {
                state = state.copy(spinning = false)
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                api.delete(id)
                state = state.copy(
                    library = state.library.filter { it.id != id },
                    detail = state.detail?.takeIf { it.item.id != id },
                )
            } catch (e: ApiException) {
                say.trySend(userMessage(e))
            } catch (e: IOException) {
                say.trySend(userMessage(e))
            } catch (e: JSONException) {
                say.trySend(userMessage(e))
            }
        }
    }

    companion object {
        const val NOT_ALLOWED = "Your account is not allowed to use deep research."
        const val SWITCHED_OFF = "Deep research is turned off on this server. An admin can turn it on."
        private const val ACTIVE_POLL_MS = 20_000L
        private const val POLL_FIRST_MS = 3_000L
        private const val POLL_MS = 2_000L
        private const val POLL_OFFLINE_MS = 5_000L
    }
}
