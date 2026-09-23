package com.motionflow.player.core.media.performance

/**
 * A measured quantity across several runs of the same condition.
 *
 * Statistics over runs, never over frames, and never a score: [mean], [minimum], [maximum] and [range]
 * describe how repeatable a measurement was. Nothing here ranks a device or a pipeline, and nothing
 * combines metrics into a single number — a mean and a range of one quantity is a fact, a weighted
 * average of several is an opinion.
 */
data class MetricStatistics(
    val sampleCount: Int,
    val mean: Double?,
    val minimum: Double?,
    val maximum: Double?,
    val range: Double?,
) {

    /** True when at least one run produced this reading. */
    val hasEvidence: Boolean get() = sampleCount > 0

    companion object {

        /** Nothing was measured. */
        val None = MetricStatistics(sampleCount = 0, mean = null, minimum = null, maximum = null, range = null)

        /**
         * Summarises the values that were actually measured.
         *
         * A run that did not measure this metric contributes nothing — not a zero — so a metric measured
         * in two runs out of three reports two samples and says so.
         */
        fun of(values: List<Double?>): MetricStatistics {
            val present = values.filterNotNull()
            if (present.isEmpty()) return None

            val min = present.min()
            val max = present.max()
            return MetricStatistics(
                sampleCount = present.size,
                mean = present.average(),
                minimum = min,
                maximum = max,
                range = max - min,
            )
        }
    }
}

/**
 * What several runs of one condition measured, summarised.
 *
 * Every field is a [MetricStatistics] rather than a number, because one run is not a characterization
 * and a summary that hid the spread would invite treating one run as one.
 */
data class PerformanceSeriesStatistics(
    val runCount: Int = 0,
    val usableRunCount: Int = 0,
    val renderedFrames: MetricStatistics = MetricStatistics.None,
    val droppedFrames: MetricStatistics = MetricStatistics.None,
    val firstFrameLatencyMs: MetricStatistics = MetricStatistics.None,
    val decoderInitializationMs: MetricStatistics = MetricStatistics.None,
    val meanFrameProcessingOffsetMs: MetricStatistics = MetricStatistics.None,
    val cpuTimeMs: MetricStatistics = MetricStatistics.None,
    val processPssKb: MetricStatistics = MetricStatistics.None,
    val heapUsedBytes: MetricStatistics = MetricStatistics.None,
    val thermalStatusPeak: MetricStatistics = MetricStatistics.None,
    val measuredDurationMs: MetricStatistics = MetricStatistics.None,
) {

    val hasEvidence: Boolean get() = usableRunCount > 0

    companion object {

        /** How many runs a comparison wants before it can speak about variation rather than a moment. */
        const val REPEATS_FOR_CHARACTERIZATION = 3

        /**
         * Summarises a series.
         *
         * Only usable runs are counted: an incomplete or failed run is kept in the series — it is a
         * finding, and it is listed — but its numbers are not averaged in, because a ten-second window
         * and a thirty-second one are not two samples of the same thing.
         */
        fun of(series: PerformanceRunSeries): PerformanceSeriesStatistics {
            val usable = series.usableRuns

            return PerformanceSeriesStatistics(
                runCount = series.runs.size,
                usableRunCount = usable.size,
                renderedFrames = MetricStatistics.of(usable.map { it.snapshot.renderedFrames?.toDouble() }),
                droppedFrames = MetricStatistics.of(usable.map { it.snapshot.droppedFrames?.toDouble() }),
                firstFrameLatencyMs = MetricStatistics.of(
                    usable.map { it.snapshot.firstFrameLatencyMs?.toDouble() },
                ),
                decoderInitializationMs = MetricStatistics.of(
                    usable.map { it.snapshot.decoderInitializationMs?.toDouble() },
                ),
                meanFrameProcessingOffsetMs = MetricStatistics.of(
                    usable.map { it.snapshot.averageFrameProcessingOffsetMs },
                ),
                cpuTimeMs = MetricStatistics.of(usable.map { it.snapshot.cpuTimeMs?.toDouble() }),
                processPssKb = MetricStatistics.of(usable.map { it.snapshot.processPssKb?.toDouble() }),
                heapUsedBytes = MetricStatistics.of(usable.map { it.snapshot.heapUsedBytes?.toDouble() }),
                thermalStatusPeak = MetricStatistics.of(usable.map { it.snapshot.thermalStatusPeak?.toDouble() }),
                measuredDurationMs = MetricStatistics.of(
                    usable.map { it.snapshot.measurementDurationMs?.toDouble() },
                ),
            )
        }
    }
}

