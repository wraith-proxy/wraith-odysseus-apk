package com.thomaspeoples.wraithodysseus.ui.email

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.thomaspeoples.wraithodysseus.email.ContactResult
import com.thomaspeoples.wraithodysseus.email.EmailApi
import com.thomaspeoples.wraithodysseus.email.EmailAttachment
import com.thomaspeoples.wraithodysseus.email.EmailMessage
import com.thomaspeoples.wraithodysseus.email.EmailSummary
import com.thomaspeoples.wraithodysseus.email.ReminderPreset
import com.thomaspeoples.wraithodysseus.email.TRANSLATE_LANGUAGES
import com.thomaspeoples.wraithodysseus.email.TranslateResult
import com.thomaspeoples.wraithodysseus.email.contactName
import com.thomaspeoples.wraithodysseus.email.fallbackMime
import com.thomaspeoples.wraithodysseus.email.forwardDraft
import com.thomaspeoples.wraithodysseus.email.hasMultipleRecipients
import com.thomaspeoples.wraithodysseus.email.hasRemoteImages
import com.thomaspeoples.wraithodysseus.email.htmlToText
import com.thomaspeoples.wraithodysseus.email.isLikelySignatureImage
import com.thomaspeoples.wraithodysseus.email.isSentFolder
import com.thomaspeoples.wraithodysseus.email.neighbours
import com.thomaspeoples.wraithodysseus.email.readerDocument
import com.thomaspeoples.wraithodysseus.email.reminderNote
import com.thomaspeoples.wraithodysseus.email.reminderPresets
import com.thomaspeoples.wraithodysseus.email.replyAllDraft
import com.thomaspeoples.wraithodysseus.email.safeFileName
import com.thomaspeoples.wraithodysseus.email.toSummary
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.ActionSheet
import com.thomaspeoples.wraithodysseus.ui.common.ConfirmDialog
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.SheetAction
import com.thomaspeoples.wraithodysseus.ui.common.openInBrowser
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The flags the read endpoint does not return, tracked as the user changes them. */
private data class ReadFlags(val read: Boolean = true, val flagged: Boolean = false, val answered: Boolean = false)

/** How far a horizontal drag has to go, in dp, to count as a swipe to the next message. */
private const val SWIPE_DP = 100

