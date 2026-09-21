package com.motionflow.player.core.media.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the arithmetic: what a session measures, and what it refuses to claim.
 *
 * Every test here is exact, because the accumulator has no clock and no I/O — the readings are handed to
 * it, so a duration is the difference between two numbers a test chose rather than however long the test
 * took.
 */
class FramePerformanceAccumulatorTest {

    @Test
    fun `a session reports nothing before it has measured anything`() {
        val accumulator = FramePerformanceAccumulator()

        val snapshot = accumulator.snapshot(FramePerformanceReadings.Empty, measurementDurationMs = null)

        assertTrue(snapshot.isEmpty)
        assertNull(snapshot.renderedFrames)
        assertNull(snapshot.droppedFrames)
        assertNull(snapshot.firstFrameLatencyMs)
        assertFalse("an empty snapshot is not a measurement", snapshot.unavailable.isEmpty())
    }

    @Test
    fun `frame counts are the difference between the closing reading and the opening one`() {
        val accumulator = FramePerformanceAccumulator()
        accumulator.begin(FramePerformanceReadings(renderedFrames = 1_000, droppedFrames = 3))

        val snapshot = accumulator.snapshot(
            FramePerformanceReadings(renderedFrames = 2_438, droppedFrames = 5),
            measurementDurationMs = 30_000,
        )

        assertEquals(1_438, snapshot.renderedFrames)
        assertEquals(2, snapshot.droppedFrames)
        assertEquals(30_000L, snapshot.measurementDurationMs)
    }

    @Test
    fun `a renderer reset mid-session is reported as unmeasured, never as a negative count`() {
        val accumulator = FramePerformanceAccumulator()
        accumulator.begin(FramePerformanceReadings(renderedFrames = 5_000, droppedFrames = 10))

        val snapshot = accumulator.snapshot(
            FramePerformanceReadings(renderedFrames = 12, droppedFrames = 0),
            measurementDurationMs = 1_000,
        )

        assertNull("a lost baseline is not a count", snapshot.renderedFrames)
        assertNull(snapshot.droppedFrames)
        assertTrue(snapshot.unavailable.contains(PerformanceMetric.RENDERED_FRAMES))
    }

    @Test
    fun `the first frame's latency is captured once and kept`() {
        val accumulator = FramePerformanceAccumulator()
        accumulator.begin(FramePerformanceReadings.Empty)

        accumulator.onFirstFrame(487L)
        accumulator.onFirstFrame(1_200L)

        assertEquals(487L, accumulator.snapshot(FramePerformanceReadings.Empty, 30_000).firstFrameLatencyMs)
    }

    @Test
    fun `a first frame reported before the session began is ignored`() {
        val accumulator = FramePerformanceAccumulator()
        accumulator.begin(FramePerformanceReadings.Empty)
        accumulator.end()

        accumulator.onFirstFrame(120L)

        assertNull(accumulator.snapshot(FramePerformanceReadings.Empty, 1_000).firstFrameLatencyMs)
    }

    @Test
    fun `the processing offset is available only when a processor reported one`() {
        val native = FramePerformanceAccumulator()
        native.begin(FramePerformanceReadings.Empty)
        val nativeSnapshot = native.snapshot(FramePerformanceReadings.Empty, 30_000)

        val effect = FramePerformanceAccumulator()
        effect.begin(FramePerformanceReadings.Empty)
        effect.onFrameProcessingOffset(totalProcessingOffsetUs = 2_400_000, frameCount = 1_200)
        val effectSnapshot = effect.snapshot(FramePerformanceReadings.Empty, 30_000)

        assertNull("the native pipeline has no frame processor at all", nativeSnapshot.averageFrameProcessingOffsetMs)
        assertTrue(nativeSnapshot.unavailable.contains(PerformanceMetric.FRAME_PROCESSING_OFFSET))
        assertEquals(2.0, effectSnapshot.averageFrameProcessingOffsetMs!!, 0.0001)
    }

    @Test
    fun `an offset with no frame count cannot become a mean`() {
        val accumulator = FramePerformanceAccumulator()
        accumulator.begin(FramePerformanceReadings.Empty)

        accumulator.onFrameProcessingOffset(totalProcessingOffsetUs = 2_400_000, frameCount = 0)
        val snapshot = accumulator.snapshot(FramePerformanceReadings.Empty, 30_000)

        assertNull("dividing by a count that was not reported would invent the denominator", snapshot.averageFrameProcessingOffsetMs)
    }

