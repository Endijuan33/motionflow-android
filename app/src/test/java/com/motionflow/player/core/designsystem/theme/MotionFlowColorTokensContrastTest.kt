package com.motionflow.player.core.designsystem.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contrast guarantees of [MotionFlowColorTokens].
 *
 * MotionFlow is a dark-first application, which makes it easy to ship grey-on-black text that
 * looks fine on a desktop monitor and becomes unreadable on a phone in daylight. The thresholds
 * below are the WCAG 2.1 AA minimums, so any future palette edit that drops a pairing under them
 * fails the build instead of reaching a device.
 */
class MotionFlowColorTokensContrastTest {

    private class Requirement(
        val description: String,
        val foreground: Long,
        val background: Long,
        val minimum: Double,
    )

    @Test
    fun `body and container pairings meet WCAG AA`() {
        val requirements = listOf(
            Requirement("onBackground on background", ON_BACKGROUND, BACKGROUND, AA_TEXT),
            Requirement("onSurface on surface", ON_SURFACE, SURFACE, AA_TEXT),
            Requirement("onSurface on background", ON_SURFACE, BACKGROUND, AA_TEXT),
            Requirement("onSurfaceVariant on surface", ON_SURFACE_VARIANT, SURFACE, AA_TEXT),
            Requirement("onSurfaceVariant on surfaceContainer", ON_SURFACE_VARIANT, SURFACE_CONTAINER, AA_TEXT),
            Requirement("onSurfaceVariant on surfaceContainerHigh", ON_SURFACE_VARIANT, SURFACE_CONTAINER_HIGH, AA_TEXT),
            Requirement("onPrimary on primary", ON_PRIMARY, PRIMARY, AA_TEXT),
            Requirement("onSecondary on secondary", ON_SECONDARY, SECONDARY, AA_TEXT),
            Requirement("onTertiary on tertiary", ON_TERTIARY, TERTIARY, AA_TEXT),
            Requirement("onError on error", ON_ERROR, ERROR, AA_TEXT),
            Requirement("onPrimaryContainer on primaryContainer", ON_PRIMARY_CONTAINER, PRIMARY_CONTAINER, AA_TEXT),
            Requirement("onSecondaryContainer on secondaryContainer", ON_SECONDARY_CONTAINER, SECONDARY_CONTAINER, AA_TEXT),
            Requirement("onTertiaryContainer on tertiaryContainer", ON_TERTIARY_CONTAINER, TERTIARY_CONTAINER, AA_TEXT),
            Requirement("onErrorContainer on errorContainer", ON_ERROR_CONTAINER, ERROR_CONTAINER, AA_TEXT),
            Requirement("inverseOnSurface on inverseSurface", INVERSE_ON_SURFACE, INVERSE_SURFACE, AA_TEXT),
        )

        assertContrast(requirements)
    }

    @Test
    fun `accents and non-text structure meet the large-text and UI thresholds`() {
        val requirements = listOf(
            Requirement("primary accent on background", PRIMARY, BACKGROUND, AA_LARGE_TEXT),
            Requirement("primary accent on surface", PRIMARY, SURFACE, AA_LARGE_TEXT),
            Requirement("secondary accent on surface", SECONDARY, SURFACE, AA_LARGE_TEXT),
            Requirement("tertiary accent on surface", TERTIARY, SURFACE, AA_LARGE_TEXT),
            Requirement("error accent on surface", ERROR, SURFACE, AA_LARGE_TEXT),
            Requirement("primary on primaryContainer", PRIMARY, PRIMARY_CONTAINER, AA_LARGE_TEXT),
            Requirement("inversePrimary on inverseSurface", INVERSE_PRIMARY, INVERSE_SURFACE, AA_LARGE_TEXT),
            Requirement("outline on background", OUTLINE, BACKGROUND, AA_LARGE_TEXT),
        )

        assertContrast(requirements)
    }

    private fun assertContrast(requirements: List<Requirement>) {
        val failures = requirements.mapNotNull { requirement ->
            val actual = contrastRatio(requirement.foreground, requirement.background)
            if (actual >= requirement.minimum) {
                null
            } else {
                "${requirement.description}: %.2f (minimum %.1f)".format(actual, requirement.minimum)
            }
        }

        assertTrue(
            "Colour pairings below threshold:\n" + failures.joinToString(separator = "\n"),
            failures.isEmpty(),
        )
    }

    private fun contrastRatio(foreground: Long, background: Long): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun relativeLuminance(argb: Long): Double =
        0.2126 * channelLuminance((argb shr 16) and 0xFF) +
            0.7152 * channelLuminance((argb shr 8) and 0xFF) +
            0.0722 * channelLuminance(argb and 0xFF)

    private fun channelLuminance(channelValue: Long): Double {
        val channel = channelValue / 255.0
        return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    }

    private companion object {
        const val AA_TEXT = 4.5
        const val AA_LARGE_TEXT = 3.0

        val ON_BACKGROUND = MotionFlowColorTokens.ON_BACKGROUND
        val BACKGROUND = MotionFlowColorTokens.BACKGROUND
        val ON_SURFACE = MotionFlowColorTokens.ON_SURFACE
        val SURFACE = MotionFlowColorTokens.SURFACE
        val ON_SURFACE_VARIANT = MotionFlowColorTokens.ON_SURFACE_VARIANT
        val SURFACE_CONTAINER = MotionFlowColorTokens.SURFACE_CONTAINER
        val SURFACE_CONTAINER_HIGH = MotionFlowColorTokens.SURFACE_CONTAINER_HIGH
        val PRIMARY = MotionFlowColorTokens.PRIMARY
        val ON_PRIMARY = MotionFlowColorTokens.ON_PRIMARY
        val PRIMARY_CONTAINER = MotionFlowColorTokens.PRIMARY_CONTAINER
        val ON_PRIMARY_CONTAINER = MotionFlowColorTokens.ON_PRIMARY_CONTAINER
        val SECONDARY = MotionFlowColorTokens.SECONDARY
        val ON_SECONDARY = MotionFlowColorTokens.ON_SECONDARY
        val SECONDARY_CONTAINER = MotionFlowColorTokens.SECONDARY_CONTAINER
        val ON_SECONDARY_CONTAINER = MotionFlowColorTokens.ON_SECONDARY_CONTAINER
        val TERTIARY = MotionFlowColorTokens.TERTIARY
        val ON_TERTIARY = MotionFlowColorTokens.ON_TERTIARY
        val TERTIARY_CONTAINER = MotionFlowColorTokens.TERTIARY_CONTAINER
        val ON_TERTIARY_CONTAINER = MotionFlowColorTokens.ON_TERTIARY_CONTAINER
        val ERROR = MotionFlowColorTokens.ERROR
        val ON_ERROR = MotionFlowColorTokens.ON_ERROR
        val ERROR_CONTAINER = MotionFlowColorTokens.ERROR_CONTAINER
        val ON_ERROR_CONTAINER = MotionFlowColorTokens.ON_ERROR_CONTAINER
        val OUTLINE = MotionFlowColorTokens.OUTLINE
        val INVERSE_SURFACE = MotionFlowColorTokens.INVERSE_SURFACE
        val INVERSE_ON_SURFACE = MotionFlowColorTokens.INVERSE_ON_SURFACE
        val INVERSE_PRIMARY = MotionFlowColorTokens.INVERSE_PRIMARY
    }
}
