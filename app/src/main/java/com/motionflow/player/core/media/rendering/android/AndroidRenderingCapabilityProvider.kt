package com.motionflow.player.core.media.rendering.android

import android.os.Build
import com.motionflow.player.core.media.rendering.RenderingCapabilityProvider
import com.motionflow.player.core.media.rendering.RenderingEnvironment

/**
 * Reports the API level the application is running on.
 *
 * The entire platform contribution, and deliberately so. The process-scoped parts of the rendering
 * path need nothing else: no `Context`, no `Window`, no `Surface`, nothing that could hold an
 * Activity alive.
 *
 * The GPU is not probed. Whether a processing stage can run is decided by whether Media3's video
 * renderer can host one, and Media3 owns that judgement — a second answer computed here could only
 * disagree with the renderer that has to do the work. Reporting the API level is a fact; guessing at
 * graphics capability is not.
 */
class AndroidRenderingCapabilityProvider : RenderingCapabilityProvider {

    override fun environment(): RenderingEnvironment = RenderingEnvironment(
        apiLevel = Build.VERSION.SDK_INT,
    )
}
