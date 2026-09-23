package com.motionflow.player.core.media.session

import android.os.Bundle
import androidx.media3.session.SessionResult
import com.motionflow.player.core.media.performance.DeviceCharacteristics
import com.motionflow.player.core.media.performance.FramePerformanceSnapshot
import com.motionflow.player.core.media.performance.MeasurementProbe
import com.motionflow.player.core.media.performance.MeasurementRecorder
import com.motionflow.player.core.media.performance.PerformanceMeasurementSupport
import com.motionflow.player.core.media.performance.PerformanceSessionCoordinator
import com.motionflow.player.core.media.performance.PerformanceSessionLength
import com.motionflow.player.core.media.performance.ProcessingPerformanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces the Phase 8 defect a Xiaomi 24069PC21G exposed: a 60-second Native session kept running
 * for over 120 seconds still exported "no runs recorded".
 *
 * The completion path is driven here on the JVM through the [MeasurementRecorder] and [MeasurementProbe]
 * seams and a controllable clock — no device, no Media3 listener — so the exact command sequence a
 * client sends (start, then a stop after the window) can be replayed and the answer inspected. The
 * defect was that finalization ran twice for an expired window: a pre-dispatch "close expired" pass
 * consumed the session, then `stop()` found nothing running and answered with a bare `NOT_RUNNING`
 * refusal carrying no measurement, so the client persisted nothing.
 */
class PerformanceCommandHandlerTest {

    private var now = 1_000L
    private val clock = { now }
    private val recorder = FakeRecorder()
    private val probe = FakeProbe()
    private val coordinator = PerformanceSessionCoordinator(probe.support())
    private val handler = PerformanceCommandHandler(
        recorder = recorder,
        coordinator = coordinator,
        probe = probe,
        mediaLoaded = { true },
        nowMs = clock,
    ).also { it.onEngineBuilt(ProcessingPerformanceMode.NATIVE) }

    @Test
    fun `a stop after the full window returns a completed run with its measurement`() {
        start(PerformanceSessionLength.SIXTY)

        // The user kept it running well past the window before stopping — the exact hardware scenario.
        now += 125_000L
        val wire = decode(stop())

        assertFalse("the session is finished", wire.sessionRunning)
        assertEquals("and its snapshot came back with it", 1_440, wire.renderedFrames)
        assertTrue("the measured window is at least the requested one", (wire.measurementDurationMs ?: 0) >= 60_000L)
        assertNull("a completed stop is not a refusal", wire.refusal)
        assertEquals(1, recorder.finishCount)
    }

    @Test
    fun `a stop exactly at the window boundary completes the run`() {
        start(PerformanceSessionLength.SIXTY)

        now += 60_000L
        val wire = decode(stop())

        assertFalse(wire.sessionRunning)
        assertEquals(1_440, wire.renderedFrames)
        assertNull(wire.refusal)
    }

    @Test
    fun `a read before the window ends keeps the session measuring and finalizes nothing`() {
        start(PerformanceSessionLength.SIXTY)

        now += 59_000L
        val wire = decode(read())

        assertTrue("still measuring at 59 s", wire.sessionRunning)
        assertEquals("no finalization has happened", 0, recorder.finishCount)
        assertTrue("the recorder is still live", recorder.isRecording)
    }

    @Test
    fun `a read after the window ends finalizes the session, for a client that never sent a stop`() {
        start(PerformanceSessionLength.SIXTY)

        now += 90_000L
        val wire = decode(read())

        assertFalse("a read past the window completes the session", wire.sessionRunning)
        assertEquals(1_440, wire.renderedFrames)
        assertEquals(1, recorder.finishCount)
    }

    @Test
    fun `a stop after a read has already finalized does not finish the recorder twice`() {
        start(PerformanceSessionLength.SIXTY)
        now += 90_000L
        decode(read())   // finalizes here
        val afterRead = recorder.finishCount

        val wire = decode(stop())

        assertEquals("the recorder is not finished again", afterRead, recorder.finishCount)
        assertFalse(wire.sessionRunning)
        assertEquals("the completed run is still reported", 1_440, wire.renderedFrames)
        assertNull(wire.refusal)
    }

