package com.motionflow.player.core.media.performance

/**
 * Something about a run that stops it from standing as evidence.
 *
 * Validation is a *naming* exercise rather than a repair one: nothing here fixes a record, and nothing
 * drops one silently. A run that cannot be used is kept and labelled, because "the third run was
 * incomplete" is a finding and a missing third run is a hole.
 */
enum class PerformanceIntegrityIssue {

    /** The session did not run its requested window. */
    SESSION_INCOMPLETE,

    /** The playback pipeline failed during the session. */
    SESSION_FAILED,

    /** The session produced no measurement at all. */
    MEASUREMENT_EMPTY,

    /** A run claiming completion lasted less than its requested window. */
    SESSION_NOT_RUN_FOR_ITS_WINDOW,

    /** The video could not be told apart from another one, so two runs cannot be proven to match. */
    VIDEO_NOT_IDENTIFIABLE,

    /** No source frame rate was recorded, so the cadence this run exercised is unknown. */
    SOURCE_RATE_MISSING,

    /** No display refresh rate was recorded, so neither rate of the pair is known. */
    DISPLAY_RATE_MISSING,

    /** The platform cannot measure a metric, and the run reports a value for it anyway. */
    UNSUPPORTED_METRIC_REPORTED,

    /** The cadence engine's classification was not copied into the record. */
    CADENCE_NOT_RECORDED,

    /** A value that was not measured arrived as a zero rather than as absent. */
    ZERO_WHERE_NOT_MEASURED,
}

/**
 * An issue, and which metric it is about when it is about one.
 *
 * The metric is carried so a finding can name the reading that was fabricated rather than pointing at a
 * category. "The platform cannot measure GPU utilisation and a run reported one" is a sentence a person
 * can act on; "unsupported metric reported" is a category they have to go looking in.
 */
data class PerformanceIntegrityFinding(
    val issue: PerformanceIntegrityIssue,
    val metric: PerformanceMetric? = null,
)

/**
 * Checks a run against what would have to be true for it to be evidence.
 *
 * Every check is a question about *the record*, not about playback: a run is not judged on how it
 * performed, only on whether it says enough to be read. That separation is what keeps this phase a
 * characterization: a slow run and a fast run are both valid, and an unlabelled run is not.
 *
 * [support] is what the platform can measure, so a metric no device can provide is not mistaken for a
 * metric this device forgot to provide.
 */
object PerformanceIntegrity {

    /** True when the requested window elapsed. A completed run that did not run its window is a contradiction. */
    private const val WINDOW_TOLERANCE = 0.9

    fun issues(
        run: PerformanceRun,
        support: PerformanceMeasurementSupport,
    ): List<PerformanceIntegrityFinding> = buildList {
        fun found(issue: PerformanceIntegrityIssue) { add(PerformanceIntegrityFinding(issue)) }
        when (run.status) {
            PerformanceRunStatus.INCOMPLETE -> found(PerformanceIntegrityIssue.SESSION_INCOMPLETE)
            PerformanceRunStatus.FAILED -> found(PerformanceIntegrityIssue.SESSION_FAILED)
            PerformanceRunStatus.COMPLETE -> Unit
        }

        if (run.snapshot.isEmpty) found(PerformanceIntegrityIssue.MEASUREMENT_EMPTY)

        // A run that says it completed has to have lasted its window. Anything shorter is an incomplete
        // session wearing the wrong label, and the label is what a comparison trusts.
        val measured = run.snapshot.measurementDurationMs
        if (run.status == PerformanceRunStatus.COMPLETE &&
            measured != null &&
            measured < (run.condition.length.durationMs * WINDOW_TOLERANCE).toLong()
        ) {
            found(PerformanceIntegrityIssue.SESSION_NOT_RUN_FOR_ITS_WINDOW)
        }

        if (!run.condition.video.isIdentifiable) found(PerformanceIntegrityIssue.VIDEO_NOT_IDENTIFIABLE)
        if (run.condition.video.sourceFps == null) found(PerformanceIntegrityIssue.SOURCE_RATE_MISSING)
        if (!run.condition.display.hasAppliedRate) found(PerformanceIntegrityIssue.DISPLAY_RATE_MISSING)
        if (run.cadence == null) found(PerformanceIntegrityIssue.CADENCE_NOT_RECORDED)

        // Every metric the platform says it cannot measure must be absent. Each one is checked
        // individually rather than by counting, so the finding names the metric that was fabricated
        // rather than pointing at a total.
        reportedWhileUnsupported(run.snapshot, support).forEach { metric ->
            add(PerformanceIntegrityFinding(PerformanceIntegrityIssue.UNSUPPORTED_METRIC_REPORTED, metric))
        }

        if (hasZeroWhereNothingWasMeasured(run.snapshot)) {
            found(PerformanceIntegrityIssue.ZERO_WHERE_NOT_MEASURED)
        }
    }

