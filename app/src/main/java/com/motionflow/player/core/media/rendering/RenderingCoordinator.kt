package com.motionflow.player.core.media.rendering

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Describes and reports on the rendering path, without joining it.
 *
 * Android-free, and deliberately without a coroutine scope: every input is an event that arrives
 * rarely, so a description is published synchronously from the event that caused it. There is no
 * timer, no polling and no per-frame work, and nothing here can delay a frame.
 *
 * The foundation never renders anything. It knows which surface Media3 is drawing on, whether a
 * processing stage could sit in front of it, and the handful of baseline numbers that can be taken
 * without touching frame data. If it were removed, playback would be identical.
 *
 * ## What it is told, and by whom
 *
 * | Input | Source | Why there |
 * | --- | --- | --- |
 * | Surface created/released, and its type | The player screen, which composes the view | The view is the screen's, so the screen is what can observe it |
 * | First frame rendered, video size | The player, through its listener | Media3 knows when it has presented a frame |
 * | API level | [capabilityProvider] | A platform fact, read once |
 *
 * [controller] is `null` in the application: no processing stage exists to bind. See
 * [RenderingController] for why, and what a later phase would have to add.
 */
class RenderingCoordinator(
    private val capabilityProvider: RenderingCapabilityProvider,
    private val controller: RenderingController? = null,
) {

    private val _diagnostics = MutableStateFlow(RenderingDiagnostics.NativeDefault)
    val diagnostics: StateFlow<RenderingDiagnostics> = _diagnostics.asStateFlow()

    private var apiLevel: Int? = null
    private var apiLevelResolved = false

    private var surfaceBound = false
    private var surfaceType = SurfaceType.UNKNOWN
    private var attachCount = 0
    private var detachCount = 0
    private var attachment: RenderingAttachment? = null

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
        attachment = bindProcessingStage(surfaceType)
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
        runCatching { controller?.onSurfaceLost() }
        attachment = null
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

    private fun bindProcessingStage(surfaceType: SurfaceType): RenderingAttachment? {
        val stage = controller ?: return null
        val environment = RenderingEnvironment(
            apiLevel = resolveApiLevel() ?: return null,
            surfaceType = surfaceType,
        )
        return runCatching { stage.onSurfaceAvailable(environment) }.getOrElse {
            // A stage that cannot bind is a stage that is not there. Playback is unaffected either
            // way, which is the whole point of the seam.
            RenderingAttachment(attached = false, reason = ProcessingUnavailableReason.STAGE_UNAVAILABLE)
        }
    }

    private fun resolveApiLevel(): Int? {
        if (!apiLevelResolved) {
            apiLevelResolved = true
            apiLevel = runCatching { capabilityProvider.environment().apiLevel }.getOrNull()
        }
        return apiLevel
    }

    private fun publish() {
        val attached = attachment?.attached == true
        val attachable = processingAttachable()
        val capabilities = RenderingCapabilities(
            apiLevel = resolveApiLevel(),
            surfaceType = if (surfaceBound) surfaceType else SurfaceType.UNKNOWN,
            processingAttachable = attachable,
        )

        _diagnostics.value = RenderingDiagnostics(
            pipeline = RenderingPipeline(
                mode = modeFor(attachable),
                capabilities = capabilities,
                surfaceBound = surfaceBound,
                processingAttached = attached,
            ),
            metrics = metrics,
            unavailableReason = unavailableReason(),
        )
    }

    /**
     * Whether a processing stage could sit in front of the current surface.
     *
     * True once a surface is bound, because Media3's renderer can host one — that is a property of the
     * renderer, not of this application. A stage bound here that reports it cannot attach makes it
     * false, so the answer always reflects the most specific thing known.
     */
    private fun processingAttachable(): Boolean = when {
        !surfaceBound -> false
        attachment?.attached == true -> true
        attachment?.reason == ProcessingUnavailableReason.STAGE_UNAVAILABLE -> false
        else -> true
    }

    private fun modeFor(attachable: Boolean): RenderingMode =
        if (attachable) RenderingMode.PROCESSING_NOT_ACTIVE else RenderingMode.PROCESSING_UNAVAILABLE

    private fun unavailableReason(): ProcessingUnavailableReason? = when {
        !surfaceBound -> ProcessingUnavailableReason.NO_SURFACE
        attachment?.attached == true -> null
        attachment?.reason != null -> attachment?.reason
        else -> ProcessingUnavailableReason.NO_STAGE_IMPLEMENTED
    }
}
