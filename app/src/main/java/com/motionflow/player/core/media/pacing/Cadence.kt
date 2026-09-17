package com.motionflow.player.core.media.pacing

import com.motionflow.player.core.media.metadata.MetadataConfidence

/**
 * The video's cadence, and how much it can be trusted.
 *
 * [fps] being `null` is a real state: no cadence was measured, so there is nothing to analyse.
 * Nothing here is ever rounded to a whole number first — 23.976 stays 23.976.
 */
data class VideoCadence(
    val fps: Float? = null,
    val confidence: MetadataConfidence = MetadataConfidence.UNKNOWN,
    val isVariableFrameRate: Boolean? = null,
) {

    /** True when there is a single, steady rate to analyse against. */
    val isUsable: Boolean
        get() = fps != null && fps.isFinite() && fps > 0f && isVariableFrameRate != true

    companion object {

        val Unknown = VideoCadence()
    }
}

/**
 * The display's cadence, as the refresh-rate engine left it.
 *
 * [refreshRateHz] is what the display *reports*, which is not necessarily what was asked for. The
 * request fields are carried so the diagnostics can say when a mismatch exists because the platform
 * would not move the display, rather than because no good mode was available.
 */
data class DisplayCadence(
    val refreshRateHz: Float? = null,
    val requestedRefreshRateHz: Float? = null,
    val requestRefused: Boolean = false,
) {

    /** True when the display reported a rate that can be analysed against. */
    val isUsable: Boolean get() = refreshRateHz != null && refreshRateHz.isFinite() && refreshRateHz > 0f

    companion object {

        val Unknown = DisplayCadence()
    }
}

/**
 * How a display refresh rate relates to a video frame rate.
 *
 * [value] is the measured ratio `displayHz / videoFps`, never rounded for display. When the ratio is
 * a short fraction — 24 fps on a 60 Hz display is 5 refreshes to 2 frames — [refreshesPerFrame] and
 * [frameCount] carry that fraction and [patternLabel] names the resulting pattern of holds ("3:2").
 * A ratio that is not a short fraction is reported unresolved: [frameCount] is 0 and no pattern is
 * claimed, because naming the nearest fraction would suggest a regularity the pairing does not have.
 */
data class CadenceRatio(
    val value: Float,
    val refreshesPerFrame: Int = 0,
    val frameCount: Int = 0,
    val patternLabel: String = "",
) {

    /** True when the ratio was recognisable as a whole number or a short fraction. */
    val isResolved: Boolean get() = frameCount > 0

    /** True when the display refreshes a whole number of times per frame. */
    val isWhole: Boolean get() = frameCount == 1

    /** True when every frame is held for the same number of refreshes. */
    val isEven: Boolean get() = isWhole
}
