package com.motionflow.player.core.media.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the frame-rate vocabulary: which rates are recognised, and what an unknown rate looks like.
 */
class FrameRateInfoTest {

    @Test
    fun `fractional rates are told apart from the whole rates next to them`() {
        assertEquals(KnownFrameRate.FPS_23_976, KnownFrameRate.nearest(23.976f))
        assertEquals(KnownFrameRate.FPS_24, KnownFrameRate.nearest(24f))

        assertEquals(KnownFrameRate.FPS_29_97, KnownFrameRate.nearest(29.97f))
        assertEquals(KnownFrameRate.FPS_30, KnownFrameRate.nearest(30f))

        assertEquals(KnownFrameRate.FPS_59_94, KnownFrameRate.nearest(59.94f))
        assertEquals(KnownFrameRate.FPS_60, KnownFrameRate.nearest(60f))
    }

    @Test
    fun `a measurement close to a named rate is recognised as that rate`() {
        // What the timestamp probe actually produces for 23.976 fps content, and for 29.97.
        assertEquals(KnownFrameRate.FPS_23_976, KnownFrameRate.nearest(23.9762f))
        assertEquals(KnownFrameRate.FPS_29_97, KnownFrameRate.nearest(29.9696f))
        assertEquals(KnownFrameRate.FPS_59_94, KnownFrameRate.nearest(59.9412f))
    }

    @Test
    fun `a rate that is not one of the named ones is left unnamed`() {
        assertNull(KnownFrameRate.nearest(15f))
        assertNull(KnownFrameRate.nearest(12.5f))
        assertNull(KnownFrameRate.nearest(48f))
        assertNull(KnownFrameRate.nearest(0f))
    }

    @Test
    fun `the named rates carry the labels a person expects`() {
        assertEquals("23.976", KnownFrameRate.FPS_23_976.label)
        assertEquals("24", KnownFrameRate.FPS_24.label)
        assertEquals("29.97", KnownFrameRate.FPS_29_97.label)
        assertEquals("59.94", KnownFrameRate.FPS_59_94.label)
        assertEquals("25", KnownFrameRate.FPS_25.label)
        assertEquals("50", KnownFrameRate.FPS_50.label)
    }

    @Test
    fun `an unknown rate reports nothing rather than zero`() {
        val unknown = FrameRateInfo.Unknown

        assertNull(unknown.fps)
        assertNull(unknown.isVariableFrameRate)
        assertNull(unknown.knownRate)
        assertFalse(unknown.isKnown)
        assertEquals(FrameRateSource.UNKNOWN, unknown.source)
        assertEquals(MetadataConfidence.UNKNOWN, unknown.confidence)
    }

    @Test
    fun `a measured rate is attributed to sample timing and keeps its variability answer`() {
        val measured = FrameRateInfo.measured(
            fps = 23.976f,
            isVariableFrameRate = true,
            confidence = MetadataConfidence.MEDIUM,
        )

        assertTrue(measured.isKnown)
        assertEquals(FrameRateSource.SAMPLE_TIMING, measured.source)
        assertEquals(MetadataConfidence.MEDIUM, measured.confidence)
        assertEquals(true, measured.isVariableFrameRate)
        assertEquals(KnownFrameRate.FPS_23_976, measured.knownRate)
    }

    @Test
    fun `a rate copied from a header is reported with low confidence and no variability claim`() {
        val fromHeader = FrameRateInfo.fromHeader(24f, FrameRateSource.CONTAINER)

        assertEquals(24f, fromHeader.fps!!, 0f)
        assertEquals(FrameRateSource.CONTAINER, fromHeader.source)
        assertEquals(MetadataConfidence.LOW, fromHeader.confidence)
        assertNull(
            "a header cannot say whether a source is variable",
            fromHeader.isVariableFrameRate,
        )
    }
}
