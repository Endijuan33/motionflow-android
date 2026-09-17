package com.motionflow.player.core.media.refresh

import com.motionflow.player.core.media.metadata.FrameRateInfo
import com.motionflow.player.core.media.metadata.FrameRateSource
import com.motionflow.player.core.media.metadata.MetadataConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the matching policy: which display mode a video's cadence leads to, and when the answer is
 * "none of them".
 */
class RefreshRatePolicyTest {

    // -- no decision ------------------------------------------------------------------------

    @Test
    fun `an unknown frame rate asks for nothing`() {
        val decision = RefreshRatePolicy.decide(
            frameRate = FrameRateInfo.Unknown,
            capabilities = displayOf(60f, 120f, currentHz = 60f),
        )

        assertEquals(RefreshRateStatus.UNKNOWN, decision.status)
        assertEquals(RefreshRateReason.NO_FRAME_RATE, decision.reason)
        assertNull("nothing may be requested without a cadence", decision.targetMode)
        assertNull(decision.cadenceHz)
    }

    @Test
    fun `a frame rate that is not a usable number asks for nothing`() {
        listOf(0f, -24f, Float.NaN).forEach { nonsense ->
            val decision = RefreshRatePolicy.decide(measuredFps(nonsense), displayOf(60f))
            assertEquals("cadence $nonsense", RefreshRateStatus.UNKNOWN, decision.status)
            assertEquals(RefreshRateReason.NO_FRAME_RATE, decision.reason)
            assertNull(decision.targetMode)
        }
    }

    @Test
    fun `a variable frame rate leaves the display alone`() {
        val variable = FrameRateInfo.measured(
            fps = 23.976f,
            isVariableFrameRate = true,
            confidence = MetadataConfidence.MEDIUM,
        )

        val decision = RefreshRatePolicy.decide(variable, displayOf(60f, 24f, currentHz = 60f))

        assertEquals(RefreshRateStatus.UNKNOWN, decision.status)
        assertEquals(RefreshRateReason.VARIABLE_FRAME_RATE, decision.reason)
        assertNull(decision.targetMode)
    }

    @Test
    fun `a display that will not report its modes asks for nothing`() {
        val decision = RefreshRatePolicy.decide(measuredFps(24f), DisplayRefreshCapabilities.Unknown)

        assertEquals(RefreshRateStatus.UNKNOWN, decision.status)
        assertEquals(RefreshRateReason.CAPABILITIES_UNKNOWN, decision.reason)
        assertNull(decision.targetMode)
    }

    @Test
    fun `automatic selection being off asks for nothing`() {
        val decision = RefreshRatePolicy.decide(
            frameRate = measuredFps(24f),
            capabilities = displayOf(24f),
            automaticEnabled = false,
        )

        assertEquals(RefreshRateStatus.MANUAL, decision.status)
        assertEquals(RefreshRateReason.AUTOMATIC_DISABLED, decision.reason)
        assertNull(decision.targetMode)
    }

    // -- exact matching --------------------------------------------------------------------

    @Test
    fun `24 fps chooses a supported 24 Hz mode`() {
        val decision = RefreshRatePolicy.decide(measuredFps(24f), displayOf(60f, 24f, currentHz = 60f))

        assertEquals(RefreshRateStatus.MATCHED, decision.status)
        assertEquals(RefreshRateReason.EXACT_MODE, decision.reason)
        assertEquals(24f, decision.targetMode?.refreshRateHz ?: 0f, 0.001f)
        assertEquals(24f, decision.cadenceHz ?: 0f, 0.001f)
    }

    @Test
    fun `23_976 fps keeps its fraction and prefers the fractional sibling`() {
        val withBoth = RefreshRatePolicy.decide(measuredFps(23.976f), displayOf(60f, 24f, 23.976f))

        assertEquals(RefreshRateStatus.MATCHED, withBoth.status)
        assertEquals(
            "a 23.976 Hz mode is a closer match than a 24 Hz one",
            23.976f,
            withBoth.targetMode?.refreshRateHz ?: 0f,
            0.0001f,
        )
        assertEquals("the cadence is not rounded to 24", 23.976f, withBoth.cadenceHz ?: 0f, 0.0001f)
    }

    @Test
    fun `23_976 fps accepts the whole-number sibling when that is all there is`() {
        val decision = RefreshRatePolicy.decide(measuredFps(23.976f), displayOf(60f, 24f))

        assertEquals(RefreshRateStatus.MATCHED, decision.status)
        assertEquals(24f, decision.targetMode?.refreshRateHz ?: 0f, 0.001f)
        assertEquals(
            "the decision still records what the video actually is",
            23.976f,
            decision.cadenceHz ?: 0f,
            0.0001f,
        )
    }

