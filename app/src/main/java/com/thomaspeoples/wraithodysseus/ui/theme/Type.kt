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

private val Body = TextStyle(fontFamily = FiraCode, fontSize = 14.sp)
private val Label = TextStyle(fontFamily = FiraCode, fontSize = 12.sp)
private val Title = TextStyle(fontFamily = FiraCode, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

val Typography = Typography(
    titleLarge = Title,
    titleMedium = Title,
    titleSmall = Title,
    bodyLarge = Body,
    bodyMedium = Body,
    bodySmall = Label,
    labelLarge = Label,
    labelMedium = Label,
    labelSmall = Label.copy(fontSize = 11.sp),
)

/** Code blocks and inline code: smaller, with room between lines. */
val CodeStyle = TextStyle(fontFamily = FiraCode, fontSize = 11.sp, lineHeight = 16.5.sp)
