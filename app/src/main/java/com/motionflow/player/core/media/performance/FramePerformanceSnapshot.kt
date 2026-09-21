package com.motionflow.player.core.media.performance

/**
 * A set of readings taken at one instant: one of these opens a session, and another closes it.
 *
 * Media3's counters are cumulative for as long as a renderer stays enabled, and CPU time, memory and
 * thermal status are properties of the process rather than of the session. Reading the same fields at
 * both ends is what turns every reported number into a *delta over that session* — the only form in
 * which two baselines can be compared.
 *
 * The name avoids the word "baseline" on purpose: this phase already has two baselines, and they are
 * the two pipelines being compared, not the opening readings of either.
 */
data class FramePerformanceReadings(
    val renderedFrames: Int? = null,
    val droppedFrames: Int? = null,
    val playbackPositionMs: Long? = null,
    val cpuTimeMs: Long? = null,
    val processPssKb: Long? = null,
    val heapUsedBytes: Long? = null,
    val thermalStatus: Int? = null,
) {

    companion object {

        /** Before anything has been read. Every delta computed against it is reported as unavailable. */
        val Empty = FramePerformanceReadings()
    }
}

/**
 * What one measurement session measured.
 *
 * Every field is nullable, and a null means *not measured* — never zero. That distinction is the whole
 * design: `droppedFrames = 0` says a pipeline dropped nothing, while `droppedFrames = null` says this
 * session produced no reading, and a diagnostics panel that printed both as "0" would be lying about
 * one of them.
 *
 * There is deliberately **no frame-rate field of any kind**. A rendered-frame count divided by a
 * duration is not a frame rate the video contains, and publishing it beside the source's real rate
 * would invite exactly the confusion the metadata engine works to prevent. The counts are counts.
 *
 * [averageFrameProcessingOffsetMs] is the one derived value, and it is derived from two measured
 * numbers that always travel together — Media3 reports a total offset and the frame count it covers —
 * so it can never be computed from half a measurement.
 */
data class FramePerformanceSnapshot(
    val renderedFrames: Int? = null,
    val droppedFrames: Int? = null,
    val frameProcessingOffsetTotalUs: Long? = null,
    val frameProcessingOffsetFrames: Int? = null,
    val firstFrameLatencyMs: Long? = null,
    val decoderInitializationMs: Long? = null,
    val playbackPositionMs: Long? = null,
    val measurementDurationMs: Long? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val cpuTimeMs: Long? = null,
    val processPssKb: Long? = null,
    val heapUsedBytes: Long? = null,
    val thermalStatusAtStart: Int? = null,
    val thermalStatusAtEnd: Int? = null,
    val thermalStatusPeak: Int? = null,
) {

    /** True when the video's size was reported. */
    val hasVideoSize: Boolean get() = videoWidth != null && videoHeight != null

    /**
     * The mean distance from scheduled presentation to presentation, in milliseconds.
     *
     * `null` unless a positive number of frames was reported with the total, because a total with no
     * frame count cannot be turned into a mean without inventing the denominator.
     */
    val averageFrameProcessingOffsetMs: Double?
        get() = frameProcessingOffsetTotalUs
            ?.takeIf { frameProcessingOffsetFrames != null && frameProcessingOffsetFrames > 0 }
            ?.let { it / MICROS_PER_MILLI / frameProcessingOffsetFrames!! }

    /** True when nothing was measured at all, so the snapshot should not be presented as a result. */
    val isEmpty: Boolean
        get() = this == Empty

    /**
     * The metrics this snapshot could not report, so a reader is told which gaps are gaps.
     *
     * Metrics no platform can measure do not appear here; they are a property of Android rather than of
     * this session, and [PerformanceMeasurementSupport.unsupported] is where they are named.
     */
    val unavailable: List<PerformanceMetric>
        get() = buildList {
            if (renderedFrames == null) add(PerformanceMetric.RENDERED_FRAMES)
            if (droppedFrames == null) add(PerformanceMetric.DROPPED_FRAMES)
            if (frameProcessingOffsetFrames == null) add(PerformanceMetric.FRAME_PROCESSING_OFFSET)
            if (firstFrameLatencyMs == null) add(PerformanceMetric.FIRST_FRAME_LATENCY)
            if (decoderInitializationMs == null) add(PerformanceMetric.DECODER_INITIALIZATION)
            if (playbackPositionMs == null) add(PerformanceMetric.PLAYBACK_POSITION)
            if (measurementDurationMs == null) add(PerformanceMetric.MEASUREMENT_DURATION)
            if (!hasVideoSize) add(PerformanceMetric.VIDEO_SIZE)
            if (cpuTimeMs == null) add(PerformanceMetric.CPU_TIME)
            if (processPssKb == null) add(PerformanceMetric.PROCESS_PSS)
            if (heapUsedBytes == null) add(PerformanceMetric.HEAP_USED)
            if (thermalStatusAtEnd == null) add(PerformanceMetric.THERMAL_STATUS)
        }

    companion object {

        /** Nothing measured yet. */
        val Empty = FramePerformanceSnapshot()

        const val MICROS_PER_MILLI = 1_000.0
    }
}
