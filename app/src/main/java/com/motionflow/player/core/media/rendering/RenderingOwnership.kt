package com.motionflow.player.core.media.rendering

/** A resource involved in getting decoded frames onto the display. */
enum class RenderingResource {

    /** The playback engine. One per process, owned by the player architecture. */
    EXO_PLAYER,

    /** The media session that publishes playback to Android. */
    MEDIA_SESSION,

    /** The view the player screen composes to host video. */
    PLAYER_VIEW,

    /** The surface video is actually drawn on. */
    VIDEO_SURFACE,

    /** A surface a future processing stage would render through. */
    PROCESSING_SURFACE,

    /** The display refresh-rate preference. */
    DISPLAY_PREFERENCE,
}

/** Who holds a resource. */
enum class ResourceOwner {

    /** A process-scoped component; outlives any screen. */
    PLAYER_PROCESS,

    /** The media session service. */
    MEDIA_SESSION_SERVICE,

    /** The player screen, for as long as it is composed. */
    PLAYER_SCREEN,

    /** Media3, from inside the rendering pipeline. */
    MEDIA3,

    /** The Phase 3 refresh-rate engine. */
    REFRESH_RATE_CONTROLLER,

    /** Nobody: the resource does not exist. */
    NOBODY,
}

/** One row of the ownership contract. */
data class ResourceOwnership(val resource: RenderingResource, val owner: ResourceOwner)

/**
 * Who owns what, as data rather than only as prose, so the contract can be asserted.
 *
 * The point of the contract is that adding a processing stage later must not move ownership of
 * anything that already has an owner. A test checks that each resource appears exactly once and that
 * the video surface stays Media3's, and another checks the property that matters most: the
 * foundation itself owns no surface at all.
 */
object RenderingOwnership {

    /**
     * Every resource, and its single owner.
     *
     * - The player and the session are process-scoped, which is why a screen never creates either.
     * - The view and its surface belong to the player screen and to Media3 respectively: the screen
     *   composes the view, Media3 creates and drives the surface it contains.
     * - The processing surface has no owner because it does not exist; that is the honest entry
     *   rather than reserving an owner for something unimplemented.
     * - The display preference belongs to the refresh-rate engine, so a rendering change cannot
     *   quietly take it over.
     */
    val contract: List<ResourceOwnership> = listOf(
        ResourceOwnership(RenderingResource.EXO_PLAYER, ResourceOwner.PLAYER_PROCESS),
        ResourceOwnership(RenderingResource.MEDIA_SESSION, ResourceOwner.MEDIA_SESSION_SERVICE),
        ResourceOwnership(RenderingResource.PLAYER_VIEW, ResourceOwner.PLAYER_SCREEN),
        ResourceOwnership(RenderingResource.VIDEO_SURFACE, ResourceOwner.MEDIA3),
        ResourceOwnership(RenderingResource.PROCESSING_SURFACE, ResourceOwner.NOBODY),
        ResourceOwnership(RenderingResource.DISPLAY_PREFERENCE, ResourceOwner.REFRESH_RATE_CONTROLLER),
    )

    /** The owner of [resource], or `null` when the contract does not mention it. */
    fun ownerOf(resource: RenderingResource): ResourceOwner? =
        contract.firstOrNull { it.resource == resource }?.owner

    /** The resources [owner] holds. */
    fun resourcesOwnedBy(owner: ResourceOwner): List<RenderingResource> =
        contract.filter { it.owner == owner }.map { it.resource }
}
