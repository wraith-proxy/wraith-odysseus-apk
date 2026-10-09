package com.thomaspeoples.wraithodysseus.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Every colour the app paints with, derived from a web theme's five base
 * colours. [Default] is Odysseus's own dark look exactly as the app shipped
 * it, so an unset or unreadable theme changes nothing.
 */
@Immutable
data class Palette(
    val background: Color,
    val foreground: Color,
    val surface: Color,
    val muted: Color,
    val outline: Color,
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val tertiary: Color,
    val error: Color,
    /** The "slightly lifted background" tone, for raised rows. */
    val raised: Color,
    val userBubble: Color,
    val aiBubble: Color,
    val bubbleBorder: Color,
    val inputBg: Color,
    val sendBtn: Color,
    val codeBg: Color,
    val codeFg: Color,
    val isLight: Boolean,
) {
    companion object {
        private val dBackground = Color(0xFF282C34)
        private val dForeground = Color(0xFF9CDEF2)
        private val dSurface = Color(0xFF111111)
        private val dOutline = Color(0xFF355A66)
        private val dPrimary = Color(0xFFE06C75)
        private val dRaised = lerp(dBackground, dForeground, 0.08f)

        val Default = Palette(
            background = dBackground, foreground = dForeground, surface = dSurface,
            muted = Color(0xFF888888), outline = dOutline, primary = dPrimary, onPrimary = Color.White,
            secondary = Color(0xFF6B8A94), tertiary = Color(0xFF50FA7B), error = Color(0xFFFF4444),
            raised = dRaised, userBubble = dRaised, aiBubble = dSurface, bubbleBorder = dOutline,
            inputBg = dSurface, sendBtn = dPrimary, codeBg = Color(0xFF1E2228), codeFg = dForeground,
            isLight = false,
        )

        /**
         * The web's `advanced` overrides win where the app has the matching
         * slot (bubbles, input, send button, code); everything else is
         * derived. The stock dark preset maps to [Default] untouched.
         */
        fun from(c: ThemeColors): Palette {
            if (c == ThemeData.PRESETS.getValue(ThemeData.DEFAULT_NAME)) return Default
            val bg = ThemeData.toColor(c.bg)
            val fg = ThemeData.toColor(c.fg)
            val panel = ThemeData.toColor(c.panel)
            val border = ThemeData.toColor(c.border)
            val red = ThemeData.toColor(c.red)
            val light = bg.luminance() > 0.5f
            fun adv(key: String) = c.advanced[key]?.let(ThemeData::toColor)
            val raised = lerp(bg, fg, 0.08f)
            return Palette(
                background = bg, foreground = fg, surface = panel,
                muted = lerp(fg, bg, 0.5f), outline = border, primary = red,
                onPrimary = if (red.luminance() > 0.5f) Color.Black else Color.White,
                secondary = lerp(fg, bg, 0.4f),
                tertiary = if (light) Color(0xFF1E8E3E) else Color(0xFF50FA7B),
                error = if (light) Color(0xFFC62828) else Color(0xFFFF4444),
                raised = raised,
                userBubble = adv("userBubbleBg") ?: raised,
                aiBubble = adv("aiBubbleBg") ?: panel,
                bubbleBorder = adv("bubbleBorder") ?: border,
                inputBg = adv("inputBg") ?: panel,
                sendBtn = adv("sendBtnBg") ?: red,
                codeBg = adv("codeBg") ?: lerp(bg, if (light) Color.White else Color.Black, 0.18f),
                codeFg = adv("codeFg") ?: fg,
                isLight = light,
            )
        }
    }
}

/** The palette in force. A snapshot state, so every composable that read a
 * colour through [Background] and friends redraws when it changes. */
object AppPalette {
    var current: Palette by mutableStateOf(Palette.Default)
        private set

    fun set(p: Palette) {
        current = p
    }

    fun reset() = set(Palette.Default)
}
