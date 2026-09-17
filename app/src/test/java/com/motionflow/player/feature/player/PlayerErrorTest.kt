package com.motionflow.player.feature.player

import androidx.media3.common.PlaybackException
import com.motionflow.player.core.media.player.PlayerError
import com.motionflow.player.core.media.player.PlayerErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Verifies the classification of playback failures, which is what decides what the user is told.
 *
 * The error codes are compile-time constants on `PlaybackException`, so this stays a plain JVM test
 * and the expectation is pinned to Media3's own values rather than to numbers copied out of it.
 */
class PlayerErrorTest {

    @Test
    fun `a missing file is reported as not found`() {
        assertEquals(
            PlayerErrorKind.FILE_NOT_FOUND,
            PlayerError.kindOf(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
        )
    }

    @Test
    fun `a lapsed uri grant is reported as a permission problem`() {
        assertEquals(
            PlayerErrorKind.PERMISSION_DENIED,
            PlayerError.kindOf(PlaybackException.ERROR_CODE_IO_NO_PERMISSION),
        )
    }

    @Test
    fun `formats the device cannot decode are reported as unsupported`() {
        listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        ).forEach { errorCode ->
            assertEquals(
                "error code $errorCode",
                PlayerErrorKind.UNSUPPORTED_FORMAT,
                PlayerError.kindOf(errorCode),
            )
        }
    }

    @Test
    fun `damaged containers are reported as unsupported rather than as decoder faults`() {
        assertEquals(
            PlayerErrorKind.UNSUPPORTED_FORMAT,
            PlayerError.kindOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
        )
    }

    @Test
    fun `decoder faults are reported separately from unsupported formats`() {
        listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        ).forEach { errorCode ->
            assertEquals(
                "error code $errorCode",
                PlayerErrorKind.DECODER_FAILURE,
                PlayerError.kindOf(errorCode),
            )
        }
    }

    @Test
    fun `read failures are reported as file access problems`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        ).forEach { errorCode ->
            assertEquals(
                "error code $errorCode",
                PlayerErrorKind.FILE_ACCESS,
                PlayerError.kindOf(errorCode),
            )
        }
    }

    @Test
    fun `unclassified failures fall back to a generic playback error`() {
        assertEquals(
            PlayerErrorKind.PLAYBACK_FAILED,
            PlayerError.kindOf(PlaybackException.ERROR_CODE_UNSPECIFIED),
        )
        assertEquals(
            "an unknown code must still produce a presentable error",
            PlayerErrorKind.PLAYBACK_FAILED,
            PlayerError.kindOf(Int.MAX_VALUE),
        )
    }

    @Test
    fun `the technical detail is carried alongside the classification`() {
        val error = PlayerError.from(
            errorCode = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            technicalDetail = "Source error: container unsupported",
        )

        assertEquals(PlayerErrorKind.UNSUPPORTED_FORMAT, error.kind)
        assertEquals("Source error: container unsupported", error.technicalDetail)
        assertNotNull("the UI needs a message for every kind", error.messageRes)
    }
}
