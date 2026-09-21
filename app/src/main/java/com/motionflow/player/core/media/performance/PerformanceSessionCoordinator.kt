package com.motionflow.player.core.media.performance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the measurement session's lifecycle, and nothing about the measurement itself.
 *
 * It decides *whether* a session is running, for how long, and under which pipeline; the arithmetic
 * lives in [FramePerformanceAccumulator], and the Media3 callbacks that feed it live on the Android
 * side. Splitting it that way keeps every rule testable on the JVM — duplicate starts, duplicate stops,
 * a session that runs out of time, a start with nothing to measure — without a player or a clock.
 *
 * No scope, and no timer. Time arrives as a parameter, and a session closes when someone asks or when
 * an event reveals that its window has passed. Nothing here schedules anything, which is why the report
 * cannot grow a polling loop by accident.
 */
class PerformanceSessionCoordinator(
    initialSupport: PerformanceMeasurementSupport = PerformanceMeasurementSupport(),
) {

    private val _diagnostics = MutableStateFlow(PerformanceDiagnostics(support = initialSupport))
    val diagnostics: StateFlow<PerformanceDiagnostics> = _diagnostics.asStateFlow()

    private var nextSessionId = 0L
    private var session: PerformanceSession? = null
    private var snapshot: FramePerformanceSnapshot = FramePerformanceSnapshot.Empty
    private var device: DeviceCharacteristics = DeviceCharacteristics.Unknown
    private var support: PerformanceMeasurementSupport = initialSupport
    private var mode: ProcessingPerformanceMode = ProcessingPerformanceMode.NATIVE

    /**
     * Records the pipeline the engine is configured for.
     *
     * Owned by the service, because only the code that builds the engine knows what it built — and it
     * is also what turns the mode into [ProcessingPerformanceMode.FAILED] if the effect pipeline was
     * configured and the player then failed.
     */
    fun onModeChanged(mode: ProcessingPerformanceMode) {
        this.mode = mode
        publish()
    }

    /** Records what this platform can measure, once the API level is known. */
    fun onSupport(support: PerformanceMeasurementSupport) {
        this.support = support
        publish()
    }

    /** Records the device facts a reader needs to interpret the numbers. */
    fun onDeviceCharacteristics(device: DeviceCharacteristics) {
        this.device = device
        publish()
    }

    /**
     * Opens a session.
     *
     * Refused when a session is already running, so a second start cannot silently discard the first
     * one's window, and refused when nothing is loaded — a measurement of a player that is not playing
     * anything would collect zeroes that read like findings.
     */
    fun start(
        request: PerformanceSessionRequest,
        nowMs: Long,
        mediaLoaded: Boolean,
    ): PerformanceSessionOutcome {
        if (isRunning(nowMs)) return PerformanceSessionOutcome.refused(PerformanceSessionRefusal.ALREADY_RUNNING)
        if (!mediaLoaded) return PerformanceSessionOutcome.refused(PerformanceSessionRefusal.NO_MEDIA)

        val started = PerformanceSession(
            id = PerformanceSessionId(nextSessionId++),
            mode = request.mode,
            length = request.length,
            startedAtMs = nowMs,
            sourceFps = request.sourceFps,
            displayRefreshRateHz = request.displayRefreshRateHz,
        )
        session = started
        snapshot = FramePerformanceSnapshot.Empty
        this.mode = request.mode
        publish()

        return PerformanceSessionOutcome.accepted(started)
    }

    /**
     * Closes a session with its final measurement.
     *
     * Refused when nothing is running: a stop that did not match a start must not publish a snapshot
     * under a session that never existed.
     */
    fun stop(
        nowMs: Long,
        finalSnapshot: FramePerformanceSnapshot?,
        failed: Boolean = false,
    ): PerformanceSessionOutcome {
        val current = session
        if (current == null || !current.isRunning) {
            return PerformanceSessionOutcome.refused(PerformanceSessionRefusal.NOT_RUNNING)
        }

        val endedMode = if (failed) ProcessingPerformanceMode.FAILED else current.mode
        val ended = current.ended(nowMs, endedMode)
        session = ended
        if (finalSnapshot != null) snapshot = finalSnapshot
        if (failed) mode = ProcessingPerformanceMode.FAILED
        publish()

        return PerformanceSessionOutcome.accepted(ended)
    }

    /**
     * Publishes the current picture, closing a session whose window has passed.
     *
     * The only way state leaves this class, so a reader cannot observe a session that has run out of
     * time as if it were still collecting.
     */
    fun read(nowMs: Long, latest: FramePerformanceSnapshot? = null): PerformanceDiagnostics {
        if (latest != null) snapshot = latest

        val current = session
        if (current != null && current.isRunning && current.hasExpired(nowMs)) {
            session = current.ended(nowMs)
        }
        publish()

        return _diagnostics.value
    }

    /** True when a session is collecting and its window has not passed. */
    fun isRunning(nowMs: Long): Boolean {
        val current = session ?: return false
        return current.isRunning && !current.hasExpired(nowMs)
    }

    /** The stable identifier of the session in progress, or `null` while none is. */
    val runningSessionId: PerformanceSessionId? get() = session?.takeIf { it.isRunning }?.id

    private fun publish() {
        _diagnostics.value = PerformanceDiagnostics(
            mode = mode,
            session = session,
            snapshot = snapshot,
            support = support,
            device = device,
        )
    }
}
