package com.motionflow.player.core.media.metadata

import android.content.Context
import android.database.Cursor
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * What the storage provider knows about a document, before anything is parsed.
 *
 * Kept separate from [VideoMetadata] because it comes from a single cheap query, and because the
 * player needs the label immediately in order to present the media item correctly, while the
 * technical description can arrive later.
 */
data class MediaDocumentInfo(
    val title: String? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
)

/**
 * Reads metadata for a media source.
 *
 * Nothing here may touch the main thread: implementations do file and provider I/O.
 */
interface VideoMetadataReader {

    /** The label, size and MIME type of the document, from the storage provider alone. */
    suspend fun document(sourceUri: String): MediaDocumentInfo

    /**
     * The technical description of [sourceUri].
     *
     * [documentInfo] is passed back in when the caller has already read it, so the provider is not
     * queried twice for the same source. [hint] carries what Media3 parsed from the tracks, when a
     * player has got that far.
     *
     * @throws MetadataReadException when the failure is already classified.
     */
    suspend fun read(
        sourceUri: String,
        documentInfo: MediaDocumentInfo? = null,
        hint: TrackFormatHint? = null,
    ): VideoMetadata
}

/**
 * The production reader: the storage provider for the label and size, `MediaExtractor` for the
 * container and track headers, and a bounded timestamp probe for the frame rate.
 *
 * No frames are decoded and no sample data is copied. `MediaExtractor` parses the container's
 * headers and, at most, walks the sample table of the first two seconds; the decoder lookup only
 * asks the platform which decoder *would* be used, without creating one.
 */
class AndroidVideoMetadataReader(private val context: Context) : VideoMetadataReader {

    override suspend fun document(sourceUri: String): MediaDocumentInfo = withContext(Dispatchers.IO) {
        val uri = Uri.parse(sourceUri)
        val fromProvider = runCatching {
            context.contentResolver.query(uri, DOCUMENT_COLUMNS, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                MediaDocumentInfo(
                    title = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME),
                    sizeBytes = cursor.longOrNull(OpenableColumns.SIZE),
                )
            }
        }.getOrNull()

        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()

