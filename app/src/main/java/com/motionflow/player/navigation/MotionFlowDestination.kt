package com.motionflow.player.navigation

/**
 * The destinations MotionFlow can navigate to.
 *
 * Routes are declared once, here, so that navigation is driven by a typed value instead of string
 * literals scattered across feature code. Adding a destination means adding a case and a
 * `composable` entry in [MotionFlowNavHost].
 */
enum class MotionFlowDestination(val route: String) {
    HOME("home"),
    SETTINGS("settings"),
}