    @Test
    fun `29_97 fps is not treated as 30 fps`() {
        val withBoth = RefreshRatePolicy.decide(measuredFps(29.97f), displayOf(60f, 30f, 29.97f))

        assertEquals(29.97f, withBoth.cadenceHz ?: 0f, 0.0001f)
        assertEquals(29.97f, withBoth.targetMode?.refreshRateHz ?: 0f, 0.0001f)

        val onlyWhole = RefreshRatePolicy.decide(measuredFps(29.97f), displayOf(60f, 30f))
        assertEquals(RefreshRateStatus.MATCHED, onlyWhole.status)
        assertEquals(30f, onlyWhole.targetMode?.refreshRateHz ?: 0f, 0.001f)
        assertEquals(
            "a 30 Hz mode is the right mode for 29.97 fps, but it does not make the video 30 fps",
            29.97f,
            onlyWhole.cadenceHz ?: 0f,
            0.0001f,
        )
    }

    @Test
    fun `59_94 fps keeps its fraction`() {
        val decision = RefreshRatePolicy.decide(measuredFps(59.94f), displayOf(60f, 59.94f, 30f))

        assertEquals(RefreshRateStatus.MATCHED, decision.status)
        assertEquals(59.94f, decision.cadenceHz ?: 0f, 0.0001f)
        assertEquals(59.94f, decision.targetMode?.refreshRateHz ?: 0f, 0.0001f)
    }

    // -- multiples -------------------------------------------------------------------------

    @Test
    fun `a mode at twice the cadence is a match, and the slower one wins`() {
        val decision = RefreshRatePolicy.decide(measuredFps(30f), displayOf(60f, 120f))

        assertEquals(RefreshRateStatus.MATCHED, decision.status)
        assertEquals(RefreshRateReason.INTEGER_MULTIPLE, decision.reason)
        assertEquals(
            "both 60 and 120 present 30 fps perfectly; the slower one costs less",
            60f,
            decision.targetMode?.refreshRateHz ?: 0f,
            0.001f,
        )
    }

    @Test
    fun `the highest number available is not chosen for being highest`() {
        val decision = RefreshRatePolicy.decide(measuredFps(24f), displayOf(24f, 120f, currentHz = 60f))

        assertEquals(RefreshRateStatus.MATCHED, decision.status)
        assertEquals(
            "24 fps at 24 Hz shows each frame once; 120 Hz would show it five times",
            24f,
            decision.targetMode?.refreshRateHz ?: 0f,
            0.001f,
        )
    }

    @Test
    fun `a display whose only multiple is far above the cadence uses the multiple`() {
        val decision = RefreshRatePolicy.decide(measuredFps(24f), displayOf(60f, 120f, 240f))

        assertEquals(RefreshRateStatus.MATCHED, decision.status)
        assertEquals(RefreshRateReason.INTEGER_MULTIPLE, decision.reason)
        assertEquals(120f, decision.targetMode?.refreshRateHz ?: 0f, 0.001f)
    }

    @Test
    fun `a rate that is not a whole multiple is not treated as one`() {
        // 60 Hz presents 24 fps as a repeating 2,3,2,3 pattern; that is judder, not a match.
        val decision = RefreshRatePolicy.decide(measuredFps(24f), displayOf(60f))

        assertEquals(RefreshRateStatus.FALLBACK, decision.status)
        assertEquals(RefreshRateReason.BEST_EFFORT, decision.reason)
    }

    // -- fallback --------------------------------------------------------------------------

    @Test
    fun `an unsupported exact rate falls back to the slowest rate that can still show every frame`() {
        val decision = RefreshRatePolicy.decide(measuredFps(24f), displayOf(50f, 60f))

        assertEquals(RefreshRateStatus.FALLBACK, decision.status)
        assertEquals(RefreshRateReason.BEST_EFFORT, decision.reason)
        assertEquals(50f, decision.targetMode?.refreshRateHz ?: 0f, 0.001f)
    }

    @Test
    fun `a display without 24 Hz never yields a mode it does not have`() {
        val capabilities = displayOf(60f, 90f, 120f)
        val decision = RefreshRatePolicy.decide(measuredFps(24f), capabilities)

        val target = decision.targetMode
        assertTrue("a decision must name one of the display's own modes", target != null)
        assertTrue(
            "the target must come from the capability list",
            capabilities.modes.contains(target),
        )
    }

    @Test
    fun `a display without 60 Hz does not assume 60 Hz exists`() {
        val decision = RefreshRatePolicy.decide(measuredFps(60f), displayOf(50f))

        assertEquals(RefreshRateStatus.UNSUPPORTED, decision.status)
        assertEquals(RefreshRateReason.NO_SUITABLE_MODE, decision.reason)
        assertNull(decision.targetMode)
    }

    @Test
    fun `no lower refresh rate is chosen when none can show every frame`() {
        val decision = RefreshRatePolicy.decide(measuredFps(60f), displayOf(24f, 30f))

        assertEquals(RefreshRateStatus.UNSUPPORTED, decision.status)
        assertNull(decision.targetMode)
    }

    // -- confidence ------------------------------------------------------------------------