        MediaDocumentInfo(
            title = fromProvider?.title,
            sizeBytes = fromProvider?.sizeBytes,
            mimeType = mimeType,
        )
    }

    override suspend fun read(
        sourceUri: String,
        documentInfo: MediaDocumentInfo?,
        hint: TrackFormatHint?,
    ): VideoMetadata = withContext(Dispatchers.IO) {
        val document = documentInfo ?: document(sourceUri)
        val container = readContainer(Uri.parse(sourceUri))

        VideoMetadata(
            sourceUri = sourceUri,
            title = document.title,
            mimeType = document.mimeType,
            durationMs = container?.durationMs,
            fileSizeBytes = document.sizeBytes,
            video = mergeVideoTrack(hint, container),
            audio = mergeAudioTrack(hint, container),
        )
    }

    private fun readContainer(uri: Uri): ContainerMetadata? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            scanTracks(extractor)
        } catch (failure: MetadataReadException) {
            throw failure
        } catch (failure: SecurityException) {
            throw MetadataReadException(MetadataError.PERMISSION_DENIED, cause = failure)
        } catch (failure: IllegalArgumentException) {
            throw MetadataReadException(MetadataError.INVALID_URI, cause = failure)
        } catch (failure: Exception) {
            // MediaExtractor reports an unparseable container as an IOException, which is why an
            // unreadable source and an unsupported one share a classification here.
            Log.w(TAG, "Container header could not be parsed", failure)
            throw MetadataReadException(MetadataError.EXTRACTION_FAILED, cause = failure)
        } finally {
            // The extractor holds a file descriptor; release it on every path out.
            runCatching { extractor.release() }
        }
    }

    private fun scanTracks(extractor: MediaExtractor): ContainerMetadata {
        var videoTrackIndex = -1
        var videoFormat: MediaFormat? = null
        var audioTrack: AudioTrackMetadata? = null
        var durationUs = Long.MIN_VALUE

        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mimeType = format.stringOrNull(MediaFormat.KEY_MIME)

            val trackDurationUs = format.longOrNull(MediaFormat.KEY_DURATION)
            if (trackDurationUs != null && trackDurationUs > durationUs) durationUs = trackDurationUs

            when {
                mimeType?.startsWith(VIDEO_MIME_PREFIX) == true && videoTrackIndex < 0 -> {
                    videoTrackIndex = index
                    videoFormat = format
                }

                mimeType?.startsWith(AUDIO_MIME_PREFIX) == true && audioTrack == null -> {
                    audioTrack = AudioTrackMetadata(
                        mimeType = mimeType,
                        channelCount = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT).positiveOrNull(),
                        sampleRateHz = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE).positiveOrNull(),
                        bitrateBitsPerSecond = format.intOrNull(MediaFormat.KEY_BIT_RATE).positiveOrNull(),
                    )
                }
            }
        }

        if (videoTrackIndex < 0) {
            throw MetadataReadException(
                MetadataError.UNSUPPORTED_FORMAT,
                "Container holds no video track",
            )
        }

        val timing = probeFrameTiming(extractor, videoTrackIndex)

        return ContainerMetadata(
            durationMs = durationUs.takeIf { it > 0L }?.let { it / 1_000L },
            videoFormat = videoFormat,
            decoderName = videoFormat?.let(::findDecoderName),
            frameTiming = timing,
            audioTrack = audioTrack,
        )
    }

    /**
     * Walks the video track's timestamps for a bounded window and analyses the intervals.
     *
     * Reading timestamps moves the extractor's cursor through the sample table, which is a header
     * lookup rather than a decode, and it stops after two seconds of content regardless of how long
     * the file is. Returns `null` when there is too little to measure.
     */
    private fun probeFrameTiming(extractor: MediaExtractor, trackIndex: Int): FrameTiming? {
        extractor.selectTrack(trackIndex)

        val intervalsUs = ArrayList<Long>(SAMPLE_PROBE_LIMIT)
        var previousUs = Long.MIN_VALUE
        var firstUs = Long.MIN_VALUE
        var examined = 0

        while (examined < SAMPLE_PROBE_LIMIT) {
            val timeUs = extractor.sampleTime
            if (timeUs < 0L) break

            if (firstUs == Long.MIN_VALUE) firstUs = timeUs
            if (previousUs != Long.MIN_VALUE) {
                val intervalUs = timeUs - previousUs
                // Zero-length intervals appear in files with repeated timestamps; they are not
                // frame intervals and would drag a rate down if they were counted.
                if (intervalUs > 0L) intervalsUs.add(intervalUs)
            }
            previousUs = timeUs
            examined++

            if (timeUs - firstUs >= PROBE_WINDOW_US) break
            if (!extractor.advance()) break
        }

        return analyseFrameTiming(intervalsUs)
    }

    /**
     * Asks the platform which decoder it would pick, without creating one.
     *
     * `MediaCodecList` answers from its own registry, so this costs a lookup rather than a codec
     * instantiation, and it is best-effort: an unusual format simply yields no name.
     */
    private fun findDecoderName(format: MediaFormat): String? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun mergeVideoTrack(
        hint: TrackFormatHint?,
        container: ContainerMetadata?,
    ): VideoTrackMetadata? {
        val format = container?.videoFormat
        val mimeType = hint?.videoMimeType ?: format?.stringOrNull(MediaFormat.KEY_MIME)

        if (mimeType == null && format == null && hint?.width == null) return null

        val frameRate = container?.frameTiming?.let(::frameRateFromTiming)
            ?: hint?.frameRate?.let { FrameRateInfo.fromHeader(it, FrameRateSource.PLAYER_FORMAT) }
            ?: format?.floatOrNull(MediaFormat.KEY_FRAME_RATE)
                ?.let { FrameRateInfo.fromHeader(it, FrameRateSource.CONTAINER) }
            ?: FrameRateInfo.Unknown

        return VideoTrackMetadata(
            width = hint?.width ?: format?.intOrNull(MediaFormat.KEY_WIDTH).positiveOrNull(),
            height = hint?.height ?: format?.intOrNull(MediaFormat.KEY_HEIGHT).positiveOrNull(),
            rotationDegrees = hint?.rotationDegrees ?: format?.intOrNull(MediaFormat.KEY_ROTATION),
            frameRate = frameRate,
            codecMimeType = mimeType,
            codecName = hint?.videoCodecName,
            decoderName = container?.decoderName,
            bitrateBitsPerSecond = hint?.bitrateBitsPerSecond
                ?: format?.intOrNull(MediaFormat.KEY_BIT_RATE).positiveOrNull(),
            // Pixel aspect ratio is not exposed by the framework's track headers; only Media3's
            // parsed format carries it.
            pixelWidthHeightRatio = hint?.pixelWidthHeightRatio,
            color = hint?.color ?: format?.let(::colorOfFormat),
        )
    }

    private fun mergeAudioTrack(
        hint: TrackFormatHint?,
        container: ContainerMetadata?,
    ): AudioTrackMetadata? {
        val track = container?.audioTrack
        val mimeType = hint?.audioMimeType ?: track?.mimeType
        if (mimeType == null && track == null) return null

        return AudioTrackMetadata(
            mimeType = mimeType,
            codecName = hint?.audioCodecName,
            channelCount = hint?.audioChannelCount ?: track?.channelCount,
            sampleRateHz = hint?.audioSampleRateHz ?: track?.sampleRateHz,
            bitrateBitsPerSecond = hint?.audioBitrateBitsPerSecond ?: track?.bitrateBitsPerSecond,
        )
    }

    private fun frameRateFromTiming(timing: FrameTiming): FrameRateInfo = FrameRateInfo.measured(
        fps = timing.fps,
        // A bounded window can prove a source varies and cannot prove it does not, so a uniform
        // window reports "not determined" rather than claiming a constant rate.
        isVariableFrameRate = if (timing.isVariableInWindow) true else null,
        confidence = when {
            timing.isVariableInWindow -> MetadataConfidence.MEDIUM
            timing.intervalCount >= HIGH_CONFIDENCE_INTERVALS -> MetadataConfidence.HIGH
            else -> MetadataConfidence.LOW
        },
    )

    private fun colorOfFormat(format: MediaFormat): HdrInfo? = toHdrInfo(
        colorSpace = format.intOrNull(MediaFormat.KEY_COLOR_STANDARD) ?: UNSET,
        colorTransfer = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER) ?: UNSET,
        bitDepth = null,
    )

    private data class ContainerMetadata(
        val durationMs: Long?,
        val videoFormat: MediaFormat?,
        val decoderName: String?,
        val frameTiming: FrameTiming?,
        val audioTrack: AudioTrackMetadata?,
    )

    private companion object {
        const val TAG = "MotionFlowMetadata"
        const val VIDEO_MIME_PREFIX = "video/"
        const val AUDIO_MIME_PREFIX = "audio/"
        const val UNSET = -1

        /** Two seconds of content is enough to measure a rate without touching the whole file. */
        const val PROBE_WINDOW_US = 2_000_000L

        /** Hard cap on how many timestamps are walked, whatever the content's frame rate. */
        const val SAMPLE_PROBE_LIMIT = 240

        /** Below this many intervals a rate is reported with low confidence. */
        const val HIGH_CONFIDENCE_INTERVALS = 60

        val DOCUMENT_COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    }
}

