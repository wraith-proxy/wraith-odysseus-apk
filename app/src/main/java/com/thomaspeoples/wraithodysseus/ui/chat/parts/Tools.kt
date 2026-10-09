package com.thomaspeoples.wraithodysseus.ui.chat.parts

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.GeneratedImage
import com.thomaspeoples.wraithodysseus.chat.ToolCard
import com.thomaspeoples.wraithodysseus.chat.ToolDiff
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.CodeStyle
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.theme.Tertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Added = Color(0xFF98C379)
private val Removed = Color(0xFFE06C75)

/** One tool call as a folded node: tool, state, and on a tap the command, output, diff and any screenshot. */
@Composable
internal fun ToolCardView(card: ToolCard) {
    val tint = when {
        card.running -> Muted
        card.ok -> Tertiary
        else -> Removed
    }
    val state = when {
        card.running -> "running…"
        card.ok -> "done"
        else -> "failed"
    }
    Collapsible(
        title = card.tool.ifBlank { "tool" },
        icon = when {
            card.running -> Lucide.Terminal
            card.ok -> Lucide.Check
            else -> Lucide.X
        },
        tint = tint,
        trailing = listOfNotNull(card.progress?.takeIf { card.running }, state, card.exitCode?.takeIf { !card.ok }?.let { "exit $it" }).joinToString(" · "),
    ) {
        if (card.command.isNotBlank() && card.diff == null) Mono(card.command, Modifier.fillMaxWidth())
        if (card.running) card.tail?.let { Mono(it, Modifier.fillMaxWidth().heightIn(max = 140.dp), scroll = true) }
        if (card.output.isNotBlank()) Output(card.output, card.exitCode)
        card.diff?.let { DiffView(it) }
        card.screenshot?.let { Screenshot(it) }
    }
}

@Composable
private fun Mono(text: String, modifier: Modifier = Modifier, scroll: Boolean = false) {
    Text(
        text,
        style = CodeStyle,
        color = OdyColors.CodeFg,
        softWrap = scroll,
        modifier = modifier
            .background(OdyColors.CodeBg.copy(alpha = 0.6f), MaterialTheme.shapes.small)
            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier.horizontalScroll(rememberScrollState()))
            .padding(6.dp),
    )
}

/** Output in a box that scrolls on its own, cut short with the rest behind "Show all". */
@Composable
private fun Output(text: String, exit: Int?) {
    var all by rememberSaveable { mutableStateOf(false) }
    val (shown, cut) = if (all) text to 0 else truncateOutput(text)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Output" + (exit?.let { " (exit $it)" } ?: ""), color = Muted, style = MaterialTheme.typography.labelSmall)
        Mono(shown, Modifier.fillMaxWidth().heightIn(max = 240.dp), scroll = true)
        if (cut > 0) TextButton(onClick = { all = true }) { Text("Show all (+$cut characters)") }
    }
}

@Composable
private fun DiffView(d: ToolDiff) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(d.file, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (d.newFile) Text("new", color = Added, style = MaterialTheme.typography.labelSmall)
            if (d.added > 0) Text("+${d.added}", color = Added, style = MaterialTheme.typography.labelSmall)
            if (d.removed > 0) Text("−${d.removed}", color = Removed, style = MaterialTheme.typography.labelSmall)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .background(OdyColors.CodeBg.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(6.dp),
        ) {
            diffLines(d.text).forEach { l ->
                val color = when (l.kind) {
                    DiffKind.Add -> Added
                    DiffKind.Del -> Removed
                    DiffKind.Hunk -> OdyColors.AccentWarm
                    DiffKind.Meta -> Muted
                    DiffKind.Context -> OdyColors.CodeFg
                }
                Text(l.text.ifEmpty { " " }, style = CodeStyle, color = color, softWrap = false)
            }
        }
    }
}

/** A browser tool's inline screenshot (a data URI; anything else never reaches here). */
@Composable
private fun Screenshot(src: String) {
    val image by produceState<ImageBitmap?>(null, src) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Base64.decode(src.substringAfter("base64,"), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    image?.let { Image(it, "Screenshot", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().background(OdyColors.CodeBg, MaterialTheme.shapes.small)) }
}

/** An image the model made. The picture sits behind the server's login, so the app names it and gives the prompt. */
@Composable
internal fun GeneratedImages(images: List<GeneratedImage>) {
    images.forEach { img ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(Lucide.Image, contentDescription = null, tint = Muted, modifier = Modifier.size(16.dp).padding(top = 2.dp))
            Column {
                Text("Generated image", style = MaterialTheme.typography.labelMedium)
                img.prompt?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                val meta = listOfNotNull(img.model, img.size).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
