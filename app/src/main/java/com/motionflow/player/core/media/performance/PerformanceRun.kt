package com.motionflow.player.core.media.performance

/**
 * A stable, non-identifying fingerprint of the video a run was taken on.
 *
 * Derived from the video's *shape* — its byte size, duration and pixel dimensions — and never from its
 * name. That is deliberate: a fingerprint has to survive an export so two runs can be recognised as the
 * same file, and a hash of a local filename is one guess away from the filename itself. Two different
 * files with identical size, duration and resolution collide, which is acceptable for grouping runs and
 * is the only cost of not carrying a name off the device.
 *
 * It is computed the same way everywhere, so a run recorded today and a run recorded next month can be
 * compared without either of them carrying a path.
 */
@JvmInline
value class VideoFingerprint(val value: String) {

    companion object {

        /** The fingerprint of a video, or `null` when too little is known to tell two files apart. */
        fun of(
            sizeBytes: Long?,
            durationMs: Long?,
            width: Int?,
            height: Int?,
        ): VideoFingerprint? {
            if (sizeBytes == null && durationMs == null) return null

            val shape = listOf(sizeBytes, durationMs, width?.toLong(), height?.toLong())
                .joinToString(SHAPE_SEPARATOR) { it?.toString() ?: "?" }
            return VideoFingerprint(fnv1a64(shape))
        }

        private const val SHAPE_SEPARATOR = "|"

        /**
         * FNV-1a, 64-bit, as hexadecimal.
         *
         * Not a cryptographic hash and not meant to be one: nothing here needs collision resistance
         * against an adversary, only a short stable string that reveals nothing. A cryptographic digest
         * would suggest a security property this data does not need and cannot use.
         */
        private fun fnv1a64(input: String): String {
            var hash = -0x340d631b7bdddcdbL // 14695981039346656037 unsigned, as a Long
            for (byte in input.encodeToByteArray()) {
                hash = hash xor (byte.toLong() and 0xFF)
                hash *= 0x100000001b3L // 1099511628211
            }
            return hash.toULong().toString(radix = 16)
        }
    }
}

/**
 * What a video actually is, recorded independently of what any engine concluded from it being played.
 *
 * [sourceFps] is the metadata engine's *measurement* from sample timing, and [namedRate] is the engine's
 * own vocabulary for a rate it recognised — a label, not a second measurement. Both are kept, because a
 * run that recorded only a label would be hiding the number the label was chosen from, and a run that
 * recorded only a number could not be compared with a document that states a standard rate.
 */
data class VideoCharacteristics(
    val fingerprint: VideoFingerprint?,
    val sourceFps: Float?,
    val namedRate: String?,
    val isVariableFrameRate: Boolean?,
    val confidence: String?,
    val width: Int?,
    val height: Int?,
    val containerType: String?,
    val durationMs: Long?,
) {

    /** True when enough is known to tell this video from another one. */
    val isIdentifiable: Boolean get() = fingerprint != null

    companion object {

        /** Nothing known yet. */
        val Unknown = VideoCharacteristics(
            fingerprint = null,
            sourceFps = null,
            namedRate = null,
            isVariableFrameRate = null,
            confidence = null,
            width = null,
            height = null,
            containerType = null,
            durationMs = null,
        )
    }
}

/**
 * What the display was asked for, and what it did — which are two different facts.
 *
 * Media3 and Android between them can tell an application that a request failed, and can tell it what
 * rate the display reports now. They cannot tell it that a request was quietly ignored: the honest
 * reading of "a rate was requested, no error came back, and the display reports another rate" is that
 * the request was not applied, and the refusal was never announced. That is [NOT_APPLIED], and it is
 * deliberately not called a refusal.
 */
enum class DisplayRequestOutcome {

    /** Nothing was asked for — no cadence to match, or the automatic preference is off. */
    NOT_REQUESTED,

    /** The display reports the rate that was requested. */
    HONOURED,

    /** The platform reported an error for the request. */
    REFUSED,

    /** A rate was requested, no error came back, and the display reports a different one. */
    NOT_APPLIED,

    /** A rate was requested and no applied rate could be read, so nothing is claimed. */
    UNKNOWN,
}

/**
 * What the display is doing, recorded per run.
 *
 * The panel's own list is carried because a measurement means something different on a display that had
 * one mode to choose from than on one that had six. The selection itself remains the refresh engine's:
 * nothing here chooses a mode, and nothing here second-guesses one.
 */
