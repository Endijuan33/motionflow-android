package com.motionflow.player.core.media.pacing

import com.motionflow.player.core.media.metadata.FrameRateInfo
import com.motionflow.player.core.media.metadata.MetadataConfidence
import com.motionflow.player.core.media.refresh.RefreshRatePolicy
import com.motionflow.player.core.media.refresh.RefreshRateState
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The pacing engine's state: what the cadence is, and what was done about it. */
data class FramePacingState(
    val diagnostics: FramePacingDiagnostics? = null,
    val decision: FramePacingDecision = FramePacingDecision.NotEvaluated,
)

/**
 * Analyses a video's cadence against a display's, whenever either changes.
 *
 * The display side comes from the refresh-rate engine's state rather than from the platform: that
 * engine has already chosen a preference and knows what the display actually reports afterwards, so
 * the two engines stay separate — one decides the display, the other describes the resulting
 * cadence — with no selection logic repeated here.
 *
 * Android-free: there is nothing to reach for, because the analysis is arithmetic. [scope] must
 * outlive the coordinator and be cancelled by its owner.
 *
 * ## Work happens only on a change
 *
 * A cadence from the metadata engine and a display state from the refresh engine are the only
 * inputs; each triggers one evaluation, and a burst of inputs collapses into one evaluation of the
 * latest state. There is no timer, no per-frame work and no polling, and an input identical to the
 * last one changes nothing.
 *
 * [controller] is `null` in the application, which is what makes every decision diagnostic. See
 * [FramePacingController] for why, and what would have to change for one to be bound.
 */
class FramePacingCoordinator(
    private val scope: CoroutineScope,
    private val controller: FramePacingController? = null,
) {

    private val _state = MutableStateFlow(FramePacingState())
    val state: StateFlow<FramePacingState> = _state.asStateFlow()

    /** Set while an evaluation is owed, so a burst of inputs costs one analysis of the latest state. */
    private val evaluationOwed = MutableStateFlow(false)

    private var video: VideoCadence = VideoCadence.Unknown
    private var display: DisplayCadence = DisplayCadence.Unknown

    init {
        scope.launch {
            evaluationOwed.filter { it }.collect {
                evaluationOwed.value = false
                evaluate()
            }
        }
    }

    /** Feeds in the video's cadence, as the metadata engine refined it. */
    fun onVideoFrameRate(frameRate: FrameRateInfo) {
        video = frameRate.toVideoCadence()
        requestEvaluation()
    }

    /** Feeds in the display's cadence, as the refresh-rate engine left it. */
    fun onRefreshRateState(refreshRate: RefreshRateState) {
        display = refreshRate.toDisplayCadence()
        requestEvaluation()
    }

    private fun requestEvaluation() {
        evaluationOwed.value = true
    }

    private fun evaluate() {
        val diagnostics = FramePacingPolicy.analyse(video, display)

        // A mechanism is only worth consulting when there is something to pace: a cadence that cannot
        // be shown evenly. An unknown cadence, or one the display already matches, needs no action.
        val needsPacing = diagnostics.mode == FramePacingMode.CADENCE_MISMATCH ||
            diagnostics.mode == FramePacingMode.UNSUPPORTED

        val application = if (needsPacing) {
            controller?.let { pacing ->
                runCatching { pacing.apply(diagnostics.toDecision()) }.getOrElse {
                    FramePacingApplication(
                        mechanism = FramePacingMechanism.NONE,
                        error = FramePacingError.UNKNOWN,
                    )
                }
            }
        } else {
            null
        }

        _state.update {
            FramePacingState(
                diagnostics = diagnostics,
                decision = diagnostics.toDecision().copy(
                    mechanism = application?.mechanism ?: FramePacingMechanism.NONE,
                    error = application?.error,
                ),
            )
        }
    }
}

/** Projects the metadata engine's cadence model onto the one the analysis uses. */
internal fun FrameRateInfo.toVideoCadence(): VideoCadence = VideoCadence(
    fps = fps,
    confidence = confidence,
    isVariableFrameRate = isVariableFrameRate,
)

/**
 * Projects the refresh engine's state onto the display cadence, including whether the display ended
 * up where it was asked to.
 *
 * A request that was refused, or accepted and then ignored by the platform, both leave the display
 * somewhere other than the requested rate. Either way the pacing diagnostics need to know, so the
 * two cases are folded into one flag: it means "the display is not at the rate that was asked for".
 */
internal fun RefreshRateState.toDisplayCadence(): DisplayCadence {
    val reported = displayRefreshRateHz
    val requested = appliedRefreshRateHz
    val notAtRequestedRate = requested != null &&
        reported != null &&
        abs(requested - reported) > RefreshRatePolicy.RATE_EPSILON_HZ

    return DisplayCadence(
        refreshRateHz = reported,
        requestedRefreshRateHz = requested,
        requestRefused = error != null || notAtRequestedRate,
    )
}

/**
 * The decision implied by a diagnosis.
 *
 * The mechanism is deliberately absent here and added by the coordinator: only a controller can
 * claim pacing was applied, and this projection must not be able to claim it on its own.
 */
internal fun FramePacingDiagnostics.toDecision(): FramePacingDecision = FramePacingDecision(
    mode = mode,
    reason = reason,
    isReliable = confidence == MetadataConfidence.HIGH || confidence == MetadataConfidence.MEDIUM,
    mechanism = FramePacingMechanism.NONE,
)
