package com.motionflow.player.core.media.rendering

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Describes the rendering path, without joining it.
 *
 * Android-free, and deliberately without a coroutine scope: every input is an event that arrives
 * rarely, so a description is published synchronously from the event that caused it. There is no
 * timer, no polling and no per-frame work, and nothing here can delay a frame.
 *
 * The foundation never renders anything and never attaches anything. It knows which surface Media3 is
 * drawing on and the handful of baseline numbers that can be taken without touching frame data. If it
 * were removed, playback would be identical.
 *
 * ## What it is told, and by whom
 *
 * | Input | Source | Why there |
 * | --- | --- | --- |
 * | Surface created/released, and its type | The player screen, which composes the view | The view is the screen's, so the screen is what can observe it |
 * | First frame rendered, video size | The player, through its listener | Media3 knows when it has presented a frame |
 *
 * Whether a processing stage is attached is *not* decided here: that question belongs to
 * `core/media/processing`, which asks the player directly. Describing a path and changing it are
 * different jobs, and keeping them in one class is how a foundation starts to touch frames.
 */
class RenderingCoordinator {

    private val _diagnostics = MutableStateFlow(RenderingDiagnostics.NativeDefault)
    val diagnostics: StateFlow<RenderingDiagnostics> = _diagnostics.asStateFlow()

    private var surfaceBound = false
    private var surfaceType = SurfaceType.UNKNOWN
    private var attachCount = 0
    private var detachCount = 0

    private var metrics = RenderingMetrics.Empty

    /**
     * Records that the player screen has a surface.
     *
     * A second call while one is already recorded is ignored: a surface that is already bound has not
     * been attached again, and counting it would make the baseline numbers lie.
     */
    fun onSurfaceCreated(surfaceType: SurfaceType) {
        if (surfaceBound) return

        surfaceBound = true
        attachCount++
        this.surfaceType = surfaceType
        publish()
    }

    /**
     * Records that the surface has gone.
     *
     * Ignored when no surface is recorded, so a release arriving twice, or arriving without its
     * create — a stale event from a view that has already been disposed — cannot unset a binding that
     * is still current.
     */
    fun onSurfaceReleased() {
        if (!surfaceBound) return

        surfaceBound = false
        detachCount++
        publish()
    }

    /** Records how long the player took to present its first frame for the current item. */
    fun onFirstFrameRendered(latencyMs: Long?) {
        metrics = metrics.copy(firstFrameLatencyMs = latencyMs)
        publish()
    }

    /** Records the size of the video being rendered. */
    fun onVideoSizeChanged(width: Int?, height: Int?) {
        metrics = metrics.copy(videoWidth = width, videoHeight = height)
        publish()
    }

    private fun publish() {
        _diagnostics.value = RenderingDiagnostics(
            surface = RenderingSurface(
                // A surface that has gone is not a surface of any kind, whatever kind it was.
                type = if (surfaceBound) surfaceType else SurfaceType.UNKNOWN,
                bound = surfaceBound,
            ),
            metrics = metrics.copy(
                surfaceAttachCount = attachCount,
                surfaceDetachCount = detachCount,
            ),
        )
    }
}
