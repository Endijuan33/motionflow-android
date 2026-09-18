package com.motionflow.player.core.media.rendering

/**
 * The platform facts a rendering environment offers.
 *
 * Deliberately tiny, and deliberately free of Android types: the API level is a number and the
 * surface type is an enum, so this can be handed to a processing stage, asserted in a test, or
 * described in diagnostics without carrying a `Context`, a `Window` or a `Surface` anywhere.
 */
data class RenderingEnvironment(
    val apiLevel: Int,
    val surfaceType: SurfaceType = SurfaceType.UNKNOWN,
)

/**
 * Reports what the platform offers the rendering path.
 *
 * Kept as a seam so the coordinator can be tested against environments this machine is not — an old
 * API level, a texture-view surface, a device with no surface at all.
 *
 * Nothing here probes the GPU. The capability that decides whether a processing stage can run is
 * whether Media3's video renderer can host one, and Media3 is the authority on whether the graphics
 * environment exists; a second opinion computed here could only disagree with the renderer that
 * actually has to do the work.
 */
interface RenderingCapabilityProvider {

    /** Reads the environment. Must not create a graphics resource, and must not throw. */
    fun environment(): RenderingEnvironment
}

/**
 * What the environment supports, and what has been established about it.
 *
 * [processingAttachable] is `null` until a surface is bound, because attachment is a property of a
 * surface: there is nothing to attach a stage to before one exists. It is never guessed.
 */
data class RenderingCapabilities(
    val apiLevel: Int? = null,
    val surfaceType: SurfaceType = SurfaceType.UNKNOWN,
    val processingAttachable: Boolean? = null,
) {

    companion object {

        /** Nothing is known yet. */
        val Unknown = RenderingCapabilities()
    }
}

/** Why a processing stage is not attached. */
enum class ProcessingUnavailableReason {

    /** No surface is bound, so there is nothing for a stage to attach to. */
    NO_SURFACE,

    /**
     * A stage could be attached and none exists.
     *
     * This is the state of the application: Media3's renderer hosts a `VideoFrameProcessor` when
     * effects are supplied, and no effects are supplied, because an attached stage would allocate
     * and copy frame resources on every frame — which this phase exists to avoid.
     */
    NO_STAGE_IMPLEMENTED,

    /** A stage was asked to attach and reported that it could not. */
    STAGE_UNAVAILABLE,
}

/**
 * Baseline numbers for the rendering path.
 *
 * Only measurements that can be taken without touching the frame pipeline appear here. Anything that
 * would require per-frame callbacks — rendered and dropped frame counts, frame-processing offsets —
 * is available from Media3's `AnalyticsListener` but not through a media session, so it is not
 * claimed. See `ARCHITECTURE.md` for what would be needed to consume it.
 *
 * Nothing here identifies a file, a person or a location.
 */
data class RenderingMetrics(
    val firstFrameLatencyMs: Long? = null,
    val surfaceAttachCount: Int = 0,
    val surfaceDetachCount: Int = 0,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
) {

    /** True when the video's size is known. */
    val hasVideoSize: Boolean get() = videoWidth != null && videoHeight != null

    companion object {

        val Empty = RenderingMetrics()
    }
}

/** The rendering path in use. */
data class RenderingPipeline(
    val mode: RenderingMode = RenderingMode.NATIVE_MEDIA3,
    val capabilities: RenderingCapabilities = RenderingCapabilities.Unknown,
    val surfaceBound: Boolean = false,
    val processingAttached: Boolean = false,
) {

    /** Always false in this phase: no processing stage is ever attached. */
    val processingActive: Boolean get() = processingAttached

    companion object {

        val NativeDefault = RenderingPipeline()
    }
}

/**
 * Everything the rendering foundation can say about the current path.
 *
 * [unavailableReason] explains a [RenderingMode.PROCESSING_UNAVAILABLE] or a bound-but-inactive
 * pipeline, so the diagnostics never have to guess why processing is not running.
 */
data class RenderingDiagnostics(
    val pipeline: RenderingPipeline = RenderingPipeline.NativeDefault,
    val metrics: RenderingMetrics = RenderingMetrics.Empty,
    val unavailableReason: ProcessingUnavailableReason? = null,
) {

    /** The surface type, or [SurfaceType.UNKNOWN] before one is bound. */
    val surfaceType: SurfaceType get() = pipeline.capabilities.surfaceType

    companion object {

        val NativeDefault = RenderingDiagnostics()
    }
}
