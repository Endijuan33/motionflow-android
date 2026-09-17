package com.motionflow.player.feature.player

import androidx.compose.runtime.Immutable
import com.motionflow.player.core.media.metadata.MetadataResult
import com.motionflow.player.core.media.player.PlayerError
import com.motionflow.player.core.media.player.PlayerState

/**
 * Everything the player surface renders.
 *
 * The loading flag is derived from [playerState] rather than stored, so the two can never disagree.
 * The technical description of the source is carried as a [MetadataResult] so the panel can show
 * its own progress and its own failures independently of playback.
 */
@Immutable
data class PlayerUiState(
    val playerState: PlayerState = PlayerState.IDLE,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
    val isRepeatEnabled: Boolean = false,
    val error: PlayerError? = null,
    val videoTitle: String? = null,
    val metadata: MetadataResult = MetadataResult.Loading,
) {

    /** True while the player is filling its buffer and cannot render a frame yet. */
    val isLoading: Boolean get() = playerState == PlayerState.BUFFERING

    /** True once the duration is known and nothing is broken, so the scrubber can be used. */
    val isSeekable: Boolean get() = durationMs > 0L && error == null

    /** True when a media item has been handed to the player. */
    val hasMedia: Boolean get() = playerState != PlayerState.IDLE

    /**
     * The duration to present: the player's, once it knows one, because that is the media actually
     * loaded, and the container's until then.
     */
    val displayDurationMs: Long?
        get() = when {
            durationMs > 0L -> durationMs
            else -> (metadata as? MetadataResult.Success)?.metadata?.durationMs
        }

    companion object {

        const val DEFAULT_PLAYBACK_SPEED = 1.0f

        /**
         * The speeds the player surface steps through. Playback speed is a first-class Media3
         * capability; this is just the ladder the single speed control cycles.
         */
        val PLAYBACK_SPEED_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        /** The step after [current], wrapping back to the start at the top of the ladder. */
        fun nextPlaybackSpeed(current: Float): Float =
            PLAYBACK_SPEED_STEPS.firstOrNull { it > current } ?: PLAYBACK_SPEED_STEPS.first()
    }
}
