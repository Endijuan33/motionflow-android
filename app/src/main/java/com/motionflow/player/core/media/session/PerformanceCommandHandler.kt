package com.motionflow.player.core.media.session

import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.motionflow.player.core.media.performance.PerformanceSessionCoordinator
import com.motionflow.player.core.media.performance.PerformanceSessionLength
import com.motionflow.player.core.media.performance.PerformanceSessionRefusal
import com.motionflow.player.core.media.performance.PerformanceSessionRequest
import com.motionflow.player.core.media.performance.ProcessingPerformanceMode
import com.motionflow.player.core.media.performance.android.AndroidPerformanceProbe
import com.motionflow.player.core.media.performance.android.PerformanceRecorder

/**
 * Answers measurement commands where the player lives.
 *
 * The far end of the request path, and the only place a session is opened or closed. It holds no
 * player and no surface: Media3's counters arrive through the recorder, the clock arrives through a
 * lambda, and whether anything is loaded arrives through a lambda, so nothing here can keep a released
 * engine alive.
 *
 * ## How a session is bounded
 *
 * Three things keep a session from outliving its purpose, and none of them is a timer:
 *
 * 1. The client stops it when its window has passed, because the client chose the window.
 * 2. Every command — start, stop, read — first closes a session whose window has already passed, so a
 *    stop that never arrives cannot leave the recorder accumulating.
 * 3. The service is torn down when the pipeline changes, which closes everything with it.
 */
class PerformanceCommandHandler(
    private val recorder: PerformanceRecorder,
    private val coordinator: PerformanceSessionCoordinator,
    private val probe: AndroidPerformanceProbe,
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
        closeExpired(now)

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

    private fun stop(now: Long): PerformanceWire {
        val outcome = coordinator.stop(now, recorder.finish(), failed = recorder.hasFailed)
        val refusal = outcome.refusal
        if (refusal != null) return PerformanceWire.refused(refusal)

        return PerformanceWire.of(coordinator.diagnostics.value)
    }

    private fun read(now: Long): PerformanceWire {
        val latest = if (recorder.isRecording) recorder.live() else null
        coordinator.read(now, latest)

        return PerformanceWire.of(coordinator.diagnostics.value)
    }

    /** Closes a session whose window has passed, so nothing accumulates without a request. */
    private fun closeExpired(now: Long) {
        val session = coordinator.diagnostics.value.session ?: return
        if (!session.isRunning || !session.hasExpired(now)) return

        coordinator.stop(now, recorder.finish(), failed = recorder.hasFailed)
    }

    private companion object {

        val HANDLED_ACTIONS = setOf(
            PerformanceSessionContract.ACTION_START,
            PerformanceSessionContract.ACTION_STOP,
            PerformanceSessionContract.ACTION_READ,
        )
    }
}