// -- MediaFormat reading -----------------------------------------------------------------------
//
// MediaFormat stores a value as whatever type the extractor supplied: the frame rate can arrive as
// an Integer or a Float, and durations as a Long or an Integer, depending on the container. These
// readers accept either rather than guessing.

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) null else runCatching { getInteger(key) }.getOrNull()

private fun MediaFormat.longOrNull(key: String): Long? = when {
    !containsKey(key) -> null
    else -> runCatching { getLong(key) }
        .recoverCatching { getInteger(key).toLong() }
        .getOrNull()
}

private fun MediaFormat.floatOrNull(key: String): Float? = when {
    !containsKey(key) -> null
    else -> runCatching { getFloat(key) }
        .recoverCatching { getInteger(key).toFloat() }
        .getOrNull()
}

private fun MediaFormat.stringOrNull(key: String): String? =
    if (!containsKey(key)) null else runCatching { getString(key) }.getOrNull()

private fun Int?.positiveOrNull(): Int? = this?.takeIf { it > 0 }

private fun Cursor.stringOrNull(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun Cursor.longOrNull(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}

// -- Frame timing analysis ---------------------------------------------------------------------

/** A rate measured from a bounded window of sample timestamps. */
internal data class FrameTiming(
    val fps: Float,
    val isVariableInWindow: Boolean,
    val intervalCount: Int,
)

/**
 * Derives a frame rate from consecutive sample intervals.
 *
 * @param intervalsUs gaps between consecutive timestamps, in microseconds, in presentation order.
 *
 * The intervals are filtered against their median before a rate is computed. That does two things:
 * it drops stream discontinuities (an edit, a dropped run of frames) that would otherwise pull the
 * average, and it makes the rate robust on containers whose timestamps are dithered — a container
 * with millisecond precision emits alternating 41 ms and 42 ms intervals for 23.976 fps content,
 * and averaging those recovers 23.976 where taking one interval would report 24 or 23.
 *
 * Variability is then judged on the same filtered set: more than a tenth of the intervals deviating
 * by more than 5% means the window is not constant. That is evidence about the window only.
 */
internal fun analyseFrameTiming(intervalsUs: List<Long>): FrameTiming? {
    if (intervalsUs.size < MIN_INTERVALS) return null

    val sorted = intervalsUs.sorted()
    val median = sorted[sorted.size / 2]
    if (median <= 0L) return null

    val discontinuityLimit = (median * DISCONTINUITY_MULTIPLIER).toLong()
    val inliers = intervalsUs.filter { abs(it - median) <= discontinuityLimit }
    if (inliers.size < MIN_INTERVALS) return null

    val averageUs = inliers.sum().toDouble() / inliers.size
    if (averageUs <= 0.0) return null

    val tolerance = averageUs * VARIABILITY_TOLERANCE
    val irregular = inliers.count { abs(it - averageUs) > tolerance }
    val isVariable = irregular > inliers.size * VARIABILITY_OUTLIER_SHARE

    return FrameTiming(
        fps = (MICROS_PER_SECOND / averageUs).toFloat(),
        isVariableInWindow = isVariable,
        intervalCount = inliers.size,
    )
}

private const val MICROS_PER_SECOND = 1_000_000.0

/** Fewer intervals than this and a rate is not worth reporting. */
private const val MIN_INTERVALS = 8

/** Intervals beyond half the median apart are treated as discontinuities, not as frames. */
private const val DISCONTINUITY_MULTIPLIER = 1.5

/** How far an interval may sit from the average and still count as regular. */
private const val VARIABILITY_TOLERANCE = 0.05

/** The share of irregular intervals that turns a window into a variable frame rate one. */
private const val VARIABILITY_OUTLIER_SHARE = 0.10
