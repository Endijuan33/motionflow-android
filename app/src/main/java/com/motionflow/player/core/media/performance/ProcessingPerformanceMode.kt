package com.motionflow.player.core.media.performance

/**
 * Which playback pipeline a measurement was taken on.
 *
 * Three values, and only three, because this phase compares two known pipelines and reports when one
 * of them could not run. There is deliberately no *interpolation* state: nothing here generates a
 * frame, and a label claiming otherwise would be the single most misleading thing this project could
 * print. [EFFECT_PIPELINE] says a stage is in the path — an identity one, measured — and says nothing
 * about pictures being invented.
 *
 * The distinction between "the effect pipeline is in the path" and "a stage reported attached"
 * (Phase 6's `ProcessingMode`) is real and deliberate: this enum names the *condition a measurement
 * was taken under*, which is decided before playback starts, while Phase 6's model reports the live
 * attachment state of whatever is playing. A session records its condition; the diagnostics report
 * both.
 */
enum class ProcessingPerformanceMode {

    /**
     * MediaCodec → Media3's own video renderer → SurfaceView → display, with no processing stage.
     *
     * The control condition: every other measurement is read against this one.
     */
    NATIVE,

    /**
     * The same path with Media3's effect pipeline armed by an identity effect.
     *
     * The frames still come from the decoder, are still presented once each, and are still the same
     * pictures. What changes is that they travel through the renderer's own frame processor on the way
     * to the surface, which is the cost this baseline exists to measure.
     */
    EFFECT_PIPELINE,

    /** The effect pipeline was requested and the player could not run it. Playback is not implied to have survived. */
    FAILED;

    /** True when a measurement taken in this mode describes a working pipeline. */
    val isMeasurable: Boolean get() = this != FAILED

    companion object {

        /** Resolves a mode name, or `null` when it is absent or not one this build knows. */
        fun fromName(name: String?): ProcessingPerformanceMode? =
            name?.let { candidate -> entries.firstOrNull { it.name == candidate } }
    }
}
