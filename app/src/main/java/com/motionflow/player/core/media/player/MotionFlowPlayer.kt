package com.motionflow.player.core.media.player

import android.content.Context
import android.util.Log
import androidx.media3.common.Player
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
 */
class MotionFlowPlayer(context: Context) {

    /** The engine, exposed as the narrowest interface its consumers need. */
    val player: Player = PlayerFactory.createExoPlayer(context)

    /** Where a processing stage would be attached. Holds no Android type and no frame data. */
    private val processingEndpoint = PlayerProcessingEndpoint()

    /**
     * Answers a request to put a processing stage into the video path, or take it out.
     *
     * This is the end of the request path and the only place a stage could be attached, because this
     * class is the only processing-aware code that holds the `ExoPlayer`. It delegates the decision to
     * [PlayerProcessingEndpoint], which is Android-free and therefore testable, and which explains why
     * this build attaches nothing: the effects module is absent and Media3 requires the pipeline to be
     * armed before `prepare()`.
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