    @Test
    fun `an early stop finalizes the shorter run rather than losing it`() {
        start(PerformanceSessionLength.SIXTY)

        now += 12_000L
        val wire = decode(stop())

        assertFalse(wire.sessionRunning)
        assertEquals(1, recorder.finishCount)
        // The integrity layer, not the handler, decides that a 12 s window is not a usable 60 s run.
        assertTrue("the short window is reported honestly", (wire.measurementDurationMs ?: 0) < 60_000L)
    }

    @Test
    fun `starting a second session after one expired finalizes the first and does not lose it`() {
        start(PerformanceSessionLength.SIXTY)
        now += 70_000L

        // A fresh start arrives while the previous window has passed but no stop was sent.
        val startWire = decode(start(PerformanceSessionLength.THIRTY))

        assertEquals("the expired session was finalized", 1, recorder.finishCount)
        assertTrue("and a new session is now measuring", startWire.sessionRunning)
        assertEquals(2, recorder.beginCount)
    }

    @Test
    fun `a duplicate stop reports the completed run once and finishes the recorder once`() {
        start(PerformanceSessionLength.SIXTY)
        now += 60_000L

        val first = decode(stop())
        val second = decode(stop())

        assertEquals("finalized exactly once", 1, recorder.finishCount)
        assertEquals(1_440, first.renderedFrames)
        assertEquals("the second stop still describes the completed run", 1_440, second.renderedFrames)
        assertFalse(second.sessionRunning)
        assertNull(second.refusal)
    }

    @Test
    fun `a start with no media is refused and nothing is recorded`() {
        val quiet = PerformanceCommandHandler(
            recorder = recorder,
            coordinator = PerformanceSessionCoordinator(probe.support()),
            probe = probe,
            mediaLoaded = { false },
            nowMs = clock,
        ).also { it.onEngineBuilt(ProcessingPerformanceMode.NATIVE) }

        val wire = decode(quiet.handle(PerformanceSessionContract.startCommand, startArgs(PerformanceSessionLength.SIXTY)))

        assertTrue(wire.refused)
        assertEquals(0, recorder.beginCount)
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun start(length: PerformanceSessionLength) =
        handler.handle(PerformanceSessionContract.startCommand, startArgs(length))

    private fun stop() = handler.handle(PerformanceSessionContract.stopCommand, Bundle())

    private fun read() = handler.handle(PerformanceSessionContract.readCommand, Bundle())

    private fun startArgs(length: PerformanceSessionLength): Bundle = Bundle().apply {
        PerformanceSessionContract.StartArguments(
            length = length,
            sourceFps = 24f,
            displayRefreshRateHz = 60f,
        ).applyTo(this)
    }

    private fun decode(result: SessionResult): PerformanceWire {
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        return PerformanceWire.fromBundle(result.extras)
    }

    private class FakeRecorder : MeasurementRecorder {
        var beginCount = 0
        var finishCount = 0
        private var recording = false

        override val isRecording: Boolean get() = recording
        override val hasFailed: Boolean get() = false

        override fun begin() {
            beginCount++
            recording = true
        }

        override fun finish(): FramePerformanceSnapshot {
            finishCount++
            recording = false
            return SNAPSHOT
        }

        override fun live(): FramePerformanceSnapshot = SNAPSHOT.copy(renderedFrames = 200)

        private companion object {
            val SNAPSHOT = FramePerformanceSnapshot(
                renderedFrames = 1_440,
                droppedFrames = 2,
                firstFrameLatencyMs = 412L,
                decoderInitializationMs = 120L,
                playbackPositionMs = 60_000L,
                // The recorder computes this from its own clock; the fake reports a full window so the
                // integrity layer treats a not-stopped-early run as complete.
                measurementDurationMs = 60_050L,
                videoWidth = 1920,
                videoHeight = 1080,
                cpuTimeMs = 8_000L,
                processPssKb = 190_000L,
                thermalStatusAtStart = 0,
                thermalStatusAtEnd = 0,
                thermalStatusPeak = 0,
            )
        }
    }

    private class FakeProbe : MeasurementProbe {
        override fun support(): PerformanceMeasurementSupport = PerformanceMeasurementSupport.forApiLevel(36)

        override fun deviceCharacteristics(supportedDisplayRefreshRatesHz: List<Float>): DeviceCharacteristics =
            DeviceCharacteristics(apiLevel = 36, primaryAbi = "arm64-v8a")
    }
}
