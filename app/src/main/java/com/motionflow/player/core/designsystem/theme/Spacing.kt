package com.motionflow.player.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale, derived from a 4dp base unit.
 *
 * Every gap, inset and padding in the application resolves to one of these steps so that density
 * stays predictable as new screens arrive.
 */
@Immutable
data class MotionFlowSpacing(
    val none: Dp = 0.dp,
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val extraLarge: Dp = 32.dp,
    val huge: Dp = 48.dp,
)

internal val LocalMotionFlowSpacing = staticCompositionLocalOf { MotionFlowSpacing() }
