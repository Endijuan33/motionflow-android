package com.motionflow.player.feature.player

import android.app.Application
import android.content.ComponentName
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.motionflow.player.MotionFlowApplication
import com.motionflow.player.core.media.metadata.MetadataError
import com.motionflow.player.core.media.metadata.MetadataResult
import com.motionflow.player.core.media.metadata.TrackFormatHint
import com.motionflow.player.core.media.pacing.FramePacingCoordinator
import com.motionflow.player.core.media.pacing.FramePacingState
import com.motionflow.player.core.media.player.PlayerError
import com.motionflow.player.core.media.player.PlayerErrorKind
import com.motionflow.player.core.media.player.PlayerState
import com.motionflow.player.core.media.performance.PerformanceCommandResult
import android.os.Build
import android.os.SystemClock
import com.motionflow.player.BuildConfig
import com.motionflow.player.core.media.metadata.MetadataResult
import com.motionflow.player.core.media.pacing.FramePacingMechanism
import com.motionflow.player.core.media.performance.CadenceObservation
import com.motionflow.player.core.media.performance.DeviceRecord
import com.motionflow.player.core.media.performance.FramePerformanceSnapshot
import com.motionflow.player.core.media.performance.PerformanceFeasibilityPolicy
import com.motionflow.player.core.media.performance.PerformanceMetric
import com.motionflow.player.core.media.performance.PerformanceReport
import com.motionflow.player.core.media.performance.DisplayCharacteristics
import com.motionflow.player.core.media.performance.DisplayRequestOutcome
import com.motionflow.player.core.media.performance.PerformanceArchive
import com.motionflow.player.core.media.performance.PerformanceComparison
import com.motionflow.player.core.media.performance.PerformanceDiagnostics
import com.motionflow.player.core.media.performance.PerformanceRun
import com.motionflow.player.core.media.performance.PerformanceRunStatus
import com.motionflow.player.core.media.performance.ProcessingPerformanceMode
import com.motionflow.player.core.media.performance.PerformanceSessionLength
import com.motionflow.player.core.media.performance.PerformanceSessionRequest
import com.motionflow.player.core.media.performance.RunCondition
import com.motionflow.player.core.media.performance.RunTimestamp
import com.motionflow.player.core.media.performance.VideoCharacteristics
import com.motionflow.player.core.media.performance.VideoFingerprint
import com.motionflow.player.core.media.performance.android.MediaSessionPerformanceController
import com.motionflow.player.core.media.processing.ProcessingCoordinator
import com.motionflow.player.core.media.processing.ProcessingDiagnostics
import com.motionflow.player.core.media.processing.ProcessingRequest
import com.motionflow.player.core.media.processing.android.MediaSessionProcessingController
import com.motionflow.player.core.media.refresh.DisplayCapabilityProvider
import com.motionflow.player.core.media.refresh.RefreshRateController
import com.motionflow.player.core.media.refresh.RefreshRateCoordinator
import com.motionflow.player.core.media.refresh.RefreshRateState
import com.motionflow.player.core.media.rendering.RenderingCoordinator
import com.motionflow.player.core.media.rendering.RenderingDiagnostics
import com.motionflow.player.core.media.rendering.SurfaceType
import com.motionflow.player.core.media.session.MotionFlowMediaSessionService
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives playback through the application's media session, and reads the source's metadata
 * alongside it.
 *
 * The screen holds no `ExoPlayer`: it connects a [MediaController] to
 * [MotionFlowMediaSessionService], which owns the single engine for the process. That is what makes
 * rotation and screen navigation safe — the view model and its controller are torn down and
 * rebuilt, while the session and its player carry on — and it is what keeps the player out of the
 * composition, where it would be recreated and leaked.
 *
 * Playback and metadata are independent paths on purpose. Playback waits only for the document's
 * label, which the storage provider answers immediately and which the media item needs in order to
 * be presented correctly; the technical read runs on its own coroutine and its failure never stops
 * the video.
 */
class PlayerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val metadataRepository = (application as MotionFlowApplication).metadataRepository

    /**
     * The measurements taken so far, held by the application.
     *
     * Reading it here rather than owning it: a comparison needs both pipelines, and switching pipeline
     * restarts the service — so the history has to outlive every screen and every service.
     */
    private val performanceHistoryStore = (application as MotionFlowApplication).performanceHistoryStore

    /**
     * Every run recorded in this process, grouped by condition.
     *
     * The archive is where a characterization lives: runs of the same video, rate and window are grouped
     * so repeat-to-repeat variation can be seen, and the comparison of two pipelines is drawn from two
     * groups that describe the same conditions.
     */
    private val performanceRunStore = (application as MotionFlowApplication).performanceRunStore

    /** The archive, for a panel that shows what has been measured. */
    val performanceArchive: StateFlow<PerformanceArchive> = performanceRunStore.archive

    private val refreshRateCoordinator = RefreshRateCoordinator(viewModelScope)

    /**
     * Display refresh-rate diagnostics for the current video.
     *
     * Kept apart from [uiState] because it changes for different reasons: playback state moves
     * constantly, while this moves only when the video's cadence, the display or the user's
     * preference does.
     */
    val refreshRateState: StateFlow<RefreshRateState> = refreshRateCoordinator.state

    /**
     * Cadence and pacing diagnostics for the current video.
     *
     * No pacing mechanism is supplied, so every decision is a diagnosis. See `FramePacingController`
     * for why nothing in the current architecture can change frame presentation without replacing
     * Media3's video renderer — the diagnostics say "diagnostic only" precisely because that is the
     * honest answer, rather than reporting a fix that does not exist.
     */
    private val framePacingCoordinator = FramePacingCoordinator(viewModelScope)

    val framePacingState: StateFlow<FramePacingState> = framePacingCoordinator.state

    /**
     * What the rendering path is doing, and what it could do.
     *
     * The foundation describes Media3's own path; it never joins it. Nothing here can delay a frame,
     * and if this coordinator were removed, playback would be indistinguishable — which is the
     * property the tests check.
     */
    private val renderingCoordinator = RenderingCoordinator()

    val renderingDiagnostics: StateFlow<RenderingDiagnostics> = renderingCoordinator.diagnostics

    /**
     * Whether a processing stage is in the video path, and why not when it is not.
     *
     * A separate question from rendering, with a separate owner: the rendering foundation describes
     * frames arriving at a surface, while this reports attachment — and attachment can only change at
     * the process-owned player, through the session command the screen and the session service share.
     * Nothing here holds an `ExoPlayer`, and nothing here can make one.
     */
    private val processingCoordinator = ProcessingCoordinator()

    val processingDiagnostics: StateFlow<ProcessingDiagnostics> = processingCoordinator.diagnostics

    /**
     * The measurement picture, as the session last reported it — with the refusal, if there was one.
     *
     * The numbers are the service's: it owns the recorder, because Media3's counters belong to the
     * engine it owns. This state is a mirror updated from command answers, so nothing here can disagree
     * with what was actually measured — and the answer travels with its refusal, because "the player
     * declined" and "nobody was asked" are different findings.
     */
    private val _performanceReport = MutableStateFlow(PerformanceCommandResult())

    val performanceReport: StateFlow<PerformanceCommandResult> = _performanceReport.asStateFlow()

    /**
     * The two baselines side by side, from the application's history.
     *
     * Application-scoped because a comparison spans two runs under two pipelines, and changing the
     * pipeline restarts the service that produced the first measurement.
     */
    val performanceComparison: StateFlow<PerformanceComparison> = performanceHistoryStore.history
        .map { it.comparison }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = performanceHistoryStore.history.value.comparison,
        )

    /** The transport, bound to the session connection and released with this view model. */
    private var performanceController: MediaSessionPerformanceController? = null

    /** The window the running measurement was started with, so it can be stopped on time. */
    private var measurementLength: PerformanceSessionLength? = null

    /** When the running measurement began, as a monotonic reading. Not a wall clock, and never exported. */
    private var measurementStartedAtMs: Long? = null

    /** When the current item was asked to prepare, so first-frame latency can be measured. */
    private var prepareRequestedAtNanos: Long? = null

    private val sourceUri: String? = PlayerRoute.sourceUriOf(savedStateHandle)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /**
     * The player the video surface renders from, or `null` until the session connection is up.
     * Exposed separately from [uiState] so that a position tick does not invalidate the surface.
     */
    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var metadataJob: Job? = null
    private var lastFormatHint: TrackFormatHint? = null

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) = syncState()

        override fun onIsPlayingChanged(isPlaying: Boolean) = syncState()

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = syncState()

        override fun onRepeatModeChanged(repeatMode: Int) = syncState()

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = syncState()

        override fun onTracksChanged(tracks: Tracks) {
            syncState()
            // Media3 can describe the codec, colour and bitrate of each track once it has parsed
            // them, which it has not done at the moment playback starts. When that description
            // arrives, the metadata is read again with it — once, because the repository recognises
            // an already enriched description and will not repeat the work.
            val hint = TrackFormatHint.from(tracks) ?: return
            if (hint == lastFormatHint) return
            lastFormatHint = hint
            observeMetadata(hint)
        }

        override fun onRenderedFirstFrame() {
            // Measured across prepare-to-presentation, which is the interval a viewer would notice.
            val requestedAt = prepareRequestedAtNanos
            renderingCoordinator.onFirstFrameRendered(
                latencyMs = requestedAt?.let { (System.nanoTime() - it) / NANOS_PER_MILLI },
            )
            // A first frame is a meaningful state change, and one of the few moments a measurement is
            // worth refreshing: no timer is involved, and nothing is published per frame.
            readMeasurement()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // Media3 reports 0x0 for a size it does not know; that is not a size.
            renderingCoordinator.onVideoSizeChanged(
                width = videoSize.width.takeIf { it > 0 },
                height = videoSize.height.takeIf { it > 0 },
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            // The full exception goes to logcat; only the classification reaches the UI.
            Log.w(TAG, "Playback failed with code ${error.errorCode}", error)
            _uiState.update { state ->
                state.copy(
                    error = PlayerError.from(
                        errorCode = error.errorCode,
                        technicalDetail = error.message ?: "PlaybackException ${error.errorCode}",
                    ),
                )
            }
        }
    }

    init {
        connectToSession()
        pollPositionWhileAttached()
        // The refresh engine owns the display decision; the pacing engine analyses what that left
        // behind, including the case where the platform would not move the display.
        viewModelScope.launch {
            refreshRateCoordinator.state.collect { state ->
                framePacingCoordinator.onRefreshRateState(state)
                // And the processing report carries both rates, so a reader does not have to join
                // three sources to answer one question. They are copies; processing derives nothing
                // from them and cannot change them.
                processingCoordinator.onCadence(
                    videoFps = state.frameRate.fps,
                    displayRefreshRateHz = state.displayRefreshRateHz,
                )
            }
        }
    }

    fun playPause() {
        val controller = controller ?: return
        when {
            controller.playbackState == Player.STATE_ENDED -> {
                controller.seekTo(0L)
                controller.play()
            }

            controller.isPlaying -> controller.pause()
            else -> controller.play()
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun cyclePlaybackSpeed() {
        val controller = controller ?: return
        controller.setPlaybackSpeed(
            PlayerUiState.nextPlaybackSpeed(controller.playbackParameters.speed),
        )
    }

    fun toggleRepeatMode() {
        val controller = controller ?: return
        controller.repeatMode = if (controller.repeatMode == Player.REPEAT_MODE_OFF) {
            Player.REPEAT_MODE_ALL
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    /** Clears the failures and asks for both the current item and its description again. */
    fun retry() {
        _uiState.update { it.copy(error = null) }
        observeMetadata(lastFormatHint)

        val controller = controller
        if (controller == null) {
            if (controllerFuture == null) connectToSession()
            return
        }
        controller.prepare()
    }

    /**
     * Hands the refresh-rate engine the player window's display and its controller.
     *
     * Called by the screen when it appears and again after a configuration change, because the
     * window — and with it any display preference — is recreated each time. The screen owns both
     * objects: the window belongs to the UI layer, and holding it here would mean this view model
     * reaching into Android display APIs directly.
     */
    fun onRefreshRateEnvironmentAttached(
        controller: RefreshRateController,
        capabilities: DisplayCapabilityProvider,
    ) {
        refreshRateCoordinator.attach(controller, capabilities)
    }

    /** Hands the display back when the player screen goes away. */
    fun onRefreshRateEnvironmentDetached() {
        refreshRateCoordinator.detach()
    }

    /** Turns automatic refresh-rate selection on or off for this session. */
    fun setAutomaticRefreshRate(enabled: Boolean) {
        refreshRateCoordinator.setAutomaticEnabled(enabled)
    }

    /**
     * Reports that the player screen has a video surface, and what kind.
     *
     * The screen owns the view, so it is the only thing that can observe this. Only values cross the
     * boundary — never the view or its surface.
     *
     * Both coordinators are told, because they answer different questions: the rendering foundation
     * describes the surface, and the processing report needs to know whether there is anything for a
     * stage to render through. Neither of them is a second source of truth, and neither may hold the
     * surface.
     */
    fun onRenderingSurfaceCreated(surfaceType: SurfaceType) {
        renderingCoordinator.onSurfaceCreated(surfaceType)
        processingCoordinator.onSurfaceChanged(bound = true)
    }

    /** Reports that the player screen's surface has gone, so no binding outlives it. */
    fun onRenderingSurfaceReleased() {
        renderingCoordinator.onSurfaceReleased()
        processingCoordinator.onSurfaceChanged(bound = false)
    }

    /**
     * Asks for a processing stage to be put into the video path, or taken out of it.
     *
     * The answer is reported through [processingDiagnostics] rather than returned, so a caller cannot
     * mistake a refusal for a failure of playback. Nothing is exposed in the interface yet that calls
     * this: a control that could never succeed would be a lie, and no stage exists to attach. The path
     * is live and covered by tests, and it is what a later phase drives.
     */
    fun requestProcessing(request: ProcessingRequest) {
        viewModelScope.launch {
            processingCoordinator.request(request)
        }
    }

    /**
     * Starts a measured session of [length] on the pipeline the engine was built for.
     *
     * The window is the client's to keep: it asks for a length, waits exactly that long, and asks the
     * session to close. One wait, not a timer — nothing is polled, and nothing is published per frame.
     *
     * The two rates travel with the request because the engines that own them are here: the metadata
     * engine measured the source's rate, and the refresh engine owns the display. This phase consumes
     * both rather than deriving either.
     */
    fun startMeasurement(length: PerformanceSessionLength) {
        if (_performanceReport.value.diagnostics.isMeasuring) return

        viewModelScope.launch {
            val controller = performanceController ?: return@launch
            val result = runCatching {
                controller.start(
                    PerformanceSessionRequest(
                        length = length,
                        mode = _performanceReport.value.diagnostics.mode,
                        sourceFps = refreshRateCoordinator.state.value.frameRate.fps,
                        displayRefreshRateHz = refreshRateCoordinator.state.value.displayRefreshRateHz,
                    ),
                )
            }.getOrElse { PerformanceCommandResult.Unreachable }

            _performanceReport.value = result
            if (result.unreachable || result.diagnostics.session?.isRunning != true) return@launch

            measurementLength = length
            measurementStartedAtMs = SystemClock.elapsedRealtime()
            // The controlled window, elapsed once. A session that is stopped early is closed by
            // stopMeasurement instead, and the service closes one whose window has passed without it.
            delay(length.durationMs)
            closeMeasurement(length)
        }
    }

    /** Ends the running measurement early, keeping what it measured and labelling it as short. */
    fun stopMeasurement() {
        val length = measurementLength ?: return
        if (!_performanceReport.value.diagnostics.isMeasuring) return

        measurementLength = null
        viewModelScope.launch { closeMeasurement(length) }
    }

    /**
     * Asks the session for the current picture.
     *
     * Called when the screen already knows something changed — a first frame, the end of a session —
     * so a measurement updates without a timer and without per-frame work.
     */
    private fun readMeasurement() {
        if (!_performanceReport.value.diagnostics.isMeasuring) return

        viewModelScope.launch {
            val controller = performanceController ?: return@launch
            val result = runCatching { controller.read() }.getOrElse { PerformanceCommandResult.Unreachable }
            if (!result.unreachable) _performanceReport.value = result
        }
    }

    /**
     * Closes the session and files the measurement under the pipeline it was taken on.
     *
     * A failed run is not filed: the diagnostics report the failure as a failure, and keeping its
     * numbers beside a complete measurement would invite comparing nothing with something.
     */
    private suspend fun closeMeasurement(length: PerformanceSessionLength) {
        val controller = performanceController ?: return

        val result = runCatching { controller.stop() }.getOrElse { PerformanceCommandResult.Unreachable }
        if (result.unreachable) return

        _performanceReport.value = result
        val session = result.diagnostics.session
        if (session != null && !session.isRunning) {
            performanceHistoryStore.record(session.mode, result.diagnostics.snapshot)
            performanceRunStore.record(runRecordOf(length, session.mode, result.diagnostics.snapshot))
        }
    }

    /**
     * Files a finished session as a characterization run.
     *
     * The status is decided from what was measured rather than trusted from a label: a run that claims to
     * have completed but did not last its window is recorded as incomplete, because the label is what a
     * comparison trusts and a short window compared with a full one would be a false difference.
     *
     * Every context field comes from the engine that owns it — the metadata engine for the video, the
     * refresh engine for the display, the cadence engine for the classification — and none of them is
     * re-derived here. That is what keeps the processing pipeline from being able to change what cadence
     * means.
     */
    private fun runRecordOf(
        length: PerformanceSessionLength,
        mode: ProcessingPerformanceMode,
        snapshot: FramePerformanceSnapshot,
    ): PerformanceRun {
        val measured = snapshot.measurementDurationMs
        val status = when {
            mode == ProcessingPerformanceMode.FAILED -> PerformanceRunStatus.FAILED
            measured != null && measured >= (length.durationMs * COMPLETE_WINDOW_FRACTION).toLong() ->
                PerformanceRunStatus.COMPLETE

            else -> PerformanceRunStatus.INCOMPLETE
        }

        return PerformanceRun(
            index = 0,
            startedAt = measurementStartedAtMs?.let(::RunTimestamp),
            condition = RunCondition(
                mode = mode,
                video = videoCharacteristics(),
                display = displayCharacteristics(),
                length = length,
            ),
            snapshot = snapshot,
            unsupported = snapshot.unavailable,
            cadence = cadenceObservation(),
            status = status,
        )
    }

    /** What the metadata engine measured about the video, copied rather than reinterpreted. */
    private fun videoCharacteristics(): VideoCharacteristics {
        val metadata = (_uiState.value.metadata as? MetadataResult.Success)?.metadata
        val track = metadata?.video
        val frameRate = track?.frameRate

        return VideoCharacteristics(
            fingerprint = VideoFingerprint.of(
                sizeBytes = metadata?.fileSizeBytes,
                durationMs = metadata?.durationMs,
                width = track?.width,
                height = track?.height,
            ),
            sourceFps = frameRate?.fps,
            namedRate = frameRate?.knownRate?.name,
            isVariableFrameRate = frameRate?.isVariableFrameRate,
            confidence = frameRate?.confidence?.name,
            width = track?.width,
            height = track?.height,
            containerType = metadata?.mimeType,
            durationMs = metadata?.durationMs,
        )
    }

    /**
     * What the display was asked for and what it did.
     *
     * The refresh engine remains the only thing that chooses a mode; this copies its decision, its
     * applied rate and its error, and derives nothing it did not already say. The one thing it cannot say
     * is whether a request was *silently* ignored, and that is recorded as `NOT_APPLIED` rather than as a
     * refusal: a rate that differs, with no error, is a request that did not take effect, and calling it
     * refused would be inventing a refusal nobody reported.
     */
    private fun displayCharacteristics(): DisplayCharacteristics {
        val state = refreshRateCoordinator.state.value
        val requestedHz = state.decision.targetMode?.refreshRateHz
        val appliedHz = state.appliedRefreshRateHz

        val outcome = when {
            requestedHz == null -> DisplayRequestOutcome.NOT_REQUESTED
            state.error != null -> DisplayRequestOutcome.REFUSED
            appliedHz == null -> DisplayRequestOutcome.UNKNOWN
            abs(appliedHz - requestedHz) <= REFRESH_RATE_EPSILON_HZ -> DisplayRequestOutcome.HONOURED
            else -> DisplayRequestOutcome.NOT_APPLIED
        }

        return DisplayCharacteristics(
            panelRefreshRatesHz = state.capabilities.modes
                .filter { it.isUsable }
                .map { it.refreshRateHz }
                .distinct(),
            requestedRefreshRateHz = requestedHz,
            appliedRefreshRateHz = appliedHz,
            automaticSelectionEnabled = state.isAutomaticEnabled,
            outcome = outcome,
            engineStatus = state.decision.status.name,
            errorName = state.error?.name,
        )
    }

    /** The cadence and pacing engines' own conclusions, quoted so the interaction can be checked. */
    private fun cadenceObservation(): CadenceObservation {
        val pacing = framePacingState.value
        val refresh = refreshRateCoordinator.state.value

        return CadenceObservation(
            cadenceMode = (pacing.diagnostics?.mode ?: pacing.decision.mode).name,
            cadenceReason = (pacing.diagnostics?.reason ?: pacing.decision.reason).name,
            pacingMode = pacing.decision.mode.name,
            pacingApplied = pacing.decision.mechanism != FramePacingMechanism.NONE,
            sourceFps = refresh.frameRate.fps,
            displayRefreshRateHz = refresh.appliedRefreshRateHz,
        )
    }

    /**
     * The characterization as plain text, for a person to keep or share themselves.
     *
     * Produced on demand and never sent anywhere: there is no analytics dependency, no uploader and no
     * background anything. The device fields are the coarse hardware class the phase's rules allow —
     * manufacturer, model and an ABI, never an identifier — and the video appears as a fingerprint
     * derived from its shape rather than from its name.
     */
    fun characterizationText(): String {
        val archive = performanceRunStore.archive.value
        val pair = archive.comparablePair()
        val support = _performanceReport.value.diagnostics.support

        return PerformanceReport.text(
            device = deviceRecord(),
            native = pair.native,
            effect = pair.effect,
            feasibility = PerformanceFeasibilityPolicy.evaluate(pair.native, pair.effect, support),
            support = support,
        )
    }

    /** The build and the coarse hardware class, and nothing that identifies a person or a device. */
    private fun deviceRecord(): DeviceRecord {
        val display = refreshRateCoordinator.state.value.capabilities

        return DeviceRecord(
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE,
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull(),
            cpuArchitecture = System.getProperty("os.arch"),
            screenWidthPx = null,
            screenHeightPx = null,
            screenDensityDpi = null,
            // Deliberately unrecorded: the refresh engine owns display access and exposes modes, not HDR
            // capability. A second display reader for one boolean would be a second owner of the display.
            hdrCapable = null,
            supportedDisplayRefreshRatesHz = display.modes.map { it.refreshRateHz }.distinct(),
            memoryClassMb = null,
            thermalApiAvailable = _performanceReport.value.diagnostics.support
                .supports(PerformanceMetric.THERMAL_STATUS),
            media3Version = null,
            build = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
    }

    override fun onCleared() {
        // Restore the display before anything else: the preference belongs to this screen.
        refreshRateCoordinator.detach()
        // And let go of the surface, so a processing stage could never outlive the view.
        renderingCoordinator.onSurfaceReleased()
        processingCoordinator.onSurfaceChanged(bound = false)
        // The session goes on serving other screens; this view model just stops being able to ask it
        // for anything, so a request in flight cannot publish into a destroyed screen.
        processingCoordinator.unbindController()
        // The measurement transport goes with it: a request in flight must not publish into a
        // destroyed screen's state.
        performanceController = null
        measurementLength = null

        val controller = controller
        if (controller != null) {
            // Leaving the player surface pauses playback. Backgrounding the application is a
            // different case: playback continues there on purpose, controlled from the media
            // notification, which is what the session service exists for.
            controller.pause()
            controller.removeListener(playerListener)
        }
        this.controller = null
        _player.value = null

        // Releasing the connection does not stop the session: the service keeps the player, and a
        // later screen attaches to the same instance instead of building a second one.
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null

        super.onCleared()
    }

    private fun connectToSession() {
        val context = getApplication<Application>()
        val sessionToken = SessionToken(
            context,
            ComponentName(context, MotionFlowMediaSessionService::class.java),
        )

        val future = MediaController.Builder(context, sessionToken)
            .setListener(DisconnectionWatcher())
            .buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val connected = runCatching { future.get() }.getOrElse { failure ->
                    Log.w(TAG, "Could not connect to the media session", failure)
                    _uiState.update { state ->
                        state.copy(
                            error = PlayerError(
                                kind = PlayerErrorKind.PLAYBACK_FAILED,
                                technicalDetail = failure.message.orEmpty(),
                            ),
                        )
                    }
                    null
                }
                if (connected != null) onSessionConnected(connected)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun onSessionConnected(controller: MediaController) {
        Log.d(TAG, "Connected to the media session")
        this.controller = controller
        controller.addListener(playerListener)
        _player.value = controller
        // The connection is the only route to the process-owned player, so it is also what makes the
        // processing command reachable — or not, if the session does not offer it, which the
        // controller discovers per request rather than assuming here.
        processingCoordinator.bindController(MediaSessionProcessingController(controller))
        // The measurement travels the same route, for the same reason: the recorder lives where the
        // player lives, and a screen can only ask.
        performanceController = MediaSessionPerformanceController(controller)
        loadSource(controller)
        syncState()
    }

    /**
     * Drops the controller when the session releases it.
     *
     * The session is released deliberately when the measured pipeline changes — the engine is rebuilt
     * for the new pipeline — so a screen can find itself holding a controller whose session is gone. A
     * call on a released controller throws, so the reference is dropped and the screen falls back to
     * its no-player state.
     *
     * No reconnection is attempted: a reconnect loop around a teardown that was intended would be a
     * worse failure than an empty screen, and reopening the video connects again.
     */
    private inner class DisconnectionWatcher : MediaController.Listener {

        override fun onDisconnected(controller: MediaController) {
            if (this@PlayerViewModel.controller !== controller) return

            Log.d(TAG, "Media session released; dropping the controller")
            this@PlayerViewModel.controller = null
            performanceController = null
            measurementLength = null
            _player.value = null
            _performanceReport.value = PerformanceCommandResult.Unreachable
        }
    }

    private fun loadSource(controller: MediaController) {
        val sourceUri = sourceUri
        if (sourceUri == null || !PlayerRoute.isSupportedSource(sourceUri)) {
            // Deliberately no URI in the log line: a media path identifies what someone is watching.
            Log.w(TAG, "Rejected a media source MotionFlow cannot open")
            _uiState.update { state ->
                state.copy(
                    error = PlayerError(
                        kind = PlayerErrorKind.INVALID_SOURCE,
                        technicalDetail = "Source scheme is not content:// or file://",
                    ),
                    metadata = MetadataResult.Error(MetadataError.INVALID_URI),
                )
            }
            return
        }

        viewModelScope.launch {
            // One provider query, and the only thing playback waits for: the media item needs its
            // label now, or the notification and the chrome would show nothing until the technical
            // read finished.
            val title = metadataRepository.document(sourceUri).title
            controller.setMediaItem(mediaItem(sourceUri, title))
            prepareRequestedAtNanos = System.nanoTime()
            controller.prepare()
            controller.play()

            observeMetadata(lastFormatHint)
        }
    }

    /**
     * Collects the metadata read for the current source, replacing any read still in flight.
     *
     * Cancelling the previous collection is what makes a new source supersede an old one: the
     * abandoned read stops at its next suspension point instead of publishing into the state.
     */
    private fun observeMetadata(hint: TrackFormatHint?) {
        val sourceUri = sourceUri ?: return
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch {
            metadataRepository.metadata(sourceUri, hint).collect { result ->
                _uiState.update { state -> state.copy(metadata = result) }
                // Only a description carries a cadence. A loading or failed read leaves the last
                // known one in place rather than briefly declaring the frame rate unknown, which
                // would discard a display preference that is still right for the video on screen.
                val frameRate = (result as? MetadataResult.Success)?.metadata?.video?.frameRate
                if (frameRate != null) {
                    refreshRateCoordinator.onVideoFrameRate(frameRate)
                    framePacingCoordinator.onVideoFrameRate(frameRate)
                }
            }
        }
    }

    private fun mediaItem(sourceUri: String, title: String?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        return MediaItem.Builder()
            .setUri(sourceUri)
            .setMediaId(sourceUri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun syncState() {
        val controller = controller ?: return
        _uiState.update { state ->
            state.copy(
                playerState = PlayerState.fromPlaybackState(controller.playbackState),
                isPlaying = controller.isPlaying,
                durationMs = controller.duration.coerceAtLeast(0L),
                positionMs = controller.currentPosition.coerceAtLeast(0L),
                bufferedPositionMs = controller.bufferedPosition.coerceAtLeast(0L),
                playbackSpeed = controller.playbackParameters.speed,
                isRepeatEnabled = controller.repeatMode != Player.REPEAT_MODE_OFF,
                videoTitle = controller.mediaMetadata.title?.toString(),
            )
        }
    }

    /**
     * Media3 reports position by callback only when it changes discontinuously, so the progress
     * readout is polled. The interval is the seek bar's effective resolution; updates that do not
     * change the state are dropped by the `StateFlow` before they can cause recomposition, so a
     * paused player costs nothing.
     */
    private fun pollPositionWhileAttached() {
        viewModelScope.launch {
            while (isActive) {
                syncState()
                delay(POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val TAG = "MotionFlowPlayback"
        const val POSITION_POLL_INTERVAL_MS = 500L
        const val NANOS_PER_MILLI = 1_000_000L

        /** A run has to last this much of its window to count as complete, matching the integrity rule. */
        const val COMPLETE_WINDOW_FRACTION = 0.9

        /** Two refresh rates are the same rate when they differ by less than this. */
        const val REFRESH_RATE_EPSILON_HZ = 0.5f
    }
}
