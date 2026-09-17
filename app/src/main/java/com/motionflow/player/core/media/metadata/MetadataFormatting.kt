package com.motionflow.player.core.media.metadata

import androidx.annotation.StringRes
import androidx.media3.common.MimeTypes
import com.motionflow.player.R
import java.util.Locale

/**
 * Presentation helpers for media metadata.
 *
 * Pure and free of Android framework types so that every rule below — which unit a value is shown
 * in, when a rate is displayed as a named rate, what an unknown value looks like — is unit tested.
 * Nothing here ever renders a missing value as a zero: the caller gets `null` and decides what to
 * show instead.
 */

/** A value with the unit it should be shown in, so the unit itself stays localisable. */
data class MetadataQuantity(
    @StringRes val unitRes: Int,
    val amount: String,
)

/**
 * The frame rate as a display value: a named rate when it is one of the common ones, otherwise the
 * measurement to three decimals with trailing zeros removed.
 */
internal fun frameRateValue(info: FrameRateInfo): String? {
    val fps = info.fps ?: return null
    return info.knownRate?.label ?: formatFps(fps)
}

/** Formats an arbitrary rate without rounding it to a whole number. */
internal fun formatFps(fps: Float): String {
    val rounded = String.format(Locale.US, "%.3f", fps)
    return rounded.trimEnd('0').trimEnd('.')
}

/** `1920 × 1080`, or `null` unless both dimensions are known. */
internal fun resolutionValue(width: Int?, height: Int?): String? {
    if (width == null || height == null) return null
    if (width <= 0 || height <= 0) return null
    return "$width × $height"
}

/**
 * A human name for a video codec, preferring the container's MIME type and falling back to the
 * codec string Media3 parsed.
 */
internal fun videoCodecValue(mimeType: String?, codecName: String?): String? {
    val fromMime = mimeType?.let(VIDEO_CODEC_NAMES::get)
    if (fromMime != null) return fromMime
    return codecName?.takeIf { it.isNotBlank() }
}

/** A human name for an audio codec, else `null`. */
internal fun audioCodecValue(mimeType: String?, codecName: String?): String? {
    val fromMime = mimeType?.let(AUDIO_CODEC_NAMES::get)
    if (fromMime != null) return fromMime
    return codecName?.takeIf { it.isNotBlank() }
}

/** A bitrate split into its value and a unit, or `null` when there is nothing to show. */
internal fun bitrateQuantity(bitsPerSecond: Int?): MetadataQuantity? {
    val bitrate = bitsPerSecond?.takeIf { it > 0 } ?: return null
    return if (bitrate >= 1_000_000) {
        MetadataQuantity(R.string.metadata_unit_mbps, formatDecimal(bitrate / 1_000_000.0, 1))
    } else {
        MetadataQuantity(R.string.metadata_unit_kbps, (bitrate / 1_000).toString())
    }
}

/** A file size split into its value and a unit, or `null` when there is nothing to show. */
internal fun fileSizeQuantity(bytes: Long?): MetadataQuantity? {
    val size = bytes?.takeIf { it > 0 } ?: return null
    val megabytes = size / 1_000_000.0
    return when {
        megabytes >= 1_000 -> MetadataQuantity(R.string.metadata_unit_gb, formatDecimal(megabytes / 1_000.0, 2))
        megabytes >= 1 -> MetadataQuantity(R.string.metadata_unit_mb, formatDecimal(megabytes, 1))
        else -> MetadataQuantity(R.string.metadata_unit_kb, (size / 1_000).coerceAtLeast(1).toString())
    }
}

/** A sample rate in kHz, or `null`. */
internal fun sampleRateQuantity(hertz: Int?): MetadataQuantity? {
    val rate = hertz?.takeIf { it > 0 } ?: return null
    return MetadataQuantity(R.string.metadata_unit_khz, formatDecimal(rate / 1_000.0, 1))
}

/** The name of a colour gamut. Standard abbreviations, so they are not translated. */
internal fun colorSpaceLabel(colorSpace: VideoColorSpace): String = when (colorSpace) {
    VideoColorSpace.BT601 -> "BT.601"
    VideoColorSpace.BT709 -> "BT.709"
    VideoColorSpace.BT2020 -> "BT.2020"
}

/** The name of a transfer characteristic, using the name the industry uses for it. */
internal fun colorTransferLabel(transfer: VideoColorTransfer): String = when (transfer) {
    VideoColorTransfer.SDR -> "SDR"
    VideoColorTransfer.HDR10 -> "HDR10"
    VideoColorTransfer.HLG -> "HLG"
    VideoColorTransfer.OTHER -> "Other"
}

/** A playback position or duration, formatted as `m:ss` or `h:mm:ss`. */
internal fun durationValue(durationMs: Long?): String? {
    val duration = durationMs?.takeIf { it > 0 } ?: return null
    val totalSeconds = duration / 1_000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3_600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** Locale-independent decimal formatting: these values are measurements, not prose. */
private fun formatDecimal(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

private val VIDEO_CODEC_NAMES = mapOf(
    MimeTypes.VIDEO_H264 to "H.264",
    MimeTypes.VIDEO_H265 to "H.265",
    MimeTypes.VIDEO_MP4V to "MPEG-4",
    MimeTypes.VIDEO_H263 to "H.263",
    MimeTypes.VIDEO_MPEG2 to "MPEG-2",
    MimeTypes.VIDEO_VP8 to "VP8",
    MimeTypes.VIDEO_VP9 to "VP9",
    MimeTypes.VIDEO_AV1 to "AV1",
    MimeTypes.VIDEO_DOLBY_VISION to "Dolby Vision",
)

private val AUDIO_CODEC_NAMES = mapOf(
    MimeTypes.AUDIO_AAC to "AAC",
    MimeTypes.AUDIO_MPEG to "MP3",
    MimeTypes.AUDIO_OPUS to "Opus",
    MimeTypes.AUDIO_VORBIS to "Vorbis",
    MimeTypes.AUDIO_FLAC to "FLAC",
    MimeTypes.AUDIO_AC3 to "AC-3",
    MimeTypes.AUDIO_E_AC3 to "E-AC-3",
    MimeTypes.AUDIO_DTS to "DTS",
    MimeTypes.AUDIO_RAW to "PCM",
)
