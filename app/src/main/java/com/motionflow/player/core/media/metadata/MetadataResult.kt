package com.motionflow.player.core.media.metadata

import androidx.annotation.StringRes
import com.motionflow.player.R
import java.io.FileNotFoundException
import java.io.IOException

/**
 * What went wrong while reading metadata, in terms the UI can present.
 *
 * Deliberately separate from the playback errors: a file can be perfectly readable as a file and
 * still fail to describe itself, and the reverse is true too. The player screen shows both
 * independently.
 */
enum class MetadataError(@StringRes val messageRes: Int) {
    /** The source is not something that can be opened at all. */
    INVALID_URI(R.string.metadata_error_invalid_uri),

    /** The container is not supported, or holds no video track. */
    UNSUPPORTED_FORMAT(R.string.metadata_error_unsupported_format),

    /** The app is not allowed to read the source. */
    PERMISSION_DENIED(R.string.metadata_error_permission_denied),

    /** The source could be opened but its details could not be read. */
    EXTRACTION_FAILED(R.string.metadata_error_extraction_failed),

    /** Anything else. */
    UNKNOWN(R.string.metadata_error_unknown),

    ;

    companion object {

        /**
         * Classifies a failure raised while reading metadata.
         *
         * Judged from the exception type alone, so it stays a plain JVM function.
         */
        fun from(failure: Throwable): MetadataError = when (failure) {
            is MetadataReadException -> failure.error

            is SecurityException -> PERMISSION_DENIED

            is FileNotFoundException -> EXTRACTION_FAILED

            is IOException -> EXTRACTION_FAILED

            is UnsupportedOperationException -> UNSUPPORTED_FORMAT

            is IllegalArgumentException -> INVALID_URI

            else -> UNKNOWN
        }
    }
}

/**
 * Raised when a read fails in a way the reader has already classified.
 *
 * Lets the reader report "unsupported container" or "no permission" as a fact rather than leaving
 * the repository to infer it from an exception type it cannot distinguish.
 */
class MetadataReadException(
    val error: MetadataError,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: error.name, cause)

/**
 * The state of a metadata read.
 *
 * There is no idle state: the player destination always has a source, so a read is either running,
 * finished or failed.
 */
sealed interface MetadataResult {

    /** The read is in progress. */
    data object Loading : MetadataResult

    /** The read produced a description. Fields inside it may still be unknown. */
    data class Success(val metadata: VideoMetadata) : MetadataResult

    /** The read could not produce a description. */
    data class Error(val error: MetadataError) : MetadataResult
}
