package com.thomaspeoples.wraithodysseus.ui.email

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thomaspeoples.wraithodysseus.email.EmailAccount
import com.thomaspeoples.wraithodysseus.email.EmailApi
import com.thomaspeoples.wraithodysseus.email.EmailSummary
import com.thomaspeoples.wraithodysseus.email.UnreadInbox
import com.thomaspeoples.wraithodysseus.email.UnreadPass
import com.thomaspeoples.wraithodysseus.email.describe
import com.thomaspeoples.wraithodysseus.email.mergeUnread
import com.thomaspeoples.wraithodysseus.email.pageUnread
import com.thomaspeoples.wraithodysseus.email.unreadPasses
import com.thomaspeoples.wraithodysseus.net.ApiException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class InboxState(
    val inbox: UnreadInbox = UnreadInbox(emptyList(), 0, emptyList()),
    /** Account id -> name for the accounts being listed, for row labels. */
    val accountNames: Map<String, String> = emptyMap(),
    /** False until the first load finishes, so "no mail" is not shown early. */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** Set when even the account list could not be fetched. */
    val problem: String? = null,
    /** True when there are accounts but none of them is enabled. */
    val noAccounts: Boolean = false,
)

/** Kept per back-stack entry, so coming back from a message does not page
 * every account again. */
class InboxViewModel(private val api: EmailApi) : ViewModel() {
    var state by mutableStateOf(InboxState())
        private set

    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            state = state.copy(loading = true, problem = null)
            val accounts = try {
                api.accounts()
            } catch (e: ApiException) {
                state = state.copy(loading = false, loaded = true, problem = e.message)
                return@launch
            } catch (e: IOException) {
                state = state.copy(loading = false, loaded = true, problem = describe(e))
                return@launch
            }
            val passes = unreadPasses(accounts)
            val names = passes.filterNotNull().associate { it.id to it.name }
            val done = mutableListOf<Pair<EmailAccount?, UnreadPass>>()
            // One account at a time, showing each as it lands: the first
            // screenful should not wait for the slowest mailbox.
            for (account in passes) {
                done += account to pageUnread { offset -> api.unreadPage(account?.id, offset) }
                state = state.copy(inbox = mergeUnread(done), accountNames = names)
            }
            state = state.copy(
                inbox = mergeUnread(done),
                accountNames = names,
                loading = false,
                loaded = true,
                noAccounts = passes.isEmpty(),
            )
        }
    }

    /** Opening a message marks it seen on the server, so it leaves the
     * unread list here too without a reload. */
    fun opened(email: EmailSummary) {
        val inbox = state.inbox
        if (inbox.emails.none { it.key == email.key }) return
        state = state.copy(
            inbox = inbox.copy(
                emails = inbox.emails.filterNot { it.key == email.key },
                count = (inbox.count - 1).coerceAtLeast(0),
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(api: EmailApi, onOpen: (EmailSummary) -> Unit, onCompose: () -> Unit, menu: @Composable () -> Unit = {}) {
    val vm = viewModel { InboxViewModel(api) }
    val state = vm.state
    // Labels only help when there is more than one mailbox in the list.
    val showAccount = state.accountNames.size > 1

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (state.loaded || state.inbox.count > 0) "Unread (${state.inbox.count})" else "Unread") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onCompose) {
                        Icon(Icons.Filled.Create, contentDescription = "Compose")
                    }
                    menu()
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.problem?.let { Banner(it, MaterialTheme.colorScheme.error) }
            state.inbox.errors.forEach { Banner(it, MaterialTheme.colorScheme.error) }

            when {
                state.noAccounts -> Centered("No enabled email accounts. Add or enable one in the web UI.")
                state.loaded && state.problem == null && state.inbox.emails.isEmpty() -> Centered("No unread mail")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.inbox.emails, key = { it.key }) { email ->
                        EmailRow(
                            email = email,
                            account = if (showAccount) state.accountNames[email.accountId] else null,
                            onClick = {
                                vm.opened(email)
                                onOpen(email)
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmailRow(email: EmailSummary, account: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The unread dot: everything here is unread, but it is the web UI's
        // cue and it lines the rows up.
        Box(
            Modifier.padding(top = 6.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    email.fromName.ifBlank { email.fromAddress },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    listDate(email.dateEpoch),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                email.subject.ifBlank { "(no subject)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            account?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

/** A full-width notice strip, tinted with [color]. */
@Composable
internal fun Banner(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(color.copy(alpha = 0.1f), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Time for today, day and month this year, full date before that. */
internal fun listDate(epochSeconds: Double, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val date = Instant.ofEpochMilli((epochSeconds * 1000).toLong()).atZone(zone)
    val today = now.atZone(zone)
    val pattern = when {
        date.toLocalDate() == today.toLocalDate() -> "HH:mm"
        date.year == today.year -> "d MMM"
        else -> "d MMM yyyy"
    }
    return date.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
}
