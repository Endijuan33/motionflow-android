package com.motionflow.player.core.media.performance.android

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.AlphaScale
import com.motionflow.player.core.media.performance.ProcessingPerformanceMode

/**
 * The effects that define each measurement baseline, in one place.
 *
 * This is the only file in the project that names a Media3 effect class, and it is deliberately dull:
 * the whole of the effect-pipeline baseline is one constructor call.
 *
 * ## The identity effect, and why it is this one
 *
 * [AlphaScale] with a scale of `1` is the safest pass-through Media3 1.11.1 publishes, and it is
 * documented as one rather than merely being one in practice:
 *
 * - Its Javadoc: *"An `alphaScale` value of `1` means no change is applied."*
 * - It reports `isNoOp(inputWidth, inputHeight) == true` for that value.
 * - `AlphaScaleShaderProgram` sets the identity transformation and texture matrices, and its
 *   `configure()` returns the input size unchanged, so nothing is scaled, cropped, resampled or
 *   recoloured. The only shader arithmetic is a multiply by exactly `1.0f`.
 * - It carries no timing behaviour at all: no frame-rate change, no speed change, no timestamp
 *   adjustment, no audio path, and nothing that could influence the display mode.
 *
 * ## Why a declared no-op still measures something
 *
 * `GlEffect.isNoOp` is a hint, and Media3's *playback* path never consults it — verified by reading
 * `PlaybackVideoGraphWrapper` and `DefaultVideoFrameProcessor`, neither of which mentions it. So this
 * effect is not skipped: supplying it makes the renderer build its frame processor, upload each frame
 * to a texture, run the shader and composite the result to the output surface. That is precisely the
 * cost this baseline exists to measure, and it is why the effect has to be a real one rather than a
 * marker type the pipeline would ignore.
 */
object ProcessingBaselines {

    /** The alpha scale that changes nothing. */
    const val IDENTITY_ALPHA = 1f

    /**
     * What to hand `ExoPlayer.setVideoEffects` for [mode], or `null` to call it not at all.
     *
     * The distinction between `null` and an empty list is the whole reason this returns a nullable:
     * `MediaCodecVideoRenderer` builds its frame processor whenever the effects field is non-null, so
     * passing `emptyList()` for the control condition would quietly put the native baseline on the
     * pipeline it is supposed to be the control for.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun effectsFor(mode: ProcessingPerformanceMode): List<Effect>? = when (mode) {
        ProcessingPerformanceMode.EFFECT_PIPELINE -> listOf(AlphaScale(IDENTITY_ALPHA))
        ProcessingPerformanceMode.NATIVE, ProcessingPerformanceMode.FAILED -> null
    }
}
