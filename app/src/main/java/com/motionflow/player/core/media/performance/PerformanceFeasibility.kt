package com.motionflow.player.core.media.performance

/**
 * The factual categories this phase's evidence gate can report.
 *
 * Several may apply at once, and none of them is a score: they say what the evidence *is*, never whether
 * it is good. A single device measured once is `MEASUREMENT_INCOMPLETE` and `ADDITIONAL_DEVICE_DATA_REQUIRED`,
 * and neither of those is a criticism of the pipeline or of the device.
 */
enum class PerformanceFeasibilityState {

    /** No comparison between the two pipelines can be made from the evidence collected so far. */
    MEASUREMENT_INCOMPLETE,

    /** The effect pipeline ran: at least one run completed with it in the path. */
    PIPELINE_OPERATIONAL,

    /** Both pipelines have enough usable runs to compare, and the conditions matched. */
    PIPELINE_OVERHEAD_CHARACTERIZED,

    /** Repeated runs of the same condition differed by more than the reporting thresholds allow for. */
    HARDWARE_VARIANCE_OBSERVED,

    /** The effect pipeline's thermal peak exceeded the native one by at least one platform status step. */
    THERMAL_IMPACT_OBSERVED,

    /** The cadence classification was recorded and did not change when the pipeline changed. */
    CADENCE_INTERACTION_CHARACTERIZED,

    /** More runs, or another device, are needed before the gate can be answered. */
    ADDITIONAL_DEVICE_DATA_REQUIRED,
}

/** Which kind of statement a line of the evidence catalogue is. */
enum class PerformanceEvidenceBucket {

    /** Something the hardware reported. */
    OBSERVED,

    /** Something derived arithmetically from observed values. */
    CALCULATED,

    /** Something the platform does not expose. */
    UNKNOWN,

    /** Something this phase did not exercise. */
    NOT_TESTED,

    /** Something that remains to be investigated, stated as a question rather than a finding. */
    HYPOTHESIS,
}

/** One line of the evidence catalogue, labelled with what kind of statement it is. */
data class EvidenceStatement(val bucket: PerformanceEvidenceBucket, val statement: String)

/**
 * What the collected evidence licenses, and what it does not.
 *
 * The distinction the brief insists on is enforced here rather than left to prose: every statement is
 * filed under one of the five buckets, so a reader can see at a glance which sentences came off a device,
 * which came out of arithmetic, and which are still open questions.
 */
data class PerformanceFeasibility(
    val states: List<PerformanceFeasibilityState>,
    val evidence: List<EvidenceStatement>,
) {

    val isResolved: Boolean
        get() = PerformanceFeasibilityState.MEASUREMENT_INCOMPLETE !in states

    /** The states, as names, for a rendering that has to be plain text. */
    val stateNames: List<String> get() = states.map { it.name }
}

/**
 * Decides the gate from the evidence, and never from an expectation.
 *
 * Every rule is a statement about what was recorded, and the thresholds are reporting thresholds: they
 * decide when run-to-run spread is worth mentioning, not when a pipeline is acceptable. With no runs at
 * all, the decision is the honest one — the hypothesis is not answered, and the states say why.
 */
object PerformanceFeasibilityPolicy {

    /** First-frame latency spread worth reporting, as a fraction of its own mean. */
    const val FIRST_FRAME_VARIANCE_FRACTION = 0.15

    /** Dropped-frame spread worth reporting, in frames. */
    const val DROPPED_FRAME_VARIANCE = 2.0

    /** CPU-time spread worth reporting, as a fraction of its own mean. */
    const val CPU_VARIANCE_FRACTION = 0.10

