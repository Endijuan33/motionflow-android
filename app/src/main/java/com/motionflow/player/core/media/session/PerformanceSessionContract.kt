package com.motionflow.player.core.media.session

import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.motionflow.player.core.media.performance.DeviceCharacteristics
import com.motionflow.player.core.media.performance.FramePerformanceSnapshot
import com.motionflow.player.core.media.performance.PerformanceDiagnostics
import com.motionflow.player.core.media.performance.PerformanceMeasurementSupport
import com.motionflow.player.core.media.performance.PerformanceSession
import com.motionflow.player.core.media.performance.PerformanceSessionId
import com.motionflow.player.core.media.performance.PerformanceSessionLength
import com.motionflow.player.core.media.performance.PerformanceSessionRefusal
import com.motionflow.player.core.media.performance.ProcessingPerformanceMode

/**
 * The wire contract for a measurement session: three actions, one flat answer.
 *
 * A measurement lives where the player lives — Media3's diagnostics are reachable only from the
 * `ExoPlayer`, and a screen holds a `MediaController` — so the screen asks over the session and the
 * service answers, exactly as the processing commands do, and for the same reason.
 *
 * ## Why the answer is flat
 *
 * A snapshot is a couple of dozen numbers and a session's identity is a handful more. Rather than nest
 * bundles, every field travels at the top level of one bundle and is *absent* when it was not measured.
 * Absence is a real value here: it is the difference between "zero dropped frames" and "no reading", so
 * the codec checks for a key rather than reading a default, and a missing number never becomes a zero.
 *
 * ## What is tested, and what cannot be
 *
 * [PerformanceWire] is the primitive shape, and [PerformanceWire.of] and [PerformanceWire.toDiagnostics]
 * are the mapping — pure, and therefore covered by tests. [PerformanceWire.toBundle] and
 * [PerformanceWire.fromBundle] only read and write those primitives, because `android.os.Bundle` is a
 * stub off-device: a test of them would be a test of the stub.
 */
object PerformanceSessionContract {

    /** Asks for a session to start. */
    const val ACTION_START = "com.motionflow.player.performance.START"

    /** Asks for the session in progress to close, and for its final measurement. */
    const val ACTION_STOP = "com.motionflow.player.performance.STOP"

    /** Asks for the current picture without changing anything. */
    const val ACTION_READ = "com.motionflow.player.performance.READ"

    val startCommand = SessionCommand(ACTION_START, Bundle())
    val stopCommand = SessionCommand(ACTION_STOP, Bundle())
    val readCommand = SessionCommand(ACTION_READ, Bundle())

    /** Every command this contract declares, for a session to offer a controller. */
    val commands: List<SessionCommand> = listOf(startCommand, stopCommand, readCommand)

    /**
     * The arguments a start request carries.
     *
     * The two rates are passed in by the screen because the engines that own them live there: the
     * metadata engine measured the source's frame rate and the refresh engine owns the display. This
     * phase consumes both rather than deriving either, which is why a rate that was not known simply
     * does not travel.
     */
    data class StartArguments(
        val length: PerformanceSessionLength,
        val sourceFps: Float?,
        val displayRefreshRateHz: Float?,
    ) {

        fun applyTo(bundle: Bundle) {
            bundle.putInt(KEY_LENGTH_SECONDS, length.seconds)
            sourceFps?.let { bundle.putFloat(KEY_SOURCE_FPS, it) }
            displayRefreshRateHz?.let { bundle.putFloat(KEY_DISPLAY_HZ, it) }
        }

        companion object {

            /**
             * Reads a start request, or `null` when the length is absent or is not one of the
             * controlled windows. An unreadable request is refused rather than defaulted: a session of
             * an unknown length is not a controlled measurement.
             */
            fun from(bundle: Bundle): StartArguments? {
                if (!bundle.containsKey(KEY_LENGTH_SECONDS)) return null
                val length = PerformanceSessionLength.ofSeconds(bundle.getInt(KEY_LENGTH_SECONDS)) ?: return null
                return StartArguments(
                    length = length,
                    sourceFps = bundle.floatOrNull(KEY_SOURCE_FPS),
                    displayRefreshRateHz = bundle.floatOrNull(KEY_DISPLAY_HZ),
                )
            }
        }
    }
}