    /** The metrics a run reports a value for while the platform says it cannot measure them. */
    private fun reportedWhileUnsupported(
        snapshot: FramePerformanceSnapshot,
        support: PerformanceMeasurementSupport,
    ): List<PerformanceMetric> = buildList {
        if (snapshot.cpuTimeMs != null && !support.supports(PerformanceMetric.CPU_TIME)) {
            add(PerformanceMetric.CPU_TIME)
        }
        if (snapshot.processPssKb != null && !support.supports(PerformanceMetric.PROCESS_PSS)) {
            add(PerformanceMetric.PROCESS_PSS)
        }
        if (snapshot.heapUsedBytes != null && !support.supports(PerformanceMetric.HEAP_USED)) {
            add(PerformanceMetric.HEAP_USED)
        }
        if (snapshot.thermalStatusAtEnd != null && !support.supports(PerformanceMetric.THERMAL_STATUS)) {
            add(PerformanceMetric.THERMAL_STATUS)
        }
        if (snapshot.renderedFrames != null && !support.supports(PerformanceMetric.RENDERED_FRAMES)) {
            add(PerformanceMetric.RENDERED_FRAMES)
        }
        if (snapshot.droppedFrames != null && !support.supports(PerformanceMetric.DROPPED_FRAMES)) {
            add(PerformanceMetric.DROPPED_FRAMES)
        }
    }

    /**
     * True when a count that only exists for the effect pipeline is zero in a run that had no processor.
     *
     * The one place "zero versus absent" can actually be got wrong: a frame-processing offset of zero in
     * the native pipeline would read as a processor that kept up perfectly, when there was no processor
     * at all.
     */
    private fun hasZeroWhereNothingWasMeasured(snapshot: FramePerformanceSnapshot): Boolean =
        snapshot.frameProcessingOffsetFrames == 0 && snapshot.frameProcessingOffsetTotalUs == null

    /** True when [run] can be used as evidence. */
    fun isUsable(run: PerformanceRun, support: PerformanceMeasurementSupport): Boolean =
        run.isUsable && issues(run, support).isEmpty()

    /** The issues that stop a run being evidence, as kinds, for a caller that only needs to count them. */
    fun issueKinds(run: PerformanceRun, support: PerformanceMeasurementSupport): List<PerformanceIntegrityIssue> =
        issues(run, support).map { it.issue }.distinct()
}

/**
 * Why two runs cannot be compared.
 *
 * §5 of this phase's brief is explicit that only the pipeline may differ between the two baselines, so
 * each of these is a way that rule would be broken. Nothing here stops a user from measuring whatever
 * they like — it stops the *comparison* from being presented as if the conditions had matched.
 */
enum class PerformanceComparisonBlocker {

    /** No complete run on the native pipeline for this video. */
    NATIVE_EVIDENCE_MISSING,

    /** No complete run on the effect pipeline for this video. */
    EFFECT_EVIDENCE_MISSING,

