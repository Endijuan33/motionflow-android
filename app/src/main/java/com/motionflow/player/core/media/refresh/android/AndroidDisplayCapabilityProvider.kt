package com.motionflow.player.core.media.refresh.android

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Window
import com.motionflow.player.core.media.refresh.DisplayCapabilityProvider
import com.motionflow.player.core.media.refresh.DisplayModeInfo
import com.motionflow.player.core.media.refresh.DisplayRefreshCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Reads display modes from the display the player window is on.
 *
 * Everything here is public API that exists from API 23 (`Display.getSupportedModes`,
 * `Display.getMode`, `DisplayManager.registerDisplayListener`), and minSdk is 26, so there is no
 * version branching and nothing reflective. Nothing throws: a window that is not attached, a display
 * that cannot be resolved, or a device that declines to list its modes all come back as
 * [DisplayRefreshCapabilities.Unknown], which the policy reads as "decline to choose".
 *
 * The window's own display is preferred over the default one, so a video playing on an external or
 * folded-out display is described by the display it is actually on.
 */
class AndroidDisplayCapabilityProvider(private val window: Window) : DisplayCapabilityProvider {

    override fun capabilities(): DisplayRefreshCapabilities {
        val display = resolveDisplay() ?: return DisplayRefreshCapabilities.Unknown

        val modes = runCatching { display.supportedModes }.getOrNull().orEmpty()
        if (modes.isEmpty()) {
            return DisplayRefreshCapabilities(displayId = display.displayId)
        }

        val currentModeId = runCatching { display.mode?.modeId }.getOrNull()

        return DisplayRefreshCapabilities(
            displayId = display.displayId,
            modes = modes.map { mode ->
                DisplayModeInfo(
                    modeId = mode.modeId,
                    width = mode.physicalWidth,
                    height = mode.physicalHeight,
                    refreshRateHz = mode.refreshRate,
                    isCurrent = mode.modeId == currentModeId,
                )
            },
        )
    }

    override fun displayChanges(): Flow<Unit> = callbackFlow {
        val displayManager = displayManager()
        if (displayManager == null) {
            // A device that offers no display service has nothing to report; the flow stays open and
            // silent rather than pretending a change happened.
            awaitClose { }
            return@callbackFlow
        }

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                this@callbackFlow.trySend(Unit)
            }
        }

        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        awaitClose { runCatching { displayManager.unregisterDisplayListener(listener) } }
    }

    private fun resolveDisplay(): Display? = runCatching {
        window.decorView.display ?: displayManager()?.getDisplay(Display.DEFAULT_DISPLAY)
    }.getOrNull()

    private fun displayManager(): DisplayManager? =
        runCatching { window.context.getSystemService(DisplayManager::class.java) }.getOrNull()
}
