package com.thomaspeoples.wraithodysseus.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** A light web theme gets a light scheme, so Material's own defaults for the
 * slots the palette does not name (container tones, scrims) stay legible. */
fun Palette.toColorScheme(): ColorScheme = if (isLight) {
    lightColorScheme(
        primary = primary, onPrimary = onPrimary, secondary = secondary, tertiary = tertiary,
        background = background, onBackground = foreground, surface = surface, onSurface = foreground,
        surfaceVariant = raised, onSurfaceVariant = muted, outline = outline, error = error,
    )
} else {
    darkColorScheme(
        primary = primary, onPrimary = onPrimary, secondary = secondary, tertiary = tertiary,
        background = background, onBackground = foreground, surface = surface, onSurface = foreground,
        surfaceVariant = raised, onSurfaceVariant = muted, outline = outline, error = error,
    )
}

/** The web theme in force ([AppPalette], and the font of [AppStyle]); Odysseus's dark look until one loads. */
@Composable
fun WraithOdysseusTheme(content: @Composable () -> Unit) {
    val font = AppStyle.current.font
    MaterialTheme(
        colorScheme = AppPalette.current.toColorScheme(),
        typography = remember(font) { typographyFor(font.family) },
        shapes = Shapes,
        content = content,
    )
}
