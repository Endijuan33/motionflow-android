package com.motionflow.player.core.media.processing

/**
 * Where a processing stage would be attached: beside the player, and nowhere else.
 *
 * This is the far end of the request path —
 * `player surface → view model → session command → session service → MotionFlowPlayer → here` — and it
 * is the only processing code that lives next to the `ExoPlayer`, which is why the effect call, when
 * it exists, belongs here and in no other class.
 *
 * ## What it does, and why it is not more
 *
 * It answers every request with the truth and attaches nothing, because in this build nothing can be
 * attached. Two verified facts, both specific to Media3 1.11.1, are the reason:
 *
 * 1. **The effects module is not on the classpath.** `ExoPlayerImpl.setVideoEffects` is guarded by
 *    `Class.forName("androidx.media3.effect.SingleInputVideoGraph$Factory")` and throws
 *    `IllegalStateException("Could not find required lib-effect dependencies.")` when that lookup
 *    fails. The guard runs on *every* call, so `setVideoEffects(emptyList())` throws too — there is no
 *    safe way to call the API from this build, not even to clear it. Supporting it means adding
 *    `androidx.media3:media3-effect`, a graphics module whose shaders compile frame copies on the GPU,
 *    and adding that would be an active processing path rather than the inactive seam this phase is
 *    allowed to introduce.
 * 2. **Even with the module, the pipeline cannot be armed on demand.** `setVideoEffects` must be called
 *    before the video renderer is first enabled for the effects pipeline to exist
 *    (`MediaCodecVideoRenderer.onEnabled` builds the sink only while its effects field is non-null),
 *    while the effects themselves may be replaced afterwards. Attaching on request therefore cannot be
 *    the call that creates the pipeline; making on-demand attachment possible would mean installing a
 *    pass-through graphics pipeline for every session up front — a per-frame copy for people who never
 *    request processing.
 *
 * So the request is answered, refused, and recorded; playback keeps the path it had. Nothing here
 * touches a frame, a timestamp, a surface, the decoder or the display, and a refusal cannot make
 * playback unavailable.
 *
 * ## What a later phase changes
 *
 * Exactly this class, and exactly two things: the dependency in the version catalog, and the enable
 * branch below becoming `ExoPlayer.setVideoEffects(effects)` with [effectsModuleLinked] left at its
 * default. Nothing else in the application has to move, which is the property this phase exists to
 * establish.
 */
class PlayerProcessingEndpoint(

    /**
     * Whether this build links `androidx.media3:media3-effect`, which `setVideoEffects` requires.
     *
     * A build fact, not a device fact, and a parameter rather than a constant so that the branch below
     * is real executable code that a test can drive both ways instead of dead code a compiler warns
     * about. False for this application.
     */
    private val effectsModuleLinked: Boolean = false,
) {

    /**
     * Answers a processing request where the player lives.
     *
     * A disable request is answered by confirming the state that already holds: the player is on
     * Media3's own path and nothing is attached. That is what makes the phase's fallback guarantee
     * literal — after any refusal, a later disable request returns the player to native rendering.
     */
    fun onRequest(request: ProcessingRequest): ProcessingResult = when (request) {
        ProcessingRequest.ENABLE -> when {
            effectsModuleLinked -> ProcessingResult.refused(ProcessingReason.NO_STAGE_IMPLEMENTED)
            else -> ProcessingResult.refused(ProcessingReason.EFFECTS_MODULE_ABSENT)
        }

        ProcessingRequest.DISABLE -> ProcessingResult.Detached
    }
}
