package com.motionflow.player.core.media.metadata

import kotlin.math.abs

/**
 * How much a metadata value should be trusted.
 *
 * Reported alongside frame rate because a rate measured from sample timing and a rate copied from a
 * container header are not equally reliable, and the refresh-rate controller in a later phase has to
 * be able to tell them apart before it acts on one.
 */
enum class MetadataConfidence {
    /** Measured directly, with enough evidence to rely on. */
    HIGH,

    /** Measured or parsed, but with a caveat (few samples, or the source is not constant). */
    MEDIUM,

    /** Copied from a header, or measured from too little data to be sure of. */
    LOW,

    /** No value available. */
    UNKNOWN,
}

/** Where a frame rate came from. */
enum class FrameRateSource {
    /** Measured from the timestamps of consecutive samples of the video track. */
    SAMPLE_TIMING,

    /** Reported by the container's track header. */
    CONTAINER,

    /** Reported by Media3's parsed [androidx.media3.common.Format]. */
    PLAYER_FORMAT,

    /** Not determined. */
    UNKNOWN,
}

/**
 * The frame rates worth recognising exactly.
 *
 * The fractional rates are the ones that matter: 23.976 is not 24, and 29.97 is not 30. Rounding
 * either to the nearest integer is the mistake that makes a player drop or repeat a frame every
 * 41 seconds, so they are named and compared with a tolerance instead of being rounded.
 */
enum class KnownFrameRate(val fps: Float, val label: String) {
    FPS_23_976(23.976f, "23.976"),
    FPS_24(24f, "24"),
    FPS_25(25f, "25"),
    FPS_29_97(29.97f, "29.97"),
    FPS_30(30f, "30"),
    FPS_50(50f, "50"),
    FPS_59_94(59.94f, "59.94"),
    FPS_60(60f, "60"),

    ;

    companion object {

        /**
         * How far a measured rate may sit from a named rate and still be recognised as it.
         *
         * A hundredth of a frame per second is far tighter than the gap between neighbouring rates
         * (0.024 fps between 23.976 and 24, 0.03 between 29.97 and 30) and far looser than the error
         * a timestamp measurement introduces, so a rate is either recognised or it is not.
         */
        const val SNAP_TOLERANCE_FPS = 0.01f

        /** The named rate closest to [fps], or `null` when it is not close enough to any of them. */
        fun nearest(fps: Float): KnownFrameRate? {
            val candidate = entries.minByOrNull { abs(it.fps - fps) } ?: return null
            return candidate.takeIf { abs(it.fps - fps) <= SNAP_TOLERANCE_FPS }
        }
    }
}

/**
 * What is known about a video's frame rate.
 *
 * Both fields are nullable on purpose. `fps == null` means the rate could not be determined, and
 * `isVariableFrameRate == null` means variability could not be determined — which is the honest
 * answer for most files, because proving a source is constant requires reading its whole timing
 * table. See [FrameRateSource] and `ARCHITECTURE.md` for what each source can and cannot prove.
 */
data class FrameRateInfo(
    val fps: Float? = null,
    val isVariableFrameRate: Boolean? = null,
    val source: FrameRateSource = FrameRateSource.UNKNOWN,
    val confidence: MetadataConfidence = MetadataConfidence.UNKNOWN,
) {

    /** True when a rate is known. */
    val isKnown: Boolean get() = fps != null

    /** The named rate this value corresponds to, when it is one of the common rates. */
    val knownRate: KnownFrameRate? get() = fps?.let(KnownFrameRate::nearest)

    companion object {

        /** Nothing could be determined. */
        val Unknown = FrameRateInfo()

        /** A rate measured from sample timestamps. */
        fun measured(
            fps: Float,
            isVariableFrameRate: Boolean?,
            confidence: MetadataConfidence,
        ): FrameRateInfo = FrameRateInfo(
            fps = fps,
            isVariableFrameRate = isVariableFrameRate,
            source = FrameRateSource.SAMPLE_TIMING,
            confidence = confidence,
        )

        /**
         * A rate taken from a header rather than measured.
         *
         * Headers store whole numbers far more often than fractional ones, so this is reported with
         * low confidence: a container that says "24" may well be holding 23.976.
         */
        fun fromHeader(fps: Float, source: FrameRateSource): FrameRateInfo = FrameRateInfo(
            fps = fps,
            isVariableFrameRate = null,
            source = source,
            confidence = MetadataConfidence.LOW,
        )
    }
}
