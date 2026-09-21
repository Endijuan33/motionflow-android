package com.motionflow.player.core.media.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the comparison: differences between two measured baselines, and nothing that could be read as
 * a verdict.
 *
 * The model has no score, no ranking and no "best pipeline" field, and these tests hold it to that as
 * much as to its arithmetic — a comparison that recommended one pipeline would be making a decision the
 * measurements cannot support.
 */
class PerformanceComparisonTest {

    @Test
    fun `a comparison with one baseline states that it has nothing to compare`() {
        val comparison = PerformanceComparison(native = native())

        assertFalse(comparison.hasBothBaselines)
        assertNull(comparison.firstFrameLatencyDeltaMs)
        assertNull(comparison.droppedFramesDelta)
        assertTrue(comparison.incomparable.contains(PerformanceMetric.FIRST_FRAME_LATENCY))
    }

    @Test
    fun `deltas point from native to the effect pipeline`() {
        val comparison = PerformanceComparison(native = native(), effectPipeline = effect())

        assertEquals("75 ms later", 75L, comparison.firstFrameLatencyDeltaMs)
        assertEquals("-2 frames", -2, comparison.renderedFramesDelta)
        assertEquals("5 more drops", 5, comparison.droppedFramesDelta)
        assertEquals(900L, comparison.cpuTimeDeltaMs)
    }

    @Test
    fun `a metric only one side measured is not compared`() {
        val comparison = PerformanceComparison(
            native = FramePerformanceSnapshot(firstFrameLatencyMs = 412L),
            effectPipeline = FramePerformanceSnapshot(firstFrameLatencyMs = 487L),
        )

        assertNull(comparison.droppedFramesDelta)
        assertNull(comparison.cpuTimeDeltaMs)
        assertEquals(75L, comparison.firstFrameLatencyDeltaMs)
        assertTrue(comparison.incomparable.contains(PerformanceMetric.DROPPED_FRAMES))
    }

    @Test
    fun `a processing offset is the effect pipeline's own, because the native path has no processor`() {
        val comparison = PerformanceComparison(native = native(), effectPipeline = effect())

        assertEquals(2.0, comparison.frameProcessingOffsetMs!!, 0.0001)
        assertNull(
            "there is nothing to subtract when one side has no frame processor",
            comparison.frameProcessingOffsetDeltaMs,
        )
    }

    @Test
    fun `a thermal difference is the most severe status each measurement saw`() {
        val comparison = PerformanceComparison(
            native = FramePerformanceSnapshot(thermalStatusPeak = 0),
            effectPipeline = FramePerformanceSnapshot(thermalStatusPeak = 2),
        )

        assertEquals(2, comparison.thermalStatusDifference)
    }

    @Test
    fun `the history keeps one measurement per pipeline`() {
        val history = PerformanceHistory.Empty
            .record(ProcessingPerformanceMode.NATIVE, native())
            .record(ProcessingPerformanceMode.EFFECT_PIPELINE, effect())

        assertTrue(history.comparison.hasBothBaselines)
        assertEquals(412L, history.native?.firstFrameLatencyMs)
        assertEquals(487L, history.effectPipeline?.firstFrameLatencyMs)
    }

    @Test
    fun `a failed run is not kept as a result`() {
        val history = PerformanceHistory.Empty.record(ProcessingPerformanceMode.FAILED, native())

        assertNull(history.native)
        assertNull(history.effectPipeline)
        assertFalse(history.comparison.hasBothBaselines)
    }

    @Test
    fun `an empty measurement is not kept as a result`() {
        val history = PerformanceHistory.Empty.record(ProcessingPerformanceMode.NATIVE, FramePerformanceSnapshot())

        assertNull(history.native)
    }

    @Test
    fun `recording a pipeline twice replaces its earlier measurement`() {
        val history = PerformanceHistory.Empty
            .record(ProcessingPerformanceMode.NATIVE, FramePerformanceSnapshot(firstFrameLatencyMs = 412L))
            .record(ProcessingPerformanceMode.NATIVE, FramePerformanceSnapshot(firstFrameLatencyMs = 500L))

        assertEquals(500L, history.native?.firstFrameLatencyMs)
    }

    @Test
    fun `the store publishes what it records, and can forget it`() {
        val store = PerformanceHistoryStore()

        store.record(ProcessingPerformanceMode.NATIVE, native())
        assertEquals(412L, store.history.value.native?.firstFrameLatencyMs)

        store.clear()
        assertNull(store.history.value.native)
    }

    private fun native() = FramePerformanceSnapshot(
        firstFrameLatencyMs = 412L,
        renderedFrames = 1_438,
        droppedFrames = 2,
        cpuTimeMs = 4_120L,
        processPssKb = 184_320L,
    )

    private fun effect() = FramePerformanceSnapshot(
        firstFrameLatencyMs = 487L,
        renderedFrames = 1_436,
        droppedFrames = 7,
        cpuTimeMs = 5_020L,
        processPssKb = 189_440L,
        frameProcessingOffsetTotalUs = 2_400_000,
        frameProcessingOffsetFrames = 1_200,
    )
}
