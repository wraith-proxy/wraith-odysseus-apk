package com.thomaspeoples.wraithodysseus.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.chat.ChatContext
import com.thomaspeoples.wraithodysseus.chat.ChatSwitch
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.SheetShape
import java.util.Locale

/** The web's slider ends: past 8192 tokens means "no limit" (chat.js:533). */
internal const val MAX_TOKENS_STEPS_TOP = 8448f
internal const val MAX_TOKENS_LIMIT = 8192

/** "12,345 / 128,000", as the Used row reads. */
fun usedText(c: ChatContext): String = "%,d / %,d".format(Locale.US, c.usedTokens, c.contextLength)

/** Max tokens as the slider shows it; null (no override) and the top step both read "No limit". */
fun maxTokensText(v: Int?): String = if (v == null || v > MAX_TOKENS_LIMIT) "No limit" else "%,d".format(Locale.US, v)

/** What the slider's value saves: the top step clears the override. */
fun maxTokensToSave(slider: Float): Int? = slider.toInt().takeIf { it <= MAX_TOKENS_LIMIT }

/**
 * The "Chat Context" sheet (the web's popup from the context pill): how full
 * the window is, this chat's memory and skill switches, thinking and
 * generation settings, and Compact. Compact is refused here while a run
 * is going; the server also refuses one it is not told about (409), and a
 * chat too short to compact answers with its own message, shown as it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val c = vm.context
    ModalBottomSheet(onDismissRequest = onDismiss, shape = SheetShape, containerColor = MaterialTheme.colorScheme.surface) {
        if (c == null) {
            Text("Context is not available", color = Muted, modifier = Modifier.padding(20.dp))
            return@ModalBottomSheet
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Chat Context", style = MaterialTheme.typography.titleMedium)
            Bar(c.percent)
            Line("Used", usedText(c))
            Line("Usage", "${c.percent}%")
            Line("Messages", "%,d".format(Locale.US, c.messages))
            if (c.model.isNotBlank()) Line("Window model", c.model.substringAfterLast('/'))

            SwitchRow("Memory extraction", c.memoryExtraction) { vm.setSwitch(ChatSwitch.MemoryExtraction, it) }
            SwitchRow("Memory injection", c.memoryInjection) { vm.setSwitch(ChatSwitch.MemoryInjection, it) }
            SwitchRow("Skill injection", c.skillInjection) { vm.setSwitch(ChatSwitch.SkillInjection, it) }

            if (c.thinkingSupported) {
                SwitchRow("Thinking", c.thinkingMode == "on") {
                    vm.saveGeneration(if (it) "on" else "off", c.temperatureOverride, c.maxTokensOverride)
                }
            }
            val levels = vm.currentChoice()?.reasoningLevels.orEmpty()
            if (levels.isNotEmpty()) {
                Text("Reasoning effort", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf("default") + levels).forEach { level ->
                        val on = (c.reasoningEffort ?: "default") == level
                        FilterChip(
                            selected = on,
                            onClick = { vm.saveGeneration(c.thinkingMode, c.temperatureOverride, c.maxTokensOverride, effort = level) },
                            label = { Text(level, style = MaterialTheme.typography.labelMedium) },
                            shape = CircleShape,
                        )
                    }
                }
            }

            var temp by remember(c.temperatureOverride) { mutableFloatStateOf((c.temperatureOverride ?: 1.0).toFloat()) }
            Line("Temperature", "%.1f".format(Locale.US, temp))
            Slider(
                value = temp,
                onValueChange = { temp = (Math.round(it * 10) / 10f) },
                onValueChangeFinished = { vm.saveGeneration(c.thinkingMode, temp.toDouble(), c.maxTokensOverride) },
                valueRange = 0f..2f,
            )
            var tokens by remember(c.maxTokensOverride) { mutableFloatStateOf((c.maxTokensOverride ?: MAX_TOKENS_STEPS_TOP.toInt()).toFloat()) }
            Line("Max tokens", maxTokensText(tokens.toInt()))
            Slider(
                value = tokens,
                onValueChange = { tokens = it },
                onValueChangeFinished = { vm.saveGeneration(c.thinkingMode, c.temperatureOverride, maxTokensToSave(tokens)) },
                valueRange = 256f..MAX_TOKENS_STEPS_TOP,
                steps = 31,
            )

            if (c.canCompact) {
                Button(
                    onClick = {
                        onDismiss()
                        vm.compact()
                    },
                    enabled = !vm.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Lucide.Shrink, null, Modifier.size(16.dp))
                    Text(if (vm.running) "  Compact (wait for the reply)" else "  Compact")
                }
            }
        }
    }
}

@Composable
private fun Bar(percent: Double) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Muted.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxWidth((percent / 100.0).toFloat().coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(contextColor(percent)))
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(if (on) "On" else "Off", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
