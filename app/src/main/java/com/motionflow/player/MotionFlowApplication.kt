package com.motionflow.player

import android.app.Application
import com.motionflow.player.core.media.metadata.VideoMetadataRepository
import com.motionflow.player.core.media.performance.PerformanceHistoryStore
import com.motionflow.player.core.media.player.PlaybackConfigurationStore

/**
 * Process-level entry point for MotionFlow.
 *
 * Owns the things that must outlive any screen. The metadata repository lives here rather than in a
 * view model so that a description read once stays read: navigating away from the player and back,
 * or opening the same file from a future library screen, reuses it instead of re-reading the file.
 *
 * It also owns the two things a measurement needs to outlive the code that produced them:
 *
 * - **The requested playback configuration**, because the engine is built by the media session service,
 *   which may not exist yet when the choice is made and which stops between measurements.
 * - **The measurement history**, because a comparison spans two runs under two pipelines, and changing
 *   the pipeline restarts the service.
 *
 * Neither is persisted. There is no settings architecture in this project yet, and inventing one for an
 * experiment's settings would be a worse decision than making the experiment cheap to repeat.
 */
class MotionFlowApplication : Application() {

    /** Reads and caches technical descriptions of media sources. */
    val metadataRepository: VideoMetadataRepository by lazy {
        VideoMetadataRepository.from(this)
    }

    /** The pipeline the next engine should be built for. */
    val playbackConfigurationStore: PlaybackConfigurationStore by lazy {
        PlaybackConfigurationStore()
    }

    /** The last measurement taken on each baseline. */
    val performanceHistoryStore: PerformanceHistoryStore by lazy {
        PerformanceHistoryStore()
    }
}