/**
 * One message, as the web UI's reader. The body is the sandboxed HTML (or
 * plain text on request); the buttons are AI reply, Reply, Reply all, Forward
 * and More. Reply, Reply all, Forward and AI reply open [ComposeScreen] in place, so the original they quote never has to
 * travel through a navigation argument. The server marks the message seen as
 * part of the read.
 *
 * The read returns no flags, so the favorite / done / read state is taken
 * from the inbox row in [ReadHandoff] and kept up to date here as it changes;
 * with no such row it starts as read, not favorited, not done. Previous and
 * next walk [ReadHandoff.siblings] when the inbox has provided it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadScreen(api: EmailApi, accountId: String?, folder: String, uid: String, onBack: () -> Unit, isAdmin: Boolean = false) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    // Which message is open. Arrows and swipes change it in place.
    var curAcct by rememberSaveable { mutableStateOf(accountArg(accountId)) }
    var curFolder by rememberSaveable { mutableStateOf(folder) }
    var curUid by rememberSaveable { mutableStateOf(uid) }
    val acct = accountFromArg(curAcct)
    val key = "$acct/$curFolder/$curUid"

    var list by remember { mutableStateOf(ReadHandoff.siblings) }
    val hint = remember { ReadHandoff.opened }
    val nb = remember(list, key) { neighbours(list, key) }

    var message by remember(key) { mutableStateOf<EmailMessage?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var flags by remember(key) {
        val row = (list + listOfNotNull(hint)).firstOrNull { it.key == key }
        mutableStateOf(row?.let { ReadFlags(it.isRead, it.isFlagged, it.isAnswered) } ?: ReadFlags())
    }
    var attachments by remember(key) { mutableStateOf<List<EmailAttachment>>(emptyList()) }
    var attLoading by remember(key) { mutableStateOf(false) }
    var attFailed by remember(key) { mutableStateOf(false) }
    var openingKey by remember(key) { mutableStateOf<String?>(null) }
    var translation by remember(key) { mutableStateOf<TranslationState>(TranslationState.Idle) }
    var allowImages by remember(key) { mutableStateOf(false) }
    var plain by rememberSaveable(key) { mutableStateOf(false) }
    var mine by remember { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }

    var composeMode by rememberSaveable(key) { mutableStateOf("") }
    // The context typed for the AI draft; the composer starts generating
    // with it when aiStart is set, and Reply leaves it unset.
    var aiHint by rememberSaveable(key) { mutableStateOf("") }
    var aiStart by rememberSaveable(key) { mutableStateOf(false) }
    var askHint by rememberSaveable(key) { mutableStateOf(false) }

    var moreSheet by remember { mutableStateOf(false) }
    var translateSheet by remember { mutableStateOf(false) }
    var customLanguage by remember { mutableStateOf(false) }
    var remindSheet by remember { mutableStateOf(false) }
    var confirmPermanent by remember { mutableStateOf(false) }

    val scroll = rememberScrollState()

    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    // The attachment list arrives separately. A failure is shown, with Retry,
    // and Forward waits: sending without it would silently drop the files.
    suspend fun loadAttachments() {
        attLoading = true
        attFailed = false
        try {
            attachments = api.attachments(acct, curFolder, curUid)
        } catch (_: ApiException) {
            attFailed = true
        } catch (_: IOException) {
            attFailed = true
        } finally {
            attLoading = false
        }
    }

    LaunchedEffect(key) {
        scroll.scrollTo(0)
        try {
            val m = api.read(acct, curFolder, curUid)
            message = m
            if (!m.markSeenFailed) flags = flags.copy(read = true)
            if (m.attachmentsDeferred) {
                loadAttachments()
            } else {
                attachments = m.attachments
            }
        } catch (e: ApiException) {
            error = e.message
        } catch (e: IOException) {
            error = userMessage(e)
        }
    }
    // The user's own addresses, so Reply all leaves them out. Without them
    // nothing is excluded, which only makes Reply all a little longer.
    LaunchedEffect(Unit) {
        try {
            mine = api.accounts().map { it.fromAddress }.filter { it.isNotBlank() }
        } catch (_: ApiException) {
        } catch (_: IOException) {
        }
    }

    fun goTo(row: EmailSummary) {
        ReadHandoff.opened = row
        curAcct = accountArg(row.accountId)
        curFolder = row.folder
        curUid = row.uid
    }

    /** Moves on after the open message left its folder: the next one, else the previous, else back. */
    fun leaveMessage() {
        val n = neighbours(list, key)
        list = list.filterNot { it.key == key }
        ReadHandoff.siblings = list
        ReadHandoff.removed += key
        val target = n?.next ?: n?.previous
        if (target != null) goTo(target) else onBack()
    }

    /** One server call with the busy guard. A failure goes to the snackbar and leaves the screen as it was. */
    fun act(success: String? = null, leave: Boolean = false, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val failed = try {
                block()
                null
            } catch (e: ApiException) {
                e.message
            } catch (e: IOException) {
                userMessage(e)
            }
            busy = false
            when {
                failed != null -> snackbar.showSnackbar(failed)
                leave -> leaveMessage()
                success != null -> snackbar.showSnackbar(success)
            }
        }
    }

    fun translate(language: String) {
        val m = message ?: return
        val text = m.body.ifEmpty { htmlToText(m.bodyHtml) }
        if (text.isBlank()) {
            say("No email body to translate")
            return
        }
        translation = TranslationState.Busy(language)
        scope.launch {
            val sender = if (m.fromName.isBlank()) m.fromAddress else "${m.fromName} <${m.fromAddress}>"
            translation = try {
                when (val r = api.translate(text, m.subject, sender, language)) {
                    is TranslateResult.Done -> TranslationState.Done(r.language, r.text)
                    TranslateResult.SameLanguage -> TranslationState.Same(language)
                    TranslateResult.StillWorking -> TranslationState.Working(language)
                }
            } catch (e: ApiException) {
                TranslationState.Failed(language, e.message)
            } catch (e: IOException) {
                TranslationState.Failed(language, userMessage(e))
            }
        }
    }

    /** Saves [a] under the app cache; the folder and uid are those of the message that holds it. */
    suspend fun download(a: EmailAttachment): File {
        val sourceUid = a.sourceUid ?: curUid
        val file = File(context.cacheDir, "attachments/${safeFileName(sourceUid)}-${a.index}/${safeFileName(a.filename)}")
        api.saveAttachment(acct, a.sourceFolder ?: curFolder, sourceUid, a.index, file)
        return file
    }

    fun attKey(a: EmailAttachment) = "${a.sourceUid ?: curUid}/${a.index}"

    fun withAttachment(a: EmailAttachment, block: suspend () -> Unit) {
        if (openingKey != null) return
        openingKey = attKey(a)
        scope.launch {
            val failed = try {
                block()
                null
            } catch (e: ApiException) {
                e.message
            } catch (e: IOException) {
                userMessage(e)
            }
            openingKey = null
            failed?.let { say(it) }
        }
    }

    fun openAttachment(a: EmailAttachment) = withAttachment(a) {
        if (!viewFile(context, download(a))) throw ApiException(0, "No app can open ${a.filename}")
    }

    fun addToCalendar(a: EmailAttachment) = withAttachment(a) {
        val bytes = download(a).let { f -> withContext(Dispatchers.IO) { f.readBytes() } }
        val r = api.importCalendar(a.filename, bytes)
        say(
            when {
                r.imported == 0 && r.skipped > 0 -> "Already in your calendar"
                else -> "${r.imported} event${if (r.imported == 1) "" else "s"} added to ${r.calendar.ifBlank { "calendar" }}"
            },
        )
    }

    val loaded = message
    if (loaded != null && composeMode.isNotEmpty()) {
        val close = { composeMode = ""; aiStart = false }
        BackHandler { close() }
        when (composeMode) {
            "replyAll" -> ComposeScreen(
                api = api,
                onDone = close,
                reply = loaded,
                replyAccountId = acct,
                prefill = replyAllDraft(loaded, acct, mine),
                title = "Reply all",
                isAdmin = isAdmin,
            )
            "forward" -> ComposeScreen(
                api = api,
                onDone = close,
                prefill = forwardDraft(loaded, acct),
                title = "Forward",
                forward = ForwardSource(acct, curFolder, curUid, attachments.filterNot(::isLikelySignatureImage)),
                isAdmin = isAdmin,
            )
            else -> ComposeScreen(
                api = api,
                onDone = close,
                reply = loaded,
                replyAccountId = acct,
                generateWithHint = if (aiStart) aiHint else null,
                isAdmin = isAdmin,
            )
        }
        return
    }

    val swipePx = with(androidx.compose.ui.platform.LocalDensity.current) { SWIPE_DP.dp.toPx() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { nb?.let { Text("${it.position} of ${it.total}", style = MaterialTheme.typography.labelLarge) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (nb != null) {
                        IconButton(enabled = nb.previous != null, onClick = { nb.previous?.let(::goTo) }) { Icon(Lucide.ChevronLeft, "Previous message") }
                        IconButton(enabled = nb.next != null, onClick = { nb.next?.let(::goTo) }) { Icon(Lucide.ChevronRight, "Next message") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                error != null -> Column { Banner(error.orEmpty(), MaterialTheme.colorScheme.error) }
                loaded == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(nb) {
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragEnd = {
                                    if (total > swipePx) nb?.previous?.let(::goTo) else if (total < -swipePx) nb?.next?.let(::goTo)
                                },
                                onDragCancel = { total = 0f },
                            ) { _, delta -> total += delta }
                        }
                        .verticalScroll(scroll),
                ) {
                    if (loaded.markSeenFailed) Banner("Could not mark this message read on the server.", OdyColors.Warn)
                    ReadHeader(
                        loaded,
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Email address", loaded.fromAddress))
                            say("Address copied")
                        },
                        onSave = {
                            act { saveSender(api, loaded, ::say) }
                        },
                    )
                    ReadActions(
                        replyAll = hasMultipleRecipients(loaded, mine),
                        forwardEnabled = !attLoading && !attFailed,
                        onAi = { askHint = true },
                        onReply = { aiStart = false; composeMode = "reply" },
                        onReplyAll = { composeMode = "replyAll" },
                        onForward = { composeMode = "forward" },
                        onMore = { moreSheet = true },
                    )
                    if (askHint) {
                        AiReplyDialog(
                            initial = aiHint,
                            onDismiss = { askHint = false },
                            onConfirm = { aiHint = it; aiStart = true; askHint = false; composeMode = "reply" },
                        )
                    }
                    TranslationPanel(translation, onRetry = ::translate, onDismiss = { translation = TranslationState.Idle })
                    AttachmentsBlock(
                        attachments = attachments,
                        related = loaded.relatedAttachments,
                        loading = attLoading,
                        failed = attFailed,
                        onRetry = { scope.launch { loadAttachments() } },
                        busyKey = openingKey,
                        onOpen = ::openAttachment,
                        onCalendar = ::addToCalendar,
                        keyOf = ::attKey,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    val hasHtml = loaded.bodyHtml.isNotBlank()
                    if (hasHtml) {
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { plain = !plain }) { Text(if (plain) "Show formatted" else "Show plain text") }
                            if (!plain && !allowImages && hasRemoteImages(loaded.bodyHtml)) {
                                TextButton(onClick = { allowImages = true }) {
                                    Icon(Lucide.Image, null, Modifier.padding(end = 6.dp))
                                    Text("Load images")
                                }
                            }
                        }
                    }
                    if (hasHtml && !plain) {
                        val fg = cssColor(MaterialTheme.colorScheme.onBackground)
                        val link = cssColor(MaterialTheme.colorScheme.primary)
                        val rule = cssColor(MaterialTheme.colorScheme.outline)
                        val document = remember(loaded.bodyHtml, fg, link, rule) { readerDocument(loaded.bodyHtml, fg, link, rule) }
                        val cid = remember(key) { CidFetcher(api, acct, curFolder, curUid) }
                        HtmlBody(
                            document = document,
                            allowImages = allowImages,
                            fetchCid = cid::fetch,
                            onLink = { url ->
                                val scheme = url.substringBefore(':').lowercase()
                                if (scheme in LINK_SCHEMES) {
                                    if (!openInBrowser(context, url)) say("No app to open it with")
                                }
                            },
                            onSwipe = { dir -> (if (dir > 0) nb?.next else nb?.previous)?.let(::goTo) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                loaded.body.ifEmpty { htmlToText(loaded.bodyHtml) },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (moreSheet && loaded != null) {
        val actions = buildList {
            add(SheetAction("Remind to reply", Lucide.AlarmClock) { remindSheet = true })
            add(SheetAction("Translate…", Lucide.Languages) { translateSheet = true })
            add(SheetAction(if (flags.read) "Mark unread" else "Mark read", if (flags.read) Lucide.Mail else Lucide.MailOpen) {
                act(if (flags.read) "Marked unread. Refresh the inbox to see it there again." else "Marked read") {
                    if (flags.read) api.markUnread(acct, curFolder, curUid) else api.markRead(acct, curFolder, curUid)
                    flags = flags.copy(read = !flags.read)
                }
            })
            add(SheetAction(if (flags.flagged) "Unfavorite" else "Favorite", Lucide.Bookmark) {
                val on = !flags.flagged
                act(if (on) "Favorited" else "Removed from favorites") {
                    api.flag(acct, curFolder, curUid, on)
                    flags = flags.copy(flagged = on)
                }
            })
            if (!isSentFolder(curFolder)) {
                add(SheetAction(if (flags.answered) "Not done" else "Done", Lucide.Check) {
                    val done = !flags.answered
                    act(if (done) "Marked done" else "Marked not done") {
                        // Like the web: Done also reads it; undoing it leaves the read state.
                        if (done) {
                            api.markAnswered(acct, curFolder, curUid)
                            api.markRead(acct, curFolder, curUid)
                            flags = flags.copy(answered = true, read = true)
                        } else {
                            api.clearAnswered(acct, curFolder, curUid)
                            flags = flags.copy(answered = false)
                        }
                    }
                })
            }
            add(SheetAction("Archive", Lucide.Archive) { act(leave = true) { api.archive(acct, curFolder, curUid) } })
            add(SheetAction("Save sender to contacts", Lucide.UserPlus) { act { saveSender(api, loaded, ::say) } })
            add(SheetAction("Move to Spam", Lucide.ShieldAlert) { act(leave = true) { api.move(acct, curFolder, curUid, "Junk") } })
            add(SheetAction("Move to Trash", Lucide.Trash2) { act(leave = true) { api.delete(acct, curFolder, curUid, loaded.messageId) } })
            add(SheetAction("Delete permanently", Lucide.Trash2, destructive = true) { confirmPermanent = true })
        }
        ActionSheet(actions, onDismiss = { moreSheet = false }, title = loaded.subject.ifBlank { "(no subject)" })
    }

    if (translateSheet) {
        val actions = TRANSLATE_LANGUAGES.map { language -> SheetAction(language, Lucide.Languages) { translate(language) } } +
            SheetAction("Other language…", Lucide.Pencil) { customLanguage = true }
        ActionSheet(actions, onDismiss = { translateSheet = false }, title = "Translate to")
    }
    if (customLanguage) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { customLanguage = false },
            title = { Text("Translate to", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(typed, { typed = it }, singleLine = true, placeholder = { Text("Write language...") })
            },
            confirmButton = {
                TextButton(enabled = typed.isNotBlank(), onClick = { customLanguage = false; translate(typed.trim()) }) { Text("Translate") }
            },
            dismissButton = { TextButton(onClick = { customLanguage = false }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (remindSheet && loaded != null) {
        val presets = reminderPresets(LocalDateTime.now(ZoneId.systemDefault()))
        fun remind(due: LocalDateTime?) = act("Reminder set") {
            api.createNote(reminderNote(loaded.toSummary(acct), due, api.origin()))
        }
        val actions = presets.map { p -> SheetAction(presetLabel(p), Lucide.AlarmClock) { remind(p.due) } } +
            SheetAction("Pick date…", Lucide.CalendarPlus) { pickDateTime(context, presets[1].due) { remind(it) } }
        ActionSheet(actions, onDismiss = { remindSheet = false }, title = "Remind me")
    }

    if (confirmPermanent) {
        ConfirmDialog(
            title = "Delete permanently?",
            text = "This message is removed from the mail server for good, not moved to Trash.",
            confirm = "Delete",
            onConfirm = { act(leave = true) { api.deletePermanent(acct, curFolder, curUid) } },
            onDismiss = { confirmPermanent = false },
        )
    }
}

private val LINK_SCHEMES = setOf("http", "https", "mailto", "tel")

/** Saves the sender as the web's "Save sender to contacts" does. */
private suspend fun saveSender(api: EmailApi, m: EmailMessage, say: (String) -> Unit) {
    if (m.fromAddress.isBlank()) throw ApiException(0, "No sender address")
    say(
        when (api.addContact(contactName(m), m.fromAddress)) {
            ContactResult.AlreadySaved -> "Already in contacts"
            ContactResult.Saved -> "Saved to contacts"
        },
    )
}

/** Hands [file] to whichever app opens it, through the FileProvider. False when none does. */
private fun viewFile(context: Context, file: File): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: fallbackMime(file.name)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** "Tomorrow · Sat 10 Oct, 8:00 AM"; today's own time needs no date. */
private fun presetLabel(p: ReminderPreset, today: LocalDate = LocalDate.now()): String {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(p.due)
    val day = if (p.due.toLocalDate() == today) "" else DateTimeFormatter.ofPattern("EEE d MMM, ").format(p.due)
    return "${p.label} · $day$time"
}
