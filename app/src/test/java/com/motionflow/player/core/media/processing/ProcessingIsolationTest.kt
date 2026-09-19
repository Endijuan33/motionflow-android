package com.motionflow.player.core.media.processing

import com.motionflow.player.core.media.metadata.FrameRateInfo
import com.motionflow.player.core.media.metadata.MetadataConfidence
import com.motionflow.player.core.media.pacing.FramePacingCoordinator
import com.motionflow.player.core.media.pacing.FramePacingState
import com.motionflow.player.core.media.refresh.DisplayCapabilityProvider
import com.motionflow.player.core.media.refresh.DisplayModeInfo
import com.motionflow.player.core.media.refresh.DisplayRefreshCapabilities
import com.motionflow.player.core.media.refresh.RefreshRateApplication
import com.motionflow.player.core.media.refresh.RefreshRateController
import com.motionflow.player.core.media.refresh.RefreshRateCoordinator
import com.motionflow.player.core.media.refresh.RefreshRateRequest
import com.motionflow.player.core.media.refresh.RefreshRateSource
import com.motionflow.player.core.media.refresh.RefreshRateState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the boundary between processing and the two engines whose facts it reports alongside.
 *
 * Processing is the last of five lines in the diagnostics panel and the only one that can change
 * anything, so the separation matters: a request changes whether a stage is attached, and nothing
 * else. The refresh and pacing engines are driven to a real, published state here — a display, a
 * cadence, a decision — so that "unchanged" means something, and a later refactor that let processing
 * reach into the display decision or the cadence classification is caught rather than argued about.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingIsolationTest {

    @Test
    fun `a processing request cannot move the display or the cadence classification`() =
        runTest(UnconfinedTestDispatcher()) {
            val refresh = RefreshRateCoordinator(backgroundScope)
            val pacing = FramePacingCoordinator(backgroundScope)
            refresh.attach(FakeRefreshController(), FakeDisplayProvider())
            refresh.onVideoFrameRate(measuredFps(23.976f))
            pacing.onVideoFrameRate(measuredFps(23.976f))
            pacing.onRefreshRateState(refresh.state.value)
            val refreshBefore: RefreshRateState = refresh.state.value
            val pacingBefore: FramePacingState = pacing.state.value
            assertTrue("the display engine has produced a real state", refreshBefore.frameRate.fps != null)

            val coordinator = ProcessingCoordinator().apply {
                onSurfaceChanged(bound = true)
                bindController(AttachingController)
            }
            coordinator.onCadence(
                videoFps = refreshBefore.frameRate.fps,
                displayRefreshRateHz = refreshBefore.displayRefreshRateHz,
            )
            coordinator.request(ProcessingRequest.ENABLE)

            assertTrue(
                "the stage attached, as far as this report is concerned",
                coordinator.diagnostics.value.effectAttached,
            )
            assertEquals(refreshBefore, refresh.state.value)
            assertEquals(pacingBefore, pacing.state.value)
            refresh.detach()
        }

    @Test
    fun `the rates in the processing report are the ones the other engines published`() =
        runTest(UnconfinedTestDispatcher()) {
            val refresh = RefreshRateCoordinator(backgroundScope)
            refresh.attach(FakeRefreshController(), FakeDisplayProvider())
            refresh.onVideoFrameRate(measuredFps(23.976f))

            val coordinator = ProcessingCoordinator().apply { onSurfaceChanged(bound = true) }
            coordinator.onCadence(
                videoFps = refresh.state.value.frameRate.fps,
                displayRefreshRateHz = refresh.state.value.displayRefreshRateHz,
            )
            coordinator.request(ProcessingRequest.DISABLE)

            val diagnostics = coordinator.diagnostics.value
            assertEquals(
                "a copy of the metadata engine's number",
                refresh.state.value.frameRate.fps,
                diagnostics.videoFps,
            )
            assertEquals(
                "a copy of the display engine's number, and not an output rate",
                refresh.state.value.displayRefreshRateHz,
                diagnostics.displayRefreshRateHz,
            )
            refresh.detach()
        }

    private object AttachingController : ProcessingController {

        override suspend fun request(request: ProcessingRequest): ProcessingResult =
            ProcessingResult(ProcessingOutcome.ATTACHED)
    }

    private class FakeRefreshController : RefreshRateController {

        override fun apply(request: RefreshRateRequest): RefreshRateApplication =
            RefreshRateApplication(applied = true, source = RefreshRateSource.WINDOW_REFRESH_RATE)

        override fun clear() = Unit
    }

    private class FakeDisplayProvider : DisplayCapabilityProvider {

        private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        override fun capabilities(): DisplayRefreshCapabilities = DisplayRefreshCapabilities(
            displayId = 0,
            modes = listOf(
                DisplayModeInfo(modeId = 0, width = 1920, height = 1080, refreshRateHz = 60f, isCurrent = true),
                DisplayModeInfo(modeId = 1, width = 1920, height = 1080, refreshRateHz = 24f),
            ),
        )

        override fun displayChanges(): Flow<Unit> = changes
    }
}

private fun measuredFps(fps: Float): FrameRateInfo = FrameRateInfo.measured(
    fps = fps,
    isVariableFrameRate = null,
    confidence = MetadataConfidence.HIGH,
)
