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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.HistoryMessage
import com.thomaspeoples.wraithodysseus.chat.TurnState
import com.thomaspeoples.wraithodysseus.chat.UploadedFile
import com.thomaspeoples.wraithodysseus.ui.common.Markdown
import com.thomaspeoples.wraithodysseus.ui.common.ThinkingBlock
import com.thomaspeoples.wraithodysseus.ui.theme.AiBubbleShape
import com.thomaspeoples.wraithodysseus.ui.theme.BUBBLE_MAX_WIDTH_FRACTION
import com.thomaspeoples.wraithodysseus.ui.theme.BubblePadding
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.theme.UserBubbleShape
import kotlinx.coroutines.Dispatchers
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

/** One message from the history. */
@Composable
internal fun MessageItem(m: HistoryMessage, thumbnail: suspend (String) -> ByteArray) {
    val user = m.role == "user"
    Bubble(user) {
        if (m.attachments.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { m.attachments.forEach { Thumb(it, thumbnail) } }
        }
        m.thinking?.let { ThinkingBlock(it, active = false) }
        when {
            user -> Text(m.content, style = MaterialTheme.typography.bodyMedium)
            m.content.isNotBlank() -> Markdown(m.content)
        }
    }
}

/** The reply still streaming in. */
@Composable
internal fun LiveBubble(turn: TurnState) {
    val answer = turn.answer
    Bubble(user = false) {
        if (turn.thinking.isNotEmpty()) ThinkingBlock(turn.thinking, active = answer.isEmpty())
        if (answer.isNotEmpty()) Markdown(answer)
        else if (turn.thinking.isEmpty()) Text("…", color = Muted)
    }
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