data class DisplayCharacteristics(
    val panelRefreshRatesHz: List<Float> = emptyList(),
    val requestedRefreshRateHz: Float? = null,
    val appliedRefreshRateHz: Float? = null,
    val automaticSelectionEnabled: Boolean? = null,
    val outcome: DisplayRequestOutcome = DisplayRequestOutcome.UNKNOWN,
    val engineStatus: String? = null,
    val errorName: String? = null,
) {

    /** True when a rate was recorded for the display at all. */
    val hasAppliedRate: Boolean get() = appliedRefreshRateHz != null

    /** The rate a measurement's cadence should be read against: what the display did, not what it was asked. */
    val effectiveRefreshRateHz: Float? get() = appliedRefreshRateHz ?: requestedRefreshRateHz
}

/** The clock reading a run started at, so repeated runs can be told apart without a wall clock. */
data class RunTimestamp(val elapsedRealtimeMs: Long)

/** Everything that was held constant for a run, and the one thing that was not. */
data class RunCondition(
    val mode: ProcessingPerformanceMode,
    val video: VideoCharacteristics,
    val display: DisplayCharacteristics,
    val length: PerformanceSessionLength,
)

/** How a run ended. */
enum class PerformanceRunStatus {

    /** The requested window elapsed and the session closed with a measurement. */
    COMPLETE,

    /**
     * The session started and did not run its window — stopped early, or the screen went away.
     *
     * Kept rather than discarded, and never counted as a measurement: a short run's frame counts are
     * real and its comparison with a full window would not be.
     */
    INCOMPLETE,

    /** The playing pipeline failed during the session, so the numbers describe a run that did not finish. */
    FAILED,
}

/**
 * The cadence engine's own classification, copied at the moment of a run.
 *
 * Names rather than values, on purpose: this package must not re-derive cadence, and the honest way to
 * record another engine's conclusion is to quote it. If the classification changes while the processing
 * pipeline is active, that is visible here — and if it does not, nothing in this file can have caused it.
 */
data class CadenceObservation(
    val cadenceMode: String?,
    val cadenceReason: String?,
    val pacingMode: String?,
    val pacingApplied: Boolean?,
    val sourceFps: Float?,
    val displayRefreshRateHz: Float?,
)

/**
 * One completed, incomplete or failed measurement, with everything needed to interpret it.
 *
 * This is the record the hardware characterization is made of, and the reason it exists as a type rather
 * than as a log line: repeated runs of the same condition have to be grouped, compared and exported
 * without any of them losing the context that makes them meaningful — or gaining a context they did not
 * have.
 *
 * There is no score field, no rank and no verdict, and their absence is structural: every field is either
 * something that was measured, something that was recorded alongside it, or how the run ended.
 */
data class PerformanceRun(
    /** 1-based, so a reader can say "run 2 of 3" without an identifier that could leave the device. */
    val index: Int,
    val startedAt: RunTimestamp?,
    val condition: RunCondition,
    val snapshot: FramePerformanceSnapshot,
    val unsupported: List<PerformanceMetric>,
    val cadence: CadenceObservation? = null,
    val status: PerformanceRunStatus,
) {

    /** True when this run may stand as a measurement of its condition. */
    val isUsable: Boolean get() = status == PerformanceRunStatus.COMPLETE && !snapshot.isEmpty

    /** The video this run was taken on, as a value a grouping can be keyed by. */
    val videoKey: VideoFingerprint? get() = condition.video.fingerprint
}

/**
 * The runs taken on one condition, in the order they happened.
 *
 * Several runs of the same thing are the unit of evidence for this phase: one run is an anecdote, and a
 * range across three says more than any single number can.
 */
data class PerformanceRunSeries(
    val condition: RunCondition,
    val runs: List<PerformanceRun> = emptyList(),
) {

    /** The runs that may be counted as measurements. */
    val usableRuns: List<PerformanceRun> get() = runs.filter { it.isUsable }

    /** True when at least one run of this condition completed. */
    val hasEvidence: Boolean get() = usableRuns.isNotEmpty()

    /** Adds a run, numbering it within the series. */
    fun withRun(run: PerformanceRun): PerformanceRunSeries = copy(runs = runs + run)

    /** The mode this series measures. */
    val mode: ProcessingPerformanceMode get() = condition.mode
}
