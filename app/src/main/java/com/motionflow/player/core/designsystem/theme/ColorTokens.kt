package com.motionflow.player.core.designsystem.theme

/**
 * Framework-independent colour tokens, expressed as `0xAARRGGBB` values.
 *
 * Keeping the raw values free of Compose types lets the palette be unit tested on the JVM and
 * gives the design system a single source of truth that any future non-Compose surface (a widget,
 * a notification, an overlay) can read from.
 *
 * Contrast ratios for every text-on-surface pairing in this palette are asserted by
 * `MotionFlowColorTokensContrastTest`.
 */
internal object MotionFlowColorTokens {

    // Neutral surfaces, ordered from the deepest background to the most elevated container.
    const val BACKGROUND: Long = 0xFF08090C
    const val SURFACE: Long = 0xFF0F1116
    const val SURFACE_CONTAINER_LOWEST: Long = 0xFF060709
    const val SURFACE_CONTAINER_LOW: Long = 0xFF12141A
    const val SURFACE_CONTAINER: Long = 0xFF161920
    const val SURFACE_CONTAINER_HIGH: Long = 0xFF1B1F27
    const val SURFACE_CONTAINER_HIGHEST: Long = 0xFF21262F
    const val SURFACE_VARIANT: Long = 0xFF262B34

    // Content on those surfaces.
    const val ON_BACKGROUND: Long = 0xFFF2F4F8
    const val ON_SURFACE: Long = 0xFFE8EBF0
    const val ON_SURFACE_VARIANT: Long = 0xFFA8B0BD

    // Primary accent: the cyan used for motion and playback affordances.
    const val PRIMARY: Long = 0xFF4FD8FF
    const val ON_PRIMARY: Long = 0xFF00202B
    const val PRIMARY_CONTAINER: Long = 0xFF0B3A4A
    const val ON_PRIMARY_CONTAINER: Long = 0xFFA8E9FF

    // Secondary: cool blue, reserved for secondary controls and timing information.
    const val SECONDARY: Long = 0xFF8FB3FF
    const val ON_SECONDARY: Long = 0xFF0B2350
    const val SECONDARY_CONTAINER: Long = 0xFF1B2C4D
    const val ON_SECONDARY_CONTAINER: Long = 0xFFD6E2FF

    // Tertiary: violet, reserved for interpolation and processing affordances.
    const val TERTIARY: Long = 0xFFC9A9FF
    const val ON_TERTIARY: Long = 0xFF23005C
    const val TERTIARY_CONTAINER: Long = 0xFF3A2560
    const val ON_TERTIARY_CONTAINER: Long = 0xFFEDDCFF

    // Errors.
    const val ERROR: Long = 0xFFFFB4AB
    const val ON_ERROR: Long = 0xFF690005
    const val ERROR_CONTAINER: Long = 0xFF93000A
    const val ON_ERROR_CONTAINER: Long = 0xFFFFDAD6

    // Structure and separation.
    const val OUTLINE: Long = 0xFF6E7789
    const val OUTLINE_VARIANT: Long = 0xFF333A45

    // Inverse roles, used by snackbars and other temporarily light surfaces.
    const val INVERSE_SURFACE: Long = 0xFFE3E6EC
    const val INVERSE_ON_SURFACE: Long = 0xFF1A1D22
    const val INVERSE_PRIMARY: Long = 0xFF00688A

    const val SCRIM: Long = 0xFF000000
}
