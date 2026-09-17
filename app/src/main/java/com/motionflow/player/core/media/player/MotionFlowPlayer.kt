package com.motionflow.player.core.media.player

import android.content.Context
import android.util.Log
import androidx.media3.common.Player

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

    /** Releases the engine and everything it holds. Safe to call once, from the owner's teardown. */
    fun release() {
        Log.d(TAG, "Releasing ExoPlayer instance")
        player.release()
    }

    private companion object {
        const val TAG = "MotionFlowPlayer"
    }
}