/**
 * Why a difference is or is not expressed as a percentage.
 *
 * A ratio against zero is not a large ratio, it is an undefined one, and a measurement whose control
 * value is zero has no baseline to grow from. Saying so is more useful than printing a number that
 * would look like a finding.
 */
enum class PerformanceOverheadBasis {

    /** Both pipelines measured the metric and the control value was not zero. */
    ABSOLUTE_AND_RELATIVE,

    /** Both measured it and the control value was zero, so only the absolute difference exists. */
    ABSOLUTE_ONLY_BASELINE_IS_ZERO,

    /** One pipeline did not measure it, so there is nothing to compare. */
    UNAVAILABLE_MEASURED_ON_ONE_SIDE_ONLY,
}

/**
 * The difference between the two pipelines for one metric.
 *
 * Signed as *effect minus native*, so a positive difference means the effect pipeline used more of
 * something. Absolute differences are always reported when both sides measured the metric; the ratio is
 * reported only when it means something.
 */
data class PerformanceOverhead(
    val metric: PerformanceMetric,
    val nativeMean: Double?,
    val effectMean: Double?,
    val absoluteDelta: Double?,
    val relativeDelta: Double?,
    val basis: PerformanceOverheadBasis,
) {

    companion object {

        /** Compares one metric across two summaries, refusing to divide by nothing. */
        fun of(
            metric: PerformanceMetric,
            native: MetricStatistics,
            effect: MetricStatistics,
        ): PerformanceOverhead {
            val nativeMean = native.mean
            val effectMean = effect.mean

            if (nativeMean == null || effectMean == null) {
                return PerformanceOverhead(
                    metric = metric,
                    nativeMean = nativeMean,
                    effectMean = effectMean,
                    absoluteDelta = null,
                    relativeDelta = null,
                    basis = PerformanceOverheadBasis.UNAVAILABLE_MEASURED_ON_ONE_SIDE_ONLY,
                )
            }

            val absolute = effectMean - nativeMean
            return if (nativeMean == 0.0) {
                PerformanceOverhead(
                    metric = metric,
                    nativeMean = nativeMean,
                    effectMean = effectMean,
                    absoluteDelta = absolute,
                    relativeDelta = null,
                    basis = PerformanceOverheadBasis.ABSOLUTE_ONLY_BASELINE_IS_ZERO,
                )
            } else {
                PerformanceOverhead(
                    metric = metric,
                    nativeMean = nativeMean,
                    effectMean = effectMean,
                    absoluteDelta = absolute,
                    relativeDelta = absolute / nativeMean,
                    basis = PerformanceOverheadBasis.ABSOLUTE_AND_RELATIVE,
                )
            }
        }
    }
}

/**
 * Every defensible comparison between two pipelines, and nothing else.
 *
 * The list is fixed rather than open: these are the metrics whose units are unambiguous, so two runs of
 * them can be subtracted. A metric that was not measured on both sides appears with no difference, which
 * is a statement rather than an omission.
 */
object PerformanceOverheadPolicy {

    fun compare(
        native: PerformanceSeriesStatistics,
        effect: PerformanceSeriesStatistics,
    ): List<PerformanceOverhead> = listOf(
        PerformanceOverhead.of(PerformanceMetric.RENDERED_FRAMES, native.renderedFrames, effect.renderedFrames),
        PerformanceOverhead.of(PerformanceMetric.DROPPED_FRAMES, native.droppedFrames, effect.droppedFrames),
        PerformanceOverhead.of(
            PerformanceMetric.FIRST_FRAME_LATENCY,
            native.firstFrameLatencyMs,
            effect.firstFrameLatencyMs,
        ),
        PerformanceOverhead.of(
            PerformanceMetric.DECODER_INITIALIZATION,
            native.decoderInitializationMs,
            effect.decoderInitializationMs,
        ),
        PerformanceOverhead.of(PerformanceMetric.CPU_TIME, native.cpuTimeMs, effect.cpuTimeMs),
        PerformanceOverhead.of(PerformanceMetric.PROCESS_PSS, native.processPssKb, effect.processPssKb),
        PerformanceOverhead.of(PerformanceMetric.HEAP_USED, native.heapUsedBytes, effect.heapUsedBytes),
        PerformanceOverhead.of(
            PerformanceMetric.THERMAL_STATUS,
            native.thermalStatusPeak,
            effect.thermalStatusPeak,
        ),
    )
}
