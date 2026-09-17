package com.motionflow.player.core.media.refresh

import com.motionflow.player.core.media.metadata.FrameRateInfo
import com.motionflow.player.core.media.metadata.MetadataConfidence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the coordinator's behaviour around the decision: when it asks the platform, when it stays
 * quiet, and what it leaves behind when the screen goes away.
 *
 * The coordinator's scope runs unconfined here so that its signal handling completes inline, which
 * keeps every assertion about *when* a request happened exact rather than timing dependent, and it is
 * the test's `backgroundScope` because the coordinator's collectors live as long as it does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshRateCoordinatorTest {

    @Test
    fun `a decision is applied once it has both a cadence and a display`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = FakeController()
            val coordinator = RefreshRateCoordinator(backgroundScope)

            coordinator.onVideoFrameRate(measuredFps(24f))
            assertEquals("nothing to apply against yet", 0, controller.applications.size)

            coordinator.attach(controller, FakeCapabilityProvider(displayOf(60f, 24f, currentHz = 60f)))
            assertEquals(1, controller.applications.size)
            assertEquals(24f, controller.applications.single().refreshRateHz, 0.001f)

            coordinator.detach()
        }

    @Test
    fun `the same decision is not requested twice`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeController()
        val coordinator = RefreshRateCoordinator(backgroundScope)
        coordinator.attach(controller, FakeCapabilityProvider(displayOf(60f, 24f, currentHz = 60f)))

        coordinator.onVideoFrameRate(measuredFps(24f))
        assertEquals(1, controller.applications.size)

        // The metadata reader publishes twice for one video: the container read, then the refined
        // Media3 read. A rate that differs only in its low bits is not a second request.
        coordinator.onVideoFrameRate(measuredFps(24.0002f))
        coordinator.requestRecompute()

        assertEquals("a float that differs in its last bits is not a new request", 1, controller.applications.size)

        coordinator.detach()
    }

    @Test
    fun `a refined cadence does produce a new request`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeController()
        val coordinator = RefreshRateCoordinator(backgroundScope)
        coordinator.attach(
            controller,
            FakeCapabilityProvider(displayOf(60f, 24f, 23.976f, currentHz = 60f)),
        )

        coordinator.onVideoFrameRate(measuredFps(24f))
        coordinator.onVideoFrameRate(measuredFps(23.976f))

        assertEquals(2, controller.applications.size)
        assertEquals(24f, controller.applications.first().refreshRateHz, 0.001f)
        assertEquals(
            "the refined rate is the one the display ends up matching",
            23.976f,
            controller.applications.last().refreshRateHz,
            0.0001f,
        )

        coordinator.detach()
    }

    @Test
    fun `an unknown cadence never asks for anything`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeController()
        val coordinator = RefreshRateCoordinator(backgroundScope)
        coordinator.attach(controller, FakeCapabilityProvider(displayOf(60f, 120f)))

        coordinator.onVideoFrameRate(FrameRateInfo.Unknown)

        assertEquals(0, controller.applications.size)
        assertNull(coordinator.state.value.appliedRefreshRateHz)

        coordinator.detach()
    }

    @Test
    fun `a display change is recomputed`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeController()
        val provider = FakeCapabilityProvider(displayOf(60f, currentHz = 60f))
        val coordinator = RefreshRateCoordinator(backgroundScope)
        coordinator.attach(controller, provider)
        coordinator.onVideoFrameRate(measuredFps(24f))

        assertEquals("60 Hz cannot show 24 fps evenly, and it is all there is", 0, controller.applications.size)

        // The window moved to a display that offers 24 Hz.
        provider.changeDisplay(displayOf(60f, 24f, currentHz = 60f))

        assertEquals(1, controller.applications.size)
        assertEquals(24f, controller.applications.single().refreshRateHz, 0.001f)

        coordinator.detach()
    }

    @Test
    fun `leaving the screen restores the display and forgets the request`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = FakeController()
            val coordinator = RefreshRateCoordinator(backgroundScope)
            coordinator.attach(controller, FakeCapabilityProvider(displayOf(60f, 24f, currentHz = 60f)))
            coordinator.onVideoFrameRate(measuredFps(24f))
            assertEquals(1, controller.applications.size)

            coordinator.detach()

            assertEquals("the display must be handed back", 1, controller.clears)
            assertNull(coordinator.state.value.appliedRefreshRateHz)
            assertEquals(RefreshRateSource.NONE, coordinator.state.value.appliedSource)
        }

    @Test
    fun `leaving the screen without having asked for anything does not touch the display`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = FakeController()
            val coordinator = RefreshRateCoordinator(backgroundScope)
            coordinator.attach(controller, FakeCapabilityProvider(displayOf(60f)))
            coordinator.onVideoFrameRate(FrameRateInfo.Unknown)

            coordinator.detach()

            assertEquals("nothing was changed, so nothing needs restoring", 0, controller.clears)
        }

    @Test
    fun `turning automatic selection off hands the display back`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeController()
        val coordinator = RefreshRateCoordinator(backgroundScope)
        coordinator.attach(controller, FakeCapabilityProvider(displayOf(60f, 24f, currentHz = 60f)))
        coordinator.onVideoFrameRate(measuredFps(24f))
        assertEquals(1, controller.applications.size)

        coordinator.setAutomaticEnabled(false)

        assertEquals(1, controller.clears)
        assertEquals(RefreshRateStatus.MANUAL, coordinator.state.value.decision.status)
        assertNull(coordinator.state.value.appliedRefreshRateHz)

        coordinator.detach()
    }

    @Test
    fun `a refused request is reported without pretending it worked`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = FakeController().apply { refuseEverything = true }
            val coordinator = RefreshRateCoordinator(backgroundScope)
            coordinator.attach(controller, FakeCapabilityProvider(displayOf(60f, 24f, currentHz = 60f)))
            coordinator.onVideoFrameRate(measuredFps(24f))

            val state = coordinator.state.value
            assertEquals(RefreshRateStatus.UNSUPPORTED, state.decision.status)
            assertEquals(RefreshRateReason.PLATFORM_REJECTED, state.decision.reason)
            assertEquals(RefreshRateError.PLATFORM_REJECTED, state.error)
            assertNull("nothing was applied, so nothing may be claimed", state.appliedRefreshRateHz)

            // A refusal must not stop the engine from trying again on the next trigger.
            coordinator.requestRecompute()
            assertEquals(2, controller.applications.size)

            coordinator.detach()
        }

    @Test
    fun `a controller that throws is treated as a refusal`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeController().apply { throwOnApply = true }
        val coordinator = RefreshRateCoordinator(backgroundScope)
        coordinator.attach(controller, FakeCapabilityProvider(displayOf(60f, 24f, currentHz = 60f)))

        coordinator.onVideoFrameRate(measuredFps(24f))

        assertEquals(RefreshRateStatus.UNSUPPORTED, coordinator.state.value.decision.status)
        assertNull(coordinator.state.value.appliedRefreshRateHz)

        coordinator.detach()
    }

    @Test
    fun `a capability provider that throws leaves the decision unknown`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = FakeController()
            val provider = FakeCapabilityProvider(displayOf(60f)).apply { throwOnRead = true }
            val coordinator = RefreshRateCoordinator(backgroundScope)
            coordinator.attach(controller, provider)

            coordinator.onVideoFrameRate(measuredFps(24f))

            val state = coordinator.state.value
            assertEquals(RefreshRateStatus.UNKNOWN, state.decision.status)
            assertEquals(RefreshRateReason.CAPABILITIES_UNKNOWN, state.decision.reason)
            assertEquals(0, controller.applications.size)

            coordinator.detach()
        }

    @Test
    fun `a burst of inputs settles on the video that is actually on screen`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = FakeController()
            val coordinator = RefreshRateCoordinator(backgroundScope)
            coordinator.attach(
                controller,
                FakeCapabilityProvider(displayOf(60f, 24f, 30f, currentHz = 60f)),
            )

            // Three videos in quick succession, as if the user is moving between files.
            coordinator.onVideoFrameRate(measuredFps(24f))
            coordinator.onVideoFrameRate(measuredFps(30f))
            coordinator.onVideoFrameRate(measuredFps(24f))

            val state = coordinator.state.value
            assertEquals(
                "the state must describe the video that is on screen now",
                24f,
                state.decision.cadenceHz ?: 0f,
                0.001f,
            )
            assertEquals(
                "and the last request must be the one that matches it",
                24f,
                controller.applications.last().refreshRateHz,
                0.001f,
            )

            coordinator.detach()
        }

    @Test
    fun `state keeps the video cadence and the display rate apart`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeController()
        val coordinator = RefreshRateCoordinator(backgroundScope)
        coordinator.attach(controller, FakeCapabilityProvider(displayOf(60f, 24f, currentHz = 24f)))

        coordinator.onVideoFrameRate(measuredFps(23.976f))

        val state = coordinator.state.value
        assertEquals(23.976f, state.frameRate.fps ?: 0f, 0.0001f)
        assertEquals(
            "the display's rate is not the video's rate",
            24f,
            state.displayRefreshRateHz ?: 0f,
            0.001f,
        )

        coordinator.detach()
    }

    private class FakeController : RefreshRateController {

        val applications = mutableListOf<RefreshRateRequest>()
        var clears = 0
        var refuseEverything = false
        var throwOnApply = false

        override fun apply(request: RefreshRateRequest): RefreshRateApplication {
            if (throwOnApply) throw IllegalStateException("the window is gone")
            applications += request
            return if (refuseEverything) {
                RefreshRateApplication(false, RefreshRateSource.NONE, RefreshRateError.PLATFORM_REJECTED)
            } else {
                RefreshRateApplication(true, RefreshRateSource.WINDOW_REFRESH_RATE)
            }
        }

        override fun clear() {
            clears++
        }
    }

    private class FakeCapabilityProvider(
        private var current: DisplayRefreshCapabilities,
    ) : DisplayCapabilityProvider {

        private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        var throwOnRead = false

        override fun capabilities(): DisplayRefreshCapabilities {
            if (throwOnRead) throw IllegalStateException("the display is gone")
            return current
        }

        override fun displayChanges(): Flow<Unit> = changes

        /** Simulates the display being reconfigured: new capabilities, then a change signal. */
        fun changeDisplay(next: DisplayRefreshCapabilities) {
            current = next
            changes.tryEmit(Unit)
        }
    }
}

private fun measuredFps(fps: Float): FrameRateInfo = FrameRateInfo.measured(
    fps = fps,
    isVariableFrameRate = null,
    confidence = MetadataConfidence.HIGH,
)
