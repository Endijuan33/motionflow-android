package com.motionflow.player.core.media.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the conversion that turned a timestamp into a plausible-looking latency.
 *
 * Media3 reports the first frame's time as a `SystemClock.elapsedRealtime()` timestamp. On the hardware
 * where this was found it produced a reading of 326,012,320 ms — about three and a half days, which is
 * an uptime rather than a latency, and which no reader could tell apart from a slow device. These tests
 * exist so that cannot come back.
 */
class FirstFrameLatencyTest {

    private val sessionStart = 1_000_000L

    @Test
    fun `the latency is the difference between the render time and the session start`() {
        val latency = FirstFrameLatency.of(renderTimeMs = sessionStart + 412L, sessionStartMs = sessionStart)

        assertEquals(412L, latency)
    }

    @Test
    fun `an absolute elapsed-realtime timestamp cannot survive as a latency`() {
        // The shape of the defect: a device up for days reports a render time of days, and if that is
        // stored as a duration it is a number that looks like a measurement.
        val deviceUptimeMs = 326_012_320L

        val latency = FirstFrameLatency.of(renderTimeMs = deviceUptimeMs, sessionStartMs = sessionStart)

        assertEquals("the epoch is subtracted away", deviceUptimeMs - sessionStart, latency)
        assertTrue("and what remains is the latency itself", latency!! < deviceUptimeMs)
    }

    @Test
    fun `a frame that arrived before the session began is not this session's measurement`() {
        val latency = FirstFrameLatency.of(renderTimeMs = sessionStart - 5_000L, sessionStartMs = sessionStart)

        assertNull("not a negative latency, and not a zero: nothing was measured in this window", latency)
    }

    @Test
    fun `a first frame at the exact start of the session is a zero, which is a measured zero`() {
        val latency = FirstFrameLatency.of(renderTimeMs = sessionStart, sessionStartMs = sessionStart)

        assertEquals(0L, latency)
    }

    @Test
    fun `a missing reading on either side is not measured`() {
        assertNull(FirstFrameLatency.of(renderTimeMs = null, sessionStartMs = sessionStart))
        assertNull(FirstFrameLatency.of(renderTimeMs = sessionStart + 10, sessionStartMs = null))
        assertNull(FirstFrameLatency.of(renderTimeMs = null, sessionStartMs = null))
    }

    @Test
    fun `a latency is always a non-negative duration`() {
        val cases = listOf(
            null to null,
            null to sessionStart,
            sessionStart to null,
            sessionStart - 1 to sessionStart,
            sessionStart to sessionStart,
            sessionStart + 1 to sessionStart,
            sessionStart + 1_000_000 to sessionStart,
        )

        cases.forEach { (render, start) ->
            val latency = FirstFrameLatency.of(renderTimeMs = render, sessionStartMs = start)
            if (latency != null) {
                assertTrue("latency must never be negative, got $latency", latency >= 0)
            }
        }
    }

    @Test
    fun `the accumulator still refuses to carry a first frame into a new session`() {
        val accumulator = FramePerformanceAccumulator()
        accumulator.begin(FramePerformanceReadings.Empty)
        accumulator.onFirstFrame(FirstFrameLatency.of(sessionStart + 412L, sessionStart))
        accumulator.end()

        accumulator.begin(FramePerformanceReadings.Empty)

        assertNull(
            "a session cannot inherit the previous session's first frame",
            accumulator.snapshot(FramePerformanceReadings.Empty, 30_000).firstFrameLatencyMs,
        )
    }
}
