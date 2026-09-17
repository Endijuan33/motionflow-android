package com.motionflow.player.core.media.refresh

import com.motionflow.player.core.media.metadata.FrameRateInfo
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the diagnostics need to say about the display, for one video on one display.
 *
 * [frameRate] is the video's cadence and [displayRefreshRateHz] is the panel's; they are different
 * quantities and are named so that they cannot be confused.
 */
data class RefreshRateState(
    val isAutomaticEnabled: Boolean = true,
    val frameRate: FrameRateInfo = FrameRateInfo.Unknown,
    val capabilities: DisplayRefreshCapabilities = DisplayRefreshCapabilities.Unknown,
    val decision: RefreshRateDecision = RefreshRateDecision.Unknown,
    val appliedRefreshRateHz: Float? = null,
    val appliedSource: RefreshRateSource = RefreshRateSource.NONE,
    val error: RefreshRateError? = null,
) {

    /** The rate the panel reports it is running at. Never the video's frame rate. */
    val displayRefreshRateHz: Float? get() = capabilities.currentMode?.refreshRateHz
}

/**
 * Decides, applies and remembers display refresh-rate preferences for the player screen.
 *
 * Android-free on purpose: the platform is reached through [RefreshRateController] and
 * [DisplayCapabilityProvider], both of which are faked in tests. That is what makes the whole
 * decision path — including what happens when a request is refused — verifiable without a device.
 *
 * ## When work happens
 *
 * Only when an input changes: a new frame rate from the metadata, a display change, the automatic
 * preference being toggled, or the screen attaching. There is no timer, no per-frame check and no
 * capability polling. Recomputations are coalesced through a conflated signal, so a burst of inputs
 * settles with one decision for the latest state rather than a queue of stale requests.
 *
 * ## What it does not do
 *
 * It does not touch playback. A refused request is recorded as a diagnostic and nothing else; the
 * video keeps playing at whatever rate the display is in.
 */
class RefreshRateCoordinator(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(RefreshRateState())
    val state: StateFlow<RefreshRateState> = _state.asStateFlow()

    /**
     * Set while a recomputation is owed.
     *
     * A flag rather than a queue: several inputs arriving together describe the *latest* state, so
     * one recomputation covers them all and a burst cannot pile up stale requests. It is also why
     * nothing is lost if an input arrives before the handler has started.
     */
    private val recomputeOwed = MutableStateFlow(false)

    private var controller: RefreshRateController? = null
    private var capabilityProvider: DisplayCapabilityProvider? = null
    private var displayChangesJob: Job? = null

    private var frameRate: FrameRateInfo = FrameRateInfo.Unknown
    private var automaticEnabled: Boolean = true
    private var appliedRequest: RefreshRateRequest? = null

    init {
        scope.launch {
            recomputeOwed.filter { it }.collect {
                recomputeOwed.value = false
                applyCurrentDecision()
            }
        }
    }

    /** Feeds the video's cadence in. Called when metadata loads and again when Media3 refines it. */
    fun onVideoFrameRate(frameRate: FrameRateInfo) {
        this.frameRate = frameRate
        requestRecompute()
    }

    /** Turns automatic selection on or off. Off means "stop requesting; use the system default". */
    fun setAutomaticEnabled(enabled: Boolean) {
        if (automaticEnabled == enabled) return
        automaticEnabled = enabled
        requestRecompute()
    }

    /**
     * Binds the window's display and its controller.
     *
     * The applied-request bookkeeping is dropped here: a new window starts with no preference of its
     * own, so the current decision has to be requested again — which is exactly what should happen
     * after a rotation.
     */
    fun attach(controller: RefreshRateController, capabilities: DisplayCapabilityProvider) {
        this.controller = controller
        capabilityProvider = capabilities
        appliedRequest = null

        displayChangesJob?.cancel()
        displayChangesJob = scope.launch {
            capabilities.displayChanges().collect { requestRecompute() }
        }

        requestRecompute()
    }

    /**
     * Unbinds the window and hands the display back.
     *
     * Called when the player screen leaves, so a rate chosen for one video does not outlive it.
     */
    fun detach() {
        displayChangesJob?.cancel()
        displayChangesJob = null

        val controller = controller
        if (controller != null && appliedRequest != null) {
            runCatching { controller.clear() }
        }
        appliedRequest = null
        this.controller = null
        capabilityProvider = null

        _state.update { current ->
            current.copy(
                capabilities = DisplayRefreshCapabilities.Unknown,
                appliedRefreshRateHz = null,
                appliedSource = RefreshRateSource.NONE,
                error = null,
            )
        }
    }

    /** Asks for a decision to be reconsidered, without doing any work on the calling frame. */
    fun requestRecompute() {
        recomputeOwed.value = true
    }

    private fun applyCurrentDecision() {
        val controller = controller ?: return
        val provider = capabilityProvider ?: return

        val capabilities = runCatching { provider.capabilities() }
            .getOrElse { DisplayRefreshCapabilities.Unknown }
        val decision = RefreshRatePolicy.decide(frameRate, capabilities, automaticEnabled)

        if (!automaticEnabled) {
            if (appliedRequest != null) {
                runCatching { controller.clear() }
                appliedRequest = null
            }
            publish(capabilities, decision, source = RefreshRateSource.NONE, error = null)
            return
        }

        val target = decision.targetMode
        if (target == null) {
            publish(
                capabilities = capabilities,
                decision = decision,
                source = currentSource(),
                error = null,
            )
            return
        }

        val request = RefreshRateRequest(modeId = target.modeId, refreshRateHz = target.refreshRateHz)
        if (request.matches(appliedRequest)) {
            // Same ask as last time: repeating it would be wasted work, and on some devices
            // a repeated mode request causes a visible flicker.
            publish(
                capabilities = capabilities,
                decision = decision,
                source = currentSource(),
                error = null,
            )
            return
        }

        val application = runCatching { controller.apply(request) }.getOrElse {
            RefreshRateApplication(
                applied = false,
                source = RefreshRateSource.NONE,
                error = RefreshRateError.UNKNOWN,
            )
        }

        appliedRequest = if (application.applied) request else null

        val rejected = !application.applied
        publish(
            capabilities = capabilities,
            decision = if (rejected) {
                decision.copy(
                    status = RefreshRateStatus.UNSUPPORTED,
                    reason = RefreshRateReason.PLATFORM_REJECTED,
                    targetMode = null,
                )
            } else {
                decision
            },
            source = application.source,
            error = application.error,
        )
    }

    private fun publish(
        capabilities: DisplayRefreshCapabilities,
        decision: RefreshRateDecision,
        source: RefreshRateSource,
        error: RefreshRateError?,
    ) {
        val request = appliedRequest
        _state.update { current ->
            current.copy(
                isAutomaticEnabled = automaticEnabled,
                frameRate = frameRate,
                capabilities = capabilities,
                decision = decision,
                appliedRefreshRateHz = request?.refreshRateHz,
                appliedSource = source,
                error = error,
            )
        }
    }

    /** The source that is still in force from an earlier application, if any. */
    private fun currentSource(): RefreshRateSource =
        if (appliedRequest != null) RefreshRateSource.WINDOW_REFRESH_RATE else RefreshRateSource.NONE

    /** Rounded comparison, so a float that differs in its last bits is not a new request. */
    private fun RefreshRateRequest.matches(other: RefreshRateRequest?): Boolean {
        if (other == null) return false
        if (modeId != null && other.modeId != null && modeId != other.modeId) return false
        return abs(refreshRateHz - other.refreshRateHz) <= RefreshRatePolicy.RATE_EPSILON_HZ
    }
}