    /** The two pipelines were measured on videos with different fingerprints. */
    DIFFERENT_VIDEO,

    /** The two pipelines were measured on videos with different resolutions. */
    DIFFERENT_RESOLUTION,

    /** The two pipelines were measured on videos with different source frame rates. */
    DIFFERENT_SOURCE_RATE,

    /** The two pipelines were measured at different display rates. */
    DIFFERENT_DISPLAY_RATE,

    /** The two pipelines were measured over different windows. */
    DIFFERENT_DURATION,

    /** At least one run was incomplete, so its numbers describe a shorter window. */
    INCOMPLETE_RUN_PRESENT,

    /** At least one run failed, so its numbers describe a run that did not finish. */
    FAILED_RUN_PRESENT,
}

/** Whether a pair of series may be compared, and what stops it if not. */
data class PerformanceComparisonEligibility(
    val blockers: List<PerformanceComparisonBlocker> = emptyList(),
) {

    val eligible: Boolean get() = blockers.isEmpty()

    companion object {

        /**
         * Decides whether the native and effect series describe the same conditions.
         *
         * Only the pipeline may differ. Everything else — the video, its resolution, its rate, the
         * display's rate, the window — has to match, and the *first* run of each series is used as the
         * series' representative because every run in a series shares its condition by construction.
         */
        fun of(native: PerformanceRunSeries?, effect: PerformanceRunSeries?): PerformanceComparisonEligibility {
            val blockers = mutableListOf<PerformanceComparisonBlocker>()

            val nativeRun = native?.usableRuns?.firstOrNull()
            val effectRun = effect?.usableRuns?.firstOrNull()

            if (nativeRun == null) blockers += PerformanceComparisonBlocker.NATIVE_EVIDENCE_MISSING
            if (effectRun == null) blockers += PerformanceComparisonBlocker.EFFECT_EVIDENCE_MISSING

            native?.runs?.let { runs ->
                if (runs.any { it.status == PerformanceRunStatus.INCOMPLETE }) {
                    blockers += PerformanceComparisonBlocker.INCOMPLETE_RUN_PRESENT
                }
                if (runs.any { it.status == PerformanceRunStatus.FAILED }) {
                    blockers += PerformanceComparisonBlocker.FAILED_RUN_PRESENT
                }
            }
            effect?.runs?.let { runs ->
                if (runs.any { it.status == PerformanceRunStatus.INCOMPLETE }) {
                    blockers += PerformanceComparisonBlocker.INCOMPLETE_RUN_PRESENT
                }
                if (runs.any { it.status == PerformanceRunStatus.FAILED }) {
                    blockers += PerformanceComparisonBlocker.FAILED_RUN_PRESENT
                }
            }

            if (nativeRun != null && effectRun != null) {
                val nativeVideo = nativeRun.condition.video
                val effectVideo = effectRun.condition.video

                if (nativeVideo.fingerprint != effectVideo.fingerprint) {
                    blockers += PerformanceComparisonBlocker.DIFFERENT_VIDEO
                }
                if (nativeVideo.width != effectVideo.width || nativeVideo.height != effectVideo.height) {
                    blockers += PerformanceComparisonBlocker.DIFFERENT_RESOLUTION
                }
                if (nativeVideo.sourceFps != effectVideo.sourceFps) {
                    blockers += PerformanceComparisonBlocker.DIFFERENT_SOURCE_RATE
                }
                if (nativeRun.condition.display.effectiveRefreshRateHz !=
                    effectRun.condition.display.effectiveRefreshRateHz
                ) {
                    blockers += PerformanceComparisonBlocker.DIFFERENT_DISPLAY_RATE
                }
                if (nativeRun.condition.length != effectRun.condition.length) {
                    blockers += PerformanceComparisonBlocker.DIFFERENT_DURATION
                }
            }

            return PerformanceComparisonEligibility(blockers.distinct())
        }
    }
}
