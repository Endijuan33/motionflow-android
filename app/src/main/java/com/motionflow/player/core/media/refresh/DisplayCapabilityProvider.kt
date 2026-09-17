package com.motionflow.player.core.media.refresh

import kotlinx.coroutines.flow.Flow

/**
 * Reports what the display behind the player window can do.
 *
 * Separate from [RefreshRateController] because reading capabilities and setting a preference fail
 * independently: a device can enumerate its modes perfectly and still refuse every request, and the
 * diagnostics need to tell those two apart.
 */
interface DisplayCapabilityProvider {

    /**
     * A snapshot of the display's modes. Never throws: a display that cannot be resolved or will not
     * report its modes yields [DisplayRefreshCapabilities.Unknown], which the policy treats as
     * "do not choose" rather than as a reason to guess.
     */
    fun capabilities(): DisplayRefreshCapabilities

    /**
     * Emits whenever the display configuration changes — a mode switch, a density or size change, a
     * move to another display. Collected only while the player screen is attached, so no monitoring
     * outlives the screen that needs it.
     */
    fun displayChanges(): Flow<Unit>
}
