package com.motionflow.player.core.media.rendering

/**
 * Binds a rendering environment for a future frame-processing stage, and reports whether it attached.
 *
 * ## Why nothing implements this
 *
 * The seam exists in Media3 already, and it is not the one this interface would have guessed. Media3's
 * video renderer hosts a `VideoFrameProcessor` — `ExoPlayer.setVideoEffects(List<Effect>)` supplies
 * the effects, and the `Effect` interface lives in `media3-common`, so no extra dependency is needed
 * to name one. A processing stage is therefore attached *inside* Media3's own renderer, with no custom
 * `RenderersFactory`, no custom `MediaCodec` handling and no second surface.
 *
 * Three things follow, and together they explain why this phase binds nothing:
 *
 * 1. **Attaching is not free.** The renderer builds the processor and its GL pipeline only once
 *    effects are supplied; from then on every frame is copied through a texture. That is the opposite
 *    of "no GPU texture per frame, no unnecessary copy, no added latency", which this phase requires.
 *    So no effects are supplied and the processor is never created — verified in
 *    `MediaCodecVideoRenderer`, which skips the whole path while its effects list is null.
 * 2. **It must happen where the player lives.** The `ExoPlayer` instance belongs to the media session
 *    service, not to a screen, so attaching effects is a service-side operation. A future phase needs
 *    a session command to carry it, which is infrastructure this phase does not build.
 * 3. **It is not needed to describe the path.** The diagnostics can say what is available and what is
 *    active without attaching anything.
 *
 * So this interface is the seam a later phase implements, and the coordinator runs without it, which
 * is why every state reports no processing attached.
 */
interface RenderingController {

    /**
     * Called when a surface exists that a processing stage could render through.
     *
     * Implementations must not throw and must not retain the environment beyond the call: the values
     * are plain, and the surface itself is never handed over.
     */
    fun onSurfaceAvailable(environment: RenderingEnvironment): RenderingAttachment

    /** Called when that surface goes away, so a stage can release whatever it bound. */
    fun onSurfaceLost()
}

/**
 * Whether a processing stage attached.
 *
 * [reason] is required when [attached] is false, so a refusal is never silent.
 */
data class RenderingAttachment(
    val attached: Boolean,
    val reason: ProcessingUnavailableReason? = null,
)
