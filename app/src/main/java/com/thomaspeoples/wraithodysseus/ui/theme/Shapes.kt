package com.thomaspeoples.wraithodysseus.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

// Tight 4dp on controls and code, softer on the containers around them.
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

val SendButtonShape = RoundedCornerShape(8.dp)
val ThinkingShape = RoundedCornerShape(8.dp)
val SheetShape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
val InputBarShape = RoundedCornerShape(16.dp)

// The square corner is the bubble's tail, pointing at whoever spoke.
val UserBubbleShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 0.dp, bottomStart = 18.dp)
val AiBubbleShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 0.dp)
const val BUBBLE_MAX_WIDTH_FRACTION = 0.85f
val BubblePadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)

val ToggleSize = DpSize(30.dp, 16.dp)
