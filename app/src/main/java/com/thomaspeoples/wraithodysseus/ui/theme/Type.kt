package com.thomaspeoples.wraithodysseus.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.thomaspeoples.wraithodysseus.R

// Fira Code 6.2 (OFL-1.1), bundled rather than downloaded: Google's
// downloadable-fonts provider is a Play Services dependency. Source zip and
// its SHA-256 are in docs/android-distribution.md; the licence text ships in
// assets/licenses/FiraCode-OFL.txt.
val FiraCode = FontFamily(
    Font(R.font.fira_code_regular, FontWeight.Normal),
    Font(R.font.fira_code_semibold, FontWeight.SemiBold),
)

/** The type scale in [family]: the theme's font choice, [FiraCode] by default. */
fun typographyFor(family: FontFamily): Typography {
    val body = TextStyle(fontFamily = family, fontSize = 14.sp)
    val label = TextStyle(fontFamily = family, fontSize = 12.sp)
    val title = TextStyle(fontFamily = family, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    return Typography(
        titleLarge = title,
        titleMedium = title,
        titleSmall = title,
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = label,
        labelLarge = label,
        labelMedium = label,
        labelSmall = label.copy(fontSize = 11.sp),
    )
}

val Typography = typographyFor(FiraCode)

/**
 * Code blocks and inline code: smaller, with room between lines. Fira Code;
 * [ThemeFont.codeFamily] is the family for the theme's font choice, applied
 * where the code is rendered as Markdown.
 */
val CodeStyle = TextStyle(fontFamily = FiraCode, fontSize = 11.sp, lineHeight = 16.5.sp)
