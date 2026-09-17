package com.motionflow.player.core.media.player

import androidx.media3.common.Player

/**
 * Where a loaded media item currently is, independent of the engine that reports it.
 *
 * Media3 reports playback progress as `Player.STATE_*` integers. Mapping them to an enum here keeps
 * those integers out of the UI layer, and lets the mapping be unit tested without a device.
 */
enum class PlayerState {
    /** Nothing is loaded, or loading was reset. */
    IDLE,

    /** A media item is loaded but the player has not buffered enough to render it yet. */
    BUFFERING,

    /** Enough is buffered for playback to proceed. */
    READY,

    /** Playback reached the end of the item. */
    ENDED,

    ;

    companion object {

        /** Maps a `Player.STATE_*` value onto [PlayerState]. */
        fun fromPlaybackState(playbackState: Int): PlayerState = when (playbackState) {
            Player.STATE_IDLE -> IDLE
            Player.STATE_BUFFERING -> BUFFERING
            Player.STATE_READY -> READY
            Player.STATE_ENDED -> ENDED
            else -> IDLE
        }
    }
}
