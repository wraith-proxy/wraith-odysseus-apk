package com.thomaspeoples.wraithodysseus.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.OdyColors
import com.thomaspeoples.wraithodysseus.ui.theme.Primary
import com.thomaspeoples.wraithodysseus.ui.theme.ThinkingShape

/**
 * The model's reasoning, folded away like the web UI's thinking block. It
 * opens while the model is still only thinking ([active]) and folds itself
 * once the answer starts; a tap toggles it either way.
 */
@Composable
fun ThinkingBlock(text: String, active: Boolean, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(active) }
    LaunchedEffect(active) { if (!active) open = false }

    Column(
        modifier
            .fillMaxWidth()
            .clip(ThinkingShape)
            .background(OdyColors.ThinkingContainer)
            .border(1.dp, OdyColors.ThinkingBorder, ThinkingShape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(OdyColors.ThinkingHeader)
                .clickable { open = !open }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(">", color = Primary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.rotate(if (open) 90f else 0f))
            Text(
                if (active) "  Thinking…" else "  Thinking",
                color = Primary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (open) {
            Text(
                text,
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}