    @Test
    fun `a decoder's initialisation cost is recorded, and an unknown one stays unknown`() {
        val accumulator = FramePerformanceAccumulator()
        accumulator.begin(FramePerformanceReadings.Empty)
        accumulator.onDecoderInitialized(120L)
        accumulator.onDecoderInitialized(null)

        assertEquals(120L, accumulator.snapshot(FramePerformanceReadings.Empty, 1_000).decoderInitializationMs)
    }

    @Test
    fun `a video size of zero is not a size`() {
        val accumulator = FramePerformanceAccumulator()
        accumulator.begin(FramePerformanceReadings.Empty)

        accumulator.onVideoSize(0, 0)

        assertFalse(accumulator.snapshot(FramePerformanceReadings.Empty, 1_000).hasVideoSize)
    }

    @Test
    fun `the most severe thermal status seen is kept, from the platform's own ordinals`() {
        val accumulator = FramePerformanceAccumulator()
        accumulator.begin(FramePerformanceReadings(thermalStatus = 1))

        accumulator.onThermalStatus(3)
        accumulator.onThermalStatus(2)
        val snapshot = accumulator.snapshot(FramePerformanceReadings(thermalStatus = 2), 30_000)

        assertEquals(1, snapshot.thermalStatusAtStart)
        assertEquals(2, snapshot.thermalStatusAtEnd)
        assertEquals(3, snapshot.thermalStatusPeak)
    }

    @Test
    fun `process CPU time is a delta, while memory is a reading at the end`() {
        val accumulator = FramePerformanceAccumulator()
        accumulator.begin(FramePerformanceReadings(cpuTimeMs = 10_000, processPssKb = 150_000))

        val snapshot = accumulator.snapshot(
            FramePerformanceReadings(cpuTimeMs = 14_120, processPssKb = 184_320),
            measurementDurationMs = 30_000,
        )

        assertEquals(4_120L, snapshot.cpuTimeMs)
        assertEquals("resident size is a reading, not a usage", 184_320L, snapshot.processPssKb)
    }

    @Test
    fun `every metric a session did not measure is named`() {
        val accumulator = FramePerformanceAccumulator()
        accumulator.begin(FramePerformanceReadings.Empty)

        val unavailable = accumulator.snapshot(FramePerformanceReadings.Empty, null).unavailable

        assertTrue(unavailable.contains(PerformanceMetric.RENDERED_FRAMES))
        assertTrue(unavailable.contains(PerformanceMetric.DROPPED_FRAMES))
        assertTrue(unavailable.contains(PerformanceMetric.FRAME_PROCESSING_OFFSET))
        assertTrue(unavailable.contains(PerformanceMetric.FIRST_FRAME_LATENCY))
        assertTrue(unavailable.contains(PerformanceMetric.DECODER_INITIALIZATION))
        assertTrue(unavailable.contains(PerformanceMetric.PLAYBACK_POSITION))
        assertTrue(unavailable.contains(PerformanceMetric.MEASUREMENT_DURATION))
        assertTrue(unavailable.contains(PerformanceMetric.VIDEO_SIZE))
        assertTrue(unavailable.contains(PerformanceMetric.CPU_TIME))
        assertTrue(unavailable.contains(PerformanceMetric.PROCESS_PSS))
        assertTrue(unavailable.contains(PerformanceMetric.HEAP_USED))
        assertTrue(unavailable.contains(PerformanceMetric.THERMAL_STATUS))
    }

    @Test
    fun `a second session starts from nothing, not from the first one's numbers`() {
        val accumulator = FramePerformanceAccumulator()
        accumulator.begin(FramePerformanceReadings.Empty)
        accumulator.onFirstFrame(412L)
        accumulator.snapshot(FramePerformanceReadings(renderedFrames = 100), 10_000)
        accumulator.end()

        accumulator.begin(FramePerformanceReadings(renderedFrames = 100))
        val second = accumulator.snapshot(FramePerformanceReadings(renderedFrames = 150), 10_000)

        assertNull("a stale latency must not be carried into a new window", second.firstFrameLatencyMs)
        assertEquals(50, second.renderedFrames)
    }
}
