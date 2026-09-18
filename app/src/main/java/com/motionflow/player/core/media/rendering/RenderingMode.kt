package com.motionflow.player.core.media.rendering

/**
 * What is happening to a decoded frame between the decoder and the display.
 *
 * The application never renders video itself: Media3's video renderer takes the decoder's output and
 * presents it on the surface. These modes describe whether anything *else* is in that path.
 *
 * There is deliberately no mode for an active processing stage. Media3's renderer can host one — it
 * builds a `VideoFrameProcessor` from the effects attached with `ExoPlayer.setVideoEffects` — and
 * this project does not attach any, because doing so starts a GPU pipeline that copies and re-creates
 * frame resources for every frame. A mode for it would be a claim this phase cannot support.
 */
enum class RenderingMode {

    /**
     * Media3's own path, with nothing established about processing yet.
     *
     * The default, and the honest description before the surface and the environment are known.
     */
    NATIVE_MEDIA3,

    /**
     * A processing stage could be attached to this surface, and none is.
     *
     * The frames on screen are exactly the decoder's output.
     */
    PROCESSING_NOT_ACTIVE,

    /**
     * A processing stage cannot be attached here — usually because no surface is bound yet.
     */
    PROCESSING_UNAVAILABLE,
}

/**
 * The kind of view Media3 is presenting video through.
 *
 * Derived from the view actually in use rather than assumed: Media3's `PlayerView` builds whichever
 * its `surface_type` says, and the foundation reports what it finds.
 */
enum class SurfaceType {

    /** A `SurfaceView`: its surface is composited by the system, outside the app's view hierarchy. */
    SURFACE_VIEW,

    /** A `TextureView`: its surface is a texture composited as part of the app's view hierarchy. */
    TEXTURE_VIEW,

    /** Nothing is bound, or the view is neither of the two above. */
    UNKNOWN,
}
