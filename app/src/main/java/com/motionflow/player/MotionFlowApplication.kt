package com.motionflow.player

import android.app.Application
import com.motionflow.player.core.media.metadata.VideoMetadataRepository

/**
 * Process-level entry point for MotionFlow.
 *
 * Owns the things that must outlive any screen. The metadata repository lives here rather than in a
 * view model so that a description read once stays read: navigating away from the player and back,
 * or opening the same file from a future library screen, reuses it instead of re-reading the file.
 */
class MotionFlowApplication : Application() {

    /** Reads and caches technical descriptions of media sources. */
    val metadataRepository: VideoMetadataRepository by lazy {
        VideoMetadataRepository.from(this)
    }
}
