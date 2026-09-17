package com.motionflow.player.core.media.metadata

import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the arithmetic that turns sample intervals into a frame rate.
 *
 * This is where the phase's most important claim lives: that a fractional rate survives the trip
 * from a container to the UI, and that variability is only ever reported when it was observed.
 */
class FrameTimingAnalysisTest {

    @Test
    fun `uniform intervals produce the rate they encode`() {
        val timing = requireNotNull(analyseFrameTiming(uniformIntervals(120, 41_708L)))

        assertEquals(23.976f, timing.fps, 0.01f)
        assertEquals(120, timing.intervalCount)
        assertFalse(timing.isVariableInWindow)
        assertEquals("23.976", KnownFrameRate.nearest(timing.fps)?.label)
    }

    @Test
    fun `a millisecond-dithered timeline still recovers the fractional rate`() {
        // Containers with millisecond precision cannot express a 41.708 ms interval exactly; they
        // emit alternating 41 ms and 42 ms intervals. Averaging recovers 23.976 where a single
        // interval would report 24 or 23, and the dither must not be mistaken for variability.
        val intervals = ditheredMsIntervals(frameCount = 240, fps = 23.976)

        val timing = requireNotNull(analyseFrameTiming(intervals))

        assertEquals(23.976f, timing.fps, 0.01f)
        assertFalse("dithered timestamps are not a variable frame rate", timing.isVariableInWindow)
        assertEquals("23.976", KnownFrameRate.nearest(timing.fps)?.label)
    }

    @Test
    fun `a 29_97 source is not rounded to 30`() {
        val timing = requireNotNull(analyseFrameTiming(ditheredMsIntervals(frameCount = 240, fps = 29.97)))

        assertEquals(29.97f, timing.fps, 0.01f)
        assertEquals("29.97", KnownFrameRate.nearest(timing.fps)?.label)
    }

    @Test
    fun `mixed frame rates in one window are reported as variable`() {
        val intervals = List(60) { 41_708L } + List(60) { 83_416L }

        val timing = requireNotNull(analyseFrameTiming(intervals))

        assertTrue("a window holding two different cadences is variable", timing.isVariableInWindow)
    }

    @Test
    fun `a single stream discontinuity does not move the rate or fake variability`() {
        val intervals = List(100) { 41_708L }.toMutableList().apply { add(4, 5_000_000L) }

        val timing = requireNotNull(analyseFrameTiming(intervals))

        assertEquals(23.976f, timing.fps, 0.01f)
        assertFalse("a gap is not a changed cadence", timing.isVariableInWindow)
    }

    @Test
    fun `too little evidence produces no rate at all`() {
        assertNull("no intervals", analyseFrameTiming(emptyList()))
        assertNull("a handful of intervals", analyseFrameTiming(uniformIntervals(4, 41_708L)))
    }

    @Test
    fun `degenerate intervals are refused rather than dividing by zero`() {
        assertNull("zero-length intervals", analyseFrameTiming(List(30) { 0L }))
        assertNull("negative intervals", analyseFrameTiming(List(30) { -1_000L }))
    }

    @Test
    fun `an unusually slow but steady rate is still a rate`() {
        val timing = requireNotNull(analyseFrameTiming(uniformIntervals(30, 200_000L)))

        assertEquals(5f, timing.fps, 0.01f)
        assertNull(KnownFrameRate.nearest(timing.fps))
    }

    private fun uniformIntervals(count: Int, intervalUs: Long): List<Long> = List(count) { intervalUs }

    /** Builds intervals from a timeline rounded to whole milliseconds, as a coarse container does. */
    private fun ditheredMsIntervals(frameCount: Int, fps: Double): List<Long> {
        val stepUs = 1_000_000.0 / fps
        val intervals = ArrayList<Long>(frameCount)
        var previousRoundedUs = 0L

        for (index in 1..frameCount) {
            val roundedUs = (index * stepUs / 1_000.0).roundToLong() * 1_000L
            intervals += roundedUs - previousRoundedUs
            previousRoundedUs = roundedUs
        }
        return intervals
    }
}
