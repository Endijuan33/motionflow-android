package com.motionflow.player.core.media.performance

/**
 * Turns playback events into one session's measurements.
 *
 * Pure, and fed by *events* rather than by polling. The Android side does nothing but translate an
 * `AnalyticsListener` callback into one call here, which is what lets every piece of arithmetic in this
 * phase be tested exactly, on the JVM, with no player and no device. It also means the class cannot
 * log, allocate per frame or schedule anything: it has no clock, no coroutine scope and no Android type,
 * and its only state is a handful of readings.
 *
 * ## Deltas, and refusing to invent them
 *
 * Media3's decoder counters are cumulative while a renderer stays enabled, so [begin] records where
 * they already were and [snapshot] reports the difference. If a renderer was reset mid-session — a
 * source change, a surface change — the closing reading can be *smaller* than the opening one, and the
 * difference is then meaningless rather than negative. It is reported as `null`, which a reader sees as
 * "not measured" instead of as a nonsensical count.
 *
 * Counters are read at the two ends rather than recorded as they move: Media3's callbacks are not
 * per-frame, but a counter read at the end costs nothing at all, and a session's frame counts do not
 * need to exist in between.
 */
class FramePerformanceAccumulator {

    private var opening = FramePerformanceReadings.Empty
    private var started = false

    private var processingOffsetTotalUs: Long? = null
    private var processingOffsetFrames: Int? = null
    private var firstFrameLatencyMs: Long? = null
    private var decoderInitializationMs: Long? = null
    private var videoWidth: Int? = null
    private var videoHeight: Int? = null
    private var thermalStatusPeak: Int? = null

    /** True while a session's events are being accumulated. */
    val isAccumulating: Boolean get() = started

    /**
     * Opens a session with the readings taken before it starts.
     *
     * Everything measured so far is discarded: a session reports its own window, and carrying a previous
     * session's first-frame latency into a new one would be the quietest kind of wrong answer.
     */
    fun begin(opening: FramePerformanceReadings) {
        this.opening = opening
        started = true

        processingOffsetTotalUs = null
        processingOffsetFrames = null
        firstFrameLatencyMs = null
        decoderInitializationMs = null
        videoWidth = null
        videoHeight = null
        thermalStatusPeak = opening.thermalStatus
    }

    /** Closes the session. Later events are ignored until the next [begin]. */
    fun end() {
        started = false
    }

    /**
     * Records that a frame reached the screen, [latencyMs] after `prepare()` was called.
     *
     * The first frame happens once, so the first reading is the reading: a later report describes an
     * event that has already happened, and overwriting with it would turn "how long start-up took" into
     * "the most recent number Media3 sent".
     */
    fun onFirstFrame(latencyMs: Long?) {
        if (!started) return
        if (firstFrameLatencyMs != null) return
        if (latencyMs != null && latencyMs >= 0) firstFrameLatencyMs = latencyMs
    }

    /**
     * Records the frame-processing offset Media3 reports.
     *
     * Only a pipeline with a frame processor produces these, so in the native baseline this is never
     * called and the metric is reported as unavailable rather than as zero — which is the difference
     * between "no processor" and "a processor that kept up perfectly".
     */
    fun onFrameProcessingOffset(totalProcessingOffsetUs: Long?, frameCount: Int?) {
        if (!started) return

        processingOffsetTotalUs = totalProcessingOffsetUs
        processingOffsetFrames = frameCount
    }

    /** Records how long the video decoder took to become usable. */
    fun onDecoderInitialized(initializationDurationMs: Long?) {
        if (!started) return
        if (initializationDurationMs != null && initializationDurationMs >= 0) {
            decoderInitializationMs = initializationDurationMs
        }
    }

    /** Records the size of the video being rendered. */
    fun onVideoSize(width: Int?, height: Int?) {
        if (!started) return
        if (width != null && height != null && width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
        }
    }

    /** Records a thermal status the platform published during the session, keeping the most severe. */
    fun onThermalStatus(status: Int?) {
        if (!started || status == null) return

        thermalStatusPeak = listOfNotNull(thermalStatusPeak, status).max()
    }

    /**
     * Closes the arithmetic: the session's events plus the readings taken at the end.
     *
     * [measurementDurationMs] arrives from the caller because this class has no clock, and the closing
     * readings arrive for the same reason the opening ones did — they are properties of the process and
     * of the renderer, not of this class.
     */
    fun snapshot(
        closing: FramePerformanceReadings,
        measurementDurationMs: Long?,
    ): FramePerformanceSnapshot = FramePerformanceSnapshot(
        renderedFrames = delta(closing.renderedFrames, opening.renderedFrames),
        droppedFrames = delta(closing.droppedFrames, opening.droppedFrames),
        frameProcessingOffsetTotalUs = processingOffsetTotalUs,
        frameProcessingOffsetFrames = processingOffsetFrames,
        firstFrameLatencyMs = firstFrameLatencyMs,
        decoderInitializationMs = decoderInitializationMs,
        playbackPositionMs = closing.playbackPositionMs,
        measurementDurationMs = measurementDurationMs,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        cpuTimeMs = delta(closing.cpuTimeMs, opening.cpuTimeMs),
        processPssKb = closing.processPssKb,
        heapUsedBytes = closing.heapUsedBytes,
        thermalStatusAtStart = opening.thermalStatus,
        thermalStatusAtEnd = closing.thermalStatus,
        thermalStatusPeak = listOfNotNull(thermalStatusPeak, closing.thermalStatus).maxOrNull(),
    )

    private fun delta(current: Int?, openingValue: Int?): Int? =
        if (current == null || openingValue == null) null else (current - openingValue).takeIf { it >= 0 }

    private fun delta(current: Long?, openingValue: Long?): Long? =
        if (current == null || openingValue == null) null else (current - openingValue).takeIf { it >= 0 }
}
