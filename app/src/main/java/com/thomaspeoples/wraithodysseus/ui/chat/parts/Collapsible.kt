package com.thomaspeoples.wraithodysseus.ui.chat.parts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.theme.ThinkingShape

/**
 * Which boxes of one bubble are open, held in the chat's view model by
 * "<owner>\n<title>". The live bubble is a different list item from the saved
 * reply that replaces it, so its folds are handed over ([handOverFolds]) when
 * the turn ends instead of closing again.
 */
internal class Folds(private val map: MutableMap<String, Boolean>, private val owner: String) {
    fun isOpen(title: String) = map["$owner\n$title"] == true
    fun set(title: String, open: Boolean) { map["$owner\n$title"] = open }
}

/** No provider (a bubble outside the chat list) keeps the fold state local. */
internal val LocalFolds = compositionLocalOf<Folds?> { null }

internal const val LIVE_FOLDS = "live"

/** Moves the live bubble's folds to the saved reply [to]; with no reply they are dropped. */
internal fun handOverFolds(folds: MutableMap<String, Boolean>, to: String?) {
    folds.keys.filter { it.startsWith("$LIVE_FOLDS\n") }.forEach { key ->
        val open = folds.remove(key)
        if (to != null && open != null) folds["$to\n" + key.removePrefix("$LIVE_FOLDS\n")] = open
    }
}

/**
 * A folded section like the web's sources box and tool nodes: closed until
 * tapped, a full 48dp header that TalkBack reads as a button with its state.
 * [tint] colours the title and icon (a failed tool is red). A hold on it is
 * caught by the bubble around it (see onHold), not by this tap.
 */
@Composable
internal fun Collapsible(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Muted,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val folds = LocalFolds.current
    var local by rememberSaveable(title) { mutableStateOf(false) }
    val open = folds?.isOpen(title) ?: local
    val toggle = { if (folds != null) folds.set(title, !open) else local = !open }
    Column(
        modifier
            .fillMaxWidth()
            .clip(ThinkingShape)
            .background(OdyColors.CodeBg)
            .border(1.dp, OdyColors.BubbleBorder, ThinkingShape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand", onClick = toggle)
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp)) }
            Text(
                title,
                color = tint,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelSmall) }
            Icon(if (open) Lucide.ChevronDown else Lucide.ChevronRight, contentDescription = null, tint = Muted, modifier = Modifier.size(16.dp))
        }
        if (open) Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
