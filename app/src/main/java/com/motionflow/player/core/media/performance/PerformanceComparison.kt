package com.motionflow.player.core.media.performance

/**
 * Two measurements, side by side, with their differences and nothing else.
 *
 * There is no score, no ranking, no "best mode" and no verdict, and that is a property of the type
 * rather than a promise: the fields are differences, and a difference has no opinion. Whether a
 * pipeline is worth its cost depends on a device, a video and a thermal budget that this class cannot
 * see, so deciding is left to whoever reads the numbers.
 *
 * Deltas are signed as *effect pipeline minus native*, so a positive first-frame delta means the
 * effect pipeline took longer to present its first frame, and a positive dropped-frame delta means it
 * dropped more. A delta is `null` unless both sides measured it: comparing a number with a missing one
 * would produce a difference that looks like a finding.
 */
data class PerformanceComparison(
    val native: FramePerformanceSnapshot? = null,
    val effectPipeline: FramePerformanceSnapshot? = null,
) {

    /** True when both baselines produced a measurement and a comparison is meaningful. */
    val hasBothBaselines: Boolean get() = native != null && effectPipeline != null

    /** First presentation, effect minus native, in milliseconds. Positive means the effect pipeline was slower. */
    val firstFrameLatencyDeltaMs: Long?
        get() = difference(native?.firstFrameLatencyMs, effectPipeline?.firstFrameLatencyMs)

    /** Frames presented, effect minus native. */
    val renderedFramesDelta: Int?
        get() = difference(native?.renderedFrames, effectPipeline?.renderedFrames)

    /** Frames dropped, effect minus native. */
    val droppedFramesDelta: Int?
        get() = difference(native?.droppedFrames, effectPipeline?.droppedFrames)

    /**
     * The effect pipeline's mean distance from scheduled presentation, in milliseconds.
     *
     * Its own measurement rather than a difference: the native baseline has no frame processor, so
     * there is nothing to subtract. [frameProcessingOffsetDeltaMs] stays `null` for that reason, and a
     * zero here would claim a processor that kept up perfectly, which is not the same finding.
     */
    val frameProcessingOffsetMs: Double? get() = effectPipeline?.averageFrameProcessingOffsetMs

    /** Processing offset, effect minus native, and `null` unless both pipelines had a processor. */
    val frameProcessingOffsetDeltaMs: Double?
        get() {
            val nativeOffset = native?.averageFrameProcessingOffsetMs ?: return null
            val effectOffset = effectPipeline?.averageFrameProcessingOffsetMs ?: return null
            return effectOffset - nativeOffset
        }

    /** Process CPU time consumed, effect minus native, in milliseconds. */
    val cpuTimeDeltaMs: Long? get() = difference(native?.cpuTimeMs, effectPipeline?.cpuTimeMs)

    /** Resident set size at the end of the measurement, effect minus native, in kilobytes. */
    val processPssDeltaKb: Long? get() = difference(native?.processPssKb, effectPipeline?.processPssKb)

    /** Managed heap in use at the end of the measurement, effect minus native, in bytes. */
    val heapUsedDeltaBytes: Long? get() = difference(native?.heapUsedBytes, effectPipeline?.heapUsedBytes)

    /** The most severe thermal status each measurement saw, effect minus native, where both read one. */
    val thermalStatusDifference: Int? get() = difference(native?.thermalStatusPeak, effectPipeline?.thermalStatusPeak)

    /** Metrics that could not be compared, because one side or both did not measure them. */
    val incomparable: List<PerformanceMetric>
        get() = buildList {
            if (firstFrameLatencyDeltaMs == null) add(PerformanceMetric.FIRST_FRAME_LATENCY)
            if (renderedFramesDelta == null) add(PerformanceMetric.RENDERED_FRAMES)
            if (droppedFramesDelta == null) add(PerformanceMetric.DROPPED_FRAMES)
            if (cpuTimeDeltaMs == null) add(PerformanceMetric.CPU_TIME)
            if (processPssDeltaKb == null) add(PerformanceMetric.PROCESS_PSS)
            if (thermalStatusDifference == null) add(PerformanceMetric.THERMAL_STATUS)
        }

    private fun difference(native: Int?, effect: Int?): Int? =
        if (native == null || effect == null) null else effect - native

    private fun difference(native: Long?, effect: Long?): Long? =
        if (native == null || effect == null) null else effect - native

    private fun difference(native: Double?, effect: Double?): Double? =
        if (native == null || effect == null) null else effect - native
}

/**
 * The last measurement taken on each baseline, for the length of one process.
 *
 * Held by the application rather than by a screen or by the media service, because a comparison spans
 * two runs of the *same* video under two different pipelines — and changing the pipeline restarts the
 * service. A history that lived in the service would be destroyed by the very action that makes it
 * interesting.
 *
 * Nothing is written to disk and nothing is sent anywhere: this is a reading kept in memory so two
 * numbers can be put next to each other.
 */
data class PerformanceHistory(
    val native: FramePerformanceSnapshot? = null,
    val effectPipeline: FramePerformanceSnapshot? = null,
) {

    /**
     * Files [snapshot] under [mode].
     *
     * A failed run is not a result and is not recorded: the diagnostics report the failure as a
     * failure, and keeping its zeroes beside a real measurement would invite comparing nothing with
     * something.
     */
    fun record(mode: ProcessingPerformanceMode, snapshot: FramePerformanceSnapshot): PerformanceHistory =
        when {
            !mode.isMeasurable || snapshot.isEmpty -> this
            mode == ProcessingPerformanceMode.NATIVE -> copy(native = snapshot)
            else -> copy(effectPipeline = snapshot)
        }

    /** The two baselines put side by side. */
    val comparison: PerformanceComparison get() = PerformanceComparison(native, effectPipeline)

    companion object {

        /** Nothing measured yet. */
        val Empty = PerformanceHistory()
    }
}
