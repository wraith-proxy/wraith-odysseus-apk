package com.thomaspeoples.wraithodysseus.ui.theme

import androidx.compose.ui.graphics.Color

// Odysseus's own dark palette, so the app reads as the same product as the
// web UI it talks to. Dark only: there is no light scheme to keep in sync.
// The web theme in force (see Palette): these read it each time, so a theme
// change repaints everything that uses them. Dark Odysseus is the default.
val Background: Color get() = AppPalette.current.background
val Foreground: Color get() = AppPalette.current.foreground
val Surface: Color get() = AppPalette.current.surface
val Muted: Color get() = AppPalette.current.muted
val Outline: Color get() = AppPalette.current.outline
val Primary: Color get() = AppPalette.current.primary
val Secondary: Color get() = AppPalette.current.secondary
val Tertiary: Color get() = AppPalette.current.tertiary
val Error: Color get() = AppPalette.current.error

/** The web UI's "slightly lifted background" tone, used for raised rows and
 * the user's own chat bubble. */
val Raised: Color get() = AppPalette.current.raised

/** App-specific colours Material's scheme has no slot for. */
object OdyColors {
    val Warn = Color(0xFFF0AD4E)
    val AccentWarm = Color(0xFFD19A66)

    val UserBubble: Color get() = AppPalette.current.userBubble
    val AiBubble: Color get() = AppPalette.current.aiBubble
    val BubbleBorder: Color get() = AppPalette.current.bubbleBorder

    val InputBg: Color get() = AppPalette.current.inputBg
    val SendBtn: Color get() = AppPalette.current.sendBtn

    val CodeBg: Color get() = AppPalette.current.codeBg
    val CodeFg: Color get() = AppPalette.current.codeFg

    val ThinkingContainer: Color get() = Primary.copy(alpha = 0.05f)
    val ThinkingBorder: Color get() = Primary.copy(alpha = 0.30f)
    val ThinkingHeader: Color get() = Primary.copy(alpha = 0.08f)
}
