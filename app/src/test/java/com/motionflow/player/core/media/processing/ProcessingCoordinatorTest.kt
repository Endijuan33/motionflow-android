package com.motionflow.player.core.media.processing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the processing state machine: what a request can and cannot change, what the report claims,
 * and what it refuses to claim.
 *
 * The property running through all of it is that [ProcessingDiagnostics.effectAttached] follows what a
 * player reported and nothing else. No request, however it is phrased or repeated, can produce a
 * stage that was never attached.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingCoordinatorTest {

    @Test
    fun `nothing is claimed before anything is reported`() {
        val diagnostics = ProcessingCoordinator().diagnostics.value

        assertEquals(ProcessingMode.NATIVE, diagnostics.mode)
        assertNull(diagnostics.reason)
        assertFalse(diagnostics.effectAttached)
        assertNull(diagnostics.lastAttachmentSucceeded)
        assertEquals(0, diagnostics.attachCount)
        assertEquals(0, diagnostics.detachCount)
        assertEquals(0, diagnostics.requestCount)
        assertFalse(diagnostics.capabilities.canAttach)
        assertNull(diagnostics.videoFps)
        assertNull(diagnostics.displayRefreshRateHz)
    }

    @Test
    fun `without a surface processing is unavailable`() {
        val coordinator = ProcessingCoordinator()

        coordinator.onSurfaceChanged(bound = false)

        val diagnostics = coordinator.diagnostics.value
        assertEquals(ProcessingMode.PROCESSING_UNAVAILABLE, diagnostics.mode)
        assertEquals(ProcessingReason.NO_SURFACE, diagnostics.reason)
        assertFalse(diagnostics.capabilities.canAttach)
    }

    @Test
    fun `being told there is no surface is not the same as not being told anything`() {
        val silent = ProcessingCoordinator().diagnostics.value
        val told = ProcessingCoordinator()
            .apply { onSurfaceChanged(bound = false) }
            .diagnostics
            .value

        assertEquals("nothing has been reported yet", ProcessingMode.NATIVE, silent.mode)
        assertNull("and nothing is explained, because nothing was established", silent.reason)
        assertEquals("a surface reported absent", ProcessingMode.PROCESSING_UNAVAILABLE, told.mode)
        assertEquals(ProcessingReason.NO_SURFACE, told.reason)
    }

    @Test
    fun `with a surface and no stage processing is inactive, not failed`() {
        val diagnostics = boundCoordinator().diagnostics.value

        assertEquals(ProcessingMode.PROCESSING_INACTIVE, diagnostics.mode)
        assertEquals(ProcessingReason.NO_STAGE_IMPLEMENTED, diagnostics.reason)
        assertTrue(diagnostics.capabilities.canAttach)
        assertTrue(diagnostics.isInactiveButAvailable)
        assertFalse(diagnostics.effectAttached)
    }

    @Test
    fun `an enable request the player refuses is reported as failed, with the player's reason`() = runTest {
        val controller = FakeController(ProcessingResult.refused(ProcessingReason.EFFECTS_MODULE_ABSENT))
        val coordinator = boundCoordinator(controller)

        coordinator.request(ProcessingRequest.ENABLE)

        val diagnostics = coordinator.diagnostics.value
        assertEquals(ProcessingMode.PROCESSING_FAILED, diagnostics.mode)
        assertEquals(ProcessingReason.EFFECTS_MODULE_ABSENT, diagnostics.reason)
        assertEquals(false, diagnostics.lastAttachmentSucceeded)
        assertEquals(0, diagnostics.attachCount)
        assertFalse("a refusal cannot attach anything", diagnostics.effectAttached)
        assertEquals(1, diagnostics.requestCount)
        assertEquals(listOf(ProcessingRequest.ENABLE), controller.requests)
    }

    @Test
    fun `a stage the player reports attached is reported active`() = runTest {
        val controller = FakeController(ProcessingResult(ProcessingOutcome.ATTACHED))
        val coordinator = boundCoordinator(controller)

        coordinator.request(ProcessingRequest.ENABLE)

        val diagnostics = coordinator.diagnostics.value
        assertEquals(ProcessingMode.PROCESSING_ACTIVE, diagnostics.mode)
        assertNull("a successful attachment needs no excuse", diagnostics.reason)
        assertTrue(diagnostics.effectAttached)
        assertEquals(true, diagnostics.lastAttachmentSucceeded)
        assertEquals(1, diagnostics.attachCount)
        assertEquals(0, diagnostics.detachCount)
    }

    @Test
    fun `repeating an enable request cannot attach a second stage`() = runTest {
        val controller = FakeController(ProcessingResult(ProcessingOutcome.ATTACHED))
        val coordinator = boundCoordinator(controller)

        coordinator.request(ProcessingRequest.ENABLE)
        coordinator.request(ProcessingRequest.ENABLE)

        val diagnostics = coordinator.diagnostics.value
        assertEquals("the request was sent twice", 2, diagnostics.requestCount)
        assertEquals("but only one stage was reported", 1, diagnostics.attachCount)
    }

    @Test
    fun `repeating a disable request counts one detach`() = runTest {
        val controller = FakeController(ProcessingResult(ProcessingOutcome.ATTACHED))
        val coordinator = boundCoordinator(controller)
        coordinator.request(ProcessingRequest.ENABLE)

        controller.answer = ProcessingResult.Detached
        coordinator.request(ProcessingRequest.DISABLE)
        coordinator.request(ProcessingRequest.DISABLE)

        val diagnostics = coordinator.diagnostics.value
        assertEquals(1, diagnostics.attachCount)
        assertEquals("nothing more was attached to detach", 1, diagnostics.detachCount)
        assertFalse(diagnostics.effectAttached)
        assertEquals(ProcessingMode.PROCESSING_INACTIVE, diagnostics.mode)
    }

    @Test
    fun `a disable request returns the player to native rendering after a refusal`() = runTest {
        val controller = FakeController(ProcessingResult.refused(ProcessingReason.EFFECTS_MODULE_ABSENT))
        val coordinator = boundCoordinator(controller)
        coordinator.request(ProcessingRequest.ENABLE)
        assertEquals(ProcessingMode.PROCESSING_FAILED, coordinator.diagnostics.value.mode)

        controller.answer = ProcessingResult.Detached
        coordinator.request(ProcessingRequest.DISABLE)

        val diagnostics = coordinator.diagnostics.value
        assertEquals(ProcessingMode.PROCESSING_INACTIVE, diagnostics.mode)
        assertFalse(diagnostics.effectAttached)
        assertEquals(2, diagnostics.requestCount)
    }

    @Test
    fun `an enable request with no surface is refused without reaching the player`() = runTest {
        val controller = FakeController(ProcessingResult(ProcessingOutcome.ATTACHED))
        val coordinator = ProcessingCoordinator().apply { bindController(controller) }

        coordinator.request(ProcessingRequest.ENABLE)

        assertTrue("there is nothing to attach to, so nothing was asked", controller.requests.isEmpty())
        val diagnostics = coordinator.diagnostics.value
        assertEquals(ProcessingMode.PROCESSING_UNAVAILABLE, diagnostics.mode)
        assertEquals(ProcessingReason.NO_SURFACE, diagnostics.reason)
        assertEquals(false, diagnostics.lastAttachmentSucceeded)
        assertEquals(1, diagnostics.requestCount)
    }

    @Test
    fun `a request with no controller is reported as unreachable`() = runTest {
        val coordinator = boundCoordinator()

        coordinator.request(ProcessingRequest.ENABLE)

        val diagnostics = coordinator.diagnostics.value
        assertEquals(ProcessingMode.PROCESSING_FAILED, diagnostics.mode)
        assertEquals(ProcessingReason.TRANSPORT_FAILED, diagnostics.reason)
        assertEquals(false, diagnostics.lastAttachmentSucceeded)
        assertFalse(diagnostics.effectAttached)
    }

    @Test
    fun `a request after the controller is unbound is not attempted`() = runTest {
        val controller = FakeController(ProcessingResult(ProcessingOutcome.ATTACHED))
        val coordinator = boundCoordinator(controller)

        coordinator.unbindController()
        coordinator.request(ProcessingRequest.ENABLE)

        assertTrue("a released connection is not asked anything", controller.requests.isEmpty())
        assertEquals(ProcessingReason.TRANSPORT_FAILED, coordinator.diagnostics.value.reason)
    }

    @Test
    fun `a controller that throws is contained, and playback is not told about it`() = runTest {
        val controller = FakeController(ProcessingResult.Detached).apply { throwOnRequest = true }
        val coordinator = boundCoordinator(controller)

        coordinator.request(ProcessingRequest.ENABLE)

        val diagnostics = coordinator.diagnostics.value
        assertEquals(ProcessingMode.PROCESSING_FAILED, diagnostics.mode)
        assertEquals(ProcessingReason.TRANSPORT_FAILED, diagnostics.reason)
        assertFalse(diagnostics.effectAttached)
    }

    @Test
    fun `a command the session does not offer is reported once, not retried`() = runTest {
        val controller = FakeController(
            ProcessingResult.unreachable(ProcessingReason.COMMAND_UNAVAILABLE),
        )
        val coordinator = boundCoordinator(controller)

        coordinator.request(ProcessingRequest.ENABLE)

        val diagnostics = coordinator.diagnostics.value
        assertEquals(ProcessingMode.PROCESSING_FAILED, diagnostics.mode)
        assertEquals(ProcessingReason.COMMAND_UNAVAILABLE, diagnostics.reason)
        assertEquals("one attempt, no polling loop", 1, controller.requests.size)
    }

    @Test
    fun `a surface that goes away takes the stage with it`() = runTest {
        val controller = FakeController(ProcessingResult(ProcessingOutcome.ATTACHED))
        val coordinator = boundCoordinator(controller)
        coordinator.request(ProcessingRequest.ENABLE)
        assertTrue(coordinator.diagnostics.value.effectAttached)

        coordinator.onSurfaceChanged(bound = false)

        val diagnostics = coordinator.diagnostics.value
        assertFalse("a stage with nothing to render through is not attached", diagnostics.effectAttached)
        assertEquals(1, diagnostics.detachCount)
        assertEquals(ProcessingMode.PROCESSING_UNAVAILABLE, diagnostics.mode)
    }

    @Test
    fun `a recreated surface clears the failure, because it was about the old one`() = runTest {
        val controller = FakeController(ProcessingResult.refused(ProcessingReason.EFFECTS_MODULE_ABSENT))
        val coordinator = boundCoordinator(controller)
        coordinator.request(ProcessingRequest.ENABLE)
        assertEquals(ProcessingMode.PROCESSING_FAILED, coordinator.diagnostics.value.mode)

        coordinator.onSurfaceChanged(bound = false)
        coordinator.onSurfaceChanged(bound = true)

        val diagnostics = coordinator.diagnostics.value
        assertEquals(ProcessingMode.PROCESSING_INACTIVE, diagnostics.mode)
        assertEquals(ProcessingReason.NO_STAGE_IMPLEMENTED, diagnostics.reason)
        assertNull(diagnostics.lastAttachmentSucceeded)
        assertEquals("a new screen is not a new request", 1, diagnostics.requestCount)
    }

    @Test
    fun `a repeated surface report is not a new binding`() = runTest {
        val controller = FakeController(ProcessingResult.refused(ProcessingReason.EFFECTS_MODULE_ABSENT))
        val coordinator = boundCoordinator(controller)
        coordinator.request(ProcessingRequest.ENABLE)

        coordinator.onSurfaceChanged(bound = true)

        assertEquals(
            "a stale report must not clear a record of what happened",
            ProcessingMode.PROCESSING_FAILED,
            coordinator.diagnostics.value.mode,
        )
    }

    @Test
    fun `the two observed rates are carried through unchanged`() = runTest {
        val coordinator = boundCoordinator()

        coordinator.onCadence(videoFps = 23.976f, displayRefreshRateHz = 24f)
        coordinator.onCadence(videoFps = 23.976f, displayRefreshRateHz = 24f)

        val diagnostics = coordinator.diagnostics.value
        assertEquals(23.976f, diagnostics.videoFps!!, 0f)
        assertEquals(24f, diagnostics.displayRefreshRateHz!!, 0f)
    }

    private fun boundCoordinator(controller: ProcessingController? = null): ProcessingCoordinator =
        ProcessingCoordinator().apply {
            onSurfaceChanged(bound = true)
            if (controller != null) bindController(controller)
        }

    private class FakeController(var answer: ProcessingResult) : ProcessingController {

        val requests = mutableListOf<ProcessingRequest>()
        var throwOnRequest = false

        override suspend fun request(request: ProcessingRequest): ProcessingResult {
            requests += request
            if (throwOnRequest) throw IllegalStateException("the session is gone")
            return answer
        }
    }
}
