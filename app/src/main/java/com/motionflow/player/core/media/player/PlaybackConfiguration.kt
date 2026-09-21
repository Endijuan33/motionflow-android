package com.motionflow.player.core.media.player

import com.motionflow.player.core.media.performance.ProcessingPerformanceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the playback engine should be built.
 *
 * One field, because this phase compares exactly two pipelines and nothing about how the engine is
 * constructed changes between them other than whether an identity effect is handed to the renderer
 * before the first `prepare()`.
 *
 * ## Why this exists as a value rather than as a call
 *
 * Media3 requires the effects pipeline to be armed *before* `prepare()`. There is therefore no moment
 * during playback at which the pipeline can be turned on, and no honest way to offer that as a toggle.
 * A configuration the engine is *built* from is the only shape that matches the API, and it keeps the
 * decision visible in one place: the engine is constructed for a pipeline, and it stays on it.
 */
data class PlaybackConfiguration(
    val processingMode: ProcessingPerformanceMode = ProcessingPerformanceMode.NATIVE,
) {

    /** True when the engine should be built with the effect pipeline armed. */
    val usesEffectPipeline: Boolean
        get() = processingMode == ProcessingPerformanceMode.EFFECT_PIPELINE

    companion object {

        /** The control condition, and the default. */
        val Native = PlaybackConfiguration()
    }
}

/**
 * The configuration the *user* has asked for, held for the length of one process.
 *
 * Application-scoped rather than screen- or service-scoped, because the engine is built by the media
 * session service — which may not exist yet when the choice is made, and which stops between
 * measurements. A value a screen owned would be gone exactly when the engine needed it.
 *
 * Nothing is persisted: this is not a preference, it is the setting of an experiment, and an experiment
 * that silently resumes in a different condition than the last run is worse than one that starts from
 * the control condition again.
 */
class PlaybackConfigurationStore(initial: PlaybackConfiguration = PlaybackConfiguration.Native) {

    private val _requested = MutableStateFlow(initial)

    /** What the next engine should be built for. */
    val requested: StateFlow<PlaybackConfiguration> = _requested.asStateFlow()

    /** Records the user's choice. Takes effect the next time the engine is built. */
    fun request(configuration: PlaybackConfiguration) {
        if (_requested.value == configuration) return
        _requested.value = configuration
    }
}
