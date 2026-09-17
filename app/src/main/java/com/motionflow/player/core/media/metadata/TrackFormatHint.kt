package com.motionflow.player.core.media.metadata

import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

/**
 * The technical facts Media3 reports for the tracks it has parsed.
 *
 * Captured as a plain snapshot so the metadata engine can be fed by the player when a player exists,
 * and still work without one — the library screen and the performance phases read metadata for
 * sources nothing is playing.
 */
data class TrackFormatHint(
    val videoMimeType: String? = null,
    val videoCodecName: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val bitrateBitsPerSecond: Int? = null,
    val rotationDegrees: Int? = null,
    val pixelWidthHeightRatio: Float? = null,
    val color: HdrInfo? = null,
    val audioMimeType: String? = null,
    val audioCodecName: String? = null,
    val audioChannelCount: Int? = null,
    val audioSampleRateHz: Int? = null,
    val audioBitrateBitsPerSecond: Int? = null,
) {

    companion object {

        /**
         * Snapshots the first supported video and audio format in [tracks].
         *
         * Returns `null` when the player has not parsed any track yet, which is the normal state
         * until a media item has been prepared.
         */
        @androidx.annotation.OptIn(UnstableApi::class)
        fun from(tracks: Tracks): TrackFormatHint? {
            var video: Format? = null
            var audio: Format? = null

            tracks.groups.forEach { group ->
                for (index in 0 until group.length) {
                    if (!group.isTrackSupported(index)) continue
                    val format = group.getTrackFormat(index)
                    when (group.type) {
                        C.TRACK_TYPE_VIDEO -> if (video == null) video = format
                        C.TRACK_TYPE_AUDIO -> if (audio == null) audio = format
                    }
                }
            }

            if (video == null && audio == null) return null

            return TrackFormatHint(
                videoMimeType = video?.sampleMimeType,
                videoCodecName = video?.codecs,
                width = video?.width?.positiveOrNull(),
                height = video?.height?.positiveOrNull(),
                frameRate = video?.frameRate?.positiveOrNull(),
                bitrateBitsPerSecond = video?.bitrate?.positiveOrNull(),
                rotationDegrees = video?.rotationDegrees,
                pixelWidthHeightRatio = video?.pixelWidthHeightRatio?.positiveOrNull(),
                color = video?.colorInfo?.let(::toHdrInfo),
                audioMimeType = audio?.sampleMimeType,
                audioCodecName = audio?.codecs,
                audioChannelCount = audio?.channelCount?.positiveOrNull(),
                audioSampleRateHz = audio?.sampleRate?.positiveOrNull(),
                audioBitrateBitsPerSecond = audio?.bitrate?.positiveOrNull(),
            )
        }
    }
}

/**
 * Maps a platform colour description onto the metadata model.
 *
 * Written against the framework's `MediaFormat` constants because Media3's colour constants are
 * aliases of exactly these values, so one mapping serves both the parsed-format and the
 * container-header paths. `ColorInfo` itself is part of Media3's unstable surface, which is why the
 * opt-in sits here and not on the model.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun toHdrInfo(colorInfo: ColorInfo?): HdrInfo? {
    if (colorInfo == null) return null
    return toHdrInfo(
        colorSpace = colorInfo.colorSpace,
        colorTransfer = colorInfo.colorTransfer,
        bitDepth = colorInfo.lumaBitdepth,
    )
}

internal fun toHdrInfo(colorSpace: Int, colorTransfer: Int, bitDepth: Int?): HdrInfo? {
    val space = when (colorSpace) {
        MediaFormat.COLOR_STANDARD_BT601_PAL,
        MediaFormat.COLOR_STANDARD_BT601_NTSC,
        -> VideoColorSpace.BT601

        MediaFormat.COLOR_STANDARD_BT709 -> VideoColorSpace.BT709

        MediaFormat.COLOR_STANDARD_BT2020 -> VideoColorSpace.BT2020

        else -> null
    }

    val transfer = when (colorTransfer) {
        MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> VideoColorTransfer.SDR

        MediaFormat.COLOR_TRANSFER_ST2084 -> VideoColorTransfer.HDR10

        MediaFormat.COLOR_TRANSFER_HLG -> VideoColorTransfer.HLG

        // A transfer the platform names but that is not a video presentation transfer. Anything
        // else, including the unspecified default, stays unknown rather than being guessed at.
        MediaFormat.COLOR_TRANSFER_LINEAR -> VideoColorTransfer.OTHER

        else -> null
    }

    val depth = bitDepth?.positiveOrNull()
    if (space == null && transfer == null && depth == null) return null
    return HdrInfo(colorSpace = space, transfer = transfer, bitDepth = depth)
}

/** `null` for anything that is not a real measurement: Media3 reports unset numbers as negatives. */
private fun Int.positiveOrNull(): Int? = takeIf { it > 0 }

private fun Float.positiveOrNull(): Float? = takeIf { it > 0f }
