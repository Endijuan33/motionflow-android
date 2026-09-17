package com.motionflow.player.navigation

import com.motionflow.player.feature.player.PlayerRoute

/**
 * The destinations MotionFlow can navigate to.
 *
 * A route is either a literal path or a path with one argument placeholder, such as the player's
 * source URI. Routes are declared once, here, so that navigation is driven by typed values instead
 * of string literals scattered across feature code.
 */
enum class MotionFlowDestination(val route: String) {
    HOME("home"),

    /** Requires a source URI argument; build it with [PlayerRoute.create]. */
    PLAYER("player/{${PlayerRoute.ARGUMENT_VIDEO_URI}}"),

    SETTINGS("settings"),
}
