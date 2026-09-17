package com.motionflow.player.core.media.pacing

import com.motionflow.player.core.media.metadata.MetadataConfidence

/**
 * How a video's cadence sits against a display's.
 *
 * The distinction that matters is whether every frame is held for the same number of refreshes. A
 * display refreshing twice per frame shows forty-eight frames a second of a twenty-four frame
 * source; that is even. A display refreshing two and a half times per frame shows the same frames
 * for 3 and then 2 refreshes; the motion is uneven, however evenly it repeats.
 *
 * There is deliberately no "fractionally compatible" mode. A fractional relationship is never even,
 * and the two cases a fractional mode would separate — 24 fps on 60 Hz and 23.976 fps on 59.94 Hz —
 * are the *same ratio*, exactly five refreshes to two frames. How long the repeating unit is, and
 * therefore how regular it looks, is carried by [FramePacingReason] instead.
 */
enum class FramePacingMode {

    /** The display refreshes once per frame. Nothing to pace. */
    NATIVE_CADENCE,

    /** The display refreshes a whole number of times per frame. Even, and nothing to pace. */
    INTEGER_MULTIPLE,

    /** The display cannot show each frame the same number of times. Identified, not corrected. */
    CADENCE_MISMATCH,

    /** Not enough is known to say: no cadence, a variable one, or no display rate. */
    UNKNOWN,

    /** The display refreshes slower than the source delivers frames. */
    UNSUPPORTED,
}

/** Why the cadence was classified the way it was. */
enum class FramePacingReason {

    /** Nothing has been analysed yet. */
    NOT_EVALUATED,

    /** The video's frame rate is unknown. */
    NO_FRAME_RATE,

    /** The display's refresh rate is unknown. */
    NO_DISPLAY_RATE,

    /** The source changes its cadence, so no fixed pattern describes it. */
    VARIABLE_FRAME_RATE,

    /** The rate came from a container header and may be rounded. */
    LOW_CONFIDENCE_FRAME_RATE,

    /** The display refreshes once per frame. */
    EXACT_CADENCE,

    /** The display refreshes a whole number of times per frame. */
    WHOLE_MULTIPLE,

    /** A short repeating pattern of holds: 24 fps at 60 Hz is 3 refreshes, then 2. */
    SHORT_REPEATING_PATTERN,

    /** A longer, still exactly repeating pattern of holds. */
    LONG_REPEATING_PATTERN,

    /** No repeating unit short enough to describe; the pairing drifts. */
    UNRESOLVED_PATTERN,

    /** The display cannot keep up with the source. */
    DISPLAY_TOO_SLOW,
}

/**
 * How pacing was carried out.
 *
 * Only [NONE] exists in this phase, and that is the honest value: nothing in the current
 * architecture can change when a decoded frame is presented without replacing Media3's video
 * renderer, so every decision is diagnostic.
 */
enum class FramePacingMechanism {

    /** Nothing was applied. The decision is a diagnosis. */
    NONE,
}

/** Why pacing could not be carried out. */
enum class FramePacingError {

    /** The platform refused the request. */
    PLATFORM_REJECTED,

    /** Anything else. */
    UNKNOWN,
}

/**
 * What the engine concluded, and whether anything was done about it.
 *
 * [mechanism] is the single fact behind "applied": [isApplied] is true only when a mechanism other
 * than [FramePacingMechanism.NONE] carried the decision out. This phase has no such mechanism, so
 * every decision here reports `false`, and the diagnostics can say "diagnostic only" because that is
 * what happened rather than because no one checked.
 */
data class FramePacingDecision(
    val mode: FramePacingMode = FramePacingMode.UNKNOWN,
    val reason: FramePacingReason = FramePacingReason.NOT_EVALUATED,
    val isReliable: Boolean = false,
    val mechanism: FramePacingMechanism = FramePacingMechanism.NONE,
    val error: FramePacingError? = null,
) {

    /** True when the cadence cannot be presented evenly and nothing could be done about it. */
    val isMismatched: Boolean
        get() = mode == FramePacingMode.CADENCE_MISMATCH || mode == FramePacingMode.UNSUPPORTED

    /** True only when a mechanism actually acted on presentation timing. */
    val isApplied: Boolean
        get() = mechanism != FramePacingMechanism.NONE && error == null

    companion object {

        val NotEvaluated = FramePacingDecision()
    }
}

/** The outcome of handing a decision to a pacing mechanism. */
data class FramePacingApplication(
    val mechanism: FramePacingMechanism = FramePacingMechanism.NONE,
    val error: FramePacingError? = null,
)

/**
 * Everything the analysis produced for one video on one display.
 *
 * [confidence] describes the *input*, not the conclusion: a cadence read from a container header can
 * still be classified, and the classification is still shown, but it is not something to act on
 * blindly.
 */
data class FramePacingDiagnostics(
    val video: VideoCadence,
    val display: DisplayCadence,
    val ratio: CadenceRatio? = null,
    val mode: FramePacingMode = FramePacingMode.UNKNOWN,
    val reason: FramePacingReason = FramePacingReason.NOT_EVALUATED,
    val confidence: MetadataConfidence = MetadataConfidence.UNKNOWN,
)
