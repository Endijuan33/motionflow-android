package com.motionflow.player.core.media.rendering

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the rendering foundation's behaviour: what it reports, what it refuses to claim, and how it
 * reacts to a surface appearing, going away and coming back.
 *
 * No scope is needed for most of these: every input is an event, and a description is published
 * synchronously from the event that caused it, which is itself part of what is being tested — there
 * is no window in which a stale description can be published.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RenderingCoordinatorTest {

    @Test
    fun `nothing is reported before a surface exists`() {
        val coordinator = RenderingCoordinator()

        val diagnostics = coordinator.diagnostics.value
        assertEquals(SurfaceType.UNKNOWN, diagnostics.surface.type)
        assertFalse(diagnostics.surface.bound)
        assertEquals(0, diagnostics.metrics.surfaceAttachCount)
        assertEquals(0, diagnostics.metrics.surfaceDetachCount)
    }

    @Test
    fun `a bound surface is reported with the type that was read`() {
        val coordinator = RenderingCoordinator()

        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)

        val surface = coordinator.diagnostics.value.surface
        assertTrue(surface.bound)
        assertEquals(SurfaceType.SURFACE_VIEW, surface.type)
        assertEquals(1, coordinator.diagnostics.value.metrics.surfaceAttachCount)
    }

    @Test
    fun `a second creation while a surface is bound is not a new attachment`() {
        val coordinator = RenderingCoordinator()

        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)
        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)

        assertEquals(1, coordinator.diagnostics.value.metrics.surfaceAttachCount)
    }

    @Test
    fun `a release without a creation is ignored`() {
        val coordinator = RenderingCoordinator()

        coordinator.onSurfaceReleased()

        val diagnostics = coordinator.diagnostics.value
        assertEquals(0, diagnostics.metrics.surfaceDetachCount)
        assertFalse("a stale release cannot unset anything", diagnostics.surface.bound)
    }

    @Test
    fun `a second release is not counted`() {
        val coordinator = RenderingCoordinator()

        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)
        coordinator.onSurfaceReleased()
        coordinator.onSurfaceReleased()

        val metrics = coordinator.diagnostics.value.metrics
        assertEquals(1, metrics.surfaceAttachCount)
        assertEquals(1, metrics.surfaceDetachCount)
    }

    @Test
    fun `a recreated surface is a new binding, of whatever kind it now is`() {
        val coordinator = RenderingCoordinator()

        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)
        coordinator.onSurfaceReleased()
        coordinator.onSurfaceCreated(SurfaceType.TEXTURE_VIEW)

        val diagnostics = coordinator.diagnostics.value
        assertTrue(diagnostics.surface.bound)
        assertEquals(SurfaceType.TEXTURE_VIEW, diagnostics.surface.type)
        assertEquals(2, diagnostics.metrics.surfaceAttachCount)
        assertEquals(1, diagnostics.metrics.surfaceDetachCount)
    }

    @Test
    fun `a surface that goes away is not reported as any kind of surface`() {
        val coordinator = RenderingCoordinator()
        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)

        coordinator.onSurfaceReleased()

        val surface = coordinator.diagnostics.value.surface
        assertEquals(SurfaceType.UNKNOWN, surface.type)
        assertFalse(surface.bound)
    }

    @Test
    fun `metrics are recorded and the same measurement is not re-published`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = RenderingCoordinator()
        val emissions = mutableListOf<RenderingDiagnostics>()
        val collector: Job = backgroundScope.launch { coordinator.diagnostics.collect { emissions += it } }

        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)
        coordinator.onFirstFrameRendered(latencyMs = 250L)
        coordinator.onVideoSizeChanged(width = 1920, height = 1080)
        val afterFirstPour = emissions.size

        // The same values again, as a repeated callback would deliver them.
        coordinator.onFirstFrameRendered(latencyMs = 250L)
        coordinator.onVideoSizeChanged(width = 1920, height = 1080)

        assertEquals("equal metrics produce equal state, which is not re-emitted", afterFirstPour, emissions.size)
        assertEquals(250L, coordinator.diagnostics.value.metrics.firstFrameLatencyMs)
        assertTrue(coordinator.diagnostics.value.metrics.hasVideoSize)
        collector.cancel()
    }

    @Test
    fun `an unknown video size is not recorded as a size`() {
        val coordinator = RenderingCoordinator()

        coordinator.onVideoSizeChanged(width = null, height = null)

        assertFalse(coordinator.diagnostics.value.metrics.hasVideoSize)
    }

    @Test
    fun `the first-frame measurement survives a surface change`() {
        val coordinator = RenderingCoordinator()
        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)
        coordinator.onFirstFrameRendered(latencyMs = 412L)

        coordinator.onSurfaceReleased()

        assertEquals(
            "a measurement of the path outlives the surface it was taken on",
            412L,
            coordinator.diagnostics.value.metrics.firstFrameLatencyMs,
        )
    }
}
