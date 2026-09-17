package com.motionflow.player.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion durations, in milliseconds, for the animations the application owns.
 *
 * The steps are deliberately short: MotionFlow animates chrome around a moving picture, and any
 * transition that outlasts the content underneath it feels like latency.
 */
@Immutable
data class MotionFlowMotion(
    val instantMs: Int = 100,
    val quickMs: Int = 180,
    val standardMs: Int = 260,
    val emphasizedMs: Int = 400,
)

internal val LocalMotionFlowMotion = staticCompositionLocalOf { MotionFlowMotion() }
