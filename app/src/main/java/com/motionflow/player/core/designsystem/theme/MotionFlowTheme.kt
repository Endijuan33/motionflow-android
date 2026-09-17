package com.motionflow.player.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Applies the MotionFlow design system.
 *
 * Colour, type and shape are supplied through [MaterialTheme] so that Material components pick
 * them up automatically. Spacing, elevation and motion are provided through dedicated composition
 * locals and read back through the [MotionFlowTheme] accessors.
 *
 * The token instances are allocated once and shared by every composition, so providers never
 * invalidate their readers by identity.
 */
@Composable
fun MotionFlowTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalMotionFlowSpacing provides DefaultSpacing,
        LocalMotionFlowElevation provides DefaultElevation,
        LocalMotionFlowMotion provides DefaultMotion,
    ) {
        MaterialTheme(
            colorScheme = MotionFlowDarkColorScheme,
            typography = MotionFlowTypography,
            shapes = MotionFlowShapes,
            content = content,
        )
    }
}

/**
 * Accessors for the MotionFlow tokens that Material 3 has no slot for.
 */
object MotionFlowTheme {

    val spacing: MotionFlowSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalMotionFlowSpacing.current

    val elevation: MotionFlowElevation
        @Composable
        @ReadOnlyComposable
        get() = LocalMotionFlowElevation.current

    val motion: MotionFlowMotion
        @Composable
        @ReadOnlyComposable
        get() = LocalMotionFlowMotion.current
}

private val DefaultSpacing = MotionFlowSpacing()
private val DefaultElevation = MotionFlowElevation()
private val DefaultMotion = MotionFlowMotion()
