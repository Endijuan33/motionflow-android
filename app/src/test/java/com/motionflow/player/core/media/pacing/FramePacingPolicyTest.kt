package com.motionflow.player.core.media.pacing

import com.motionflow.player.core.media.metadata.FrameRateInfo
import com.motionflow.player.core.media.metadata.MetadataConfidence
import com.motionflow.player.core.media.refresh.DisplayModeInfo
import com.motionflow.player.core.media.refresh.DisplayRefreshCapabilities
import com.motionflow.player.core.media.refresh.RefreshRatePolicy
import com.motionflow.player.core.media.refresh.RefreshRateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the cadence arithmetic: how a display's rate relates to a video's, and what the engine says
 * when it cannot relate them at all.
 */
class FramePacingPolicyTest {

    // -- the relationships the phase has to tell apart -------------------------------------

    @Test
    fun `a display at the video's own rate is native cadence`() {
        val diagnostics = analyse(fps = 24f, hz = 24f)

        assertEquals(FramePacingMode.NATIVE_CADENCE, diagnostics.mode)
        assertEquals(FramePacingReason.EXACT_CADENCE, diagnostics.reason)
        assertEquals(1f, diagnostics.ratio?.value ?: 0f, 0.0001f)
        assertEquals(1, diagnostics.ratio?.refreshesPerFrame)
        assertEquals(1, diagnostics.ratio?.frameCount)
        assertTrue("every frame is held once", diagnostics.ratio?.isEven ?: false)
        assertEquals("a whole ratio has no pattern to name", "", diagnostics.ratio?.patternLabel)
    }

    @Test
    fun `a display at twice the video's rate is an integer multiple`() {
        val diagnostics = analyse(fps = 24f, hz = 48f)

        assertEquals(FramePacingMode.INTEGER_MULTIPLE, diagnostics.mode)
        assertEquals(FramePacingReason.WHOLE_MULTIPLE, diagnostics.reason)
        assertEquals(2, diagnostics.ratio?.refreshesPerFrame)
        assertEquals(1, diagnostics.ratio?.frameCount)
        assertTrue(diagnostics.ratio?.isEven ?: false)
    }

    @Test
    fun `24 fps on a 60 Hz display is the 3 to 2 pattern`() {
        val diagnostics = analyse(fps = 24f, hz = 60f)

        assertEquals(FramePacingMode.CADENCE_MISMATCH, diagnostics.mode)
        assertEquals(FramePacingReason.SHORT_REPEATING_PATTERN, diagnostics.reason)
        assertEquals(2.5f, diagnostics.ratio?.value ?: 0f, 0.0001f)
        assertEquals(5, diagnostics.ratio?.refreshesPerFrame)
        assertEquals(2, diagnostics.ratio?.frameCount)
        assertEquals("3:2", diagnostics.ratio?.patternLabel)
        assertFalse("frames are held for 3 and then 2 refreshes", diagnostics.ratio?.isEven ?: true)
    }

    @Test
    fun `23_976 fps on a 59_94 Hz display is the same five to two relationship`() {
        val diagnostics = analyse(fps = 23.976f, hz = 59.94f)

        assertEquals(FramePacingMode.CADENCE_MISMATCH, diagnostics.mode)
        assertEquals(FramePacingReason.SHORT_REPEATING_PATTERN, diagnostics.reason)
        assertEquals(2.5f, diagnostics.ratio?.value ?: 0f, 0.0001f)
        assertEquals(
            "this is the same ratio as 24 fps on 60 Hz: 59.94 is exactly 2.5 times 23.976",
            "3:2",
            diagnostics.ratio?.patternLabel,
        )
        assertEquals(23.976f, diagnostics.video.fps ?: 0f, 0.0001f)
        assertEquals(59.94f, diagnostics.display.refreshRateHz ?: 0f, 0.0001f)
    }

    @Test
    fun `29_97 fps on a 59_94 Hz display is an exact double`() {
        val diagnostics = analyse(fps = 29.97f, hz = 59.94f)

        assertEquals(FramePacingMode.INTEGER_MULTIPLE, diagnostics.mode)
        assertEquals(FramePacingReason.WHOLE_MULTIPLE, diagnostics.reason)
        assertEquals(2, diagnostics.ratio?.refreshesPerFrame)
        assertEquals(1, diagnostics.ratio?.frameCount)
        assertTrue(diagnostics.ratio?.isEven ?: false)
    }

