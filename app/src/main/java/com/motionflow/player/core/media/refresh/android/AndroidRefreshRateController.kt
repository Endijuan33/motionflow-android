package com.motionflow.player.core.media.refresh.android

import android.util.Log
import android.view.Window
import com.motionflow.player.core.media.refresh.RefreshRateApplication
import com.motionflow.player.core.media.refresh.RefreshRateController
import com.motionflow.player.core.media.refresh.RefreshRateError
import com.motionflow.player.core.media.refresh.RefreshRateRequest
import com.motionflow.player.core.media.refresh.RefreshRateSource

/**
 * Asks the platform for a refresh rate through the player window's attributes.
 *
 * The mechanism is `WindowManager.LayoutParams.preferredRefreshRate`: the app states the rate it
 * wants while leaving every other window property alone, which is what the framework recommends over
 * pinning a whole display mode (that would also pin a resolution). AOSP documents this attribute as
 * the equivalent of `Surface.setFrameRate(rate, FRAME_RATE_COMPATIBILITY_DEFAULT)`, so it is the
 * window-level form of Android's frame-rate compatibility API.
 *
 * Below API 34 the platform requires this to be one of the rates the display reports, and the policy
 * picks its target from exactly that list — so requests are always well-formed, and there is no
 * version branch here.
 *
 * It is a request, not a command: the window manager may ignore it (multi-window, a device that
 * controls the panel itself, a mode change it deems too disruptive), which is why the diagnostics
 * report the rate the display actually reports separately from the rate that was asked for.
 *
 * Must be called on the main thread.
 */
class AndroidRefreshRateController(private val window: Window) : RefreshRateController {

    /** What the window asked for before this controller changed anything, so it can be put back. */
    private var previousPreferredRefreshRate: Float? = null

    override fun apply(request: RefreshRateRequest): RefreshRateApplication {
        if (previousPreferredRefreshRate == null) {
            previousPreferredRefreshRate = runCatching { window.attributes.preferredRefreshRate }
                .getOrNull()
        }
        return setPreferredRefreshRate(request.refreshRateHz)
    }

    override fun clear() {
        val previous = previousPreferredRefreshRate ?: return
        previousPreferredRefreshRate = null
        setPreferredRefreshRate(previous)
    }

    private fun setPreferredRefreshRate(refreshRateHz: Float): RefreshRateApplication = runCatching {
        // The framework keeps the window's own attribute object; adjusting the single field and
        // handing it back is the documented way to change window attributes without discarding
        // everything else the framework has set.
        val attributes = window.attributes
        attributes.preferredRefreshRate = refreshRateHz
        window.attributes = attributes
        RefreshRateApplication(applied = true, source = RefreshRateSource.WINDOW_REFRESH_RATE)
    }.getOrElse { failure ->
        Log.w(TAG, "The platform refused a refresh-rate request", failure)
        RefreshRateApplication(
            applied = false,
            source = RefreshRateSource.NONE,
            error = RefreshRateError.PLATFORM_REJECTED,
        )
    }

    private companion object {
        const val TAG = "MotionFlowRefresh"
    }
}
