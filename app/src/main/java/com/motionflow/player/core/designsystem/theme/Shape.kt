package com.motionflow.player.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii for MotionFlow.
 *
 * Radii stay modest on purpose: playback surfaces are rectangles that meet the edges of the
 * display, and heavily rounded containers read as generic template UI next to video content.
 */
internal val MotionFlowShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
