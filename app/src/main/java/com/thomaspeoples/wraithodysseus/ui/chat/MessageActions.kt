package com.thomaspeoples.wraithodysseus.ui.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.thomaspeoples.wraithodysseus.ui.common.ActionSheet
import com.thomaspeoples.wraithodysseus.ui.common.ConfirmDialog
import com.thomaspeoples.wraithodysseus.ui.common.SheetAction
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import kotlinx.coroutines.withTimeoutOrNull

/** Every action the web UI offers on a message (chatRenderer.js:2299 and
 * :2517 footers, plus select and share, which a phone adds). */
internal enum class MessageAction(val label: String, val icon: ImageVector) {
    Copy("Copy", Lucide.Copy),
    SelectText("Select text", Lucide.TextSelect),
    Share("Share", Lucide.Share2),
    ReadAloud("Read aloud", Lucide.Volume2),
    Fork("Fork conversation", Lucide.GitFork),
    Edit("Edit", Lucide.Pencil),
    Regenerate("Regenerate from here", Lucide.RefreshCw),
    Shorter("Rewrite shorter", Lucide.Shrink),
    Simpler("Explain simpler", Lucide.Lightbulb),
    Resend("Resend", Lucide.RotateCcw),
    Delete("Delete", Lucide.Trash2),
}

/** Reading and copying always work; the rest change the chat on the server,
 * so they need the message's database id and no run going. */
private val READ_ONLY = setOf(MessageAction.Copy, MessageAction.SelectText, MessageAction.Share, MessageAction.ReadAloud)

private fun available(all: List<MessageAction>, canChange: Boolean) =
    all.filter { canChange || it in READ_ONLY }

/** The long-press menu: all of them. Rewrite and explain only on the last reply;
 * Read aloud only on a reply, and only when the server can speak ([readAloud]). */
internal fun fullMenu(user: Boolean, lastReply: Boolean, canChange: Boolean, readAloud: Boolean = false): List<MessageAction> = available(
    if (user) {
        listOf(MessageAction.Copy, MessageAction.SelectText, MessageAction.Edit, MessageAction.Resend, MessageAction.Delete)
    } else {
        buildList {
            addAll(listOf(MessageAction.Copy, MessageAction.SelectText, MessageAction.Share))
            if (readAloud) add(MessageAction.ReadAloud)
            addAll(listOf(MessageAction.Fork, MessageAction.Edit, MessageAction.Regenerate))
            if (lastReply) addAll(listOf(MessageAction.Shorter, MessageAction.Simpler))
            add(MessageAction.Delete)
        }
    },
    canChange,
)

/** The footer's two icons; the web's defaults (copy, delete / edit, delete). */
internal fun footerButtons(user: Boolean): List<MessageAction> =
    if (user) listOf(MessageAction.Edit, MessageAction.Delete) else listOf(MessageAction.Copy, MessageAction.Delete)

/** What the footer's "···" opens: the rest of the web's list. */
internal fun overflowMenu(user: Boolean, lastReply: Boolean, canChange: Boolean, readAloud: Boolean = false): List<MessageAction> = available(
    if (user) {
        listOf(MessageAction.Copy, MessageAction.Resend)
    } else {
        buildList {
            if (readAloud) add(MessageAction.ReadAloud)
            addAll(listOf(MessageAction.Fork, MessageAction.Edit, MessageAction.Regenerate))
            if (lastReply) addAll(listOf(MessageAction.Shorter, MessageAction.Simpler))
        }
    },
    canChange,
)

/** What a message's actions do, built by the screen from the view model. */
internal class MessageMenu(
    /** The chat can be changed: a message with an id and no run going. */
    val canChange: Boolean,
    val lastReply: Boolean,
    /** The text an edit starts from; asked only when the editor opens. */
    val editText: () -> String,
    /** What Delete says it will remove; asked only when the dialog opens. */
    val deleteText: () -> String,
    val onEdit: (String) -> Unit,
    val onResend: () -> Unit,
    val onRegenerate: () -> Unit,
    val onRewrite: (String) -> Unit,
    val onFork: () -> Unit,
    val onDelete: () -> Unit,
    /** The server can speak, so Read aloud is offered on a reply. */
    val readAloud: Boolean = false,
    /** This reply is the one being read, so the action stops it. */
    val speaking: Boolean = false,
    val onReadAloud: () -> Unit = {},
    /** Continue a stopped reply; null where it does not apply. */
    val onContinue: (() -> Unit)? = null,
    /** Show kept version n of this reply. */
    val onVariant: (Int) -> Unit = {},
)

