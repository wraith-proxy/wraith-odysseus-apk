package com.thomaspeoples.wraithodysseus.ui.chat.parts

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.JobTone
import com.thomaspeoples.wraithodysseus.chat.MessageDetails
import com.thomaspeoples.wraithodysseus.chat.ResearchJob
import com.thomaspeoples.wraithodysseus.chat.researchCardState
import com.thomaspeoples.wraithodysseus.chat.variantGlyph
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted

/**
 * The web's "[Message interrupted]" / "[Cancelled by user]" line under a
 * stopped reply, with Continue when there is text to resume from
 * (chatRenderer.js:3680), and "[Message edited]" under an edited one
 * (:3715). [onContinue] is null where Continue does not apply: only the last
 * reply can be continued, and not while a run is going.
 */
@Composable
internal fun ReplyMarkers(d: MessageDetails, user: Boolean, onContinue: (() -> Unit)?) {
    if (!user && d.stopped) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Marker(if (d.cancelled) "[Cancelled by user]" else "[Message interrupted]")
            if (!d.cancelled && onContinue != null) {
                TextButton(onClick = onContinue) { Text("Continue ▸", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
    if (d.edited) Marker("[Message edited]")
}

@Composable
private fun Marker(text: String) {
    Text(text, color = Muted, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
}

/**
 * "‹ 1/2 ›" on a reply that has kept versions (chatRenderer.js:3730), with
 * the web's glyph for what the shown version is. [enabled] is false where
 * the choice cannot be saved: only the last reply's can, and not mid-run.
 */
@Composable
internal fun VariantSwitcher(d: MessageDetails, enabled: Boolean, onSwitch: (Int) -> Unit) {
    val at = d.shownVariant
    val last = d.variants.lastIndex
    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSwitch(at - 1) }, enabled = enabled && at > 0, modifier = Modifier.size(32.dp)) {
            Icon(Lucide.ChevronLeft, contentDescription = "Previous version", tint = Muted, modifier = Modifier.size(16.dp))
        }
        Text(
            "${at + 1}/${last + 1} ${variantGlyph(d.variants[at].label)}".trim(),
            color = Muted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.semantics { contentDescription = "Version ${at + 1} of ${last + 1}" },
        )
        IconButton(onClick = { onSwitch(at + 1) }, enabled = enabled && at < last, modifier = Modifier.size(32.dp)) {
            Icon(Lucide.ChevronRight, contentDescription = "Next version", tint = Muted, modifier = Modifier.size(16.dp))
        }
    }
}

/** The chat's background research, one small card each, with the status the web shows (backgroundToolJobs.js:21). */
@Composable
internal fun ResearchJobCards(jobs: List<ResearchJob>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        jobs.forEach { job ->
            val card = researchCardState(job)
            val tint = when (card.tone) {
                JobTone.Error -> MaterialTheme.colorScheme.error
                JobTone.Done -> MaterialTheme.colorScheme.onSurface
                JobTone.Running -> Muted
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Muted.copy(alpha = 0.4f), MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(job.query.ifBlank { "Research" }, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                Text("BG task · ${card.label}", color = tint, style = MaterialTheme.typography.labelSmall)
                Text(card.detail, color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
