package com.motionflow.player.core.media.player

import android.content.Context
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.motionflow.player.core.media.processing.PlayerProcessingEndpoint
import com.motionflow.player.core.media.processing.ProcessingRequest
import com.motionflow.player.core.media.processing.ProcessingResult

/**
 * Owns the playback engine for as long as the process needs it.
 *
 * Exists so that exactly one engine is created, exactly one place releases it, and every consumer
 * works against the [Player] interface rather than a concrete engine. The session service holds one
 * of these; the UI never does — it drives playback through a `MediaController`, which keeps the
 * player alive across configuration changes and screen navigation instead of being recreated with
 * the UI.
 *
 * It is built *for* a [PlaybackConfiguration], because Media3 requires the effects pipeline to exist
 * before `prepare()`. A pipeline chosen after playback began would not be the pipeline the frames had
 * travelled through, and a measurement that could not say which condition it was taken under would be
 * worth nothing.
 */
class MotionFlowPlayer(
    context: Context,
    /** How this engine was built. Reported so a measurement can name the condition it ran under. */
    val configuration: PlaybackConfiguration = PlaybackConfiguration.Native,
) {

    private val engine: ExoPlayer = PlayerFactory.createExoPlayer(context, configuration)

    /** The engine, exposed as the narrowest interface its consumers need. */
    val player: Player get() = engine

    /** Where a processing stage would be attached. Holds no Android type and no frame data. */
    private val processingEndpoint = PlayerProcessingEndpoint()

    /**
     * Opens the engine's diagnostics surface to [listener].
     *
     * The one thing a `Player` cannot do: Media3's analytics live on the `ExoPlayer`, and the service
     * owns one, so the measurement attaches here rather than reaching through a UI. Nothing a listener
     * can do changes playback — every callback used by the measurement reports what the renderer has
     * already done.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun addAnalyticsListener(listener: AnalyticsListener) {
        engine.addAnalyticsListener(listener)
    }

    /**
     * Answers a request to put a processing stage into the video path, or take it out.
     *
     * This is the end of the request path and the only place a stage could be attached, because this
     * class is the only processing-aware code that holds the `ExoPlayer`. It delegates the decision to
     * [PlayerProcessingEndpoint], which is Android-free and therefore testable.
     *
     * The engine is untouched either way. Playback position, speed, repeat mode, play/pause state, the
     * audio output and the video surface are all exactly as they were before the call, which is what
     * makes processing optional in the only sense that matters.
     */
    fun applyProcessing(request: ProcessingRequest): ProcessingResult = processingEndpoint.onRequest(request)

    /** Releases the engine and everything it holds. Safe to call once, from the owner's teardown. */
    fun release() {
        Log.d(TAG, "Releasing ExoPlayer instance")
        player.release()
    }

    private companion object {
        const val TAG = "MotionFlowPlayer"
    }
}
