package com.motionflow.player.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Elevation scale for MotionFlow.
 *
 * Depth is expressed with tonal surfaces rather than heavy shadows, so these levels stay low. They
 * exist as named tokens so that player chrome, sheets and overlays stack consistently.
 */
@Immutable
data class MotionFlowElevation(
    val level0: Dp = 0.dp,
    val level1: Dp = 1.dp,
    val level2: Dp = 3.dp,
    val level3: Dp = 6.dp,
    val level4: Dp = 8.dp,
    val level5: Dp = 12.dp,
)

internal val LocalMotionFlowElevation = staticCompositionLocalOf { MotionFlowElevation() }