    @Test
    fun `30 fps on a 60 Hz display is an integer multiple`() {
        val diagnostics = analyse(fps = 30f, hz = 60f)

        assertEquals(FramePacingMode.INTEGER_MULTIPLE, diagnostics.mode)
        assertEquals(2, diagnostics.ratio?.refreshesPerFrame)
    }

    @Test
    fun `25 fps on a 60 Hz display is a longer repeating pattern`() {
        val diagnostics = analyse(fps = 25f, hz = 60f)

        assertEquals(FramePacingMode.CADENCE_MISMATCH, diagnostics.mode)
        assertEquals(FramePacingReason.LONG_REPEATING_PATTERN, diagnostics.reason)
        assertEquals(
            "12 refreshes for 5 frames, distributed as evenly as integers allow",
            5,
            diagnostics.ratio?.frameCount,
        )
        assertEquals(12, diagnostics.ratio?.refreshesPerFrame)
        assertEquals("3:3:2:2:2", diagnostics.ratio?.patternLabel)
    }

    @Test
    fun `60 fps on a 120 Hz display is an integer multiple`() {
        val diagnostics = analyse(fps = 60f, hz = 120f)

        assertEquals(FramePacingMode.INTEGER_MULTIPLE, diagnostics.mode)
        assertEquals(2, diagnostics.ratio?.refreshesPerFrame)
    }

    // -- what cannot be analysed ----------------------------------------------------------

    @Test
    fun `an unknown frame rate is not analysed`() {
        val diagnostics = analyse(fps = null, hz = 60f)

        assertEquals(FramePacingMode.UNKNOWN, diagnostics.mode)
        assertEquals(FramePacingReason.NO_FRAME_RATE, diagnostics.reason)
        assertNull(diagnostics.ratio)
    }

    @Test
    fun `an unknown display rate is not analysed`() {
        val diagnostics = analyse(fps = 24f, hz = null)

        assertEquals(FramePacingMode.UNKNOWN, diagnostics.mode)
        assertEquals(FramePacingReason.NO_DISPLAY_RATE, diagnostics.reason)
        assertNull(diagnostics.ratio)
    }

    @Test
    fun `a nonsense rate is treated as no rate`() {
        listOf(0f, -24f, Float.NaN, Float.POSITIVE_INFINITY).forEach { nonsense ->
            assertEquals(
                "cadence $nonsense",
                FramePacingReason.NO_FRAME_RATE,
                analyse(fps = nonsense, hz = 60f).reason,
            )
        }
    }

    @Test
    fun `a variable frame rate has no fixed pattern to analyse`() {
        val diagnostics = analyse(fps = 23.976f, hz = 60f, isVariable = true)

        assertEquals(FramePacingMode.UNKNOWN, diagnostics.mode)
        assertEquals(FramePacingReason.VARIABLE_FRAME_RATE, diagnostics.reason)
        assertNull(diagnostics.ratio)
    }

    @Test
    fun `a display slower than the source is unsupported`() {
        val diagnostics = analyse(fps = 60f, hz = 50f)

        assertEquals(FramePacingMode.UNSUPPORTED, diagnostics.mode)
        assertEquals(FramePacingReason.DISPLAY_TOO_SLOW, diagnostics.reason)
        assertEquals(0.8333f, diagnostics.ratio?.value ?: 0f, 0.0001f)
        assertFalse("no pattern can show every frame here", diagnostics.ratio?.isResolved ?: true)
    }

    // -- tolerance ------------------------------------------------------------------------

    @Test
    fun `a rate a hair away from native is still native`() {
        val diagnostics = analyse(fps = 24f, hz = 24.024f)

        assertEquals(FramePacingMode.NATIVE_CADENCE, diagnostics.mode)
    }

    @Test
    fun `a rate beyond the tolerance has no describable pattern`() {
        val diagnostics = analyse(fps = 24f, hz = 24.096f)

        assertEquals(FramePacingMode.CADENCE_MISMATCH, diagnostics.mode)
        assertEquals(FramePacingReason.UNRESOLVED_PATTERN, diagnostics.reason)
        assertFalse(diagnostics.ratio?.isResolved ?: true)
    }