internal const val SHORTER = "Rewrite your last response to be shorter and more concise. Keep the key information but cut the fluff."
internal const val SIMPLER = "Explain your last response in simpler terms. Use plain language and short sentences."

/**
 * Holding anywhere on the modified view calls [onHold], including on a link,
 * a code block, the thinking header or an image. Those children take the
 * touch for their own tap in the Main pass, which would starve a plain
 * combinedClickable around them, so this watches in the Initial pass (the
 * parent sees events first), leaves them untouched until the hold is
 * certain, then swallows the rest of the gesture so the child under the
 * finger does not also fire on release.
 */
@Composable
internal fun Modifier.onHold(onHold: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onHold)
    return pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                // Lifted or dragged off: a tap or a scroll, not a hold.
                if (change == null || !change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
        } == null
        if (held) {
            latest()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
    }
}

/** The small muted icons under a message, and its "···" menu. */
@Composable
internal fun MessageFooter(user: Boolean, canChange: Boolean, onAction: (MessageAction) -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        footerButtons(user).forEach { a ->
            val enabled = canChange || a == MessageAction.Copy
            IconButton(onClick = { onAction(a) }, enabled = enabled, modifier = Modifier.size(36.dp)) {
                Icon(a.icon, contentDescription = if (a == MessageAction.Copy) "Copy message" else "${a.label} message", tint = Muted, modifier = Modifier.size(16.dp))
            }
        }
        IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) {
            Icon(Lucide.Ellipsis, contentDescription = "More actions", tint = Muted, modifier = Modifier.size(16.dp))
        }
    }
}

/** Which sheet or dialog a message has open. */
internal enum class Layer { None, Full, More, Select, Edit, Delete }

/** Runs one action of one message: the local ones here, the rest through [menu]. */
internal class MessageRunner(
    private val context: Context,
    private val text: String,
    private val menu: MessageMenu?,
    private val onLayer: (Layer) -> Unit,
    private val onCopied: () -> Unit,
    private val onFailure: (String) -> Unit,
) {
    fun run(a: MessageAction) {
        when (a) {
            MessageAction.Copy -> {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("message", text))
                onCopied()
            }
            MessageAction.SelectText -> onLayer(Layer.Select)
            MessageAction.Share -> if (!share(context, text)) onFailure("Nothing can share text here")
            MessageAction.Edit -> onLayer(Layer.Edit)
            MessageAction.Delete -> onLayer(Layer.Delete)
            MessageAction.Fork -> menu?.onFork?.invoke()
            MessageAction.Regenerate -> menu?.onRegenerate?.invoke()
            MessageAction.Resend -> menu?.onResend?.invoke()
            MessageAction.ReadAloud -> menu?.onReadAloud?.invoke()
            MessageAction.Shorter -> menu?.onRewrite?.invoke(SHORTER)
            MessageAction.Simpler -> menu?.onRewrite?.invoke(SIMPLER)
        }
    }
}

/** The sheets and dialogs of one message. */
@Composable
internal fun MessageLayers(layer: Layer, onLayer: (Layer) -> Unit, user: Boolean, text: String, menu: MessageMenu?, runner: MessageRunner) {
    val close = { onLayer(Layer.None) }

    val reading = menu?.speaking == true
    fun sheet(items: List<MessageAction>) = items.map { a ->
        val stop = a == MessageAction.ReadAloud && reading
        SheetAction(if (stop) "Stop reading" else a.label, if (stop) Lucide.Square else a.icon, destructive = a == MessageAction.Delete) { runner.run(a) }
    }

    val canChange = menu?.canChange == true
    val lastReply = menu?.lastReply == true
    when (layer) {
        Layer.None -> Unit
        Layer.Full -> ActionSheet(sheet(fullMenu(user, lastReply, canChange, menu?.readAloud == true && !user)), onDismiss = close, title = "Message actions")
        Layer.More -> ActionSheet(sheet(overflowMenu(user, lastReply, canChange, menu?.readAloud == true && !user)), onDismiss = close)
        Layer.Select -> SelectTextDialog(text, onDismiss = close)
        Layer.Edit -> menu?.let { EditDialog(it.editText(), onDismiss = close, onSave = { t -> close(); it.onEdit(t) }) }
        Layer.Delete -> menu?.let {
            ConfirmDialog(
                title = "Delete this message?",
                confirm = "Delete",
                text = it.deleteText(),
                onConfirm = it.onDelete,
                onDismiss = close,
            )
        }
    }
}

private fun share(context: Context, text: String): Boolean = try {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** The message text on a full screen of its own, so a word or a passage can
 * be selected and copied (the bubble itself is not selectable). */
@Composable
private fun SelectTextDialog(text: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Select text", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}

@Composable
private fun EditDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message", style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 14,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
