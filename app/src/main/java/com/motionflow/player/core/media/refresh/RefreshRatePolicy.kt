package com.motionflow.player.core.media.refresh

import com.motionflow.player.core.media.metadata.FrameRateInfo
import com.motionflow.player.core.media.metadata.MetadataConfidence
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Decides which display mode, if any, suits a video's cadence.
 *
 * Pure: it takes the metadata model and a snapshot of the display's capabilities and returns a
 * decision. It never touches Android, never reads a display and never asks for anything itself.
 *
 * ## The matching rule
 *
 * A display mode is considered to present a cadence when its refresh rate is an integer multiple of
 * that cadence: at 1× every frame is shown once, at 2× or 3× every frame is shown the same number of
 * times. Both are judder-free, which is the whole point — a display refreshing 2.5 times per frame
 * (24 fps content at 60 Hz) is not, no matter how high the number looks.
 *
 * Candidates are therefore ranked by tier:
 *
 * 1. **Exact** — the display refreshes once per frame (a mode at the cadence, including its
 *    fractional sibling: 24.000 Hz for 23.976 fps content).
 * 2. **Integer multiple** — the display refreshes twice, three times, five times per frame.
 * 3. **Best effort** — the *slowest* mode still fast enough to show every frame at least once.
 *
 * Within a tier the choice is deterministic: exact takes the mode closest to the cadence (so 23.976
 * fps prefers a 23.976 Hz mode over a 24.000 Hz one), multiples take the slowest (a 48 Hz mode does
 * everything a 120 Hz mode does for 24 fps content, at half the refresh cost), and best effort takes
 * the slowest too. The highest number available is never preferred for being highest.
 *
 * A change is only requested when it strictly improves on the mode the display is already in — the
 * engine will not shuffle between two equally suitable modes.
 */
object RefreshRatePolicy {

    /**
     * How close a mode's rate must be to an integer multiple of the cadence, relative to the
     * cadence, to count as one.
     *
     * 0.2% is chosen from the numbers themselves. The fractional cadence families sit exactly 0.1%
     * from their whole siblings (23.976 vs 24, 29.97 vs 30, 59.94 vs 60), so a mode in the same
     * family is always recognised. A false multiple is much further away: 120 Hz is 0.5% from five
     * times 23.976, so it is correctly not treated as one. Because the tolerance scales with the
     * cadence, it is an absolute margin of 0.048 Hz for 24 fps content and 0.12 Hz for 60 fps —
     * comfortably wider than measurement noise, far narrower than the gap between real cadences.
     */
    const val RATIO_TOLERANCE = 0.002f

    /** Rates closer than this are the same rate, so there is nothing new to request. */
    const val RATE_EPSILON_HZ = 0.01f

    private const val TIER_EXACT = 0
    private const val TIER_MULTIPLE = 1
    private const val TIER_BEST_EFFORT = 2
    private const val TIER_NONE = Int.MAX_VALUE

