package com.motionflow.player.core.media.player

import androidx.annotation.StringRes
import androidx.media3.common.PlaybackException
import com.motionflow.player.R

/**
 * What went wrong, in terms the UI can present.
 *
 * Playback failures arrive from Media3 as a numeric error code plus a technical message. The code
 * decides which user-facing explanation applies; the message is kept alongside it for logging. Raw
 * exception text never reaches the UI, because it names decoder internals and file paths that mean
 * nothing to the person watching a video.
 */
enum class PlayerErrorKind(@StringRes val messageRes: Int) {
    /** The source is not something MotionFlow can open at all (wrong scheme, or unparsable). */
    INVALID_SOURCE(R.string.player_error_invalid_source),

    /** The file is no longer there. */
    FILE_NOT_FOUND(R.string.player_error_file_not_found),

    /** The app is not allowed to read the file, usually because a URI grant lapsed. */
    PERMISSION_DENIED(R.string.player_error_permission_denied),

    /** The container or codec is not supported by this device, or the file is damaged. */
    UNSUPPORTED_FORMAT(R.string.player_error_unsupported_format),

    /** The device advertises support but the decoder could not be started. */
    DECODER_FAILURE(R.string.player_error_decoder),

    /** The file exists but could not be read through to the end. */
    FILE_ACCESS(R.string.player_error_file_access),

    /** Anything else that stopped playback. */
    PLAYBACK_FAILED(R.string.player_error_playback),
}

/**
 * A playback failure together with the detail needed to diagnose it.
 */
data class PlayerError(
    val kind: PlayerErrorKind,
    val technicalDetail: String,
) {
    @get:StringRes
    val messageRes: Int get() = kind.messageRes

    companion object {

        /** Builds an error from a Media3 error code and its technical message. */
        fun from(errorCode: Int, technicalDetail: String): PlayerError =
            PlayerError(kindOf(errorCode), technicalDetail)

        /** Classifies a `PlaybackException` error code. */
        fun kindOf(errorCode: Int): PlayerErrorKind = when (errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlayerErrorKind.FILE_NOT_FOUND

            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlayerErrorKind.PERMISSION_DENIED

            // The device cannot decode what the file contains.
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            -> PlayerErrorKind.UNSUPPORTED_FORMAT

            // The file claims to be playable but its structure is broken.
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            -> PlayerErrorKind.UNSUPPORTED_FORMAT

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            -> PlayerErrorKind.DECODER_FAILURE

            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            -> PlayerErrorKind.FILE_ACCESS

            else -> PlayerErrorKind.PLAYBACK_FAILED
        }
    }
}
