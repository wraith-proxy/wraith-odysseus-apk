package com.thomaspeoples.wraithodysseus.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// Odysseus's own dark palette, so the app reads as the same product as the
// web UI it talks to. Dark only: there is no light scheme to keep in sync.
val Background = Color(0xFF282C34)
val Foreground = Color(0xFF9CDEF2)
val Surface = Color(0xFF111111)
val Muted = Color(0xFF888888)
val Outline = Color(0xFF355A66)
val Primary = Color(0xFFE06C75)
val Secondary = Color(0xFF6B8A94)
val Tertiary = Color(0xFF50FA7B)
val Error = Color(0xFFFF4444)

/** The web UI's "slightly lifted background" tone, used for raised rows and
 * the user's own chat bubble. */
val Raised = lerp(Background, Foreground, 0.08f)

/** App-specific colours Material's scheme has no slot for. */
object OdyColors {
    val Warn = Color(0xFFF0AD4E)
    val AccentWarm = Color(0xFFD19A66)

    val UserBubble = Raised
    val AiBubble = Surface
    val BubbleBorder = Outline

    val InputBg = Surface
    val SendBtn = Primary

    val CodeBg = Color(0xFF1E2228)
    val CodeFg = Foreground

    val ThinkingContainer = Primary.copy(alpha = 0.05f)
    val ThinkingBorder = Primary.copy(alpha = 0.30f)
    val ThinkingHeader = Primary.copy(alpha = 0.08f)
}
