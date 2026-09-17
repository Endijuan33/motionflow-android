package com.motionflow.player.core.media.pacing

import com.motionflow.player.core.media.metadata.MetadataConfidence
import com.motionflow.player.core.media.refresh.RefreshRatePolicy
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Classifies the relationship between a video's cadence and a display's.
 *
 * Pure: it takes the two cadences and returns what it found. It never reads a display, never touches
 * a player and never applies anything.
 *
 * ## What the classification means
 *
 * The question is whether every frame can be held for the *same* number of refreshes.
 *
 * - `1:1` and whole multiples can. A 24 fps source on a 48 Hz display shows every frame twice.
 * - A short fraction cannot, but repeats exactly. 24 fps on 60 Hz is 5 refreshes to 2 frames: holds
 *   of 3 and then 2, forever. The motion is uneven, and the pattern is what the diagnostics name
 *   ("3:2"). 23.976 fps on 59.94 Hz is the *same* ratio — also exactly five to two — so it is
 *   classified the same way; only a policy that penalised whole-rate pairings could separate them,
 *   and that would be a judgement about the rates rather than a fact about the cadence.
 * - A ratio that is not a short fraction has no describable pattern. These are the pairings that
 *   drift: 24 fps on a 59.94 Hz panel is 2.4975, which slips a refresh every few seconds.
 *
 * ## A deliberate omission
 *
 * There is no "fractionally compatible" outcome. A fractional relationship is never even, and
 * calling one compatible would suggest the judder is not there. How long the repeating unit is —
 * and therefore how regular it looks — is carried by [FramePacingReason] instead.
 */
object FramePacingPolicy {

    /**
     * How close a ratio must be to a whole number or a fraction to count as that relationship.
     *
     * Shared with [RefreshRatePolicy] rather than chosen again: the two engines look at the same two
     * rates, and a pair the refresh engine calls a whole multiple must not be called a mismatch here.
     * At 24 fps the tolerance is a display-rate margin of 0.048 Hz — comfortably wider than
     * measurement noise, and narrow enough that 59.94 Hz is not mistaken for a multiple of 24.000
     * (which is 0.06 Hz away and genuinely drifts).
     */
    val RATIO_TOLERANCE: Float = RefreshRatePolicy.RATIO_TOLERANCE

    /**
     * The longest repeating unit still described as short.
     *
     * Two frames covers the familiar patterns — 3:2 for 24 fps at 60 Hz, 1:1 elsewhere — and is the
     * point where a person still reads the sequence as a steady pulse rather than as stutter.
     */
    const val SHORT_PATTERN_MAX_FRAMES = 2

    /**
     * The longest repeating unit searched for at all.
     *
     * Beyond eight frames the pattern is long enough that naming it would overstate how regular the
     * motion looks, and the pairings that land here are the ones from different cadence families —
     * where the repeating unit is hundreds of frames long and reads as drift.
     */
    const val PATTERN_SEARCH_MAX_FRAMES = 8

    private const val PATTERN_SEPARATOR = ":"

    /** Classifies [video] against [display]. */
    fun analyse(video: VideoCadence, display: DisplayCadence): FramePacingDiagnostics {
        val confidence = video.confidence
        val videoFps = video.fps?.takeIf { it.isFinite() && it > 0f }
        val displayHz = display.refreshRateHz?.takeIf { it.isFinite() && it > 0f }

        if (videoFps == null) {
            return FramePacingDiagnostics(
                video = video,
                display = display,
                mode = FramePacingMode.UNKNOWN,
                reason = FramePacingReason.NO_FRAME_RATE,
                confidence = confidence,
            )
        }
        if (video.isVariableFrameRate == true) {
            return FramePacingDiagnostics(
                video = video,
                display = display,
                mode = FramePacingMode.UNKNOWN,
                reason = FramePacingReason.VARIABLE_FRAME_RATE,
                confidence = confidence,
            )
        }
        if (displayHz == null) {
            return FramePacingDiagnostics(
                video = video,
                display = display,
                mode = FramePacingMode.UNKNOWN,
                reason = FramePacingReason.NO_DISPLAY_RATE,
                confidence = confidence,
            )
        }

        val value = displayHz / videoFps

        // A display that refreshes less often than the source delivers frames cannot show them all,
        // whatever the pattern: the source has to be thinned out.
        if (value < 1f - RATIO_TOLERANCE) {
            return FramePacingDiagnostics(
                video = video,
                display = display,
                ratio = CadenceRatio(value = value),
                mode = FramePacingMode.UNSUPPORTED,
                reason = FramePacingReason.DISPLAY_TOO_SLOW,
                confidence = confidence,
            )
        }

        val match = matchRatio(value)
        if (match == null) {
            return FramePacingDiagnostics(
                video = video,
                display = display,
                ratio = CadenceRatio(value = value),
                mode = FramePacingMode.CADENCE_MISMATCH,
                reason = FramePacingReason.UNRESOLVED_PATTERN,
                confidence = confidence,
            )
        }

        val ratio = CadenceRatio(
            value = value,
            refreshesPerFrame = match.refreshes,
            frameCount = match.frames,
            patternLabel = if (match.frames > 1) holdPatternLabel(match) else "",
        )

        val mode = when {
            match.frames == 1 && match.refreshes == 1 -> FramePacingMode.NATIVE_CADENCE
            match.frames == 1 -> FramePacingMode.INTEGER_MULTIPLE
            else -> FramePacingMode.CADENCE_MISMATCH
        }
        val reason = when {
            match.frames == 1 && match.refreshes == 1 -> FramePacingReason.EXACT_CADENCE
            match.frames == 1 -> FramePacingReason.WHOLE_MULTIPLE
            match.frames <= SHORT_PATTERN_MAX_FRAMES -> FramePacingReason.SHORT_REPEATING_PATTERN
            else -> FramePacingReason.LONG_REPEATING_PATTERN
        }

        return FramePacingDiagnostics(
            video = video,
            display = display,
            ratio = ratio,
            mode = mode,
            reason = reason,
            confidence = confidence,
        )
    }

    /**
     * Finds the simplest fraction the ratio equals, if there is one.
     *
     * Denominators are tried in ascending order, so the simplest description wins: 2.0 is two
     * refreshes per frame, not ten per five. Searching stops at [PATTERN_SEARCH_MAX_FRAMES].
     */
    private fun matchRatio(value: Float): RatioMatch? {
        for (frames in 1..PATTERN_SEARCH_MAX_FRAMES) {
            val refreshes = (value * frames).roundToInt()
            if (refreshes < 1) continue
            if (abs(value - refreshes.toFloat() / frames) <= RATIO_TOLERANCE) {
                return RatioMatch(refreshes, frames)
            }
        }
        return null
    }

    /**
     * Names the pattern of holds: how many refreshes each frame in the repeating unit gets.
     *
     * Refreshes are distributed as evenly as integer arithmetic allows, and the holds are written
     * longest first — 5 refreshes over 2 frames is "3:2", the pattern a display actually shows.
     */
    private fun holdPatternLabel(match: RatioMatch): String {
        val holds = IntArray(match.frames) { index ->
            (index + 1) * match.refreshes / match.frames - index * match.refreshes / match.frames
        }
        return holds.sortedDescending().joinToString(PATTERN_SEPARATOR)
    }

    private data class RatioMatch(val refreshes: Int, val frames: Int)
}