/**
 * One measurement, in a shape a `Bundle` can carry.
 *
 * Primitives only, every optional field nullable, so that "not measured" survives the trip. Both
 * conversions live here so the session service and the transport agree by construction.
 */
data class PerformanceWire(
    val accepted: Boolean = true,
    val refusal: String? = null,
    val mode: String? = null,
    val sessionId: Long? = null,
    val sessionRunning: Boolean = false,
    val sessionDurationMs: Long? = null,
    val lengthSeconds: Int? = null,
    val sourceFps: Float? = null,
    val displayRefreshRateHz: Float? = null,
    val renderedFrames: Int? = null,
    val droppedFrames: Int? = null,
    val processingOffsetTotalUs: Long? = null,
    val processingOffsetFrames: Int? = null,
    val firstFrameLatencyMs: Long? = null,
    val decoderInitializationMs: Long? = null,
    val playbackPositionMs: Long? = null,
    val measurementDurationMs: Long? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val cpuTimeMs: Long? = null,
    val processPssKb: Long? = null,
    val heapUsedBytes: Long? = null,
    val thermalStatusAtStart: Int? = null,
    val thermalStatusAtEnd: Int? = null,
    val thermalStatusPeak: Int? = null,
    val apiLevel: Int? = null,
    val primaryAbi: String? = null,
    val memoryClassMb: Int? = null,
    val thermalApiAvailable: Boolean = false,
    val supportedDisplayRefreshRatesHz: List<Float> = emptyList(),
    val thermalStatusSupported: Boolean = false,
) {

    /** Encodes the current picture, with [refusal] set when a request was declined. */
    fun toBundle(): Bundle = Bundle().apply {
        putBoolean(KEY_ACCEPTED, accepted)
        refusal?.let { putString(KEY_REFUSAL, it) }
        mode?.let { putString(KEY_MODE, it) }
        putLongOrNull(KEY_SESSION_ID, sessionId)
        putBoolean(KEY_SESSION_RUNNING, sessionRunning)
        putLongOrNull(KEY_SESSION_DURATION_MS, sessionDurationMs)
        putIntOrNull(KEY_LENGTH_SECONDS, lengthSeconds)
        putFloatOrNull(KEY_SOURCE_FPS, sourceFps)
        putFloatOrNull(KEY_DISPLAY_HZ, displayRefreshRateHz)
        putIntOrNull(KEY_RENDERED_FRAMES, renderedFrames)
        putIntOrNull(KEY_DROPPED_FRAMES, droppedFrames)
        putLongOrNull(KEY_PROCESSING_OFFSET_TOTAL_US, processingOffsetTotalUs)
        putIntOrNull(KEY_PROCESSING_OFFSET_FRAMES, processingOffsetFrames)
        putLongOrNull(KEY_FIRST_FRAME_LATENCY_MS, firstFrameLatencyMs)
        putLongOrNull(KEY_DECODER_INITIALIZATION_MS, decoderInitializationMs)
        putLongOrNull(KEY_PLAYBACK_POSITION_MS, playbackPositionMs)
        putLongOrNull(KEY_MEASUREMENT_DURATION_MS, measurementDurationMs)
        putIntOrNull(KEY_VIDEO_WIDTH, videoWidth)
        putIntOrNull(KEY_VIDEO_HEIGHT, videoHeight)
        putLongOrNull(KEY_CPU_TIME_MS, cpuTimeMs)
        putLongOrNull(KEY_PROCESS_PSS_KB, processPssKb)
        putLongOrNull(KEY_HEAP_USED_BYTES, heapUsedBytes)
        putIntOrNull(KEY_THERMAL_STATUS_START, thermalStatusAtStart)
        putIntOrNull(KEY_THERMAL_STATUS_END, thermalStatusAtEnd)
        putIntOrNull(KEY_THERMAL_STATUS_PEAK, thermalStatusPeak)
        putIntOrNull(KEY_API_LEVEL, apiLevel)
        primaryAbi?.let { putString(KEY_PRIMARY_ABI, it) }
        putIntOrNull(KEY_MEMORY_CLASS_MB, memoryClassMb)
        putBoolean(KEY_THERMAL_API_AVAILABLE, thermalApiAvailable)
        putFloatArray(KEY_DISPLAY_RATES, supportedDisplayRefreshRatesHz.toFloatArray())
        putBoolean(KEY_THERMAL_STATUS_SUPPORTED, thermalStatusSupported)
    }

    companion object {

        /** Encodes a session's state and measurement. */
        fun of(diagnostics: PerformanceDiagnostics, refusal: PerformanceSessionRefusal? = null): PerformanceWire {
            val session = diagnostics.session
            val snapshot = diagnostics.snapshot
            val device = diagnostics.device

            return PerformanceWire(
                accepted = refusal == null,
                refusal = refusal?.name,
                mode = diagnostics.mode.name,
                sessionId = session?.id?.value,
                sessionRunning = diagnostics.isMeasuring,
                sessionDurationMs = session?.measuredDurationMs,
                lengthSeconds = session?.length?.seconds,
                sourceFps = session?.sourceFps,
                displayRefreshRateHz = session?.displayRefreshRateHz,
                renderedFrames = snapshot.renderedFrames,
                droppedFrames = snapshot.droppedFrames,
                processingOffsetTotalUs = snapshot.frameProcessingOffsetTotalUs,
                processingOffsetFrames = snapshot.frameProcessingOffsetFrames,
                firstFrameLatencyMs = snapshot.firstFrameLatencyMs,
                decoderInitializationMs = snapshot.decoderInitializationMs,
                playbackPositionMs = snapshot.playbackPositionMs,
                measurementDurationMs = snapshot.measurementDurationMs,
                videoWidth = snapshot.videoWidth,
                videoHeight = snapshot.videoHeight,
                cpuTimeMs = snapshot.cpuTimeMs,
                processPssKb = snapshot.processPssKb,
                heapUsedBytes = snapshot.heapUsedBytes,
                thermalStatusAtStart = snapshot.thermalStatusAtStart,
                thermalStatusAtEnd = snapshot.thermalStatusAtEnd,
                thermalStatusPeak = snapshot.thermalStatusPeak,
                apiLevel = device.apiLevel,
                primaryAbi = device.primaryAbi,
                memoryClassMb = device.memoryClassMb,
                thermalApiAvailable = device.thermalApiAvailable,
                supportedDisplayRefreshRatesHz = device.supportedDisplayRefreshRatesHz,
                thermalStatusSupported = diagnostics.support.thermalStatus,
            )
        }

        /** Encodes a refusal, so a client can tell "no" from "nothing measured yet". */
        fun refused(refusal: PerformanceSessionRefusal): PerformanceWire =
            PerformanceWire(accepted = false, refusal = refusal.name)

        /**
         * Rebuilds the picture a client can present.
         *
         * The session's timestamps are relative to its own start: a client shows a duration, and the
         * service's clock readings mean nothing on the other side of the session, so none are invented
         * here. [PerformanceSession.measuredDurationMs] is what a panel reads, and it is exact because
         * the service sent the duration it measured.
         */
        fun fromBundle(bundle: Bundle): PerformanceWire {
            val rates = bundle.getFloatArray(KEY_DISPLAY_RATES)?.toList().orEmpty()
            return PerformanceWire(
                accepted = bundle.booleanOrDefault(KEY_ACCEPTED, true),
                refusal = bundle.getString(KEY_REFUSAL),
                mode = bundle.getString(KEY_MODE),
                sessionId = bundle.longOrNull(KEY_SESSION_ID),
                sessionRunning = bundle.booleanOrDefault(KEY_SESSION_RUNNING, false),
                sessionDurationMs = bundle.longOrNull(KEY_SESSION_DURATION_MS),
                lengthSeconds = bundle.intOrNull(KEY_LENGTH_SECONDS),
                sourceFps = bundle.floatOrNull(KEY_SOURCE_FPS),
                displayRefreshRateHz = bundle.floatOrNull(KEY_DISPLAY_HZ),
                renderedFrames = bundle.intOrNull(KEY_RENDERED_FRAMES),
                droppedFrames = bundle.intOrNull(KEY_DROPPED_FRAMES),
                processingOffsetTotalUs = bundle.longOrNull(KEY_PROCESSING_OFFSET_TOTAL_US),
                processingOffsetFrames = bundle.intOrNull(KEY_PROCESSING_OFFSET_FRAMES),
                firstFrameLatencyMs = bundle.longOrNull(KEY_FIRST_FRAME_LATENCY_MS),
                decoderInitializationMs = bundle.longOrNull(KEY_DECODER_INITIALIZATION_MS),
                playbackPositionMs = bundle.longOrNull(KEY_PLAYBACK_POSITION_MS),
                measurementDurationMs = bundle.longOrNull(KEY_MEASUREMENT_DURATION_MS),
                videoWidth = bundle.intOrNull(KEY_VIDEO_WIDTH),
                videoHeight = bundle.intOrNull(KEY_VIDEO_HEIGHT),
                cpuTimeMs = bundle.longOrNull(KEY_CPU_TIME_MS),
                processPssKb = bundle.longOrNull(KEY_PROCESS_PSS_KB),
                heapUsedBytes = bundle.longOrNull(KEY_HEAP_USED_BYTES),
                thermalStatusAtStart = bundle.intOrNull(KEY_THERMAL_STATUS_START),
                thermalStatusAtEnd = bundle.intOrNull(KEY_THERMAL_STATUS_END),
                thermalStatusPeak = bundle.intOrNull(KEY_THERMAL_STATUS_PEAK),
                apiLevel = bundle.intOrNull(KEY_API_LEVEL),
                primaryAbi = bundle.getString(KEY_PRIMARY_ABI),
                memoryClassMb = bundle.intOrNull(KEY_MEMORY_CLASS_MB),
                thermalApiAvailable = bundle.booleanOrDefault(KEY_THERMAL_API_AVAILABLE, false),
                supportedDisplayRefreshRatesHz = rates,
                thermalStatusSupported = bundle.booleanOrDefault(KEY_THERMAL_STATUS_SUPPORTED, false),
            )
        }
    }

    /**
     * Rebuilds the diagnostics a client can present.
     *
     * The session is only rebuilt when an identifier travelled: without one there was no session, and
     * inventing an empty one would put a measurement on screen that never happened.
     */
    fun toDiagnostics(): PerformanceDiagnostics {
        val parsedMode = ProcessingPerformanceMode.fromName(mode) ?: ProcessingPerformanceMode.NATIVE
        val parsedLength = lengthSeconds?.let(PerformanceSessionLength::ofSeconds)
        val identifier = sessionId

        val parsedSession = if (identifier != null && parsedLength != null) {
            PerformanceSession(
                id = PerformanceSessionId(identifier),
                mode = parsedMode,
                length = parsedLength,
                // Relative to the session's own start, because a duration is what the client shows and
                // the service's clock readings would mean nothing here.
                startedAtMs = 0L,
                endedAtMs = if (sessionRunning) null else sessionDurationMs,
                sourceFps = sourceFps,
                displayRefreshRateHz = displayRefreshRateHz,
            )
        } else {
            null
        }

        return PerformanceDiagnostics(
            mode = parsedMode,
            session = parsedSession,
            snapshot = FramePerformanceSnapshot(
                renderedFrames = renderedFrames,
                droppedFrames = droppedFrames,
                frameProcessingOffsetTotalUs = processingOffsetTotalUs,
                frameProcessingOffsetFrames = processingOffsetFrames,
                firstFrameLatencyMs = firstFrameLatencyMs,
                decoderInitializationMs = decoderInitializationMs,
                playbackPositionMs = playbackPositionMs,
                measurementDurationMs = measurementDurationMs,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                cpuTimeMs = cpuTimeMs,
                processPssKb = processPssKb,
                heapUsedBytes = heapUsedBytes,
                thermalStatusAtStart = thermalStatusAtStart,
                thermalStatusAtEnd = thermalStatusAtEnd,
                thermalStatusPeak = thermalStatusPeak,
            ),
            support = PerformanceMeasurementSupport(thermalStatus = thermalStatusSupported),
            device = DeviceCharacteristics(
                apiLevel = apiLevel,
                primaryAbi = primaryAbi,
                memoryClassMb = memoryClassMb,
                supportedDisplayRefreshRatesHz = supportedDisplayRefreshRatesHz,
                thermalApiAvailable = thermalApiAvailable,
            ),
        )
    }
}

