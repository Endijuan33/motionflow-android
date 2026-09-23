package com.motionflow.player.core.media.session

import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.motionflow.player.core.media.performance.MeasurementProbe
import com.motionflow.player.core.media.performance.MeasurementRecorder
import com.motionflow.player.core.media.performance.PerformanceSessionCoordinator
import com.motionflow.player.core.media.performance.PerformanceSessionRefusal
import com.motionflow.player.core.media.performance.PerformanceSessionRequest
import com.motionflow.player.core.media.performance.ProcessingPerformanceMode

/**
 * Answers measurement commands where the player lives.
 *
 * The far end of the request path, and the only place a session is opened or closed. It holds no
 * player and no surface: the recorder and probe are seams ([MeasurementRecorder], [MeasurementProbe]),
 * the clock arrives through a lambda, and whether anything is loaded arrives through a lambda, so
 * nothing here can keep a released engine alive — and the whole completion path can be driven on the
 * JVM without a device.
 *
 * ## How a session completes
 *
 * A session ends the instant one of two things is true, whichever comes first:
 *
 * 1. **Its window has elapsed.** Any command — start, stop, read — that arrives once the requested
 *    duration has passed finalizes the session with the recorder's final snapshot. A 60-second run is
 *    completed by the *next command after 60 seconds*, which the client sends by waiting the window and
 *    asking to stop; if that command never came, a later read would still finalize it.
 * 2. **The client asks to stop it early.** A stop before the window ends finalizes it as the shorter run
 *    it is, and the integrity layer decides whether that is usable.
 *
 * The bug this class was rewritten to fix: finalization used to run *twice* for a window that had
 * already elapsed when the stop arrived — once in a pre-dispatch "close expired" pass, then again in
 * `stop()`. The second call found no running session, returned a `NOT_RUNNING` refusal with no
 * measurement in it, and the client persisted nothing. A completed 60-second session therefore never
 * reached the store. Finalization now happens in exactly one place, and every command routes through
 * it, so a stop and an expiry cannot both consume the session and leave the caller with an empty answer.
 */
class PerformanceCommandHandler(
    private val recorder: MeasurementRecorder,
    private val coordinator: PerformanceSessionCoordinator,
    private val probe: MeasurementProbe,
    private val mediaLoaded: () -> Boolean,
    private val nowMs: () -> Long,
) {

    /** True when [command] is one of the measurement commands. */
    fun handles(command: SessionCommand): Boolean =
        command.customAction in HANDLED_ACTIONS

    /**
     * Answers a measurement command.
     *
     * A command this handler does not know is refused rather than ignored, so a controller asking for
     * something undefined is told so instead of waiting.
     */
    fun handle(command: SessionCommand, args: Bundle): SessionResult {
        val now = nowMs()

        val wire = when (command.customAction) {
            PerformanceSessionContract.ACTION_START -> start(args, now)
            PerformanceSessionContract.ACTION_STOP -> stop(now)
            PerformanceSessionContract.ACTION_READ -> read(now)
            else -> return ProcessingSessionContract.unsupportedResult()
        }

        return SessionResult(SessionResult.RESULT_SUCCESS, wire.toBundle())
    }

    /**
     * Records the pipeline the engine was built for, and what this platform can measure.
     *
     * Called when the engine is built, which is the only moment either fact changes.
     */
    fun onEngineBuilt(mode: ProcessingPerformanceMode) {
        coordinator.onSupport(probe.support())
        coordinator.onDeviceCharacteristics(probe.deviceCharacteristics(emptyList()))
        coordinator.onModeChanged(mode)
    }

    /**
     * Records a failure of the effect pipeline.
     *
     * A player error while the effect pipeline was configured is the one thing that turns the effect
     * baseline into `FAILED`: the configuration was right and the pipeline could not run it.
     */
    fun onPipelineFailed() {
        coordinator.onModeChanged(ProcessingPerformanceMode.FAILED)
    }

    private fun start(args: Bundle, now: Long): PerformanceWire {
        // A session that has run its window is finalized before a new one begins, so an abandoned run
        // cannot block the next measurement — and so its result is not lost.
        finalizeIfExpired(now)

        val arguments = PerformanceSessionContract.StartArguments.from(args)
            ?: return PerformanceWire.refused(PerformanceSessionRefusal.UNREADABLE_REQUEST)

        val outcome = coordinator.start(
            request = PerformanceSessionRequest(
                length = arguments.length,
                mode = coordinator.diagnostics.value.mode,
                sourceFps = arguments.sourceFps,
                displayRefreshRateHz = arguments.displayRefreshRateHz,
            ),
            nowMs = now,
            mediaLoaded = mediaLoaded(),
        )

        val refusal = outcome.refusal
        if (refusal != null) return PerformanceWire.refused(refusal)

        recorder.begin()
        // The recorder published an empty snapshot of its own; the coordinator is read without a new
        // measurement so the session appears as started and measuring.
        coordinator.read(now)

        return PerformanceWire.of(coordinator.diagnostics.value)
    }

    /**
     * Finalizes the session, whether it ran its window or is being stopped early.
     *
     * The single finalization point for a stop. It reads the recorder's final snapshot exactly once,
     * hands it to the coordinator, and returns the completed diagnostics — so the answer a client
     * receives from a stop always carries the measurement, which is what it persists.
     */
    private fun stop(now: Long): PerformanceWire {
        // A session that was already finalized — by an earlier expiry, or a preceding stop — is not
        // finished again: its snapshot is done, and the current diagnostics describe it. Finishing the
        // recorder twice would read stale fields for a result that is then discarded. Reporting the
        // completed session here is what makes a stop that arrives after the window still return the
        // run, which is exactly the case that used to come back empty.
        val session = coordinator.diagnostics.value.session
        if (session == null || !session.isRunning) {
            return PerformanceWire.of(coordinator.diagnostics.value)
        }

        val outcome = coordinator.stop(now, recorder.finish(), failed = recorder.hasFailed)
        val refusal = outcome.refusal
        if (refusal != null) return PerformanceWire.refused(refusal)

        return PerformanceWire.of(coordinator.diagnostics.value)
    }

    private fun read(now: Long): PerformanceWire {
        // A read after the window has elapsed finalizes the session, so a client that never sent a stop
        // still ends up with a completed run rather than one that measures forever.
        if (finalizeIfExpired(now)) {
            return PerformanceWire.of(coordinator.diagnostics.value)
        }

        val latest = if (recorder.isRecording) recorder.live() else null
        coordinator.read(now, latest)

        return PerformanceWire.of(coordinator.diagnostics.value)
    }

    /**
     * Finalizes a session whose window has passed, and reports whether it did.
     *
     * The recorder is finished here — the one other place besides [stop] — so the final snapshot is read
     * once and the session is closed with it. A session that is still within its window, or already
     * ended, is left untouched.
     */
    private fun finalizeIfExpired(now: Long): Boolean {
        val session = coordinator.diagnostics.value.session ?: return false
        if (!session.isRunning || !session.hasExpired(now)) return false

        coordinator.stop(now, recorder.finish(), failed = recorder.hasFailed)
        return true
    }

    private companion object {

        val HANDLED_ACTIONS = setOf(
            PerformanceSessionContract.ACTION_START,
            PerformanceSessionContract.ACTION_STOP,
            PerformanceSessionContract.ACTION_READ,
        )
    }
}
