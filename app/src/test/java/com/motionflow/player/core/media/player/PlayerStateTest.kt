package com.motionflow.player.core.media.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the translation from Media3 playback states to the enum the UI consumes.
 *
 * The constants are compile-time values on `Player`, so this stays a plain JVM test.
 */
class PlayerStateTest {

    @Test
    fun `every reported playback state has a mapping`() {
        assertEquals(PlayerState.IDLE, PlayerState.fromPlaybackState(Player.STATE_IDLE))
        assertEquals(PlayerState.BUFFERING, PlayerState.fromPlaybackState(Player.STATE_BUFFERING))
        assertEquals(PlayerState.READY, PlayerState.fromPlaybackState(Player.STATE_READY))
        assertEquals(PlayerState.ENDED, PlayerState.fromPlaybackState(Player.STATE_ENDED))
    }

    @Test
    fun `an unrecognised playback state is treated as idle rather than crashing`() {
        assertEquals(PlayerState.IDLE, PlayerState.fromPlaybackState(Int.MIN_VALUE))
        assertEquals(PlayerState.IDLE, PlayerState.fromPlaybackState(Int.MAX_VALUE))
    }
}
