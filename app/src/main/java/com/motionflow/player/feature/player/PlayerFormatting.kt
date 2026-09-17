package com.motionflow.player.feature.player

import kotlin.math.roundToInt

/**
 * Presentation helpers for the player surface.
 *
 * Kept out of the composables and free of `String.format` on purpose: a duration readout must not
 * change shape with the device locale, and pure functions can be unit tested here.
 */

/** Formats a position or duration as `m:ss`, or `h:mm:ss` once it passes an hour. */
internal fun formatPosition(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3_600L
    return if (hours > 0L) {
        "$hours:${minutes.padded()}:${seconds.padded()}"
    } else {
        "$minutes:${seconds.padded()}"
    }
}

/** Formats a playback speed without trailing zeros: `1`, `1.25`, `0.5`. */
internal fun formatPlaybackSpeed(speed: Float): String {
    val hundredths = (speed * 100f).roundToInt()
    if (hundredths < 0) return "$hundredths"
    return when {
        hundredths % 100 == 0 -> "${hundredths / 100}"
        hundredths % 10 == 0 -> "${hundredths / 100}.${(hundredths % 100) / 10}"
        else -> "${hundredths / 100}.${hundredths % 100}"
    }
}

private fun Long.padded(): String = if (this < 10L) "0$this" else "$this"
