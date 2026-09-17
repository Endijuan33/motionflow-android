package com.motionflow.player.core.media.refresh

/**
 * One display mode, as the platform reports it.
 *
 * A mode is a resolution plus a refresh rate, and the platform identifies it by [modeId]. Nothing
 * here assumes a particular rate exists: the list is whatever the device offers, and it is often
 * shorter than people expect.
 */
data class DisplayModeInfo(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRateHz: Float,
    val isCurrent: Boolean = false,
) {

    /** A mode with a non-positive rate or size is not one MotionFlow will ask for. */
    val isUsable: Boolean get() = refreshRateHz > 0f && width > 0 && height > 0
}

/**
 * What the display can do.
 *
 * [Unknown] is a real value, not an error: on a device that will not report its modes, the engine
 * must decline to choose rather than guess. An empty mode list is never treated as "60 Hz".
 */
data class DisplayRefreshCapabilities(
    val displayId: Int? = null,
    val modes: List<DisplayModeInfo> = emptyList(),
) {

    /** True when the platform actually reported modes. */
    val isKnown: Boolean get() = modes.any { it.isUsable }

    /** The mode the display is in right now, when the platform marks one. */
    val currentMode: DisplayModeInfo? get() = modes.firstOrNull { it.isCurrent && it.isUsable }

    /** The fastest mode available, or `null` when nothing is known. */
    val maximumRefreshRateHz: Float?
        get() = modes.filter { it.isUsable }.maxOfOrNull { it.refreshRateHz }

    /** Every distinct refresh rate available, ascending. */
    val supportedRefreshRatesHz: List<Float>
        get() = modes.filter { it.isUsable }.map { it.refreshRateHz }.distinct().sorted()

    companion object {

        /** Nothing is known about the display. */
        val Unknown = DisplayRefreshCapabilities()
    }
}
