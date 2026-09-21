package com.motionflow.player.core.media.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the session's lifecycle and its rules: one session at a time, a controlled window, and no
 * session at all when there would be nothing to measure.
 */
class PerformanceSessionCoordinatorTest {

    @Test
    fun `nothing is measured or claimed before a session starts`() {
        val diagnostics = PerformanceSessionCoordinator().diagnostics.value

        assertFalse(diagnostics.isMeasuring)
        assertFalse(diagnostics.hasMeasurement)
        assertNull(diagnostics.session)
        assertEquals(ProcessingPerformanceMode.NATIVE, diagnostics.mode)
        assertTrue(diagnostics.snapshot.isEmpty)
    }

    @Test
    fun `a session starts, runs, and reports the window the client asked for`() {
        val coordinator = PerformanceSessionCoordinator()

        val outcome = coordinator.start(request(PerformanceSessionLength.THIRTY), nowMs = 1_000, mediaLoaded = true)

        assertTrue(outcome.accepted)
        val session = outcome.session!!
        assertEquals(PerformanceSessionLength.THIRTY, session.length)
        assertEquals(1_000L, session.startedAtMs)
        assertTrue(session.isRunning)
        assertTrue(coordinator.isRunning(nowMs = 2_000))
    }

    @Test
    fun `starting with nothing loaded is refused, because zeroes would look like findings`() {
        val coordinator = PerformanceSessionCoordinator()

        val outcome = coordinator.start(request(PerformanceSessionLength.TEN), nowMs = 0, mediaLoaded = false)

        assertFalse(outcome.accepted)
        assertEquals(PerformanceSessionRefusal.NO_MEDIA, outcome.refusal)
        assertFalse(coordinator.diagnostics.value.isMeasuring)
    }

    @Test
    fun `starting twice is refused, so the first window is not silently discarded`() {
        val coordinator = PerformanceSessionCoordinator()
        coordinator.start(request(PerformanceSessionLength.TEN), nowMs = 0, mediaLoaded = true)

        val second = coordinator.start(request(PerformanceSessionLength.SIXTY), nowMs = 10, mediaLoaded = true)

        assertFalse(second.accepted)
        assertEquals(PerformanceSessionRefusal.ALREADY_RUNNING, second.refusal)
        assertEquals(
            "the first window stands",
            PerformanceSessionLength.TEN,
            coordinator.diagnostics.value.session?.length,
        )
    }

    @Test
    fun `stopping a session that never started is refused`() {
        val coordinator = PerformanceSessionCoordinator()

        val outcome = coordinator.stop(nowMs = 5_000, finalSnapshot = null)

        assertFalse(outcome.accepted)
        assertEquals(PerformanceSessionRefusal.NOT_RUNNING, outcome.refusal)
    }

    @Test
    fun `stopping twice is refused the second time`() {
        val coordinator = PerformanceSessionCoordinator()
        coordinator.start(request(PerformanceSessionLength.TEN), nowMs = 0, mediaLoaded = true)

        val first = coordinator.stop(nowMs = 10_000, finalSnapshot = null)
        val second = coordinator.stop(nowMs = 11_000, finalSnapshot = null)

        assertTrue(first.accepted)
        assertFalse(second.accepted)
        assertEquals(PerformanceSessionRefusal.NOT_RUNNING, second.refusal)
    }

    @Test
    fun `each session is numbered locally, and the numbers do not repeat`() {
        val coordinator = PerformanceSessionCoordinator()

        val first = coordinator.start(request(PerformanceSessionLength.TEN), nowMs = 0, mediaLoaded = true)
        coordinator.stop(nowMs = 10_000, finalSnapshot = null)
        val second = coordinator.start(request(PerformanceSessionLength.TEN), nowMs = 20_000, mediaLoaded = true)

        assertTrue(first.session!!.id.value != second.session!!.id.value)
    }

