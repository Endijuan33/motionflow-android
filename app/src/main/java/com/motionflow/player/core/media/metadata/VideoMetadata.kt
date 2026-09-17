package com.motionflow.player.core.media.metadata

/**
 * Colour characteristics of a video track.
 *
 * Only what the platform actually reports is carried here; nothing is inferred from resolution or
 * bit depth alone.
 */
data class HdrInfo(
    val colorSpace: VideoColorSpace? = null,
    val transfer: VideoColorTransfer? = null,
    val bitDepth: Int? = null,
) {

    /**
     * True when the transfer characteristic is a high dynamic range one, `null` when the transfer
     * characteristic is unknown, and false for a known standard dynamic range transfer.
     */
    val isHdr: Boolean?
        get() = when (transfer) {
            null -> null
            VideoColorTransfer.HDR10, VideoColorTransfer.HLG -> true
            else -> false
        }
}

/** The colour gamut a track is mastered in, as reported by the platform. */
enum class VideoColorSpace { BT601, BT709, BT2020 }

/** The transfer characteristic a track is encoded with. */
enum class VideoColorTransfer {

    /** Standard dynamic range video transfer (BT.1886-style). */
    SDR,

    /** Perceptual quantiser, i.e. HDR10 and HDR10+. */
    HDR10,

    /** Hybrid log-gamma. */
    HLG,

    /** A transfer MotionFlow does not name; the raw signal was still not standard SDR. */
    OTHER,
}

/** Technical description of a video track. */
data class VideoTrackMetadata(
    val width: Int? = null,
    val height: Int? = null,
    val rotationDegrees: Int? = null,
    val frameRate: FrameRateInfo = FrameRateInfo.Unknown,
    val codecMimeType: String? = null,
    val codecName: String? = null,
    val decoderName: String? = null,
    val bitrateBitsPerSecond: Int? = null,
    val pixelWidthHeightRatio: Float? = null,
    val color: HdrInfo? = null,
)

/** Technical description of an audio track. */
data class AudioTrackMetadata(
    val mimeType: String? = null,
    val codecName: String? = null,
    val channelCount: Int? = null,
    val sampleRateHz: Int? = null,
    val bitrateBitsPerSecond: Int? = null,
)

/**
 * Technical description of one media source.
 *
 * Every field is nullable, and `null` always means "not known" rather than zero: a missing bitrate
 * is not a bitrate of zero, and showing one would be worse than showing nothing.
 *
 * The source is held as a string rather than as `android.net.Uri` so that the model stays free of
 * the Android framework and can be constructed and asserted in plain JVM tests.
 */
data class VideoMetadata(
    val sourceUri: String,
    val title: String? = null,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val fileSizeBytes: Long? = null,
    val video: VideoTrackMetadata? = null,
    val audio: AudioTrackMetadata? = null,
) {

    /** True when an audio track was found. */
    val hasAudio: Boolean get() = audio != null
}
