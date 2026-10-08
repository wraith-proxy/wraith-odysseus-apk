package com.thomaspeoples.wraithodysseus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    secondary = Secondary,
    tertiary = Tertiary,
    background = Background,
    onBackground = Foreground,
    surface = Surface,
    onSurface = Foreground,
    surfaceVariant = Raised,
    onSurfaceVariant = Muted,
    outline = Outline,
    error = Error,
)

/** Dark only, deliberately: the web UI this mirrors has no light theme, and
 * the theme switcher was dropped from scope. */
@Composable
fun WraithOdysseusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