    /**
     * Chooses a display mode for [frameRate].
     *
     * @param automaticEnabled false when the user asked for the system default.
     */
    fun decide(
        frameRate: FrameRateInfo,
        capabilities: DisplayRefreshCapabilities,
        automaticEnabled: Boolean = true,
    ): RefreshRateDecision {
        val cadence = frameRate.fps?.takeIf { it.isFinite() && it > 0f }

        if (!automaticEnabled) {
            return decision(
                RefreshRateStatus.MANUAL,
                RefreshRateReason.AUTOMATIC_DISABLED,
                frameRate,
                cadence,
            )
        }

        if (cadence == null) {
            return decision(RefreshRateStatus.UNKNOWN, RefreshRateReason.NO_FRAME_RATE, frameRate, null)
        }

        if (frameRate.isVariableFrameRate == true) {
            // A source that changes cadence cannot be matched by a mode that does not. Staying put
            // is the conservative answer; chasing it would switch modes as the content varies.
            return decision(
                RefreshRateStatus.UNKNOWN,
                RefreshRateReason.VARIABLE_FRAME_RATE,
                frameRate,
                cadence,
            )
        }

        val usableModes = capabilities.modes.filter { it.isUsable }
        if (usableModes.isEmpty()) {
            return decision(
                RefreshRateStatus.UNKNOWN,
                RefreshRateReason.CAPABILITIES_UNKNOWN,
                frameRate,
                cadence,
            )
        }

        val exactMatches = usableModes.filter { isSameCadence(it.refreshRateHz, cadence) }
        val multipleMatches = usableModes.filter { isIntegerMultiple(it.refreshRateHz, cadence) }
        val bestEffortMatches = usableModes.filter { it.refreshRateHz >= cadence - RATE_EPSILON_HZ }
        val lowConfidence = frameRate.confidence == MetadataConfidence.LOW ||
            frameRate.confidence == MetadataConfidence.UNKNOWN

        val bestEffort = bestEffortMatches.minByOrNull { it.refreshRateHz }

        val candidate = exactMatches
            .minByOrNull { abs(it.refreshRateHz - cadence) }
            ?.let { it to RefreshRateReason.EXACT_MODE }
            ?: multipleMatches
                .minByOrNull { it.refreshRateHz }
                ?.let { it to RefreshRateReason.INTEGER_MULTIPLE }
            // A rate read from a container header is a hint — MP4 files holding 23.976 fps very
            // often declare 24 — and an integer relationship is what makes a choice safe. A guess
            // about the rate on top of a cadence that does not divide evenly is two guesses, so
            // the non-integral fallback is declined when the rate is uncertain.
            ?: bestEffort?.takeUnless { lowConfidence }?.let { it to RefreshRateReason.BEST_EFFORT }

        if (candidate == null) {
            return when {
                lowConfidence && bestEffort != null -> decision(
                    RefreshRateStatus.UNCHANGED,
                    RefreshRateReason.LOW_CONFIDENCE,
                    frameRate,
                    cadence,
                )

                // Every mode refreshes slower than the content, so no choice can show every frame.
                else -> decision(
                    RefreshRateStatus.UNSUPPORTED,
                    RefreshRateReason.NO_SUITABLE_MODE,
                    frameRate,
                    cadence,
                )
            }
        }

        val currentMode = capabilities.currentMode
        if (currentMode != null &&
            tierOf(currentMode.refreshRateHz, cadence) <= tierOf(candidate.first.refreshRateHz, cadence)
        ) {
            return decision(
                RefreshRateStatus.UNCHANGED,
                RefreshRateReason.ALREADY_SUITABLE,
                frameRate,
                cadence,
            )
        }

        val (mode, reason) = candidate
        return RefreshRateDecision(
            status = if (reason == RefreshRateReason.BEST_EFFORT) {
                RefreshRateStatus.FALLBACK
            } else {
                RefreshRateStatus.MATCHED
            },
            reason = reason,
            targetMode = mode,
            cadenceHz = cadence,
            confidence = frameRate.confidence,
        )
    }

    /** True when a display refreshing at [modeRateHz] shows each frame of [cadence] exactly once. */
    private fun isSameCadence(modeRateHz: Float, cadence: Float): Boolean =
        abs(modeRateHz - cadence) <= RATIO_TOLERANCE * cadence

    /** True when [modeRateHz] is at least twice [cadence] and a whole multiple of it. */
    private fun isIntegerMultiple(modeRateHz: Float, cadence: Float): Boolean {
        val ratio = modeRateHz / cadence
        val nearestWhole = ratio.roundToInt()
        return nearestWhole >= 2 && abs(ratio - nearestWhole) <= RATIO_TOLERANCE
    }

    /** Lower is better; [TIER_NONE] means the mode cannot present the cadence at all. */
    private fun tierOf(modeRateHz: Float, cadence: Float): Int = when {
        isSameCadence(modeRateHz, cadence) -> TIER_EXACT
        isIntegerMultiple(modeRateHz, cadence) -> TIER_MULTIPLE
        modeRateHz >= cadence - RATE_EPSILON_HZ -> TIER_BEST_EFFORT
        else -> TIER_NONE
    }

    private fun decision(
        status: RefreshRateStatus,
        reason: RefreshRateReason,
        frameRate: FrameRateInfo,
        cadenceHz: Float?,
        targetMode: DisplayModeInfo? = null,
    ): RefreshRateDecision = RefreshRateDecision(
        status = status,
        reason = reason,
        targetMode = targetMode,
        cadenceHz = cadenceHz,
        confidence = frameRate.confidence,
    )
}
