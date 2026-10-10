package com.thomaspeoples.wraithodysseus.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * The web's font choice (static/js/theme.js `FONT_MAP`, the Font select in
 * index.html). The keys are what the theme preference stores. Custom faces
 * the web discovers in a server folder (`/api/fonts/custom`) cannot be used
 * here, since fonts are never fetched, so they read as [MONO].
 */
enum class ThemeFont(val key: String) {
    /** The web's default, Fira Code, which the app bundles. */
    MONO("mono"),
    /** The web's `system-ui` stack. */
    SANS("sans"),
    /** The web's Georgia / Times stack. */
    SERIF("serif"),
    /**
     * OpenDyslexic is a web font the server hosts and the app does not
     * bundle; the web's own stack falls back to sans-serif without it, so
     * this does too.
     */
    OPENDYSLEXIC("opendyslexic"),
    ;

    /** The family for body text and titles. */
    val family: FontFamily
        get() = when (this) {
            MONO -> FiraCode
            SANS, OPENDYSLEXIC -> FontFamily.SansSerif
            SERIF -> FontFamily.Serif
        }

    /**
     * The family for code blocks. The web's `pre`/`code` keep the browser's
     * monospace face when the body font is not the monospace one, so those
     * choices get the platform's.
     */
    val codeFamily: FontFamily get() = if (this == MONO) FiraCode else FontFamily.Monospace

    companion object {
        /** theme.js `normalizeFont`: empty and the retired Gohu face mean the default; anything unknown reads as it. */
        fun parse(value: Any?): ThemeFont {
            val s = (value as? String)?.trim().orEmpty()
            return entries.firstOrNull { it.key == s } ?: MONO
        }
    }
}

/**
 * The web's background patterns the app can draw as the web does. `dots` and
 * the grid under `synapse` are plain CSS gradients (01-agent-chat.css,
 * `body.bg-pattern-dots` and `body.bg-pattern-synapse`). The others (rain,
 * constellations, perlin-flow, petals, sparkles, embers, starfield-depth,
 * ascii-fireflies) are animated canvas effects, and synapse's moving pulses
 * likewise; they fall back to the plain background, as does a pattern name
 * this app has never heard of.
 */
enum class BgPattern {
    NONE,
    /** 1px dots on a 20px grid. */
    DOTS,
    /** 1px lines on a 24px grid: synapse's static base, without its animated pulses. */
    GRID,
    ;

    companion object {
        fun parse(name: String?): BgPattern = when (name) {
            "dots" -> DOTS
            "synapse" -> GRID
            else -> NONE
        }
    }
}

/**
 * The stored theme's font and background-pattern fields (`font`,
 * `bgPattern`, `bgEffectColor`, `bgEffectIntensity` next to the colours).
 * [effectColor] is a checked `#rrggbb`, or empty for the theme's text colour,
 * as the web's `--bg-effect-color` fallback. Density, size, speed and frosted
 * glass are not applied.
 */
data class ThemeStyle(
    val font: ThemeFont = ThemeFont.MONO,
    val pattern: BgPattern = BgPattern.NONE,
    val effectColor: String = "",
    /** 0..1, the web's `--bg-effect-intensity`. */
    val intensity: Float = 1f,
) {
    companion object {
        val Default = ThemeStyle()

        /**
         * Reads the style fields of a stored theme object. [themeName] is the
         * theme's effective name: the web falls back to that preset's pattern,
         * effect colour and intensity for fields the stored object lacks
         * (theme.js `THEME_DEFAULT_*`). Never throws.
         */
        fun from(o: JSONObject, themeName: String): ThemeStyle {
            val defaults = ThemeData.defaultsFor(themeName)
            val patternName = (o.opt("bgPattern") as? String)?.takeIf { it.isNotEmpty() } ?: defaults.bgPattern
            val color = ThemeData.normalizeHex((o.opt("bgEffectColor") as? String)?.takeIf { it.isNotEmpty() } ?: defaults.bgEffectColor).orEmpty()
            val intensity = if (o.has("bgEffectIntensity")) parseIntensity(o.opt("bgEffectIntensity")) else defaults.bgEffectIntensity.toFloat()
            return ThemeStyle(ThemeFont.parse(o.opt("font")), BgPattern.parse(patternName), color, intensity)
        }

        /** theme.js `applyBgEffectIntensity`: a number clamped to 0..1, 1 when missing or not a number. */
        fun parseIntensity(v: Any?): Float {
            val n = when (v) {
                is Number -> v.toDouble()
                is String -> v.trim().toDoubleOrNull()
                else -> null
            }
            return if (n == null || n.isNaN()) 1f else n.coerceIn(0.0, 1.0).toFloat()
        }
    }
}

/** The theme's font and pattern in force; a snapshot state like [AppPalette]. */
object AppStyle {
    var current: ThemeStyle by mutableStateOf(ThemeStyle.Default)
        private set

    fun set(s: ThemeStyle) {
        current = s
    }

    fun reset() = set(ThemeStyle.Default)
}

/** One dot or line of the pattern, as the web's gradient: the effect colour at [PatternSpec.alpha]. */
data class PatternSpec(val pattern: BgPattern, val color: Color, val alpha: Float) {
    companion object {
        /** DOTS use 5% of the intensity, the grid 3.5% (the web's `color-mix` percentages). */
        fun of(style: ThemeStyle, foreground: Color): PatternSpec {
            val base = if (style.effectColor.isEmpty()) foreground else ThemeData.toColor(style.effectColor)
            val alpha = when (style.pattern) {
                BgPattern.DOTS -> 0.05f
                BgPattern.GRID -> 0.035f
                BgPattern.NONE -> 0f
            } * style.intensity.coerceIn(0f, 1f)
            return PatternSpec(style.pattern, base, alpha)
        }
    }
}

/**
 * A screen's background: [color], then the theme's pattern drawn on it,
 * behind the content, as the web paints it on the body. Use it wherever a
 * screen would paint `background(colorScheme.background)`. The style is read
 * at draw time, so a theme change repaints without recomposing.
 */
fun Modifier.themeBackground(color: Color): Modifier = drawBehind {
    drawRect(color)
    drawThemePattern(PatternSpec.of(AppStyle.current, AppPalette.current.foreground))
}

/** The pattern itself; nothing for the plain background. Tiles start at the drawing's top-left. */
private fun DrawScope.drawThemePattern(spec: PatternSpec) {
    if (spec.pattern == BgPattern.NONE || spec.alpha <= 0f) return
    val color = spec.color.copy(alpha = spec.alpha)
    val unit = 1.dp.toPx()
    when (spec.pattern) {
        BgPattern.DOTS -> {
            // radial-gradient(c 1px, transparent 1px) in a 20px tile: a disc of radius 1 at the tile centre.
            val step = 20 * unit
            var y = step / 2
            while (y < size.height + unit) {
                var x = step / 2
                while (x < size.width + unit) {
                    drawCircle(color, radius = unit, center = Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        BgPattern.GRID -> {
            // Two linear-gradients (c 1px, transparent 1px) in a 24px tile: a line along the tile's top and left edges.
            val step = 24 * unit
            var y = 0f
            while (y < size.height) {
                drawRect(color, Offset(0f, y), Size(size.width, unit))
                y += step
            }
            var x = 0f
            while (x < size.width) {
                drawRect(color, Offset(x, 0f), Size(unit, size.height))
                x += step
            }
        }
        BgPattern.NONE -> Unit
    }
}
