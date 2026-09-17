package com.motionflow.player.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the navigation routes.
 *
 * Routes are the identity of a destination: a duplicate silently shadows a destination, and a
 * malformed one fails only at runtime when navigation is attempted.
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
    fun `every path segment is a literal identifier or a single argument placeholder`() {
        MotionFlowDestination.entries.forEach { destination ->
            val route = destination.route
            assertTrue("route must not be blank", route.isNotBlank())
            assertEquals("route must be trimmed", route, route.trim())
            route.split("/").forEach { segment ->
                assertTrue(
                    "segment '$segment' of '$route' is neither an identifier nor an argument",
                    SEGMENT_PATTERN.matches(segment),
                )
            }
        }
    }

    @Test
    fun `the player destination declares the source argument`() {
        val route = MotionFlowDestination.PLAYER.route

        assertEquals("player/{videoUri}", route)
        assertTrue(
            "the player route must expose the argument the screen reads",
            route.contains(PLAYER_ARGUMENT),
        )
    }

    private companion object {
        const val PLAYER_ARGUMENT = "videoUri"
        val SEGMENT_PATTERN = Regex("([a-z][a-z0-9_]*|\\{[a-zA-Z][a-zA-Z0-9_]*})")
    }
}