    @Test
    fun `a session whose window has passed stops being running`() {
        val coordinator = PerformanceSessionCoordinator()
        coordinator.start(request(PerformanceSessionLength.TEN), nowMs = 0, mediaLoaded = true)

        assertTrue(coordinator.isRunning(nowMs = 9_999))
        assertFalse("a twelve-second-old ten-second session is over", coordinator.isRunning(nowMs = 12_000))
    }

    @Test
    fun `reading closes a session whose window has passed, so nothing accumulates without a request`() {
        val coordinator = PerformanceSessionCoordinator()
        coordinator.start(request(PerformanceSessionLength.TEN), nowMs = 0, mediaLoaded = true)

        val diagnostics = coordinator.read(nowMs = 30_000, latest = FramePerformanceSnapshot(renderedFrames = 700))

        assertFalse(diagnostics.isMeasuring)
        assertEquals(30_000L, diagnostics.session?.measuredDurationMs)
        assertEquals(700, diagnostics.snapshot.renderedFrames)
    }

    @Test
    fun `a measurement is stored, and carried with the mode it was taken under`() {
        val coordinator = PerformanceSessionCoordinator()
        coordinator.start(
            request(PerformanceSessionLength.THIRTY, ProcessingPerformanceMode.EFFECT_PIPELINE),
            nowMs = 0,
            mediaLoaded = true,
        )

        val outcome = coordinator.stop(
            nowMs = 30_000,
            finalSnapshot = FramePerformanceSnapshot(renderedFrames = 1_436, droppedFrames = 7),
        )

        assertTrue(outcome.accepted)
        assertEquals(ProcessingPerformanceMode.EFFECT_PIPELINE, outcome.session?.mode)
        assertEquals(30_000L, outcome.session?.measuredDurationMs)
        assertEquals(1_436, coordinator.diagnostics.value.snapshot.renderedFrames)
    }

    @Test
    fun `a failed session is recorded as failed, and says so in the diagnostics`() {
        val coordinator = PerformanceSessionCoordinator()
        coordinator.start(
            request(PerformanceSessionLength.TEN, ProcessingPerformanceMode.EFFECT_PIPELINE),
            nowMs = 0,
            mediaLoaded = true,
        )

        val outcome = coordinator.stop(nowMs = 3_000, finalSnapshot = null, failed = true)

        assertEquals(ProcessingPerformanceMode.FAILED, outcome.session?.mode)
        assertEquals(ProcessingPerformanceMode.FAILED, coordinator.diagnostics.value.mode)
    }

    @Test
    fun `the platform's capability list says which metrics are unsupported, and why`() {
        val old = PerformanceMeasurementSupport.forApiLevel(26)
        val modern = PerformanceMeasurementSupport.forApiLevel(36)

        assertFalse("the thermal API arrived in API 29", old.supports(PerformanceMetric.THERMAL_STATUS))
        assertTrue(modern.supports(PerformanceMetric.THERMAL_STATUS))

        listOf(old, modern).forEach { support ->
            assertFalse(support.supports(PerformanceMetric.GPU_UTILISATION))
            assertFalse(support.supports(PerformanceMetric.THERMAL_HEADROOM))
            assertFalse(support.supports(PerformanceMetric.BATTERY_DRAIN))
        }
        assertTrue(modern.unsupported.contains(PerformanceMetric.GPU_UTILISATION))
    }

    @Test
    fun `what the platform cannot measure is never reported as a session's fault`() {
        val snapshot = FramePerformanceSnapshot()

        assertFalse(
            "GPU utilisation is unavailable on every device, not missing from this session",
            snapshot.unavailable.contains(PerformanceMetric.GPU_UTILISATION),
        )
    }

    private fun request(
        length: PerformanceSessionLength,
        mode: ProcessingPerformanceMode = ProcessingPerformanceMode.NATIVE,
    ) = PerformanceSessionRequest(length = length, mode = mode)
}
