package com.motionflow.player.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Audits the non-colour token scales.
 *
 * The scales are hand written, so these tests exist to catch the two mistakes that hand written
 * scales actually attract: a step that is not on the base grid, and a step that collides with its
 * neighbour after an edit.
 */
class DesignTokensTest {

    @Test
    fun `spacing scale follows the 4dp grid and increases strictly`() {
        val spacing = MotionFlowSpacing()
        val scale = listOf(
            spacing.none,
            spacing.extraSmall,
            spacing.small,
            spacing.medium,
            spacing.large,
            spacing.extraLarge,
            spacing.huge,
        )

        assertEquals("the scale must start at zero", 0f, scale.first().value, 0f)
        assertStrictlyIncreasing("spacing", scale.drop(1))
        scale.drop(1).forEach { step ->
            assertTrue(
                "spacing step $step is off the 4dp grid",
                abs(step.value - step.value.roundToBaseUnit()) < TOLERANCE,
            )
        }
    }

    @Test
    fun `elevation scale increases strictly and stays subtle`() {
        val elevation = MotionFlowElevation()
        val scale = listOf(
            elevation.level1,
            elevation.level2,
            elevation.level3,
            elevation.level4,
            elevation.level5,
        )

        assertEquals("level 0 must be flat", 0f, elevation.level0.value, 0f)
        assertStrictlyIncreasing("elevation", scale)
        assertTrue(
            "MotionFlow expresses depth with tonal surfaces, so elevation stays low",
            scale.last().value <= MAX_ELEVATION.value,
        )
    }

    @Test
    fun `motion durations increase strictly and stay under the chrome budget`() {
        val motion = MotionFlowMotion()
        val durations = listOf(
            motion.instantMs,
            motion.quickMs,
            motion.standardMs,
            motion.emphasizedMs,
        )

        assertTrue("durations must be positive", durations.all { it > 0 })
        durations.zipWithNext { shorter, longer ->
            assertTrue("$shorter must be shorter than $longer", shorter < longer)
        }
        assertTrue(
            "chrome transitions longer than ${MAX_CHROME_DURATION_MS}ms read as latency",
            durations.last() <= MAX_CHROME_DURATION_MS,
        )
    }

    private fun assertStrictlyIncreasing(label: String, scale: List<Dp>) {
        scale.zipWithNext { smaller, larger ->
            assertTrue("$label must increase: $smaller is not below $larger", smaller.value < larger.value)
        }
    }

    private fun Float.roundToBaseUnit(): Float = (this / BASE_UNIT_DP).toInt() * BASE_UNIT_DP

    private companion object {
        const val BASE_UNIT_DP = 4f
        const val TOLERANCE = 0.001f
        const val MAX_CHROME_DURATION_MS = 500

        val MAX_ELEVATION = 16.dp
    }
}
