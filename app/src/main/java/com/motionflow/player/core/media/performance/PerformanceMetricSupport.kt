package com.motionflow.player.core.media.performance

/**
 * One thing a measurement might have reported.
 *
 * Kept as a vocabulary rather than as prose so that "unavailable" is a *value* the UI can list and a
 * test can assert, instead of a blank field a reader has to interpret. This is the phase's answer to
 * the temptation to fill a gap with an estimate: a metric that was not measured is named here.
 */
enum class PerformanceMetric {

    /** Buffers the video renderer presented. */
    RENDERED_FRAMES,

    /** Buffers the renderer dropped rather than presenting. */
    DROPPED_FRAMES,

    /** How far frames arrived from their scheduled presentation time, as Media3 reports it. */
    FRAME_PROCESSING_OFFSET,

    /** How long the first presented frame took, measured from `prepare()`. */
    FIRST_FRAME_LATENCY,

    /** How long the video decoder took to initialise. */
    DECODER_INITIALIZATION,

    /** Where playback was when the measurement ended. */
    PLAYBACK_POSITION,

    /** How long the measurement ran. */
    MEASUREMENT_DURATION,

    /** The size of the video being rendered. */
    VIDEO_SIZE,

    /** Process CPU time consumed during the measurement. */
    CPU_TIME,

    /** Resident proportional set size of the process, in kilobytes. */
    PROCESS_PSS,

    /** Managed heap in use, which is a fraction of process memory and is never presented as all of it. */
    HEAP_USED,

    /** The platform's thermal status. */
    THERMAL_STATUS,

    /** The platform's prediction of how close the device is to throttling. */
    THERMAL_HEADROOM,

    /** How hard the GPU is working. */
    GPU_UTILISATION,

    /** Power drawn by the session. */
    BATTERY_DRAIN,
}

/**
 * What this platform can measure at all.
 *
 * Two different questions are answered in two different places, and conflating them would let a
 * missing number look like a broken measurement:
 *
 * - **This type** is about the platform: a metric is unsupported here because no public API provides
 *   it, on every device, for every session. A `false` is a statement about Android, with the reason
 *   recorded on the field.
 * - **A null in [FramePerformanceSnapshot]** is about one session: the API exists, and this session did
 *   not produce a value — usually because the metric only exists for a pipeline that was not in use,
 *   such as a frame-processing offset with no frame processor.
 */
data class PerformanceMeasurementSupport(
    val renderedFrames: Boolean = true,
    val droppedFrames: Boolean = true,
    val frameProcessingOffset: Boolean = true,
    val firstFrameLatency: Boolean = true,
    val decoderInitialization: Boolean = true,
    val playbackPosition: Boolean = true,
    val cpuTime: Boolean = true,
    val processPss: Boolean = true,
    val heapUsed: Boolean = true,
    val thermalStatus: Boolean = true,
    val thermalHeadroom: Boolean = false,
    val gpuUtilisation: Boolean = false,
    val batteryDrain: Boolean = false,
) {

    /** Whether [metric] can be measured on this platform at all. */
    fun supports(metric: PerformanceMetric): Boolean = when (metric) {
        PerformanceMetric.RENDERED_FRAMES -> renderedFrames
        PerformanceMetric.DROPPED_FRAMES -> droppedFrames
        PerformanceMetric.FRAME_PROCESSING_OFFSET -> frameProcessingOffset
        PerformanceMetric.FIRST_FRAME_LATENCY -> firstFrameLatency
        PerformanceMetric.DECODER_INITIALIZATION -> decoderInitialization
        PerformanceMetric.PLAYBACK_POSITION -> playbackPosition
        // A duration is always known: it is the difference between two clock readings the session owns.
        PerformanceMetric.MEASUREMENT_DURATION -> true
        PerformanceMetric.VIDEO_SIZE -> true
        PerformanceMetric.CPU_TIME -> cpuTime
        PerformanceMetric.PROCESS_PSS -> processPss
        PerformanceMetric.HEAP_USED -> heapUsed
        PerformanceMetric.THERMAL_STATUS -> thermalStatus
        PerformanceMetric.THERMAL_HEADROOM -> thermalHeadroom
        PerformanceMetric.GPU_UTILISATION -> gpuUtilisation
        PerformanceMetric.BATTERY_DRAIN -> batteryDrain
    }

    /** Every metric this platform cannot measure, for the diagnostics to list rather than omit. */
    val unsupported: List<PerformanceMetric>
        get() = PerformanceMetric.entries.filterNot(::supports)

    companion object {

        /**
         * What the platform can measure, given how old it is.
         *
         * The only metric with an API-level floor is the thermal status, which arrived in API 29; the
         * rest of the unsupported ones are unsupported everywhere, because Android publishes no
         * reading for them:
         *
         * - **Thermal headroom** — `PowerManager.getThermalHeadroom` is not public API, so predicting
         *   how close the device is to throttling would mean guessing from a status we can read.
         * - **GPU utilisation** — there is no public API for it on any Android version, and inferring
         *   it from CPU time would be a fabrication.
         * - **Battery drain** — no API attributes consumption to one session, and deriving it from
         *   elapsed time is arithmetic dressed up as measurement. The system's own battery screens do
         *   that, with more information than this process has.
         */
        fun forApiLevel(apiLevel: Int?): PerformanceMeasurementSupport = PerformanceMeasurementSupport(
            thermalStatus = apiLevel != null && apiLevel >= THERMAL_STATUS_API_LEVEL,
        )

        /** The API level that introduced `PowerManager.getCurrentThermalStatus`. */
        const val THERMAL_STATUS_API_LEVEL = 29
    }
}
