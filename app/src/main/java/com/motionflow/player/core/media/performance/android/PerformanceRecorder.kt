package com.motionflow.player.core.media.performance.android

import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import com.motionflow.player.core.media.performance.FirstFrameLatency
import com.motionflow.player.core.media.performance.FramePerformanceAccumulator
import com.motionflow.player.core.media.performance.FramePerformanceReadings
import com.motionflow.player.core.media.performance.FramePerformanceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Turns Media3's playback events into one session's measurements, and publishes them on demand.
 *
 * It is an adapter and nothing more: each callback does one translation, and the arithmetic lives in
 * the pure [FramePerformanceAccumulator]. Keeping the Media3-typed surface this thin is what makes the
 * measurement testable — the part that decides is off-device, and the part that cannot be tested holds
 * no decisions.
 *
 * ## What it does not do
 *
 * No per-frame work: Media3's callbacks are already aggregated (a dropped-frames event carries a count,
 * a processing-offset event carries a total and the frame count it covers), and the frame counts
 * themselves are read from a counter object at the two ends of a session rather than summed as events
 * arrive. Nothing here scales with frame count. No logging, no disk, no network, no bitmap, no screen
 * capture, and no timer: a snapshot is published when [begin], [live] or [finish] is called, never on a
 * schedule.
 *
 * ## How the frame counts are read
 *
 * Media3 hands over a `DecoderCounters` instance that the renderer keeps updating while it is enabled,
 * so this class holds the reference and reads it when a session opens and closes. A counter read at the
 * end costs nothing at all, which is why the renderer's aggregated drop and offset events are the only
 * callbacks this class accumulates.
 *
 * Dropped frames come from those counters, and the `onDroppedVideoFrames` event is deliberately *not*
 * summed as well: both report the same drops, and adding them would double-count them.
 *
 * It holds no `Context`, no `Player` and no surface: the position arrives through a lambda, so a
 * measurement cannot become the reason a released player stays alive.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PerformanceRecorder(
    private val probe: AndroidPerformanceProbe,
    private val playbackPositionMs: () -> Long?,
    private val nowMs: () -> Long,
) : AnalyticsListener {

    private val accumulator = FramePerformanceAccumulator()

    private val _snapshot = MutableStateFlow(FramePerformanceSnapshot.Empty)

    /** The latest measurement: empty at the start of a session, complete when one ends. */
    val snapshot: StateFlow<FramePerformanceSnapshot> = _snapshot.asStateFlow()

    /** Media3's live counters for the current renderer, held rather than sampled on every event. */
    private var counters: DecoderCounters? = null

    private var openedAtMs: Long? = null
    private var failed = false

    /** True while a session is collecting. */
    val isRecording: Boolean get() = accumulator.isAccumulating

    /** True when the player reported an error while this session was recording. */
    val hasFailed: Boolean get() = failed

    /**
     * Opens a session: takes the opening readings and starts accumulating.
     *
     * Called on the playback thread, which is also where Media3's callbacks and the thermal listener
     * arrive, so every mutation of the accumulator happens on one thread and no lock is needed.
     */
    fun begin() {
        val opening = readings()
        openedAtMs = nowMs()
        failed = false
        accumulator.begin(opening)
        probe.startThermalWatch { status -> accumulator.onThermalStatus(status) }
        // A session that has just started has measured nothing; publishing an empty snapshot is how a
        // previous result stops being shown as if it were this one's.
        _snapshot.value = FramePerformanceSnapshot.Empty
    }

    /**
     * Closes the session and returns its measurement.
     *
     * The thermal watch stops here rather than lingering, so a device is never left with a listener
     * registered for a measurement that has ended.
     */
    fun finish(): FramePerformanceSnapshot {
        probe.stopThermalWatch()
        val closedAtMs = nowMs()
        val measured = accumulator.snapshot(readings(), openedAtMs?.let { closedAtMs - it })
        accumulator.end()
        _snapshot.value = measured
        return measured
    }

    /** The measurement so far, without closing the session. */
    fun live(): FramePerformanceSnapshot {
        val measured = accumulator.snapshot(readings(), openedAtMs?.let { nowMs() - it })
        _snapshot.value = measured
        return measured
    }

    /**
     * Process readings, the playback position and the renderer's counters, as they stand now.
     *
     * The counters are included at both ends of a session, which is what turns two cumulative readings
     * into the number of frames this session presented and dropped.
     */
    private fun readings(): FramePerformanceReadings = probe.readings().copy(
        renderedFrames = counters?.renderedOutputBufferCount,
        droppedFrames = counters?.droppedBufferCount,
        playbackPositionMs = playbackPositionMs(),
    )

    // --- the Media3 events that carry a measurement ---------------------------------------------

    /** The renderer's counters, valid for as long as it stays enabled; the source of both frame counts. */
    override fun onVideoEnabled(eventTime: EventTime, decoderCounters: DecoderCounters) {
        counters = decoderCounters
    }

    /** The renderer is going away, so its counters stop moving and are no longer read. */
    override fun onVideoDisabled(eventTime: EventTime, decoderCounters: DecoderCounters) {
        counters = null
    }

    /**
     * How long the first presented frame took, relative to this session's start.
     *
     * Media3 passes a `SystemClock.elapsedRealtime()` timestamp here, not a duration — see
     * [FirstFrameLatency], which does the conversion and refuses to state a latency for a frame that
     * arrived before the session began. Storing the raw value is what produced a first-frame reading of
     * several hundred million milliseconds on real hardware.
     */
    override fun onRenderedFirstFrame(eventTime: EventTime, output: Any, renderTimeMs: Long) {
        accumulator.onFirstFrame(FirstFrameLatency.of(renderTimeMs, openedAtMs))
    }

    /**
     * The frame-processing offset, which only a pipeline with a frame processor produces.
     *
     * The native baseline never sees this callback, which is why the metric is reported as unavailable
     * there rather than as a processor that kept up perfectly.
     */
    override fun onVideoFrameProcessingOffset(
        eventTime: EventTime,
        totalProcessingOffsetUs: Long,
        frameCount: Int,
    ) {
        accumulator.onFrameProcessingOffset(totalProcessingOffsetUs, frameCount)
    }

    /** The decoder's initialisation cost, which is the part of start-up no pipeline avoids. */
    override fun onVideoDecoderInitialized(
        eventTime: EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        accumulator.onDecoderInitialized(initializationDurationMs)
    }

    /** The size of the video being rendered, recorded rather than used in a calculation. */
    override fun onVideoSizeChanged(eventTime: EventTime, videoSize: VideoSize) {
        accumulator.onVideoSize(videoSize.width, videoSize.height)
    }

    /** An error during a measurement is recorded as a failure of that measurement, and nothing else. */
    override fun onPlayerError(eventTime: EventTime, error: PlaybackException) {
        if (accumulator.isAccumulating) failed = true
    }
}
