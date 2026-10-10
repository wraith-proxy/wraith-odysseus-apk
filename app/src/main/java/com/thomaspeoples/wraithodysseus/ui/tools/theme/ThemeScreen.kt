package com.thomaspeoples.wraithodysseus.ui.tools.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.thomaspeoples.wraithodysseus.net.ApiException
import com.thomaspeoples.wraithodysseus.ui.common.LocalSnackbar
import com.thomaspeoples.wraithodysseus.ui.common.TopLevelBar
import com.thomaspeoples.wraithodysseus.ui.common.userMessage
import com.thomaspeoples.wraithodysseus.ui.icons.Lucide
import com.thomaspeoples.wraithodysseus.ui.settings.PrefsApi
import com.thomaspeoples.wraithodysseus.ui.theme.AppPalette
import com.thomaspeoples.wraithodysseus.ui.theme.Muted
import com.thomaspeoples.wraithodysseus.ui.theme.Palette
import com.thomaspeoples.wraithodysseus.ui.theme.ThemeColors
import com.thomaspeoples.wraithodysseus.ui.theme.ThemeData
import com.thomaspeoples.wraithodysseus.ui.theme.paletteFor
import com.thomaspeoples.wraithodysseus.ui.theme.StoredTheme
import com.thomaspeoples.wraithodysseus.ui.tools.ToolEnv
import java.io.IOException
import kotlinx.coroutines.launch
import org.json.JSONException
import com.thomaspeoples.wraithodysseus.ui.theme.themeBackground

/**
 * The web's theme picker: the presets, and the custom themes saved on the
 * web. A pick is written to the account exactly as the web writes it and
 * re-themes the app at once. Fonts, density, background effects and the
 * colour editor stay in the web UI.
 */
@Composable
fun ThemeScreen(env: ToolEnv) {
    val api = remember(env.http) { ThemeApi(PrefsApi(env.http)) }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf<String?>(null) }
    var custom by remember { mutableStateOf<List<CustomTheme>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(api) {
        try {
            active = api.load()?.name ?: ThemeData.DEFAULT_NAME
            custom = api.customThemes()
        } catch (e: ApiException) {
            snackbar.showSnackbar(userMessage(e))
        } catch (e: IOException) {
            snackbar.showSnackbar(userMessage(e))
        } catch (e: JSONException) {
            snackbar.showSnackbar(userMessage(e))
        }
    }

    fun pick(name: String, colors: ThemeColors, save: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                save()
                AppPalette.set(paletteFor(StoredTheme(name, colors)))
                active = name
            } catch (e: ApiException) {
                snackbar.showSnackbar(userMessage(e))
            } catch (e: IOException) {
                snackbar.showSnackbar(userMessage(e))
            } catch (e: JSONException) {
                snackbar.showSnackbar(userMessage(e))
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().themeBackground(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        TopLevelBar("Theme", onMenu = env.onMenu)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Header("Presets") }
            items(ThemeData.PRESETS.entries.toList(), key = { "p-" + it.key }) { (name, colors) ->
                Swatch(ThemeData.displayName(name), colors, selected = active == name) {
                    pick(name, colors) { api.savePreset(name) }
                }
            }
            if (custom.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Header("Your themes") }
                items(custom, key = { "c-" + it.name }) { theme ->
                    Swatch(theme.name, theme.colors, selected = active == theme.name) {
                        pick(theme.name, theme.colors) { api.saveCustom(theme) }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Fonts, density and background effects follow the web UI.",
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(text: String) =
    Text(text, color = Muted, style = MaterialTheme.typography.labelLarge)

/** A theme drawn in its own colours, whatever theme is showing. */
@Composable
private fun Swatch(label: String, colors: ThemeColors, selected: Boolean, onClick: () -> Unit) {
    val p = Palette.from(colors)
    Surface(
        color = p.background,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else p.outline),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(p.surface, p.foreground, p.primary, p.outline).forEach {
                    Box(Modifier.weight(1f).height(22.dp).background(it, RoundedCornerShape(4.dp)))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, color = p.foreground, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                if (selected) Icon(Lucide.Check, contentDescription = "Current theme", tint = p.primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
