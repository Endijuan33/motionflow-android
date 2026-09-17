package com.motionflow.player.feature.player

import androidx.lifecycle.SavedStateHandle

/**
 * How a video source reaches the player destination.
 *
 * The source is a `content://` URI granted by the system file picker, so it contains characters
 * that are structural in a navigation route (`/`, `:`, `%`). It is therefore percent-encoded into a
 * single path segment before navigation and decoded by the navigation library when the destination
 * is created — Navigation applies exactly one decoding pass to path arguments.
 *
 * Everything here is pure string handling, so the encoding can be verified by unit tests without a
 * device — which matters, because a source that does not survive the round trip fails only at
 * runtime, as unplayable media.
 */
object PlayerRoute {

    /** Name of the navigation argument carrying the encoded source URI. */
    const val ARGUMENT_VIDEO_URI = "videoUri"

    private const val BASE = "player"

    /** Characters that may appear unescaped in a path segment, beyond the unreserved set. */
    private const val UNRESERVED = "-._~"

    private const val HEX_DIGITS = "0123456789ABCDEF"

    /** Builds the route pointing the player at [sourceUri]. */
    fun create(sourceUri: String): String = "$BASE/${encode(sourceUri)}"

    /** The source URI carried by [handle], or `null` when the destination was opened without one. */
    fun sourceUriOf(handle: SavedStateHandle): String? = handle.get<String>(ARGUMENT_VIDEO_URI)

    /**
     * Whether MotionFlow can open [sourceUri] as local media.
     *
     * Only granted content and plain files are accepted. Anything else (a network location, a
     * malformed string) is rejected before it reaches the player, so the failure is reported as an
     * unsupported source rather than as a decoder error.
     */
    fun isSupportedSource(sourceUri: String): Boolean =
        sourceUri.startsWith("content://") || sourceUri.startsWith("file://")

    /**
     * Percent-encodes [value] so it can be used as one path segment.
     *
     * Encodes every byte outside the unreserved set, including `+`, which some decoders read as a
     * space.
     */
    internal fun encode(value: String): String {
        val builder = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            val unreserved = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char in UNRESERVED
            if (unreserved) {
                builder.append(char)
            } else {
                builder.append('%')
                builder.append(HEX_DIGITS[code shr 4])
                builder.append(HEX_DIGITS[code and 0x0F])
            }
        }
        return builder.toString()
    }
}