    fun evaluate(
        native: PerformanceRunSeries?,
        effect: PerformanceRunSeries?,
        support: PerformanceMeasurementSupport,
    ): PerformanceFeasibility {
        val nativeStats = native?.let(PerformanceSeriesStatistics::of)
        val effectStats = effect?.let(PerformanceSeriesStatistics::of)
        val eligibility = PerformanceComparisonEligibility.of(native, effect)

        val states = mutableListOf<PerformanceFeasibilityState>()
        val evidence = mutableListOf<EvidenceStatement>()

        val effectUsableRuns = effect?.usableRuns.orEmpty()
        val nativeUsableRuns = native?.usableRuns.orEmpty()

        if (effectUsableRuns.isEmpty() && nativeUsableRuns.isEmpty()) {
            states += PerformanceFeasibilityState.MEASUREMENT_INCOMPLETE
            states += PerformanceFeasibilityState.ADDITIONAL_DEVICE_DATA_REQUIRED
            evidence += EvidenceStatement(
                PerformanceEvidenceBucket.NOT_TESTED,
                "No measurement session has been completed on this build, so neither baseline has been " +
                    "exercised on hardware.",
            )
            evidence += EvidenceStatement(
                PerformanceEvidenceBucket.HYPOTHESIS,
                "Whether Media3's frame processor is affordable in the playback path remains untested on " +
                    "any device.",
            )
        } else {
            if (effectUsableRuns.isNotEmpty()) {
                states += PerformanceFeasibilityState.PIPELINE_OPERATIONAL
                evidence += EvidenceStatement(
                    PerformanceEvidenceBucket.OBSERVED,
                    "The effect pipeline completed ${effectUsableRuns.size} run(s) with Media3's identity " +
                        "effect in the path; the lowest measured rendered-frame count was " +
                        "${effectStats?.renderedFrames?.minimum ?: 0}.",
                )
            }
            if (!eligibility.eligible) {
                states += PerformanceFeasibilityState.MEASUREMENT_INCOMPLETE
                states += PerformanceFeasibilityState.ADDITIONAL_DEVICE_DATA_REQUIRED
                evidence += EvidenceStatement(
                    PerformanceEvidenceBucket.NOT_TESTED,
                    "A valid comparison is blocked: ${eligibility.blockers.joinToString(", ") { it.name }}.",
                )
            }
        }

        // A comparison needs repeats on both sides: one run each is a pair of anecdotes, not a range.
        val comparisonReady = eligibility.eligible &&
            nativeUsableRuns.size >= PerformanceSeriesStatistics.REPEATS_FOR_CHARACTERIZATION &&
            effectUsableRuns.size >= PerformanceSeriesStatistics.REPEATS_FOR_CHARACTERIZATION

        if (comparisonReady && nativeStats != null && effectStats != null) {
            states += PerformanceFeasibilityState.PIPELINE_OVERHEAD_CHARACTERIZED
            evidence += EvidenceStatement(
                PerformanceEvidenceBucket.CALCULATED,
                "Overhead between the pipelines is characterized from ${nativeUsableRuns.size} native and " +
                    "${effectUsableRuns.size} effect-pipeline runs of the same video, display rate and window.",
            )

            if (variesAcrossRuns(nativeStats) || variesAcrossRuns(effectStats)) {
                states += PerformanceFeasibilityState.HARDWARE_VARIANCE_OBSERVED
                evidence += EvidenceStatement(
                    PerformanceEvidenceBucket.OBSERVED,
                    "Repeat runs of the same condition differed by more than the reporting thresholds: " +
                        "first-frame latency range ${nativeStats.firstFrameLatencyMs.range ?: 0.0} ms native " +
                        "against ${effectStats.firstFrameLatencyMs.range ?: 0.0} ms effect, dropped frames " +
                        "range ${nativeStats.droppedFrames.range ?: 0.0} native against " +
                        "${effectStats.droppedFrames.range ?: 0.0} effect.",
                )
            }

            val thermalDelta = PerformanceOverhead.of(
                PerformanceMetric.THERMAL_STATUS,
                nativeStats.thermalStatusPeak,
                effectStats.thermalStatusPeak,
            ).absoluteDelta

            if (thermalDelta != null && thermalDelta >= 1.0) {
                states += PerformanceFeasibilityState.THERMAL_IMPACT_OBSERVED
                evidence += EvidenceStatement(
                    PerformanceEvidenceBucket.OBSERVED,
                    "The effect pipeline's peak thermal status exceeded the native one by $thermalDelta " +
                        "platform step(s) over the measured windows.",
                )
            } else if (thermalDelta != null) {
                evidence += EvidenceStatement(
                    PerformanceEvidenceBucket.CALCULATED,
                    "Peak thermal status did not differ between the pipelines by a platform step " +
                        "(difference $thermalDelta).",
                )
            }

            val cadenceStable = cadenceMatches(native, effect)
            if (cadenceStable) {
                states += PerformanceFeasibilityState.CADENCE_INTERACTION_CHARACTERIZED
                evidence += EvidenceStatement(
                    PerformanceEvidenceBucket.OBSERVED,
                    "The cadence classification was identical with and without the effect pipeline for the " +
                        "measured video and display rate, so the classification does not depend on the " +
                        "processing path.",
                )
            } else {
                evidence += EvidenceStatement(
                    PerformanceEvidenceBucket.HYPOTHESIS,
                    "The cadence classification differed between the pipelines, or was not recorded on both " +
                        "sides; whether the discrimination is in the measurement or in the pipeline is open.",
                )
            }
        } else if (nativeUsableRuns.isNotEmpty() || effectUsableRuns.isNotEmpty()) {
            states += PerformanceFeasibilityState.ADDITIONAL_DEVICE_DATA_REQUIRED
            evidence += EvidenceStatement(
                PerformanceEvidenceBucket.NOT_TESTED,
                "Fewer than ${PerformanceSeriesStatistics.REPEATS_FOR_CHARACTERIZATION} usable runs exist on " +
                    "one or both pipelines, so run-to-run variation cannot be separated from the pipeline " +
                    "difference.",
            )
        }

        for (metric in support.unsupported) {
            evidence += EvidenceStatement(
                PerformanceEvidenceBucket.UNKNOWN,
                "This platform exposes no reading for ${metric.name.lowercase()}, so it is neither measured " +
                    "nor estimated.",
            )
        }

        return PerformanceFeasibility(states = states.distinct(), evidence = evidence)
    }

