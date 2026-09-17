package com.motionflow.player.feature.player

import com.motionflow.player.core.media.metadata.MetadataError
import com.motionflow.player.core.media.metadata.MetadataResult
import com.motionflow.player.core.media.metadata.VideoMetadata
import com.motionflow.player.core.media.player.PlayerError
import com.motionflow.player.core.media.player.PlayerErrorKind
import com.motionflow.player.core.media.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the state model the player surface renders, including the transitions it goes through.
 */
class PlayerUiStateTest {

    @Test
    fun `a fresh state is idle, silent and unseekable`() {
        val state = PlayerUiState()

        assertEquals(PlayerState.IDLE, state.playerState)
        assertFalse(state.isPlaying)
        assertFalse(state.isLoading)
        assertFalse(state.isSeekable)
        assertFalse(state.hasMedia)
        assertFalse(state.isRepeatEnabled)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertEquals(1.0f, state.playbackSpeed, 0.0f)
        assertEquals(null, state.error)
        assertEquals(null, state.videoTitle)
        assertEquals(MetadataResult.Loading, state.metadata)
        assertEquals(null, state.displayDurationMs)
    }

    @Test
    fun `the presented duration prefers what the player loaded over what the container claims`() {
        val fromMetadata = VideoMetadata(sourceUri = "content://sample/clip.mp4", durationMs = 600_000L)
        val loading = PlayerUiState(metadata = MetadataResult.Success(fromMetadata))

        assertEquals(
            "before the player knows a duration, the container's is shown",
            600_000L,
            loading.displayDurationMs,
        )

        assertEquals(
            "once the player knows one, that is the truth",
            634_000L,
            loading.copy(durationMs = 634_000L).displayDurationMs,
        )
        assertEquals(null, PlayerUiState().displayDurationMs)
    }

    @Test
    fun `a metadata failure does not affect playback state`() {
        val state = PlayerUiState(
            playerState = PlayerState.READY,
            isPlaying = true,
            durationMs = 120_000L,
            metadata = MetadataResult.Error(MetadataError.UNSUPPORTED_FORMAT),
        )

        assertTrue(state.isSeekable)
        assertTrue(state.isPlaying)
        assertEquals(120_000L, state.displayDurationMs)
    }

    @Test
    fun `buffering is what makes the state loading`() {
        assertTrue(PlayerUiState(playerState = PlayerState.BUFFERING).isLoading)
        assertFalse(PlayerUiState(playerState = PlayerState.READY).isLoading)
        assertFalse(PlayerUiState(playerState = PlayerState.ENDED).isLoading)
    }

    @Test
    fun `a loaded item counts as media from buffering onwards`() {
        assertFalse(PlayerUiState(playerState = PlayerState.IDLE).hasMedia)
        assertTrue(PlayerUiState(playerState = PlayerState.BUFFERING).hasMedia)
        assertTrue(PlayerUiState(playerState = PlayerState.READY).hasMedia)
        assertTrue(PlayerUiState(playerState = PlayerState.ENDED).hasMedia)
    }

    @Test
    fun `the scrubber unlocks only once a duration is known`() {
        assertFalse(PlayerUiState(playerState = PlayerState.READY).isSeekable)
        assertTrue(
            PlayerUiState(playerState = PlayerState.READY, durationMs = 120_000L).isSeekable,
        )
    }

    @Test
    fun `a failure disables seeking and clearing it restores the surface`() {
        val ready = PlayerUiState(playerState = PlayerState.READY, durationMs = 120_000L)
        val failed = ready.copy(
            error = PlayerError(PlayerErrorKind.DECODER_FAILURE, technicalDetail = "decoder"),
        )
        assertFalse(failed.isSeekable)

        // What the retry path does: drop the error and keep the playback state.
        val retried = failed.copy(error = null)
        assertTrue(retried.isSeekable)
        assertEquals(PlayerState.READY, retried.playerState)
    }

    @Test
    fun `position updates do not disturb the rest of the state`() {
        val playing = PlayerUiState(
            playerState = PlayerState.READY,
            isPlaying = true,
            durationMs = 120_000L,
            videoTitle = "clip.mp4",
        )

        val advanced = playing.copy(positionMs = 30_000L, bufferedPositionMs = 45_000L)

        assertTrue(advanced.isSeekable)
        assertTrue(advanced.isPlaying)
        assertEquals("clip.mp4", advanced.videoTitle)
    }

    @Test
    fun `the speed ladder advances and wraps`() {
        assertEquals(1.25f, PlayerUiState.nextPlaybackSpeed(1.0f), 0.0f)
        assertEquals(0.75f, PlayerUiState.nextPlaybackSpeed(0.5f), 0.0f)
        assertEquals(
            "the top of the ladder wraps back to the slowest step",
            PlayerUiState.PLAYBACK_SPEED_STEPS.first(),
            PlayerUiState.nextPlaybackSpeed(PlayerUiState.PLAYBACK_SPEED_STEPS.last()),
        )
        assertEquals(
            "an off-ladder speed snaps to the next step above it",
            0.5f,
            PlayerUiState.nextPlaybackSpeed(0.25f),
            0.0f,
        )
    }
}