    @Test
    fun `the two cadence families are not mistaken for each other`() {
        // 24.000 fps on a 59.94 Hz display is 2.4975, which is 0.1% away from five to two and slips
        // a refresh every few seconds. It must not be described as the 3:2 pattern.
        val wholeOnNtsc = analyse(fps = 24f, hz = 59.94f)
        assertEquals(FramePacingMode.CADENCE_MISMATCH, wholeOnNtsc.mode)
        assertEquals(FramePacingReason.UNRESOLVED_PATTERN, wholeOnNtsc.reason)

        // And the mirror image: 23.976 fps on a 60.000 Hz display.
        val ntscOnWhole = analyse(fps = 23.976f, hz = 60f)
        assertEquals(FramePacingReason.UNRESOLVED_PATTERN, ntscOnWhole.reason)
    }

    @Test
    fun `the tolerance is the same one the refresh engine uses`() {
        // The two engines look at the same two rates; a pair the refresh engine calls a whole
        // multiple must not be called a mismatch by the pacing engine.
        assertEquals(RefreshRatePolicy.RATIO_TOLERANCE, FramePacingPolicy.RATIO_TOLERANCE)

        listOf(30f to 60f, 23.976f to 119.88f, 25f to 50f).forEach { (fps, hz) ->
            val refreshStatus = RefreshRatePolicy.decide(
                frameRate = FrameRateInfo.measured(
                    fps = fps,
                    isVariableFrameRate = null,
                    confidence = MetadataConfidence.HIGH,
                ),
                capabilities = DisplayRefreshCapabilities(
                    displayId = 0,
                    modes = listOf(
                        DisplayModeInfo(
                            modeId = 1,
                            width = 1920,
                            height = 1080,
                            refreshRateHz = hz,
                            isCurrent = true,
                        ),
                    ),
                ),
            )
            val pacingMode = analyse(fps = fps, hz = hz).mode

            assertEquals("$fps on $hz", RefreshRateStatus.UNCHANGED, refreshStatus.status)
            assertEquals("$fps on $hz", FramePacingMode.INTEGER_MULTIPLE, pacingMode)
        }
    }

    // -- metadata quality -----------------------------------------------------------------

    @Test
    fun `a header rate is still classified but not trusted`() {
        val diagnostics = analyse(fps = 24f, hz = 60f, confidence = MetadataConfidence.LOW)

        assertEquals(FramePacingMode.CADENCE_MISMATCH, diagnostics.mode)
        assertEquals(MetadataConfidence.LOW, diagnostics.confidence)
        assertFalse("a rounded rate is not something to act on", diagnostics.toDecision().isReliable)
    }

    @Test
    fun `a measured rate is trusted`() {
        assertEquals(
            true,
            analyse(fps = 24f, hz = 60f, confidence = MetadataConfidence.HIGH).toDecision().isReliable,
        )
    }

    // -- the decision ---------------------------------------------------------------------

    @Test
    fun `no decision claims pacing was applied`() {
        listOf(
            24f to 24f,
            24f to 60f,
            25f to 60f,
            60f to 50f,
        ).forEach { (fps, hz) ->
            val decision = analyse(fps = fps, hz = hz).toDecision()

            assertFalse("$fps on $hz must not claim applied pacing", decision.isApplied)
            assertEquals(FramePacingMechanism.NONE, decision.mechanism)
        }
        assertFalse(analyse(fps = null, hz = null).toDecision().isApplied)
    }

    @Test
    fun `the decision names what could not be even`() {
        assertTrue(analyse(fps = 24f, hz = 60f).toDecision().isMismatched)
        assertTrue(analyse(fps = 60f, hz = 50f).toDecision().isMismatched)
        assertFalse(analyse(fps = 24f, hz = 48f).toDecision().isMismatched)
        assertFalse(analyse(fps = 24f, hz = 24f).toDecision().isMismatched)
    }

    private fun analyse(
        fps: Float?,
        hz: Float?,
        confidence: MetadataConfidence = MetadataConfidence.HIGH,
        isVariable: Boolean? = null,
    ): FramePacingDiagnostics = FramePacingPolicy.analyse(
        video = VideoCadence(fps = fps, confidence = confidence, isVariableFrameRate = isVariable),
        display = DisplayCadence(refreshRateHz = hz),
    )
}