    /** True when any comparable metric spread further than its reporting threshold across repeat runs. */
    private fun variesAcrossRuns(stats: PerformanceSeriesStatistics): Boolean {
        val firstFrameSpread = stats.firstFrameLatencyMs.range
        val firstFrameMean = stats.firstFrameLatencyMs.mean
        val firstFrameVaries = firstFrameSpread != null &&
            firstFrameMean != null &&
            firstFrameMean > 0 &&
            firstFrameSpread / firstFrameMean >= FIRST_FRAME_VARIANCE_FRACTION

        val droppedVaries = (stats.droppedFrames.range ?: 0.0) >= DROPPED_FRAME_VARIANCE

        val cpuSpread = stats.cpuTimeMs.range
        val cpuMean = stats.cpuTimeMs.mean
        val cpuVaries = cpuSpread != null && cpuMean != null && cpuMean > 0 &&
            cpuSpread / cpuMean >= CPU_VARIANCE_FRACTION

        return firstFrameVaries || droppedVaries || cpuVaries
    }

    /** True when both series recorded a cadence classification and the two agree. */
    private fun cadenceMatches(native: PerformanceRunSeries?, effect: PerformanceRunSeries?): Boolean {
        val nativeCadence = native?.usableRuns?.firstOrNull()?.cadence ?: return false
        val effectCadence = effect?.usableRuns?.firstOrNull()?.cadence ?: return false

        return nativeCadence.cadenceMode == effectCadence.cadenceMode &&
            nativeCadence.sourceFps == effectCadence.sourceFps &&
            nativeCadence.displayRefreshRateHz == effectCadence.displayRefreshRateHz
    }
}
