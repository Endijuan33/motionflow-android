package com.motionflow.player.core.media.refresh

import com.motionflow.player.core.media.metadata.MetadataConfidence

/**
 * What the engine decided to do about the display.
 *
 * The status describes the *matching*, never the video's frame rate. A display running at twice a
 * video's cadence is still showing the same frames twice; it is not producing new ones.
 */
enum class RefreshRateStatus {

    /** A display mode was found that presents the cadence exactly (or as an integer multiple). */
    MATCHED,

    /** A mode was chosen that can show every frame, but not at an integer multiple of the cadence. */
    FALLBACK,

    /** No request is needed: nothing on offer beats what the display is already doing, or the rate
     *  is not reliable enough to act on. The reason says which. */
    UNCHANGED,

    /** Nothing could be decided — no usable frame rate, a variable one, or unknown capabilities. */
    UNKNOWN,

    /** The cadence cannot be presented on this display. */
    UNSUPPORTED,

    /** The user asked for the system default, so the engine does not request anything. */
    MANUAL,
}

/** Why the engine reached its [RefreshRateStatus]. */
enum class RefreshRateReason {

    /** The video's frame rate is not known, so there is nothing to match. */
    NO_FRAME_RATE,

    /** The source was observed varying its cadence; switching would chase a moving target. */
    VARIABLE_FRAME_RATE,

    /** The rate came from a container header and may be rounded, so only an exact match is acted on. */
    LOW_CONFIDENCE,

    /** The display did not report its modes. */
    CAPABILITIES_UNKNOWN,

    /** No available mode can present this cadence. */
    NO_SUITABLE_MODE,

    /** The current mode is already as suitable as anything on offer. */
    ALREADY_SUITABLE,

    /** A mode in the same cadence family as the video was selected. */
    EXACT_MODE,

    /** A mode at an integer multiple of the cadence was selected; every frame is held equally. */
    INTEGER_MULTIPLE,

    /** The slowest mode that can still show every frame was selected, though the ratio is not whole. */
    BEST_EFFORT,

    /** The user chose the system default. */
    AUTOMATIC_DISABLED,

    /** The platform refused the request. */
    PLATFORM_REJECTED,
}

/** How a refresh-rate preference reached the platform. */
enum class RefreshRateSource {

    /** Nothing is being requested. */
    NONE,

    /** The window's preferred refresh rate is being requested. */
    WINDOW_REFRESH_RATE,
}

/** Why a request could not be made. */
enum class RefreshRateError {

    /** No display could be resolved for the player window. */
    NO_DISPLAY,

    /** The platform refused the request. */
    PLATFORM_REJECTED,

    /** Anything else. */
    UNKNOWN,
}

/**
 * The engine's conclusion for one video and one display.
 *
 * [targetMode] is `null` whenever nothing should be requested — which is the ordinary outcome for
 * unknown frame rates, variable sources and displays that cannot do better than what they are
 * already doing.
 */
data class RefreshRateDecision(
    val status: RefreshRateStatus = RefreshRateStatus.UNKNOWN,
    val reason: RefreshRateReason = RefreshRateReason.NO_FRAME_RATE,
    val targetMode: DisplayModeInfo? = null,
    val cadenceHz: Float? = null,
    val confidence: MetadataConfidence = MetadataConfidence.UNKNOWN,
) {

    /** True when this decision asks the platform for a display mode. */
    val requiresChange: Boolean get() = targetMode != null

    companion object {

        val Unknown = RefreshRateDecision()
    }
}

/**
 * A request to the platform: present content at [refreshRateHz], in [modeId] when one was chosen.
 *
 * [refreshRateHz] is always taken from a mode the display reports, because below API 34 the platform
 * only accepts a supported rate here.
 */
data class RefreshRateRequest(
    val modeId: Int?,
    val refreshRateHz: Float,
)

/** The outcome of handing a [RefreshRateRequest] to the platform. */
data class RefreshRateApplication(
    val applied: Boolean,
    val source: RefreshRateSource = RefreshRateSource.NONE,
    val error: RefreshRateError? = null,
)
