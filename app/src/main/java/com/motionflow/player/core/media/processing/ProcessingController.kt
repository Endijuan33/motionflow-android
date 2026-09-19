package com.motionflow.player.core.media.processing

/**
 * The application-facing seam: how a screen asks for processing to change.
 *
 * It is one method wide on purpose. A screen may *ask*; it may not reach the player. There is no
 * `ExoPlayer` here, no `Surface`, no `VideoFrameProcessor` and no rendering pipeline, and an
 * implementation must not acquire any of them: this interface crosses the process boundary through
 * the media session, where the player already lives, and a second player or a second surface is
 * exactly what the architecture forbids.
 *
 * Implementations are expected to be asynchronous and must never block a frame. A caller treats a
 * thrown exception as [ProcessingOutcome.UNREACHABLE] rather than as a playback failure: playback
 * never depends on the answer.
 */
interface ProcessingController {

    /**
     * Sends [request] to whoever owns the player, and returns what they answered.
     *
     * Must not throw. Must not retain the caller's state. A refusal is an answer, not an error.
     */
    suspend fun request(request: ProcessingRequest): ProcessingResult
}
