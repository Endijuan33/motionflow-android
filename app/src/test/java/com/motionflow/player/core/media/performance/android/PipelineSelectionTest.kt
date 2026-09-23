package com.motionflow.player.core.media.performance.android

import androidx.media3.effect.AlphaScale
import com.motionflow.player.core.media.performance.PerformanceSessionCoordinator
import com.motionflow.player.core.media.performance.ProcessingPerformanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the boundary between a selected pipeline and the effect list the player is built with.
 *
 * This is the closest a test can get to the player without a device: it proves what the configuration
 * *produces*, and the factory's one call site — `effectsFor(...)?.let { setVideoEffects(it) }` — is what
 * consumes it. A device is still needed to prove the renderer ran the effect, which is why that part is
 * reported as not tested rather than asserted here.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ProcessingBaselinesTest {

    @Test
    fun `the native pipeline supplies no effect list at all`() {
        assertNull(
            "NATIVE must be null, not an empty list: an empty list still makes MediaCodecVideoRenderer " +
                "build its frame processor, which would put the control condition on the pipeline it is " +
                "the control for",
            ProcessingBaselines.effectsFor(ProcessingPerformanceMode.NATIVE),
        )
    }

    @Test
    fun `a failed pipeline is not handed an effect list either`() {
        assertNull(ProcessingBaselines.effectsFor(ProcessingPerformanceMode.FAILED))
    }

    @Test
    fun `the effect pipeline supplies exactly Media3's identity effect`() {
        val effects = ProcessingBaselines.effectsFor(ProcessingPerformanceMode.EFFECT_PIPELINE)

        assertEquals(1, effects?.size)
        val effect = effects!!.single()
        assertTrue("the effect must be Media3's own, not one of ours", effect is AlphaScale)
        // The scale itself is private in Media3, so the identity is asserted through the behaviour it
        // declares — isNoOp — and through the constant this project pins. Both would have to change for
        // a non-identity effect to be supplied.
        assertEquals(1f, ProcessingBaselines.IDENTITY_ALPHA, 0f)
    }

    @Test
    fun `the supplied effect declares itself a no-op, which is why it can stand for a pass-through`() {
        val effect = ProcessingBaselines.effectsFor(ProcessingPerformanceMode.EFFECT_PIPELINE)!!
            .single() as AlphaScale

        assertTrue(
            "an effect that did not report itself as a no-op would be changing pictures, which is not " +
                "what this baseline is",
            effect.isNoOp(/* inputWidth = */ 1920, /* inputHeight = */ 1080),
        )
    }

    @Test
    fun `only one pipeline supplies an effect, so the two baselines cannot be confused`() {
        val modes = ProcessingPerformanceMode.entries
        val withEffects = modes.filter { ProcessingBaselines.effectsFor(it) != null }

        assertEquals(listOf(ProcessingPerformanceMode.EFFECT_PIPELINE), withEffects)
    }

    @Test
    fun `no pipeline mode claims generated frames or a rate change`() {
        assertEquals(
            listOf("NATIVE", "EFFECT_PIPELINE", "FAILED"),
            ProcessingPerformanceMode.entries.map { it.name },
        )
        assertFalse(ProcessingPerformanceMode.entries.any { "INTERPOLATION" in it.name })
    }
}

/**
 * Covers what the panel's pipeline row is allowed to say.
 *
 * The rule this protects: the row describes the player that was *built*, never the selection that was
 * made. On hardware those two were indistinguishable, which is how a correctly configured effect
 * pipeline came to look like a selection that never arrived.
 */
class PipelineReportingTest {

    @Test
    fun `the reported mode is the mode the engine was built with`() {
        val coordinator = PerformanceSessionCoordinator()

        assertEquals(
            "an engine built without effects reports the native pipeline",
            ProcessingPerformanceMode.NATIVE,
            coordinator.diagnostics.value.mode,
        )

        coordinator.onModeChanged(ProcessingPerformanceMode.EFFECT_PIPELINE)

        assertEquals(
            "and a engine built with effects reports the effect pipeline, before any measurement",
            ProcessingPerformanceMode.EFFECT_PIPELINE,
            coordinator.diagnostics.value.mode,
        )
    }

    @Test
    fun `a rebuilt engine replaces the reported mode rather than being merged with the old one`() {
        val coordinator = PerformanceSessionCoordinator()
        coordinator.onModeChanged(ProcessingPerformanceMode.EFFECT_PIPELINE)

        coordinator.onModeChanged(ProcessingPerformanceMode.NATIVE)

        assertEquals(ProcessingPerformanceMode.NATIVE, coordinator.diagnostics.value.mode)
    }

    @Test
    fun `a failed pipeline is reported as failed, not as inactive`() {
        val coordinator = PerformanceSessionCoordinator()

        coordinator.onModeChanged(ProcessingPerformanceMode.FAILED)

        assertEquals(ProcessingPerformanceMode.FAILED, coordinator.diagnostics.value.mode)
    }
}
