package com.thomaspeoples.wraithodysseus.ui.chat.parts

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.MessageDetails
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.Secondary
import com.thomaspeoples.wraithodysseus.ui.theme.Tertiary
import java.util.Date

/**
 * The line above a message, as the web's role line: a reply has a small dot,
 * the model and the time; a message of the person's own says "You", with the
 * mode chip when it ran as an agent (chatRenderer.js:1526 userModePill).
 * [name] is the model for a reply; blank hides it.
 */
@Composable
internal fun MessageHeader(user: Boolean, name: String?, timeMs: Long?, details: MessageDetails) {
    val context = LocalContext.current
    val time = remember(timeMs) { timeMs?.let { DateFormat.getTimeFormat(context).format(Date(it)) } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!user) Box(Modifier.size(7.dp).clip(CircleShape).background(Tertiary).clearAndSetSemantics {})
        Text(
            if (user) "You" else name.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = if (user) MaterialTheme.colorScheme.onSurface else Secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (user) details.modeChip?.let { chip ->
            Text(
                chip,
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                modifier = Modifier
                    .semantics { contentDescription = if (details.autoEscalated) "Auto-promoted from chat for this tool request" else "Sent in ${details.mode} mode" }
                    .border(1.dp, Muted.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        time?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Muted) }
    }
}
