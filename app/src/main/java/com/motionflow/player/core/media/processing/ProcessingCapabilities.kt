package com.motionflow.player.core.media.processing

/**
 * What the rendering environment can accept.
 *
 * This describes an *architectural* capability, not a device one. Media3's own video renderer hosts a
 * `VideoFrameProcessor` when effects are supplied, so a stage attaches inside the renderer rather than
 * beside it — a property of the renderer, not of the hardware. Nothing here probes the GPU, and
 * nothing here is evidence that any particular device could run a future interpolation model. A
 * capability that cannot be established stays `false` rather than being guessed.
 */
data class ProcessingCapabilities(

    /** Whether a video surface is bound. Nothing can be rendered through a stage without one. */
    val surfaceBound: Boolean = false,

    /**
     * Whether the renderer can host an official Media3 effect at all.
     *
     * False while no surface is bound, because the renderer needs somewhere to render to. It records
     * the *shape* of the pipeline, not its speed: see [ProcessingReason.EFFECTS_MODULE_ABSENT] for the
     * build-level prerequisite that this flag deliberately does not cover.
     */
    val canHostEffect: Boolean = false,
) {

    /** True when a stage could be attached here. Says nothing about whether one exists. */
    val canAttach: Boolean get() = surfaceBound && canHostEffect

    companion object {

        /** Nothing is known yet. */
        val Unknown = ProcessingCapabilities()
    }
}
