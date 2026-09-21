package com.motionflow.player.core.media.player

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.motionflow.player.core.media.performance.android.ProcessingBaselines

/**
 * Builds the playback engine.
 *
 * This is the only place that decides how media is decoded and rendered. The engine is left on its
 * standard Media3 pipeline: `DefaultRenderersFactory` selects `MediaCodec`-backed hardware decoders
 * for every track they support and only falls back to software when a hardware decoder is absent or
 * fails to initialise. Nothing here overrides codec selection, forces a software path, or reaches
 * into decoder internals.
 *
 * Replacing the pipeline (a custom `RenderersFactory`, an injected `VideoFrameProcessor`) is
 * therefore a change to this file alone.
 */
internal object PlayerFactory {

    private const val TAG = "MotionFlowPlayer"

    // Local files need far less buffer than a streaming source, and a smaller minimum buffer means
    // the first frame arrives sooner.
    private const val MIN_BUFFER_MS = 15_000
    private const val MAX_BUFFER_MS = 50_000
    private const val BUFFER_FOR_PLAYBACK_MS = 1_500
    private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

    /**
     * Creates a configured [ExoPlayer] for local playback.
     *
     * Audio focus is handled by the player itself: playback pauses when another app takes focus and
     * resumes when it is returned. Headphones being unplugged pauses playback rather than switching
     * to the speaker.
     *
     * ## Why the configuration is a parameter
     *
     * Media3 requires the effects pipeline to exist before `prepare()`, so the pipeline is chosen while
     * the engine is being built rather than during playback — and it is armed here, on the engine, before
     * this function returns and therefore before anything can prepare it. That ordering is the whole
     * reason the processing mode is a construction argument, and it is what makes the effect baseline a
     * controlled condition instead of a mid-playback toggle.
     */
    // DefaultLoadControl, DefaultRenderersFactory and DefaultTrackSelector are Media3's
    // "unstable" surface: their behaviour is supported, their exact signatures are not frozen yet.
    // The annotation is written fully qualified because Kotlin also has a `kotlin.OptIn`.
    @androidx.annotation.OptIn(UnstableApi::class)
    fun createExoPlayer(
        context: Context,
        configuration: PlaybackConfiguration = PlaybackConfiguration.Native,
    ): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()

        Log.d(TAG, "Creating ExoPlayer instance for ${configuration.processingMode}")

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(DefaultTrackSelector(context))
            .setLoadControl(loadControl)
            .build()
            .apply {
                // Absent for the native baseline — and absent means "not called at all", because an
                // empty list would still arm the pipeline this baseline exists to avoid.
                ProcessingBaselines.effectsFor(configuration.processingMode)?.let { setVideoEffects(it) }

                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                setHandleAudioBecomingNoisy(true)
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = false
            }
    }
}
