package com.motionflow.player.core.media.refresh

import com.motionflow.player.core.media.metadata.FrameRateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the capability model: what it reports when the platform is forthcoming, and what it
 * reports when it is not.
 */
class DisplayRefreshCapabilitiesTest {

    @Test
    fun `a display that reported nothing is unknown, not 60 Hz`() {
        val unknown = DisplayRefreshCapabilities.Unknown

        assertFalse(unknown.isKnown)
        assertNull(unknown.currentMode)
        assertNull(unknown.maximumRefreshRateHz)
        assertTrue(unknown.supportedRefreshRatesHz.isEmpty())
        assertNull(unknown.displayId)
    }

    @Test
    fun `an empty mode list is treated as unknown rather than as a default`() {
        val empty = DisplayRefreshCapabilities(displayId = 3)

        assertFalse(empty.isKnown)
        assertNull(empty.maximumRefreshRateHz)
    }

    @Test
    fun `the current mode is the one the platform marks`() {
        val capabilities = displayOf(60f, 24f, 120f, currentHz = 24f)

        val current = capabilities.currentMode

        assertEquals(24f, current?.refreshRateHz ?: 0f, 0.001f)
        assertEquals(2, current?.modeId)
        assertTrue(capabilities.isKnown)
    }

    @Test
    fun `a display with no marked current mode still reports its modes`() {
        val capabilities = displayOf(60f, 120f)

        assertNull(capabilities.currentMode)
        assertTrue(capabilities.isKnown)
        assertEquals(120f, capabilities.maximumRefreshRateHz ?: 0f, 0.001f)
    }

    @Test
    fun `supported rates are distinct and ascending`() {
        val capabilities = DisplayRefreshCapabilities(
            displayId = 0,
            modes = listOf(
                DisplayModeInfo(modeId = 1, width = 1920, height = 1080, refreshRateHz = 60f),
                DisplayModeInfo(modeId = 2, width = 1920, height = 1080, refreshRateHz = 60f),
                DisplayModeInfo(modeId = 3, width = 1280, height = 720, refreshRateHz = 120f),
                DisplayModeInfo(modeId = 4, width = 3840, height = 2160, refreshRateHz = 24f),
            ),
        )

        assertEquals(listOf(24f, 60f, 120f), capabilities.supportedRefreshRatesHz)
        assertEquals(120f, capabilities.maximumRefreshRateHz ?: 0f, 0.001f)
    }

    @Test
    fun `modes with no usable rate or size are ignored`() {
        val junk = DisplayModeInfo(modeId = 1, width = 1920, height = 1080, refreshRateHz = 0f)
        val sizeless = DisplayModeInfo(modeId = 2, width = 0, height = 0, refreshRateHz = 60f)

        assertFalse(junk.isUsable)
        assertFalse(sizeless.isUsable)

        val capabilities = DisplayRefreshCapabilities(displayId = 0, modes = listOf(junk, sizeless))
        assertFalse("a display offering only unusable modes is unknown", capabilities.isKnown)
        assertNull(capabilities.maximumRefreshRateHz)
    }

    @Test
    fun `a decision that requests nothing says so`() {
        val decision = RefreshRateDecision.Unknown

        assertFalse(decision.requiresChange)
        assertNull(decision.targetMode)
        assertNull(decision.cadenceHz)
        assertEquals(RefreshRateStatus.UNKNOWN, decision.status)
    }

    @Test
    fun `a decision that names a mode says so`() {
        val decision = RefreshRateDecision(
            status = RefreshRateStatus.MATCHED,
            reason = RefreshRateReason.EXACT_MODE,
            targetMode = DisplayModeInfo(modeId = 4, width = 1920, height = 1080, refreshRateHz = 24f),
            cadenceHz = 23.976f,
        )

        assertTrue(decision.requiresChange)
        assertEquals(4, decision.targetMode?.modeId)
    }

    @Test
    fun `state reports the display rate only from a current mode`() {
        val withoutCurrent = RefreshRateState(
            frameRate = FrameRateInfo.Unknown,
            capabilities = displayOf(60f),
        )
        assertNull(withoutCurrent.displayRefreshRateHz)

        val withCurrent = withoutCurrent.copy(capabilities = displayOf(60f, currentHz = 60f))
        assertEquals(60f, withCurrent.displayRefreshRateHz ?: 0f, 0.001f)
    }
}
