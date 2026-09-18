package com.motionflow.player.core.media.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Covers the ownership contract, which is expressed as data so it can be asserted rather than only
 * promised.
 *
 * The property that matters is that adding a processing stage later cannot move ownership of
 * anything that already has an owner — least of all onto the foundation, which owns no surface at all.
 */
class RenderingOwnershipTest {

    @Test
    fun `every resource has exactly one owner`() {
        RenderingResource.entries.forEach { resource ->
            val owners = RenderingOwnership.contract.filter { it.resource == resource }
            assertEquals("$resource is not declared exactly once", 1, owners.size)
        }
    }

    @Test
    fun `the contract covers every declared resource`() {
        assertEquals(
            "a resource with no owner is a decision nobody made",
            RenderingResource.entries.size,
            RenderingOwnership.contract.size,
        )
    }

    @Test
    fun `the player and the session belong to process-scoped components`() {
        assertEquals(
            ResourceOwner.PLAYER_PROCESS,
            RenderingOwnership.ownerOf(RenderingResource.EXO_PLAYER),
        )
        assertEquals(
            ResourceOwner.MEDIA_SESSION_SERVICE,
            RenderingOwnership.ownerOf(RenderingResource.MEDIA_SESSION),
        )
    }

    @Test
    fun `the surface belongs to Media3 rather than to the application`() {
        assertEquals(
            ResourceOwner.MEDIA3,
            RenderingOwnership.ownerOf(RenderingResource.VIDEO_SURFACE),
        )
        assertEquals(
            ResourceOwner.PLAYER_SCREEN,
            RenderingOwnership.ownerOf(RenderingResource.PLAYER_VIEW),
        )
    }

    @Test
    fun `the processing surface has no owner because it does not exist`() {
        assertEquals(
            "nothing may hold a surface that has not been built",
            ResourceOwner.NOBODY,
            RenderingOwnership.ownerOf(RenderingResource.PROCESSING_SURFACE),
        )
    }

    @Test
    fun `the display preference stays with the refresh engine`() {
        assertEquals(
            "a rendering change must not quietly take over the display",
            ResourceOwner.REFRESH_RATE_CONTROLLER,
            RenderingOwnership.ownerOf(RenderingResource.DISPLAY_PREFERENCE),
        )
    }

    @Test
    fun `the rendering foundation owns nothing`() {
        assertEquals(
            "Media3 holds the surface and nothing else",
            listOf(RenderingResource.VIDEO_SURFACE),
            RenderingOwnership.resourcesOwnedBy(ResourceOwner.MEDIA3),
        )
        assertFalse(
            "the screen composes the view; it does not own the surface inside it",
            RenderingResource.VIDEO_SURFACE in
                RenderingOwnership.resourcesOwnedBy(ResourceOwner.PLAYER_SCREEN),
        )
    }
}
