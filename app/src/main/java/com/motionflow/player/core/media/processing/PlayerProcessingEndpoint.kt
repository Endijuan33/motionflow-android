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
 * It answers every request with the truth and attaches nothing, because this application has no stage
 * to give it. One verified fact, specific to Media3 1.11.1, is the reason, and one is a decision:
 *
 * 1. **The pipeline cannot be armed on demand.** `setVideoEffects` must be called before the video
 *    renderer is first enabled for the effects pipeline to exist (`MediaCodecVideoRenderer.onEnabled`
 *    builds the sink only while its effects field is non-null), while the effects themselves may be
 *    replaced afterwards. Attaching on request therefore cannot be the call that creates the pipeline;
 *    making on-demand attachment possible would mean installing a pass-through graphics pipeline for
 *    every session up front — a per-frame copy for people who never request processing.
 * 2. **Nothing here implements a stage that changes pictures.** Phase 7 links
 *    `androidx.media3:media3-effect` and arms Media3's identity effect for the *measured* baseline,
 *    built into the engine before `prepare()`. That is a measurement condition, not an interactive
 *    feature: this endpoint's enable branch stays a refusal, and [effectsModuleLinked] records whether
 *    the dependency the call would need is even present.
 *
 * So the request is answered, refused, and recorded; playback keeps the path it had. Nothing here
 * touches a frame, a timestamp, a surface, the decoder or the display, and a refusal cannot make
 * playback unavailable.
 *
 * ## What a later phase changes
 *
 * The enable branch below, and the engine's configuration — which Phase 7 already showed how to do:
 * the pipeline is chosen while the engine is built, never during playback. Nothing else in the
 * application has to move, which is the property these phases exist to establish.
 */
class PlayerProcessingEndpoint(

    /**
     * Whether this build links `androidx.media3:media3-effect`, which `setVideoEffects` requires.
     *
     * A build fact, not a device fact, and a parameter rather than a constant so that the branch below
     * is real executable code a test can drive both ways rather than dead code a compiler warns about.
     * True since Phase 7 linked the module; the refusal reason follows it, so the diagnostics would name
     * the dependency again if it were ever removed.
     */
    private val effectsModuleLinked: Boolean = true,
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
