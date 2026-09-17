package com.motionflow.player.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the navigation routes.
 *
 * Routes are the identity of a destination: a duplicate silently shadows a destination, and a
 * blank one fails only at runtime when navigation is attempted.
 */
class MotionFlowDestinationTest {

    @Test
    fun `routes are unique`() {
        val routes = MotionFlowDestination.entries.map { it.route }

        assertEquals(
            "duplicate route in $routes",
            routes.size,
            routes.toSet().size,
        )
    }

    @Test
    fun `routes are stable non-blank identifiers`() {
        MotionFlowDestination.entries.forEach { destination ->
            val route = destination.route
            assertTrue("route must not be blank", route.isNotBlank())
            assertEquals("route must be trimmed", route, route.trim())
            assertTrue(
                "route '$route' must be a lowercase identifier so it survives URI encoding",
                route.matches(ROUTE_PATTERN),
            )
        }
    }

    private companion object {
        val ROUTE_PATTERN = Regex("[a-z][a-z0-9_]*")
    }
}
