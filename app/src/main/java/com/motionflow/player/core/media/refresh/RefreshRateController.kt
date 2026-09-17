package com.motionflow.player.core.media.refresh

/**
 * Applies refresh-rate preferences to the platform.
 *
 * Deliberately narrow so it can be faked: the coordinator decides *what* to ask for, and this only
 * carries the request out. Implementations are called from the main thread, because window and
 * display operations are main-thread work.
 */
interface RefreshRateController {

    /**
     * Asks the platform to present content at the request's rate.
     *
     * Implementations must not throw: any platform refusal is reported in the result, because a
     * rejected refresh-rate request is not a playback failure and must never interrupt the video.
     */
    fun apply(request: RefreshRateRequest): RefreshRateApplication

    /**
     * Restores whatever the platform was doing before [apply] was first called.
     *
     * Called when the player screen goes away, so a preference set for one video does not follow the
     * user around the rest of the application.
     */
    fun clear()
}
