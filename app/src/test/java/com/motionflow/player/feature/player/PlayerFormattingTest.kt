package com.motionflow.player.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the readouts on the player surface.
 *
 * These are asserted exactly because they are locale independent by construction: a duration that
 * changes shape with the device language would break the control deck's layout.
 */
class PlayerFormattingTest {

    @Test
    fun `positions under an hour read as minutes and seconds`() {
        assertEquals("0:00", formatPosition(0L))
        assertEquals("0:05", formatPosition(5_000L))
        assertEquals("1:05", formatPosition(65_000L))
        assertEquals("9:59", formatPosition(599_000L))
    }

    @Test
    fun `positions past an hour gain an hours field`() {
        assertEquals("1:00:00", formatPosition(3_600_000L))
        assertEquals("1:02:05", formatPosition(3_725_000L))
        assertEquals("10:00:00", formatPosition(36_000_000L))
    }

    @Test
    fun `an unknown or negative position reads as the start`() {
        assertEquals("0:00", formatPosition(-1L))
        assertEquals("0:00", formatPosition(Long.MIN_VALUE))
    }

    @Test
    fun `speeds are shown without trailing zeros`() {
        assertEquals("0.5", formatPlaybackSpeed(0.5f))
        assertEquals("0.75", formatPlaybackSpeed(0.75f))
        assertEquals("1", formatPlaybackSpeed(1.0f))
        assertEquals("1.25", formatPlaybackSpeed(1.25f))
        assertEquals("1.5", formatPlaybackSpeed(1.5f))
        assertEquals("2", formatPlaybackSpeed(2.0f))
    }
}
