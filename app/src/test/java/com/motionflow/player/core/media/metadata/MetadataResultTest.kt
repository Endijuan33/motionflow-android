package com.motionflow.player.core.media.metadata

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the metadata model's defaults and the classification of read failures.
 */
class MetadataResultTest {

    @Test
    fun `a source with nothing known reports nothing known`() {
        val metadata = VideoMetadata(sourceUri = "content://sample/clip.mp4")

        assertNull(metadata.title)
        assertNull(metadata.mimeType)
        assertNull(metadata.durationMs)
        assertNull(metadata.fileSizeBytes)
        assertNull(metadata.video)
        assertNull(metadata.audio)
        assertFalse(metadata.hasAudio)
    }

    @Test
    fun `a video track starts with an unknown frame rate rather than a zero`() {
        val video = VideoTrackMetadata()

        assertEquals(FrameRateInfo.Unknown, video.frameRate)
        assertFalse(video.frameRate.isKnown)
        assertNull(video.width)
        assertNull(video.rotationDegrees)
        assertNull(video.color)
    }

    @Test
    fun `hdr is decided by the transfer characteristic and unknown when it is absent`() {
        assertNull(HdrInfo(colorSpace = VideoColorSpace.BT2020).isHdr)
        assertNull(HdrInfo(bitDepth = 10).isHdr)

        assertEquals(true, HdrInfo(transfer = VideoColorTransfer.HDR10).isHdr)
        assertEquals(true, HdrInfo(transfer = VideoColorTransfer.HLG).isHdr)
        assertEquals(false, HdrInfo(transfer = VideoColorTransfer.SDR).isHdr)
    }

    @Test
    fun `an unclassified failure still produces a presentable error`() {
        assertEquals(MetadataError.UNKNOWN, MetadataError.from(RuntimeException("boom")))
        assertEquals(MetadataError.UNKNOWN, MetadataError.from(IllegalStateException("boom")))
    }

    @Test
    fun `failures are classified by what went wrong`() {
        assertEquals(MetadataError.PERMISSION_DENIED, MetadataError.from(SecurityException()))
        assertEquals(MetadataError.EXTRACTION_FAILED, MetadataError.from(FileNotFoundException()))
        assertEquals(MetadataError.EXTRACTION_FAILED, MetadataError.from(IOException()))
        assertEquals(MetadataError.INVALID_URI, MetadataError.from(IllegalArgumentException()))
        assertEquals(MetadataError.UNSUPPORTED_FORMAT, MetadataError.from(UnsupportedOperationException()))
    }

    @Test
    fun `a failure the reader already classified keeps that classification`() {
        val classified = MetadataReadException(MetadataError.UNSUPPORTED_FORMAT)

        assertEquals(MetadataError.UNSUPPORTED_FORMAT, MetadataError.from(classified))
        assertEquals(MetadataError.UNSUPPORTED_FORMAT, classified.error)
    }

    @Test
    fun `result states compare by value`() {
        val metadata = VideoMetadata(sourceUri = "content://sample/clip.mp4", title = "clip.mp4")

        assertEquals(MetadataResult.Success(metadata), MetadataResult.Success(metadata))
        assertEquals(
            MetadataResult.Error(MetadataError.INVALID_URI),
            MetadataResult.Error(MetadataError.INVALID_URI),
        )
        assertSame("the loading state is a single shared object", MetadataResult.Loading, MetadataResult.Loading)
        assertTrue(MetadataResult.Success(metadata) != MetadataResult.Loading)
    }

    @Test
    fun `every error kind carries a message for the user`() {
        MetadataError.entries.forEach { error ->
            assertTrue("no message for $error", error.messageRes != 0)
        }
    }
}
