package com.motionflow.player.core.media.rendering

/**
 * Which kind of view Media3 drew the video into.
 *
 * Read from the view that is actually in use rather than assumed, because the answer decides whether a
 * future processing stage could render through it at all — a texture view and a surface view are not
 * interchangeable for that.
 */
enum class SurfaceType {

    /** A `SurfaceView`, composited by the system as a separate layer. Media3's default. */
    SURFACE_VIEW,

    /** A `TextureView`, composited as part of the view hierarchy. */
    TEXTURE_VIEW,

    /** Not known: either no surface has been reported, or it was neither of the above. */
    UNKNOWN,
}

/**
 * The surface the video is drawn on, as the player screen observed it.
 *
 * Two values, no `Surface`, no `View` and no `Context`: the screen owns the view and observes the
 * surface, and only the description crosses the boundary. Nothing in this package can hold the surface
 * itself, which is what keeps a long-lived object from keeping a destroyed view alive.
 *
 * The only renderer this application has ever had is Media3's own: `PlayerFactory` builds a
 * `DefaultRenderersFactory` and never replaces a renderer, decoder or surface. That is an architectural
 * invariant recorded in `ARCHITECTURE.md`, not a runtime flag, so there is no "rendering mode" to
 * report here — only which surface the frames arrive at.
 */
data class RenderingSurface(
    val type: SurfaceType = SurfaceType.UNKNOWN,
    val bound: Boolean = false,
)