// --- the flat keys, and the helpers that keep absence from turning into zero ---------------------

const val KEY_ACCEPTED = "accepted"
const val KEY_REFUSAL = "refusal"
const val KEY_MODE = "mode"
const val KEY_SESSION_ID = "sessionId"
const val KEY_SESSION_RUNNING = "sessionRunning"
const val KEY_SESSION_DURATION_MS = "sessionDurationMs"
const val KEY_LENGTH_SECONDS = "lengthSeconds"
const val KEY_SOURCE_FPS = "sourceFps"
const val KEY_DISPLAY_HZ = "displayHz"
const val KEY_RENDERED_FRAMES = "renderedFrames"
const val KEY_DROPPED_FRAMES = "droppedFrames"
const val KEY_PROCESSING_OFFSET_TOTAL_US = "processingOffsetTotalUs"
const val KEY_PROCESSING_OFFSET_FRAMES = "processingOffsetFrames"
const val KEY_FIRST_FRAME_LATENCY_MS = "firstFrameLatencyMs"
const val KEY_DECODER_INITIALIZATION_MS = "decoderInitializationMs"
const val KEY_PLAYBACK_POSITION_MS = "playbackPositionMs"
const val KEY_MEASUREMENT_DURATION_MS = "measurementDurationMs"
const val KEY_VIDEO_WIDTH = "videoWidth"
const val KEY_VIDEO_HEIGHT = "videoHeight"
const val KEY_CPU_TIME_MS = "cpuTimeMs"
const val KEY_PROCESS_PSS_KB = "processPssKb"
const val KEY_HEAP_USED_BYTES = "heapUsedBytes"
const val KEY_THERMAL_STATUS_START = "thermalStatusAtStart"
const val KEY_THERMAL_STATUS_END = "thermalStatusAtEnd"
const val KEY_THERMAL_STATUS_PEAK = "thermalStatusPeak"
const val KEY_API_LEVEL = "apiLevel"
const val KEY_PRIMARY_ABI = "primaryAbi"
const val KEY_MEMORY_CLASS_MB = "memoryClassMb"
const val KEY_THERMAL_API_AVAILABLE = "thermalApiAvailable"
const val KEY_DISPLAY_RATES = "displayRates"
const val KEY_THERMAL_STATUS_SUPPORTED = "thermalStatusSupported"

private fun Bundle.putIntOrNull(key: String, value: Int?) {
    if (value != null) putInt(key, value)
}

private fun Bundle.putLongOrNull(key: String, value: Long?) {
    if (value != null) putLong(key, value)
}

private fun Bundle.putFloatOrNull(key: String, value: Float?) {
    if (value != null) putFloat(key, value)
}

private fun Bundle.intOrNull(key: String): Int? = if (containsKey(key)) getInt(key) else null

private fun Bundle.longOrNull(key: String): Long? = if (containsKey(key)) getLong(key) else null

private fun Bundle.floatOrNull(key: String): Float? = if (containsKey(key)) getFloat(key) else null

private fun Bundle.booleanOrDefault(key: String, fallback: Boolean): Boolean =
    if (containsKey(key)) getBoolean(key) else fallback
