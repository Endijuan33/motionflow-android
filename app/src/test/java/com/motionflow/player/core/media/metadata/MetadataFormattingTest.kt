package com.motionflow.player.core.media.metadata

import com.motionflow.player.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the readouts the metadata panel renders.
 *
 * The rule these tests protect is that a missing measurement never becomes a number: every helper
 * answers `null` rather than zero, so the panel falls back to "Unknown" instead of "0 × 0".
 */
class MetadataFormattingTest {

    @Test
    fun `a resolution needs both dimensions`() {
        assertEquals("1920 × 1080", resolutionValue(1920, 1080))
        assertEquals("3840 × 2160", resolutionValue(3840, 2160))

        assertNull(resolutionValue(null, 1080))
        assertNull(resolutionValue(1920, null))
        assertNull(resolutionValue(0, 0))
        assertNull(resolutionValue(-1920, 1080))
    }

    @Test
    fun `a named rate is shown by name and an unnamed rate by measurement`() {
        assertEquals("23.976", frameRateValue(measured(23.976f)))
        assertEquals("24", frameRateValue(measured(24f)))
        assertEquals("29.97", frameRateValue(measured(29.97f)))
        assertEquals("59.94", frameRateValue(measured(59.94f)))

        assertEquals("15", frameRateValue(measured(15f)))
        assertEquals("12.5", frameRateValue(measured(12.5f)))
    }

    @Test
    fun `a rate that was never measured shows nothing`() {
        assertNull(frameRateValue(FrameRateInfo.Unknown))
    }

    @Test
    fun `a measurement just off a named rate is still shown as that rate`() {
        assertEquals("23.976", frameRateValue(measured(23.9762f)))
        assertEquals("29.97", frameRateValue(measured(29.9696f)))
    }

    @Test
    fun `video codecs prefer the container's MIME type over the codec string`() {
        assertEquals("H.264", videoCodecValue("video/avc", "avc1.640028"))
        assertEquals("H.265", videoCodecValue("video/hevc", null))
        assertEquals("VP9", videoCodecValue("video/x-vnd.on2.vp9", null))
        assertEquals("AV1", videoCodecValue("video/av01", null))
        assertEquals("MPEG-4", videoCodecValue("video/mp4v-es", null))

        assertEquals(
            "an unrecognised MIME type falls back to whatever the parser reported",
            "some.codec.string",
            videoCodecValue("video/x-unknown", "some.codec.string"),
        )
    }

    @Test
    fun `a codec with nothing to show shows nothing`() {
        assertNull(videoCodecValue(null, null))
        assertNull(videoCodecValue("video/x-unknown", null))
        assertNull(videoCodecValue(null, "   "))
    }

    @Test
    fun `audio codecs are named the same way`() {
        assertEquals("AAC", audioCodecValue("audio/mp4a-latm", null))
        assertEquals("FLAC", audioCodecValue("audio/flac", null))
        assertEquals("Opus", audioCodecValue("audio/opus", null))
        assertNull(audioCodecValue(null, null))
    }

    @Test
    fun `bitrates are shown in the unit that keeps them readable`() {
        val video = bitrateQuantity(8_400_000)!!
        assertEquals(R.string.metadata_unit_mbps, video.unitRes)
        assertEquals("8.4", video.amount)

        val audio = bitrateQuantity(192_000)!!
        assertEquals(R.string.metadata_unit_kbps, audio.unitRes)
        assertEquals("192", audio.amount)

        val exactlyOneMegabit = bitrateQuantity(1_000_000)!!
        assertEquals(R.string.metadata_unit_mbps, exactlyOneMegabit.unitRes)
        assertEquals("1.0", exactlyOneMegabit.amount)
    }

    @Test
    fun `an absent bitrate is absent`() {
        assertNull(bitrateQuantity(null))
        assertNull(bitrateQuantity(0))
        assertNull(bitrateQuantity(-1))
    }

    @Test
    fun `file sizes scale from kilobytes to gigabytes`() {
        assertEquals(R.string.metadata_unit_gb, fileSizeQuantity(1_450_000_000)!!.unitRes)
        assertEquals("1.45", fileSizeQuantity(1_450_000_000)!!.amount)

        assertEquals(R.string.metadata_unit_mb, fileSizeQuantity(12_000_000)!!.unitRes)
        assertEquals("12.0", fileSizeQuantity(12_000_000)!!.amount)

        assertEquals(R.string.metadata_unit_kb, fileSizeQuantity(500_000)!!.unitRes)
        assertEquals("500", fileSizeQuantity(500_000)!!.amount)

        assertNull(fileSizeQuantity(null))
        assertNull(fileSizeQuantity(0))
    }

    @Test
    fun `sample rates are shown in kilohertz`() {
        val rate = sampleRateQuantity(48_000)!!
        assertEquals(R.string.metadata_unit_khz, rate.unitRes)
        assertEquals("48.0", rate.amount)

        assertNull(sampleRateQuantity(null))
        assertNull(sampleRateQuantity(0))
    }

    @Test
    fun `durations are shown as a clock reading and nothing when unknown`() {
        assertEquals("10:34", durationValue(634_000L))
        assertEquals("0:05", durationValue(5_000L))
        assertEquals("1:02:05", durationValue(3_725_000L))

        assertNull(durationValue(null))
        assertNull(durationValue(0L))
        assertNull(durationValue(-1L))
    }

    @Test
    fun `colour characteristics use the names the industry uses`() {
        assertEquals("BT.601", colorSpaceLabel(VideoColorSpace.BT601))
        assertEquals("BT.709", colorSpaceLabel(VideoColorSpace.BT709))
        assertEquals("BT.2020", colorSpaceLabel(VideoColorSpace.BT2020))

        assertEquals("SDR", colorTransferLabel(VideoColorTransfer.SDR))
        assertEquals("HDR10", colorTransferLabel(VideoColorTransfer.HDR10))
        assertEquals("HLG", colorTransferLabel(VideoColorTransfer.HLG))
        assertEquals("Other", colorTransferLabel(VideoColorTransfer.OTHER))
    }

    private fun measured(fps: Float) = FrameRateInfo.measured(
        fps = fps,
        isVariableFrameRate = null,
        confidence = MetadataConfidence.HIGH,
    )
}
