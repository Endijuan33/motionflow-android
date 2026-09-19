package com.motionflow.player.core.media.rendering

/**
 * Baseline numbers for the rendering path.
 *
 * Only measurements that can be taken without touching the frame pipeline appear here. Anything that
 * would require per-frame callbacks — rendered and dropped frame counts, frame-processing offsets —
 * is available from Media3's `AnalyticsListener` but not through a media session, so it is not
 * claimed.
 *
 * Nothing here identifies a file, a person or a location, and nothing is polled: a number changes when
 * an event arrives and not otherwise.
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

/**
 * What the rendering path is, as far as it can be observed from the application's side.
 *
 * The description only. Whether a processing stage is attached — and why not — is a different
 * question with a different owner (`core/media/processing`), so it is not duplicated here: a single
 * place decides processing state, and this one describes the path frames take.
 */
data class RenderingDiagnostics(
    val surface: RenderingSurface = RenderingSurface(),
    val metrics: RenderingMetrics = RenderingMetrics.Empty,
) {

    companion object {

        val NativeDefault = RenderingDiagnostics()
    }
}
