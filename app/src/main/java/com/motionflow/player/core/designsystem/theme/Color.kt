package com.motionflow.player.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The colour the player letterboxes video against.
 */
internal val MotionFlowVideoSurface = Color(MotionFlowColorTokens.VIDEO_SURFACE)

/**
 * The dark colour scheme used by every MotionFlow surface.
 *
 * MotionFlow is dark-first because its primary purpose is video playback: a dark chrome keeps
 * attention on the picture, avoids blooming on OLED panels and keeps the bars from competing with
 * frame content.
 *
 * Light theme support is added by declaring a sibling `lightColorScheme` from the same
 * [MotionFlowColorTokens] and selecting between the two inside `MotionFlowTheme`.
 */
internal val MotionFlowDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(MotionFlowColorTokens.PRIMARY),
    onPrimary = Color(MotionFlowColorTokens.ON_PRIMARY),
    primaryContainer = Color(MotionFlowColorTokens.PRIMARY_CONTAINER),
    onPrimaryContainer = Color(MotionFlowColorTokens.ON_PRIMARY_CONTAINER),
    secondary = Color(MotionFlowColorTokens.SECONDARY),
    onSecondary = Color(MotionFlowColorTokens.ON_SECONDARY),
    secondaryContainer = Color(MotionFlowColorTokens.SECONDARY_CONTAINER),
    onSecondaryContainer = Color(MotionFlowColorTokens.ON_SECONDARY_CONTAINER),
    tertiary = Color(MotionFlowColorTokens.TERTIARY),
    onTertiary = Color(MotionFlowColorTokens.ON_TERTIARY),
    tertiaryContainer = Color(MotionFlowColorTokens.TERTIARY_CONTAINER),
    onTertiaryContainer = Color(MotionFlowColorTokens.ON_TERTIARY_CONTAINER),
    error = Color(MotionFlowColorTokens.ERROR),
    onError = Color(MotionFlowColorTokens.ON_ERROR),
    errorContainer = Color(MotionFlowColorTokens.ERROR_CONTAINER),
    onErrorContainer = Color(MotionFlowColorTokens.ON_ERROR_CONTAINER),
    background = Color(MotionFlowColorTokens.BACKGROUND),
    onBackground = Color(MotionFlowColorTokens.ON_BACKGROUND),
    surface = Color(MotionFlowColorTokens.SURFACE),
    onSurface = Color(MotionFlowColorTokens.ON_SURFACE),
    surfaceVariant = Color(MotionFlowColorTokens.SURFACE_VARIANT),
    onSurfaceVariant = Color(MotionFlowColorTokens.ON_SURFACE_VARIANT),
    surfaceContainerLowest = Color(MotionFlowColorTokens.SURFACE_CONTAINER_LOWEST),
    surfaceContainerLow = Color(MotionFlowColorTokens.SURFACE_CONTAINER_LOW),
    surfaceContainer = Color(MotionFlowColorTokens.SURFACE_CONTAINER),
    surfaceContainerHigh = Color(MotionFlowColorTokens.SURFACE_CONTAINER_HIGH),
    surfaceContainerHighest = Color(MotionFlowColorTokens.SURFACE_CONTAINER_HIGHEST),
    outline = Color(MotionFlowColorTokens.OUTLINE),
    outlineVariant = Color(MotionFlowColorTokens.OUTLINE_VARIANT),
    inverseSurface = Color(MotionFlowColorTokens.INVERSE_SURFACE),
    inverseOnSurface = Color(MotionFlowColorTokens.INVERSE_ON_SURFACE),
    inversePrimary = Color(MotionFlowColorTokens.INVERSE_PRIMARY),
    scrim = Color(MotionFlowColorTokens.SCRIM),
)
