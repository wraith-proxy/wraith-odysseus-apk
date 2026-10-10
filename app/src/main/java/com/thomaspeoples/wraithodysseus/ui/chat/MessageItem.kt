package com.thomaspeoples.wraithodysseus.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.HistoryMessage
import com.thomaspeoples.wraithodysseus.chat.MessageDetails
import com.thomaspeoples.wraithodysseus.chat.TurnState
import com.thomaspeoples.wraithodysseus.chat.UploadedFile
import com.thomaspeoples.wraithodysseus.chat.modelLabel
import com.thomaspeoples.wraithodysseus.chat.shortModel
import com.thomaspeoples.wraithodysseus.chat.visibleAssistantText
import com.thomaspeoples.wraithodysseus.ui.chat.parts.DetailsFooter
import com.thomaspeoples.wraithodysseus.ui.chat.parts.FindingsBox
import com.thomaspeoples.wraithodysseus.ui.chat.parts.GeneratedImages
import com.thomaspeoples.wraithodysseus.ui.chat.parts.MessageHeader
import com.thomaspeoples.wraithodysseus.ui.chat.parts.ReplyMarkers
import com.thomaspeoples.wraithodysseus.ui.chat.parts.VariantSwitcher
import com.thomaspeoples.wraithodysseus.ui.chat.parts.RagBox
import com.thomaspeoples.wraithodysseus.ui.chat.parts.SourcesBox
import com.thomaspeoples.wraithodysseus.ui.chat.parts.ToolCardView
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.Markdown
import com.thomaspeoples.wraithodysseus.ui.common.ThinkingBlock
import com.thomaspeoples.wraithodysseus.ui.theme.AiBubbleShape
import com.thomaspeoples.wraithodysseus.ui.theme.BUBBLE_MAX_WIDTH_FRACTION
import com.thomaspeoples.wraithodysseus.ui.theme.BubblePadding
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.theme.UserBubbleShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun Bubble(user: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        // An 85% lane, with the bubble hugging its speaker's side of it.
        Box(
            Modifier.fillMaxWidth(BUBBLE_MAX_WIDTH_FRACTION),
            contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            val shape = if (user) UserBubbleShape else AiBubbleShape
            Column(
                modifier
                    .clip(shape)
                    .background(if (user) OdyColors.UserBubble else OdyColors.AiBubble)
                    .then(if (user) Modifier else Modifier.border(1.dp, OdyColors.BubbleBorder, shape))
                    .padding(BubblePadding),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { content() }
        }
    }
}

/**
 * The inside of a reply bubble, shared by the saved message and the one still
 * streaming so the one becomes the other without changing shape: header, then
 * the sources, reasoning, tool calls and images, then the text and what
 * trails it.
 */
@Composable
private fun ReplyContent(
    name: String?,
    timeMs: Long?,
    d: MessageDetails,
    thinking: String?,
    thinkingActive: Boolean,
    thinkingSeconds: String?,
    answer: String,
    placeholder: Boolean,
) {
    MessageHeader(user = false, name = name, timeMs = timeMs, details = d)
    SourcesBox(d.sourcesLabel, d.sources)
    if (!thinking.isNullOrEmpty()) ThinkingBlock(thinking, active = thinkingActive, seconds = thinkingSeconds)
    d.tools.forEach { ToolCardView(it) }
    GeneratedImages(d.images)
    if (answer.isNotBlank()) Markdown(answer)
    else if (placeholder && thinking.isNullOrEmpty() && d.tools.isEmpty()) Text("…", color = Muted)
    FindingsBox(d.findings)
    RagBox(d.ragSources)
}

/**
 * One message from the history, with its footer and, on a hold, every action.
 * [defaultModel] names the model on a reply saved without one.
 */
@Composable
internal fun MessageItem(
    m: HistoryMessage,
    thumbnail: suspend (String) -> ByteArray,
    menu: MessageMenu? = null,
    defaultModel: String? = null,
) {
    val user = m.role == "user"
    var layer by remember { mutableStateOf(Layer.None) }
    val haptic = LocalHapticFeedback.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    // What Copy, Select and Share take: the text as the web shows it.
    val text = remember(m.content, m.raw, user) { if (user) m.content else visibleAssistantText(m.raw) }
    val runner = MessageRunner(
        LocalContext.current,
        text,
        menu,
        onLayer = { layer = it },
        onCopied = { scope.launch { snackbar.showSnackbar("Copied") } },
        onFailure = { scope.launch { snackbar.showSnackbar(it) } },
    )
    val hold = {
        if (layer == Layer.None) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            layer = Layer.Full
        }
    }
    val d = m.details

    Column {
        Bubble(
            user,
            // The press is caught by onHold (it must beat links and buttons
            // inside); the semantics give TalkBack the same action by name.
            Modifier.onHold(hold).semantics { onLongClick("Message actions") { hold(); true } },
        ) {
            if (user) {
                MessageHeader(user = true, name = null, timeMs = d.timeMs, details = d)
                if (m.attachments.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { m.attachments.forEach { Thumb(it, thumbnail) } }
                }
                Text(m.content, style = MaterialTheme.typography.bodyMedium)
                ReplyMarkers(d, user = true, onContinue = null)
            } else {
                if (m.attachments.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { m.attachments.forEach { Thumb(it, thumbnail) } }
                }
                ReplyContent(
                    name = modelLabel(d.requestedModel, d.model) ?: defaultModel?.let(::shortModel),
                    timeMs = d.timeMs,
                    d = d,
                    thinking = m.thinking,
                    thinkingActive = false,
                    thinkingSeconds = d.thinkingSeconds,
                    answer = m.content,
                    placeholder = false,
                )
                ReplyMarkers(d, user = false, onContinue = menu?.let { mm -> mm.onContinue?.takeIf { mm.canChange && mm.lastReply } })
            }
        }
        if (!user && d.variants.size > 1) {
            VariantSwitcher(d, enabled = menu?.canChange == true && menu.lastReply, onSwitch = { menu?.onVariant?.invoke(it) })
        }
        if (!user) DetailsFooter(d)
        MessageFooter(user, canChange = menu?.canChange == true, onAction = runner::run, onMore = { layer = Layer.More })
    }
    MessageLayers(layer, { layer = it }, user, text, menu, runner)
}

/** The reply still streaming in, with the same header, sources, tool calls and figures as it will have saved. */
@Composable
internal fun LiveBubble(turn: TurnState, defaultModel: String? = null) {
    Column {
        Bubble(user = false) {
            ReplyContent(
                name = modelLabel(turn.requestedModel, turn.model) ?: defaultModel?.let(::shortModel),
                timeMs = turn.startedMs,
                d = turn.details,
                thinking = turn.thinking,
                thinkingActive = turn.answer.isEmpty(),
                thinkingSeconds = turn.thinkingSeconds,
                answer = turn.answer,
                placeholder = true,
            )
        }
        DetailsFooter(turn.details)
    }
}

/** A short muted line: context compacted, step limit reached, fallback... */
@Composable
internal fun NoticeLine(text: String) {
    Text(text, color = Muted, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
}

@Composable
private fun Thumb(file: UploadedFile, thumbnail: suspend (String) -> ByteArray) {
    if (!file.mime.startsWith("image/")) {
        Text(file.name, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val image by produceState<ImageBitmap?>(null, file.id) {
        value = runCatching {
            val bytes = thumbnail(file.id)
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }.getOrNull()
    }
    image?.let {
        Image(it, file.name, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.small))
    } ?: Box(Modifier.size(72.dp).clip(MaterialTheme.shapes.small).background(OdyColors.CodeBg))
}
