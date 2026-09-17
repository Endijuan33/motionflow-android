package com.motionflow.player.core.media.pacing

import com.motionflow.player.core.media.metadata.FrameRateInfo
import com.motionflow.player.core.media.metadata.FrameRateSource
import com.motionflow.player.core.media.metadata.MetadataConfidence
import com.motionflow.player.core.media.refresh.DisplayModeInfo
import com.motionflow.player.core.media.refresh.DisplayRefreshCapabilities
import com.motionflow.player.core.media.refresh.RefreshRateError
import com.motionflow.player.core.media.refresh.RefreshRateState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers how the pacing engine reacts: to the refresh engine's state, to a cadence that changes, and
 * to a mechanism being bound or absent.
 *
 * The coordinator's scope runs unconfined here so its signal handling completes inline, and it is the
 * test's `backgroundScope` because its collector lives as long as the coordinator does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FramePacingCoordinatorTest {

    @Test
    fun `a cadence against a display rate produces a diagnosis`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = FramePacingCoordinator(backgroundScope)

        coordinator.onVideoFrameRate(measuredFps(24f))
        coordinator.onRefreshRateState(displayAt(60f))

        val state = coordinator.state.value
        assertEquals(FramePacingMode.CADENCE_MISMATCH, state.decision.mode)
        assertEquals("3:2", state.diagnostics?.ratio?.patternLabel)
        assertFalse("nothing can apply pacing here", state.decision.isApplied)
    }

    @Test
    fun `the display side comes from the refresh engine's state`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = FramePacingCoordinator(backgroundScope)
        coordinator.onVideoFrameRate(measuredFps(23.976f))

        // The refresh engine moved the display to a 24 Hz mode: the cadence is now 1:1.
        coordinator.onRefreshRateState(displayAt(24f))
        assertEquals(FramePacingMode.NATIVE_CADENCE, coordinator.state.value.decision.mode)

        // And the display moved back for the next video: the same cadence is now a 3:2 pattern.
        coordinator.onRefreshRateState(displayAt(60f))
        assertEquals(FramePacingMode.CADENCE_MISMATCH, coordinator.state.value.decision.mode)
    }

    @Test
    fun `a refused refresh request is carried into the diagnosis`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = FramePacingCoordinator(backgroundScope)
        coordinator.onVideoFrameRate(measuredFps(24f))

        coordinator.onRefreshRateState(
            displayAt(60f).copy(appliedRefreshRateHz = 24f, error = RefreshRateError.PLATFORM_REJECTED),
        )

        val display = coordinator.state.value.diagnostics?.display
        assertTrue("the display never reached the requested rate", display?.requestRefused ?: false)
        assertEquals(24f, display?.requestedRefreshRateHz ?: 0f, 0.001f)
        assertEquals(60f, display?.refreshRateHz ?: 0f, 0.001f)
    }

    @Test
    fun `a request the platform silently ignored counts as not honoured`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = FramePacingCoordinator(backgroundScope)
        coordinator.onVideoFrameRate(measuredFps(24f))

        // No error is reported, but the display still reports 60 Hz after being asked for 24.
        coordinator.onRefreshRateState(displayAt(60f).copy(appliedRefreshRateHz = 24f))

        assertTrue(coordinator.state.value.diagnostics?.display?.requestRefused ?: false)
    }

    @Test
    fun `a request that was honoured is not reported as refused`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = FramePacingCoordinator(backgroundScope)
        coordinator.onVideoFrameRate(measuredFps(24f))

        coordinator.onRefreshRateState(displayAt(24f).copy(appliedRefreshRateHz = 24f))

        assertFalse(coordinator.state.value.diagnostics?.display?.requestRefused ?: true)
        assertEquals(FramePacingMode.NATIVE_CADENCE, coordinator.state.value.decision.mode)
    }

    @Test
    fun `re-analysing identical inputs changes nothing`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = FramePacingCoordinator(backgroundScope)
        val emissions = mutableListOf<FramePacingState>()
        val collector: Job = backgroundScope.launch { coordinator.state.collect { emissions += it } }

        coordinator.onVideoFrameRate(measuredFps(24f))
        coordinator.onRefreshRateState(displayAt(60f))
        val afterFirstAnalysis = emissions.size

        // The same cadence twice more, as a refreshed metadata read would deliver.
        coordinator.onVideoFrameRate(measuredFps(24f))
        coordinator.onRefreshRateState(displayAt(60f))

        assertEquals(
            "identical inputs produce equal state, which the flow does not re-emit",
            afterFirstAnalysis,
            emissions.size,
        )
        collector.cancel()
    }

    @Test
    fun `the video that is on screen now is the one described`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = FramePacingCoordinator(backgroundScope)
        coordinator.onRefreshRateState(displayAt(60f))

        // Three videos in quick succession.
        coordinator.onVideoFrameRate(measuredFps(24f))
        coordinator.onVideoFrameRate(measuredFps(30f))
        coordinator.onVideoFrameRate(measuredFps(25f))

        val state = coordinator.state.value
        assertEquals(25f, state.diagnostics?.video?.fps ?: 0f, 0.001f)
        assertEquals(FramePacingReason.LONG_REPEATING_PATTERN, state.decision.reason)
    }

    @Test
    fun `the window being recreated re-analyses the display`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = FramePacingCoordinator(backgroundScope)
        coordinator.onVideoFrameRate(measuredFps(24f))
        coordinator.onRefreshRateState(displayAt(60f))
        assertEquals(FramePacingMode.CADENCE_MISMATCH, coordinator.state.value.decision.mode)

        // Leaving the player: the display side is unknown again.
        coordinator.onRefreshRateState(RefreshRateState())
        assertEquals(FramePacingMode.UNKNOWN, coordinator.state.value.decision.mode)
        assertNull(coordinator.state.value.diagnostics?.ratio)

        // Returning to the player, now on a display that offers what the video needs.
        coordinator.onRefreshRateState(displayAt(24f))
        assertEquals(FramePacingMode.NATIVE_CADENCE, coordinator.state.value.decision.mode)
    }

    @Test
    fun `an unsupported pairing is recorded rather than thrown`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = FramePacingCoordinator(backgroundScope)

        coordinator.onVideoFrameRate(measuredFps(60f))
        coordinator.onRefreshRateState(displayAt(50f))

        val state = coordinator.state.value
        assertEquals(FramePacingMode.UNSUPPORTED, state.decision.mode)
        assertTrue(state.decision.isMismatched)
        assertFalse(state.decision.isApplied)
    }

    @Test
    fun `a bound controller receives the decision, and its refusal is recorded`() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = FakeController()
            val coordinator = FramePacingCoordinator(backgroundScope, controller)

            coordinator.onVideoFrameRate(measuredFps(24f))
            coordinator.onRefreshRateState(displayAt(60f))

            assertEquals(
                "a cadence with no display behind it yet is not worth handing over",
                1,
                controller.decisions.size,
            )
            assertEquals(FramePacingMode.CADENCE_MISMATCH, controller.decisions.last().mode)
            assertEquals(
                "a mechanism that cannot act must not make the engine claim it did",
                false,
                coordinator.state.value.decision.isApplied,
            )

            controller.refuse = true
            coordinator.onRefreshRateState(displayAt(120f))

            assertEquals(FramePacingError.PLATFORM_REJECTED, coordinator.state.value.decision.error)
            assertFalse(coordinator.state.value.decision.isApplied)
        }

    @Test
    fun `a controller that throws is treated as a refusal`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeController().apply { throwOnApply = true }
        val coordinator = FramePacingCoordinator(backgroundScope, controller)

        coordinator.onVideoFrameRate(measuredFps(24f))
        coordinator.onRefreshRateState(displayAt(60f))

        val state = coordinator.state.value
        assertEquals(FramePacingError.UNKNOWN, state.decision.error)
        assertFalse(state.decision.isApplied)
        assertNotNull("the diagnosis still stands", state.diagnostics)
    }

    @Test
    fun `a header rate produces a diagnosis that is not trusted`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = FramePacingCoordinator(backgroundScope)

        coordinator.onVideoFrameRate(FrameRateInfo.fromHeader(24f, FrameRateSource.CONTAINER))
        coordinator.onRefreshRateState(displayAt(60f))

        assertEquals(FramePacingMode.CADENCE_MISMATCH, coordinator.state.value.decision.mode)
        assertFalse(coordinator.state.value.decision.isReliable)
    }

    private class FakeController : FramePacingController {

        val decisions = mutableListOf<FramePacingDecision>()
        var refuse = false
        var throwOnApply = false

        override fun apply(decision: FramePacingDecision): FramePacingApplication {
            if (throwOnApply) throw IllegalStateException("the renderer is gone")
            decisions += decision
            return if (refuse) {
                FramePacingApplication(mechanism = FramePacingMechanism.NONE, error = FramePacingError.PLATFORM_REJECTED)
            } else {
                FramePacingApplication(mechanism = FramePacingMechanism.NONE)
            }
        }
    }
}

private fun measuredFps(fps: Float): FrameRateInfo = FrameRateInfo.measured(
    fps = fps,
    isVariableFrameRate = null,
    confidence = MetadataConfidence.HIGH,
)

/** A refresh-engine state describing a display that reports [hz] and offers it as the current mode. */
private fun displayAt(hz: Float): RefreshRateState = RefreshRateState(
    frameRate = FrameRateInfo.Unknown,
    capabilities = DisplayRefreshCapabilities(
        displayId = 0,
        modes = listOf(
            DisplayModeInfo(modeId = 1, width = 1920, height = 1080, refreshRateHz = hz, isCurrent = true),
        ),
    ),
)