    @Test
    fun `a header-derived rate still accepts an integer match`() {
        val decision = RefreshRatePolicy.decide(headerFps(24f), displayOf(60f, 24f))

        assertEquals(RefreshRateStatus.MATCHED, decision.status)
        assertEquals(24f, decision.targetMode?.refreshRateHz ?: 0f, 0.001f)
        assertEquals(MetadataConfidence.LOW, decision.confidence)
    }

    @Test
    fun `a header-derived rate declines a cadence that does not divide evenly`() {
        val decision = RefreshRatePolicy.decide(headerFps(24f), displayOf(50f, 60f))

        assertEquals(RefreshRateStatus.UNCHANGED, decision.status)
        assertEquals(RefreshRateReason.LOW_CONFIDENCE, decision.reason)
        assertNull(decision.targetMode)
    }

    @Test
    fun `an uncertain rate still reports an unsupported display as unsupported`() {
        val decision = RefreshRatePolicy.decide(headerFps(60f), displayOf(50f))

        assertEquals(RefreshRateStatus.UNSUPPORTED, decision.status)
        assertEquals(RefreshRateReason.NO_SUITABLE_MODE, decision.reason)
    }

    // -- stability -------------------------------------------------------------------------

    @Test
    fun `a display already at the right rate is left alone`() {
        val decision = RefreshRatePolicy.decide(measuredFps(24f), displayOf(60f, 24f, currentHz = 24f))

        assertEquals(RefreshRateStatus.UNCHANGED, decision.status)
        assertEquals(RefreshRateReason.ALREADY_SUITABLE, decision.reason)
        assertNull("no request is needed, so no mode is named", decision.targetMode)
    }

    @Test
    fun `a better mode is still chosen when the current one is merely adequate`() {
        // Currently at 60 Hz with a 24 Hz mode available: 60 Hz presents 24 fps as 2,3,2,3.
        val decision = RefreshRatePolicy.decide(measuredFps(24f), displayOf(60f, 24f, currentHz = 60f))

        assertEquals(RefreshRateStatus.MATCHED, decision.status)
        assertEquals(24f, decision.targetMode?.refreshRateHz ?: 0f, 0.001f)
    }

    @Test
    fun `a display already at a multiple is left alone`() {
        val decision = RefreshRatePolicy.decide(measuredFps(30f), displayOf(60f, 120f, currentHz = 60f))

        assertEquals(RefreshRateStatus.UNCHANGED, decision.status)
        assertEquals(RefreshRateReason.ALREADY_SUITABLE, decision.reason)
    }

    @Test
    fun `a cadence differing only in its last bits decides the same way`() {
        val capabilities = displayOf(60f, 24f, currentHz = 60f)

        val a = RefreshRatePolicy.decide(measuredFps(23.976f), capabilities)
        val b = RefreshRatePolicy.decide(measuredFps(23.976001f), capabilities)

        assertEquals(a.targetMode?.modeId, b.targetMode?.modeId)
        assertEquals(a.reason, b.reason)
        assertEquals(a.status, b.status)
    }

    // -- the documented tolerance ----------------------------------------------------------

    @Test
    fun `the tolerance separates fractional families from false multiples`() {
        // Same family, 0.1% apart: accepted.
        assertEquals(
            RefreshRateReason.EXACT_MODE,
            RefreshRatePolicy.decide(measuredFps(29.97f), displayOf(30f)).reason,
        )

        // 120 Hz is 0.5% from five times 23.976, which is outside the tolerance, so it is not a
        // whole multiple — 120.000 genuinely cannot present 23.976 evenly.
        val falseMultiple = RefreshRatePolicy.decide(measuredFps(23.976f), displayOf(120f))
        assertEquals(RefreshRateReason.BEST_EFFORT, falseMultiple.reason)

        // 119.88 Hz is exactly five times 23.976 and is accepted as a whole multiple.
        val trueMultiple = RefreshRatePolicy.decide(measuredFps(23.976f), displayOf(119.88f))
        assertEquals(RefreshRateReason.INTEGER_MULTIPLE, trueMultiple.reason)
        assertEquals(119.88f, trueMultiple.targetMode?.refreshRateHz ?: 0f, 0.001f)
    }

    private fun measuredFps(fps: Float): FrameRateInfo = FrameRateInfo.measured(
        fps = fps,
        isVariableFrameRate = null,
        confidence = MetadataConfidence.HIGH,
    )

    private fun headerFps(fps: Float): FrameRateInfo =
        FrameRateInfo.fromHeader(fps, FrameRateSource.CONTAINER)
}

/** Builds a display whose modes are 1920x1080, optionally marking one as the current mode. */
internal fun displayOf(
    vararg refreshRatesHz: Float,
    currentHz: Float? = null,
): DisplayRefreshCapabilities = DisplayRefreshCapabilities(
    displayId = 0,
    modes = refreshRatesHz.mapIndexed { index, refreshRateHz ->
        DisplayModeInfo(
            modeId = index + 1,
            width = 1920,
            height = 1080,
            refreshRateHz = refreshRateHz,
            isCurrent = refreshRateHz == currentHz,
        )
    },
)
