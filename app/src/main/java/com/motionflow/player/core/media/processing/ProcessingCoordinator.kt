package com.motionflow.player.core.media.processing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether a processing stage is in the video path, and why not when it is not.
 *
 * Android-free, Media3-free and without a scope of its own: it is handed events and requests, and it
 * publishes a description of what it was told. Nothing here touches a frame, a surface, the display or
 * the decoder, and nothing here can delay playback. Remove the class and playback is unchanged.
 *
 * ## What it is told, and by whom
 *
 * | Input | Source | Why there |
 * | --- | --- | --- |
 * | Surface bound or released | The player screen, which composes the view | The view is the screen's, so the screen is what can observe it |
 * | The controller | The view model, when the session connects | The player is process-owned, so a screen reaches it through the session or not at all |
 * | Cadence and display rate | The view model, from the engines that already know them | Copied, never computed here |
 *
 * ## What it is not allowed to conclude
 *
 * [ProcessingDiagnostics.effectAttached] is set only from an outcome a player reported. No request,
 * however phrased, can make this coordinator say a stage is attached, and no failure here changes
 * playback: the mode describes attachment, and a refusal leaves the path exactly as it was.
 */
class ProcessingCoordinator {

    private val _diagnostics = MutableStateFlow(ProcessingDiagnostics.Native)
    val diagnostics: StateFlow<ProcessingDiagnostics> = _diagnostics.asStateFlow()

    private var controller: ProcessingController? = null

    private var surfaceBound = false
    private var attached = false
    private var lastAttachmentSucceeded: Boolean? = null
    private var lastRequestFailed: Boolean? = null
    private var lastReason: ProcessingReason? = null

    private var attachCount = 0
    private var detachCount = 0
    private var requestCount = 0

    private var videoFps: Float? = null
    private var displayRefreshRateHz: Float? = null

    /**
     * Binds the controller that speaks for the process-owned player, or `null` to bind none.
     *
     * Called when the session connection goes up or down. It binds a transport, never a stage: nothing
     * in this class, or reachable from it, is an `ExoPlayer`.
     */
    fun bindController(controller: ProcessingController?) {
        this.controller = controller
        publish()
    }

    /** Releases the controller; a later request is answered as unreachable rather than attempted. */
    fun unbindController() = bindController(null)

    /**
     * Records that the player screen has, or no longer has, a video surface.
     *
     * A repeated report of the same state is ignored: a surface that is already bound has not been
     * bound again, and a stale release must not clear a record of what happened while it was bound.
     */
    fun onSurfaceChanged(bound: Boolean) {
        if (bound == surfaceBound) return

        surfaceBound = bound
        if (!bound && attached) {
            // The renderer cannot keep a stage that has nothing to render through.
            attached = false
            detachCount++
        }
        // A new surface is a new question; the previous answer described the previous one.
        lastAttachmentSucceeded = null
        lastRequestFailed = null
        lastReason = null
        publish()
    }

    /**
     * Asks for processing to be turned on or off, and records the answer.
     *
     * Two refusals are made here rather than sent: with no surface there is nothing to attach to, and
     * with no controller there is nobody to ask. Both are recorded as structured answers, so a caller
     * cannot tell an impossible request from an unreachable one by the absence of a result.
     */
    suspend fun request(request: ProcessingRequest) {
        requestCount++

        val result = when {
            request == ProcessingRequest.ENABLE && !surfaceBound ->
                ProcessingResult.refused(ProcessingReason.NO_SURFACE)

            else -> {
                val controller = controller
                if (controller == null) {
                    ProcessingResult.unreachable(ProcessingReason.TRANSPORT_FAILED)
                } else {
                    // A controller that throws has told us nothing; that is a transport failure, not a
                    // playback failure, and it must not escape into the caller's coroutine.
                    // Cancellation is rethrown rather than dressed up as a refusal: a caller that went
                    // away did not get refused, and swallowing it would break structured concurrency.
                    try {
                        controller.request(request)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        ProcessingResult.unreachable(ProcessingReason.TRANSPORT_FAILED)
                    }
                }
            }
        }

        record(request, result)
        publish()
    }

    /**
     * Records the source's frame rate and the display's refresh rate.
     *
     * A copy of facts two other engines already own. Processing neither influences nor derives from
     * them; they are carried so a reader does not have to join three sources to answer one question.
     */
    fun onCadence(videoFps: Float?, displayRefreshRateHz: Float?) {
        if (videoFps == this.videoFps && displayRefreshRateHz == this.displayRefreshRateHz) return

        this.videoFps = videoFps
        this.displayRefreshRateHz = displayRefreshRateHz
        publish()
    }

    private fun record(request: ProcessingRequest, result: ProcessingResult) {
        when (result.outcome) {
            ProcessingOutcome.ATTACHED -> if (!attached) {
                attached = true
                attachCount++
            }

            ProcessingOutcome.DETACHED -> if (attached) {
                attached = false
                detachCount++
            }

            // A refusal changes nothing, which is the point of refusing.
            ProcessingOutcome.REFUSED, ProcessingOutcome.UNREACHABLE -> Unit
        }

        lastRequestFailed = when (result.outcome) {
            ProcessingOutcome.ATTACHED, ProcessingOutcome.DETACHED -> false
            ProcessingOutcome.REFUSED, ProcessingOutcome.UNREACHABLE -> true
        }
        if (result.reason != null) lastReason = result.reason
        // Only an enable request answers the attach question; a disable leaves the last answer as it
        // stands, because "the last attempt failed" stays true after the attempt is abandoned.
        if (request == ProcessingRequest.ENABLE) {
            lastAttachmentSucceeded = result.attached
        }
    }

    private fun publish() {
        val capabilities = ProcessingCapabilities(
            surfaceBound = surfaceBound,
            // The renderer hosts the stage inside itself and needs somewhere to render to, so the
            // capability follows the surface. It is a statement about the pipeline's shape, not a
            // measurement of any device.
            canHostEffect = surfaceBound,
        )
        val mode = modeFor()

        _diagnostics.value = ProcessingDiagnostics(
            mode = mode,
            reason = reasonFor(mode),
            capabilities = capabilities,
            effectAttached = attached,
            lastAttachmentSucceeded = lastAttachmentSucceeded,
            attachCount = attachCount,
            detachCount = detachCount,
            requestCount = requestCount,
            videoFps = videoFps,
            displayRefreshRateHz = displayRefreshRateHz,
        )
    }

    private fun modeFor(): ProcessingMode = when {
        !surfaceBound -> ProcessingMode.PROCESSING_UNAVAILABLE
        attached -> ProcessingMode.PROCESSING_ACTIVE
        lastRequestFailed == true -> ProcessingMode.PROCESSING_FAILED
        else -> ProcessingMode.PROCESSING_INACTIVE
    }

    private fun reasonFor(mode: ProcessingMode): ProcessingReason? = when (mode) {
        ProcessingMode.NATIVE, ProcessingMode.PROCESSING_ACTIVE -> null

        ProcessingMode.PROCESSING_UNAVAILABLE -> ProcessingReason.NO_SURFACE

        // A refusal or an unreachable request has already named its cause; fall back rather than
        // showing "failed" with no reason at all.
        ProcessingMode.PROCESSING_FAILED -> lastReason ?: ProcessingReason.REQUEST_REFUSED

        // Nothing is attached and no request has been answered yet: the renderer could host a stage
        // and this application has none.
        ProcessingMode.PROCESSING_INACTIVE -> lastReason ?: ProcessingReason.NO_STAGE_IMPLEMENTED
    }
}
