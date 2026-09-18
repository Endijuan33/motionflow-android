package com.motionflow.player.core.media.rendering

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `the mode defaults to native Media3`() {
        val coordinator = RenderingCoordinator(FakeCapabilityProvider())

        val diagnostics = coordinator.diagnostics.value
        assertEquals(RenderingMode.NATIVE_MEDIA3, diagnostics.pipeline.mode)
        assertFalse(diagnostics.pipeline.surfaceBound)
        assertEquals(SurfaceType.UNKNOWN, diagnostics.surfaceType)
    }

    @Test
    fun `processing is never reported active without a stage`() {
        val coordinator = RenderingCoordinator(FakeCapabilityProvider())

        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)
        coordinator.onFirstFrameRendered(latencyMs = 120L)

        val pipeline = coordinator.diagnostics.value.pipeline
        assertFalse("nothing is attached, so nothing may claim to be", pipeline.processingActive)
        assertFalse(pipeline.processingAttached)
    }

    @Test
    fun `unknown capabilities stay unknown rather than being guessed`() {
        val coordinator = RenderingCoordinator(FakeCapabilityProvider())

        val capabilities = coordinator.diagnostics.value.pipeline.capabilities
        assertNull(capabilities.apiLevel)
        assertNull("nothing is known about attachability before a surface exists", capabilities.processingAttachable)
        assertEquals(SurfaceType.UNKNOWN, capabilities.surfaceType)
    }

    @Test
    fun `a bound surface makes processing attachable but inactive`() {
        val coordinator = RenderingCoordinator(FakeCapabilityProvider(apiLevel = 34))

        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)

        val diagnostics = coordinator.diagnostics.value
        assertEquals(RenderingMode.PROCESSING_NOT_ACTIVE, diagnostics.pipeline.mode)
        assertEquals(true, diagnostics.pipeline.capabilities.processingAttachable)
        assertEquals(34, diagnostics.pipeline.capabilities.apiLevel)
        assertEquals(SurfaceType.SURFACE_VIEW, diagnostics.surfaceType)
        assertEquals(
            "the reason explains why nothing is running",
            ProcessingUnavailableReason.NO_STAGE_IMPLEMENTED,
            diagnostics.unavailableReason,
        )
    }

    @Test
    fun `without a surface, processing is unavailable`() {
        val coordinator = RenderingCoordinator(FakeCapabilityProvider())

        coordinator.onFirstFrameRendered(latencyMs = 90L)

        val diagnostics = coordinator.diagnostics.value
        assertEquals(RenderingMode.PROCESSING_UNAVAILABLE, diagnostics.pipeline.mode)
        assertEquals(ProcessingUnavailableReason.NO_SURFACE, diagnostics.unavailableReason)
        assertEquals("a fact about the surface is not overwritten by a metric", false, diagnostics.pipeline.capabilities.processingAttachable)
    }

    @Test
    fun `a second creation while a surface is bound is not a new attachment`() {
        val coordinator = RenderingCoordinator(FakeCapabilityProvider())

        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)
        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)

        assertEquals(1, coordinator.diagnostics.value.metrics.surfaceAttachCount)
    }

    @Test
    fun `a release without a creation is ignored`() {
        val coordinator = RenderingCoordinator(FakeCapabilityProvider())

        coordinator.onSurfaceReleased()

        val metrics = coordinator.diagnostics.value.metrics
        assertEquals(0, metrics.surfaceDetachCount)
        assertEquals("a stale release cannot unset anything", false, coordinator.diagnostics.value.pipeline.surfaceBound)
        assertEquals(RenderingMode.NATIVE_MEDIA3, coordinator.diagnostics.value.pipeline.mode)
    }

    @Test
    fun `a second release is not counted`() {
        val coordinator = RenderingCoordinator(FakeCapabilityProvider())

        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)
        coordinator.onSurfaceReleased()
        coordinator.onSurfaceReleased()

        val metrics = coordinator.diagnostics.value.metrics
        assertEquals(1, metrics.surfaceAttachCount)
        assertEquals(1, metrics.surfaceDetachCount)
    }

    @Test
    fun `a recreated surface is a new binding`() {
        val coordinator = RenderingCoordinator(FakeCapabilityProvider())

        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)
        coordinator.onSurfaceReleased()
        coordinator.onSurfaceCreated(SurfaceType.TEXTURE_VIEW)

        val diagnostics = coordinator.diagnostics.value
        assertTrue(diagnostics.pipeline.surfaceBound)
        assertEquals(SurfaceType.TEXTURE_VIEW, diagnostics.surfaceType)
        assertEquals(2, diagnostics.metrics.surfaceAttachCount)
        assertEquals(1, diagnostics.metrics.surfaceDetachCount)
    }

    @Test
    fun `a surface that goes away is reported as unknown`() {
        val coordinator = RenderingCoordinator(FakeCapabilityProvider())
        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)

        coordinator.onSurfaceReleased()

        val diagnostics = coordinator.diagnostics.value
        assertEquals(SurfaceType.UNKNOWN, diagnostics.surfaceType)
        assertEquals(RenderingMode.PROCESSING_UNAVAILABLE, diagnostics.pipeline.mode)
        assertEquals(ProcessingUnavailableReason.NO_SURFACE, diagnostics.unavailableReason)
    }

    @Test
    fun `the environment is offered to a stage when a surface appears, and taken back when it goes`() {
        val controller = FakeController()
        val coordinator = RenderingCoordinator(FakeCapabilityProvider(apiLevel = 31), controller)

        coordinator.onSurfaceCreated(SurfaceType.TEXTURE_VIEW)
        assertEquals(1, controller.environments.size)
        assertEquals(31, controller.environments.single().apiLevel)
        assertEquals(SurfaceType.TEXTURE_VIEW, controller.environments.single().surfaceType)
        assertTrue(coordinator.diagnostics.value.pipeline.processingActive)

        coordinator.onSurfaceReleased()
        assertEquals(1, controller.surfaceLostCount)
        assertFalse(coordinator.diagnostics.value.pipeline.processingActive)
        assertEquals(
            "and the reason becomes the missing surface",
            ProcessingUnavailableReason.NO_SURFACE,
            coordinator.diagnostics.value.unavailableReason,
        )
    }

    @Test
    fun `a stage that refuses to attach is reported as unavailable`() {
        val controller = FakeController().apply { attach = false }
        val coordinator = RenderingCoordinator(FakeCapabilityProvider(), controller)

        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)

        val diagnostics = coordinator.diagnostics.value
        assertEquals(RenderingMode.PROCESSING_UNAVAILABLE, diagnostics.pipeline.mode)
        assertEquals(ProcessingUnavailableReason.STAGE_UNAVAILABLE, diagnostics.unavailableReason)
        assertEquals(false, diagnostics.pipeline.capabilities.processingAttachable)
        assertFalse("a stage that cannot attach is not attached", diagnostics.pipeline.processingActive)
    }

    @Test
    fun `a stage that throws is contained`() {
        val controller = FakeController().apply { throwOnAttach = true }
        val coordinator = RenderingCoordinator(FakeCapabilityProvider(), controller)

        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)

        val diagnostics = coordinator.diagnostics.value
        assertEquals(RenderingMode.PROCESSING_UNAVAILABLE, diagnostics.pipeline.mode)
        assertEquals(ProcessingUnavailableReason.STAGE_UNAVAILABLE, diagnostics.unavailableReason)
        assertFalse(diagnostics.pipeline.processingActive)
    }

    @Test
    fun `a capability provider that throws leaves the API level unknown`() {
        val coordinator = RenderingCoordinator(FakeCapabilityProvider().apply { throwOnRead = true })

        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)

        val diagnostics = coordinator.diagnostics.value
        assertNull("no API level was read, so none is claimed", diagnostics.pipeline.capabilities.apiLevel)
        assertEquals(
            "the surface is still described, because that was observed directly",
            SurfaceType.SURFACE_VIEW,
            diagnostics.surfaceType,
        )
    }

    @Test
    fun `rendering and processing are reported as separate facts`() {
        val coordinator = RenderingCoordinator(FakeCapabilityProvider())

        coordinator.onSurfaceCreated(SurfaceType.SURFACE_VIEW)

        val pipeline = coordinator.diagnostics.value.pipeline
        assertEquals("Media3 is the renderer", RenderingMode.PROCESSING_NOT_ACTIVE, pipeline.mode)
        assertFalse("and nothing is processing", pipeline.processingActive)
        assertEquals(SurfaceType.SURFACE_VIEW, pipeline.capabilities.surfaceType)
    }

    @Test
    fun `metrics are recorded and the same measurement is not re-published`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = RenderingCoordinator(FakeCapabilityProvider())
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
        val coordinator = RenderingCoordinator(FakeCapabilityProvider())

        coordinator.onVideoSizeChanged(width = null, height = null)

        assertFalse(coordinator.diagnostics.value.metrics.hasVideoSize)
    }

    private class FakeCapabilityProvider(private val apiLevel: Int = 36) : RenderingCapabilityProvider {

        var throwOnRead = false

        override fun environment(): RenderingEnvironment {
            if (throwOnRead) throw IllegalStateException("the platform is unavailable")
            return RenderingEnvironment(apiLevel = apiLevel)
        }
    }

    private class FakeController : RenderingController {

        val environments = mutableListOf<RenderingEnvironment>()
        var surfaceLostCount = 0
        var attach = true
        var throwOnAttach = false

        override fun onSurfaceAvailable(environment: RenderingEnvironment): RenderingAttachment {
            if (throwOnAttach) throw IllegalStateException("no rendering stage is available")
            environments += environment
            return if (attach) {
                RenderingAttachment(attached = true)
            } else {
                RenderingAttachment(attached = false, reason = ProcessingUnavailableReason.STAGE_UNAVAILABLE)
            }
        }

        override fun onSurfaceLost() {
            surfaceLostCount++
        }
    }
}
