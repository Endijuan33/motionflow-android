package com.motionflow.player.core.media.session

import android.os.Bundle
import com.motionflow.player.core.media.performance.DeviceCharacteristics
import com.motionflow.player.core.media.performance.FramePerformanceSnapshot
import com.motionflow.player.core.media.performance.PerformanceDiagnostics
import com.motionflow.player.core.media.performance.PerformanceMeasurementSupport
import com.motionflow.player.core.media.performance.PerformanceSession
import com.motionflow.player.core.media.performance.PerformanceSessionId
import com.motionflow.player.core.media.performance.PerformanceSessionLength
import com.motionflow.player.core.media.performance.PerformanceSessionRefusal
import com.motionflow.player.core.media.performance.ProcessingPerformanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers what a measurement looks like on the wire, and what a client can rebuild from it.
 *
 * The mapping is tested rather than the `Bundle` around it: `android.os.Bundle` is a stub off-device, so
 * a test that wrote into one and read it back would be testing the stub. What is tested here is every
 * decision the codec makes — which fields travel, which stay absent, and what a refusal means.
 */
class PerformanceSessionContractTest {

    @Test
    fun `a start request carries only what the client knows`() {
        val arguments = PerformanceSessionContract.StartArguments(
            length = PerformanceSessionLength.THIRTY,
            sourceFps = 23.976f,
            displayRefreshRateHz = 120f,
        )

        assertEquals(PerformanceSessionLength.THIRTY, arguments.length)
        assertEquals(23.976f, arguments.sourceFps!!, 0f)
        assertEquals(120f, arguments.displayRefreshRateHz!!, 0f)
    }

    @Test
    fun `an unreadable start request is refused rather than defaulted`() {
        // A bundle with no length: a session of an unknown length is not a controlled measurement.
        assertNull(PerformanceSessionContract.StartArguments.from(Bundle()))
    }

    @Test
    fun `the three commands are distinct, and each is declared`() {
        val actions = PerformanceSessionContract.commands.map { it.customAction }

        assertEquals(3, actions.distinct().size)
        assertTrue(PerformanceSessionContract.ACTION_START in actions)
        assertTrue(PerformanceSessionContract.ACTION_STOP in actions)
        assertTrue(PerformanceSessionContract.ACTION_READ in actions)
    }

    @Test
    fun `a session's whole measurement survives the wire`() {
        val diagnostics = PerformanceDiagnostics(
            mode = ProcessingPerformanceMode.EFFECT_PIPELINE,
            session = PerformanceSession(
                id = PerformanceSessionId(7),
                mode = ProcessingPerformanceMode.EFFECT_PIPELINE,
                length = PerformanceSessionLength.THIRTY,
                startedAtMs = 0,
                endedAtMs = 30_000,
                sourceFps = 23.976f,
                displayRefreshRateHz = 120f,
            ),
            snapshot = FramePerformanceSnapshot(
                renderedFrames = 1_436,
                droppedFrames = 7,
                frameProcessingOffsetTotalUs = 2_400_000,
                frameProcessingOffsetFrames = 1_200,
                firstFrameLatencyMs = 487L,
                decoderInitializationMs = 120L,
                playbackPositionMs = 30_000L,
                measurementDurationMs = 30_000L,
                videoWidth = 1920,
                videoHeight = 1080,
                cpuTimeMs = 5_020L,
                processPssKb = 189_440L,
                heapUsedBytes = 12_582_912L,
                thermalStatusAtStart = 0,
                thermalStatusAtEnd = 1,
                thermalStatusPeak = 1,
            ),
            support = PerformanceMeasurementSupport.forApiLevel(36),
            device = DeviceCharacteristics(
                apiLevel = 36,
                primaryAbi = "arm64-v8a",
                memoryClassMb = 512,
                supportedDisplayRefreshRatesHz = listOf(60f, 120f),
                thermalApiAvailable = true,
            ),
        )

        val rebuilt = PerformanceWire.of(diagnostics).toDiagnostics()

        assertEquals(ProcessingPerformanceMode.EFFECT_PIPELINE, rebuilt.mode)
        assertFalse(rebuilt.isMeasuring)
        assertEquals(7L, rebuilt.session?.id?.value)
        assertEquals(30_000L, rebuilt.session?.measuredDurationMs)
        assertEquals(23.976f, rebuilt.session?.sourceFps!!, 0f)
        assertEquals(1_436, rebuilt.snapshot.renderedFrames)
        assertEquals(7, rebuilt.snapshot.droppedFrames)
        assertEquals(2.0, rebuilt.snapshot.averageFrameProcessingOffsetMs!!, 0.0001)
        assertEquals(487L, rebuilt.snapshot.firstFrameLatencyMs)
        assertEquals(1_920, rebuilt.device.supportedDisplayRefreshRatesHz.size * 960)
        assertEquals("arm64-v8a", rebuilt.device.primaryAbi)
        assertTrue(rebuilt.support.supports(com.motionflow.player.core.media.performance.PerformanceMetric.THERMAL_STATUS))
    }

    @Test
    fun `a metric that was not measured stays unmeasured across the wire`() {
        val native = PerformanceDiagnostics(
            mode = ProcessingPerformanceMode.NATIVE,
            session = PerformanceSession(
                id = PerformanceSessionId(1),
                mode = ProcessingPerformanceMode.NATIVE,
                length = PerformanceSessionLength.TEN,
                startedAtMs = 0,
                endedAtMs = 10_000,
            ),
        )

        val rebuilt = PerformanceWire.of(native).toDiagnostics()

        assertNull("a zero would claim a processor that kept up perfectly", rebuilt.snapshot.frameProcessingOffsetFrames)
        assertNull(rebuilt.snapshot.averageFrameProcessingOffsetMs)
        assertTrue(rebuilt.snapshot.unavailable.contains(com.motionflow.player.core.media.performance.PerformanceMetric.FRAME_PROCESSING_OFFSET))
    }

    @Test
    fun `a refusal travels as a refusal, and is not mistaken for an empty measurement`() {
        val wire = PerformanceWire.refused(PerformanceSessionRefusal.NO_MEDIA)

        assertFalse(wire.accepted)
        assertEquals(PerformanceSessionRefusal.NO_MEDIA.name, wire.refusal)
        assertNull("no session was started, so none is reported", wire.sessionId)
    }

    @Test
    fun `a running session is reported as running`() {
        val running = PerformanceDiagnostics(
            mode = ProcessingPerformanceMode.NATIVE,
            session = PerformanceSession(
                id = PerformanceSessionId(2),
                mode = ProcessingPerformanceMode.NATIVE,
                length = PerformanceSessionLength.SIXTY,
                startedAtMs = 0,
            ),
        )

        val rebuilt = PerformanceWire.of(running).toDiagnostics()

        assertTrue(rebuilt.isMeasuring)
        assertNull(rebuilt.session?.measuredDurationMs)
    }

    @Test
    fun `a measurement with no session does not invent one`() {
        val rebuilt = PerformanceWire(mode = ProcessingPerformanceMode.NATIVE.name).toDiagnostics()

        assertNull(rebuilt.session)
        assertFalse(rebuilt.hasMeasurement)
    }

    @Test
    fun `a mode the wire does not carry is reported as the control condition`() {
        val rebuilt = PerformanceWire(mode = null).toDiagnostics()

        assertEquals(ProcessingPerformanceMode.NATIVE, rebuilt.mode)
    }
}
