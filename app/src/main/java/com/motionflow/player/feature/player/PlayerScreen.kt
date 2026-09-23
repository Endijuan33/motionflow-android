package com.motionflow.player.feature.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.motionflow.player.R
import com.motionflow.player.core.designsystem.theme.MotionFlowTheme
import com.motionflow.player.core.designsystem.theme.MotionFlowVideoSurface
import com.motionflow.player.core.media.metadata.AudioTrackMetadata
import com.motionflow.player.core.media.metadata.FrameRateInfo
import com.motionflow.player.core.media.metadata.HdrInfo
import com.motionflow.player.core.media.metadata.MetadataConfidence
import com.motionflow.player.core.media.metadata.MetadataError
import com.motionflow.player.core.media.metadata.MetadataQuantity
import com.motionflow.player.core.media.metadata.MetadataResult
import com.motionflow.player.core.media.metadata.VideoMetadata
import com.motionflow.player.core.media.metadata.VideoTrackMetadata
import com.motionflow.player.core.media.metadata.audioCodecValue
import com.motionflow.player.core.media.metadata.bitrateQuantity
import com.motionflow.player.core.media.metadata.colorSpaceLabel
import com.motionflow.player.core.media.metadata.colorTransferLabel
import com.motionflow.player.core.media.metadata.durationValue
import com.motionflow.player.core.media.metadata.fileSizeQuantity
import com.motionflow.player.core.media.metadata.frameRateValue
import com.motionflow.player.core.media.metadata.formatFps
import com.motionflow.player.core.media.metadata.resolutionValue
import com.motionflow.player.core.media.metadata.sampleRateQuantity
import com.motionflow.player.core.media.metadata.videoCodecValue
import com.motionflow.player.core.media.pacing.DisplayCadence
import com.motionflow.player.core.media.pacing.FramePacingDecision
import com.motionflow.player.core.media.pacing.FramePacingMechanism
import com.motionflow.player.core.media.pacing.FramePacingMode
import com.motionflow.player.core.media.pacing.FramePacingPolicy
import com.motionflow.player.core.media.pacing.FramePacingReason
import com.motionflow.player.core.media.pacing.FramePacingState
import com.motionflow.player.core.media.pacing.VideoCadence
import com.motionflow.player.core.media.player.PlayerError
import com.motionflow.player.core.media.player.PlayerErrorKind
import com.motionflow.player.core.media.player.PlayerState
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.motionflow.player.core.media.performance.DeviceCharacteristics
import com.motionflow.player.core.media.performance.DisplayRequestOutcome
import com.motionflow.player.core.media.performance.PerformanceArchive
import com.motionflow.player.core.media.performance.FramePerformanceSnapshot
import com.motionflow.player.core.media.performance.PerformanceCommandResult
import com.motionflow.player.core.media.performance.PerformanceComparison
import com.motionflow.player.core.media.performance.PerformanceDiagnostics
import com.motionflow.player.core.media.performance.PerformanceMeasurementSupport
import com.motionflow.player.core.media.performance.PerformanceMetric
import com.motionflow.player.core.media.performance.PerformanceSessionLength
import com.motionflow.player.core.media.performance.ProcessingPerformanceMode
import com.motionflow.player.core.media.processing.ProcessingCapabilities
import com.motionflow.player.core.media.processing.ProcessingDiagnostics
import com.motionflow.player.core.media.processing.ProcessingMode
import com.motionflow.player.core.media.processing.ProcessingReason
import com.motionflow.player.core.media.refresh.RefreshRateReason
import com.motionflow.player.core.media.refresh.RefreshRateState
import com.motionflow.player.core.media.refresh.RefreshRateStatus
import com.motionflow.player.core.media.refresh.android.AndroidDisplayCapabilityProvider
import com.motionflow.player.core.media.refresh.android.AndroidRefreshRateController
import com.motionflow.player.core.media.rendering.RenderingDiagnostics
import com.motionflow.player.core.media.rendering.RenderingMetrics
import com.motionflow.player.core.media.rendering.RenderingSurface
import com.motionflow.player.core.media.rendering.SurfaceType

/**
 * The playback surface.
 *
 * The screen renders state and forwards intents; the player itself lives in the media session.
 * The content is laid out as a fixed top bar, a video stage that takes the remaining height and a
 * control deck underneath. That split is what a fullscreen mode later rearranges: the stage grows
 * to fill the display and the chrome moves over the picture.
 */
@Composable
fun PlayerScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val refreshRate by viewModel.refreshRateState.collectAsStateWithLifecycle()
    val framePacing by viewModel.framePacingState.collectAsStateWithLifecycle()
    val rendering by viewModel.renderingDiagnostics.collectAsStateWithLifecycle()
    val processing by viewModel.processingDiagnostics.collectAsStateWithLifecycle()
    val performance by viewModel.performanceReport.collectAsStateWithLifecycle()
    val comparison by viewModel.performanceComparison.collectAsStateWithLifecycle()
    val archive by viewModel.performanceArchive.collectAsStateWithLifecycle()

    RequestMediaNotificationPermission()
    AttachRefreshRateEnvironment(viewModel)

    PlayerContent(
        uiState = uiState,
        player = player,
        refreshRate = refreshRate.toDiagnosticsModel(),
        framePacing = framePacing,
        rendering = rendering,
        processing = processing,
        performance = performance,
        comparison = comparison,
        archive = archive,
        onStartMeasurement = viewModel::startMeasurement,
        onStopMeasurement = viewModel::stopMeasurement,
        onCharacterizationText = viewModel::characterizationText,
        onSurfaceChange = { surfaceType ->
            if (surfaceType == null) {
                viewModel.onRenderingSurfaceReleased()
            } else {
                viewModel.onRenderingSurfaceCreated(surfaceType)
            }
        },
        onNavigateBack = onNavigateBack,
        onPlayPause = viewModel::playPause,
        onSeek = viewModel::seekTo,
        onCycleSpeed = viewModel::cyclePlaybackSpeed,
        onToggleRepeat = viewModel::toggleRepeatMode,
        onSetAutomaticRefreshRate = viewModel::setAutomaticRefreshRate,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

/**
 * Binds the refresh-rate engine to this window for as long as the screen is composed.
 *
 * The window, and with it any display preference, is recreated by every configuration change, so
 * the engine is re-bound each time — which is also what makes the current decision be requested
 * again on the new window. The platform objects are created here rather than in the view model so
 * that the decision logic never touches Android display APIs directly.
 */
@Composable
private fun AttachRefreshRateEnvironment(viewModel: PlayerViewModel) {
    val window = LocalActivity.current?.window
    val controller = remember(window) { window?.let(::AndroidRefreshRateController) }
    val capabilityProvider = remember(window) { window?.let(::AndroidDisplayCapabilityProvider) }

    DisposableEffect(controller, capabilityProvider) {
        if (controller != null && capabilityProvider != null) {
            viewModel.onRefreshRateEnvironmentAttached(controller, capabilityProvider)
        }
        onDispose { viewModel.onRefreshRateEnvironmentDetached() }
    }
}

/**
 * The refresh-rate facts the diagnostics line draws.
 *
 * The engine's own state carries a list of display modes, which Compose cannot prove stable, so the
 * screen projects the handful of values it renders. This also keeps the line skippable while the
 * playback position ticks.
 */
@Immutable
private data class RefreshRateDiagnosticsModel(
    val videoFps: Float?,
    val displayRefreshRateHz: Float?,
    val status: RefreshRateStatus,
    val reason: RefreshRateReason,
    val automaticEnabled: Boolean,
)

private fun RefreshRateState.toDiagnosticsModel(): RefreshRateDiagnosticsModel =
    RefreshRateDiagnosticsModel(
        videoFps = frameRate.fps,
        displayRefreshRateHz = displayRefreshRateHz,
        status = decision.status,
        reason = decision.reason,
        automaticEnabled = isAutomaticEnabled,
    )

@Composable
private fun PlayerContent(
    uiState: PlayerUiState,
    player: Player?,
    refreshRate: RefreshRateDiagnosticsModel,
    framePacing: FramePacingState,
    rendering: RenderingDiagnostics,
    processing: ProcessingDiagnostics,
    performance: PerformanceCommandResult,
    comparison: PerformanceComparison,
    archive: PerformanceArchive,
    onStartMeasurement: (PerformanceSessionLength) -> Unit,
    onStopMeasurement: () -> Unit,
    onCharacterizationText: () -> String,
    onSurfaceChange: (SurfaceType?) -> Unit,
    onNavigateBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    onToggleRepeat: () -> Unit,
    onSetAutomaticRefreshRate: (Boolean) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        PlayerTopBar(title = uiState.videoTitle, onNavigateBack = onNavigateBack)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MotionFlowVideoSurface),
        ) {
            VideoStage(
                player = player,
                onSurfaceChange = onSurfaceChange,
                modifier = Modifier.fillMaxSize(),
            )

            if (uiState.isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            uiState.error?.let { error ->
                PlaybackErrorNotice(
                    error = error,
                    onRetry = onRetry,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        PlaybackControls(
            isPlaying = uiState.isPlaying,
            positionMs = uiState.positionMs,
            durationMs = uiState.durationMs,
            playbackSpeed = uiState.playbackSpeed,
            isRepeatEnabled = uiState.isRepeatEnabled,
            seekEnabled = uiState.isSeekable,
            transportEnabled = uiState.error == null && uiState.hasMedia,
            onPlayPause = onPlayPause,
            onSeek = onSeek,
            onCycleSpeed = onCycleSpeed,
            onToggleRepeat = onToggleRepeat,
        )

        // Passed as plain values rather than as the MetadataResult itself: the interface type is not
        // provably stable, and this panel must be skipped while the position ticks.
        val metadataResult = uiState.metadata
        MetadataPanel(
            metadata = (metadataResult as? MetadataResult.Success)?.metadata,
            error = (metadataResult as? MetadataResult.Error)?.error,
            isLoading = metadataResult is MetadataResult.Loading,
            durationMs = uiState.displayDurationMs,
        )

        RefreshRateDiagnostics(
            model = refreshRate,
            onSetAutomatic = onSetAutomaticRefreshRate,
        )

        FramePacingDiagnostics(state = framePacing)

        RenderingSection(
            diagnostics = rendering,
            processing = processing,
            pipeline = performance.diagnostics.mode,
        )

        PerformanceSection(
            report = performance,
            comparison = comparison,
            archive = archive,
            onStart = onStartMeasurement,
            onStop = onStopMeasurement,
            characterizationText = onCharacterizationText,
        )
    }
}

/**
 * Hosts the Media3 `PlayerView`.
 *
 * `PlayerView` builds and owns the video surface — a `SurfaceView` by default — keeps the video's
 * aspect ratio, and handles the surface's creation and destruction itself, which is exactly the part
 * that leaks when it is done by hand. Its own controller is switched off, leaving the view as a pure
 * video stage.
 *
 * The view, and the surface inside it, belong to this screen and are never handed to the rendering
 * foundation or to any long-lived object. Two values cross the boundary: which kind of surface
 * Media3 built, and whether one exists at all. Nothing here can affect when a frame is presented.
 */
@Composable
private fun VideoStage(
    player: Player?,
    onSurfaceChange: (SurfaceType?) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                setUseController(false)
                onSurfaceChange(surfaceTypeOf(this))
            }
        },
        update = { view ->
            view.setPlayer(player)
        },
        onRelease = { view ->
            // Detach before the view goes away so the surface is never held by a dead player.
            view.setPlayer(null)
            onSurfaceChange(null)
        },
    )
}

/**
 * Reads which kind of surface `PlayerView` actually built, rather than assuming one.
 *
 * The default is a `SurfaceView`. A texture view would be reported as such if that ever changed, and
 * anything else is reported as unknown rather than being called a surface view.
 */
// getVideoSurfaceView is part of Media3's unstable surface: what it returns is supported, the exact
// signature is not frozen yet. The annotation is written fully qualified because Kotlin also has a
// `kotlin.OptIn`.
@androidx.annotation.OptIn(UnstableApi::class)
private fun surfaceTypeOf(playerView: PlayerView): SurfaceType = when (playerView.videoSurfaceView) {
    is SurfaceView -> SurfaceType.SURFACE_VIEW
    is TextureView -> SurfaceType.TEXTURE_VIEW
    else -> SurfaceType.UNKNOWN
}

@Composable
private fun PlayerTopBar(
    title: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MotionFlowTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.small, vertical = spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavigationBackButton(onClick = onNavigateBack)
        Spacer(modifier = Modifier.width(spacing.small))
        Text(
            text = title ?: stringResource(R.string.player_untitled),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    isRepeatEnabled: Boolean,
    seekEnabled: Boolean,
    transportEnabled: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    onToggleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MotionFlowTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Scrubber(
            positionMs = positionMs,
            durationMs = durationMs,
            enabled = seekEnabled,
            onSeek = onSeek,
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatPosition(positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatPosition(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            PlayPauseButton(
                isPlaying = isPlaying,
                enabled = transportEnabled,
                onClick = onPlayPause,
            )
            Spacer(modifier = Modifier.weight(1f))
            SecondaryControlButton(
                label = stringResource(R.string.player_speed_action),
                value = stringResource(
                    R.string.player_speed_format,
                    formatPlaybackSpeed(playbackSpeed),
                ),
                isActive = playbackSpeed != PlayerUiState.DEFAULT_PLAYBACK_SPEED,
                enabled = transportEnabled,
                onClick = onCycleSpeed,
            )
            SecondaryControlButton(
                label = stringResource(R.string.player_repeat_action),
                value = null,
                isActive = isRepeatEnabled,
                enabled = transportEnabled,
                onClick = onToggleRepeat,
            )
        }
    }
}

@Composable
private fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // While a drag is in flight the thumb follows the finger instead of the player, so the
    // readout does not fight the user; the seek is issued once, on release.
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    val upperBoundMs = durationMs.coerceAtLeast(1L)
    val displayedMs = (scrubPositionMs ?: positionMs).coerceIn(0L, upperBoundMs)

    Slider(
        value = displayedMs.toFloat(),
        onValueChange = { scrubPositionMs = it.toLong() },
        onValueChangeFinished = {
            scrubPositionMs?.let(onSeek)
            scrubPositionMs = null
        },
        valueRange = 0f..upperBoundMs.toFloat(),
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(
        if (isPlaying) R.string.player_action_pause else R.string.player_action_play,
    )
    val color = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Canvas(modifier = Modifier.size(TRANSPORT_ICON_SIZE)) {
            if (isPlaying) drawPauseMark(color) else drawPlayMark(color)
        }
    }
}

@Composable
private fun NavigationBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.player_action_back)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Canvas(modifier = Modifier.size(CHROME_ICON_SIZE)) {
            val stroke = size.width * STROKE_RATIO
            drawLine(
                color = color,
                start = Offset(size.width * 0.62f, size.height * 0.22f),
                end = Offset(size.width * 0.34f, size.height * 0.50f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(size.width * 0.34f, size.height * 0.50f),
                end = Offset(size.width * 0.62f, size.height * 0.78f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun SecondaryControlButton(
    label: String,
    value: String?,
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = label },
    ) {
        Text(
            text = value ?: label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun PlaybackErrorNotice(
    error: PlayerError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MotionFlowTheme.spacing
    Column(
        modifier = modifier.padding(spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        Text(
            text = stringResource(error.messageRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text(
                text = stringResource(R.string.player_action_retry),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * Opts into the notification permission on API 33+, where the media notification is suppressed
 * without it. Playback does not depend on the answer.
 */
@Composable
private fun RequestMediaNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> Log.i(NOTIFICATION_TAG, "Media notification permission=$granted") },
    )

    LaunchedEffect(Unit) {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val granted = ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(permission)
    }
}

private fun DrawScope.drawPlayMark(color: Color) {
    val path = Path().apply {
        moveTo(size.width * 0.24f, size.height * 0.14f)
        lineTo(size.width * 0.84f, size.height * 0.50f)
        lineTo(size.width * 0.24f, size.height * 0.86f)
        close()
    }
    drawPath(path = path, color = color)
}

private fun DrawScope.drawPauseMark(color: Color) {
    val barWidth = size.width * 0.26f
    val barHeight = size.height * 0.72f
    val cornerRadius = CornerRadius(barWidth * 0.35f)
    val barSize = Size(barWidth, barHeight)
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width * 0.20f, size.height * 0.14f),
        size = barSize,
        cornerRadius = cornerRadius,
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width * 0.54f, size.height * 0.14f),
        size = barSize,
        cornerRadius = cornerRadius,
    )
}

/**
 * The technical description of the loaded source.
 *
 * Collapsed it shows what a person looks for first — resolution, frame rate, codec — so the
 * essentials need no interaction; expanded it adds the fields that matter when something looks
 * wrong. A value that is not known is rendered as unknown rather than as a zero, so the panel never
 * implies a measurement that was never made.
 */
@Composable
private fun MetadataPanel(
    metadata: VideoMetadata?,
    error: MetadataError?,
    isLoading: Boolean,
    durationMs: Long?,
    modifier: Modifier = Modifier,
) {
    val spacing = MotionFlowTheme.spacing
    val unknown = stringResource(R.string.metadata_unknown)
    var expanded by remember { mutableStateOf(false) }

    val summary = metadata?.let { metadataSummary(it) }
    val details = metadata?.let { metadataDetails(it, durationMs, unknown) }.orEmpty()
    val status = when {
        metadata != null -> summary ?: unknown
        error != null -> stringResource(error.messageRes)
        isLoading -> stringResource(R.string.metadata_loading)
        else -> unknown
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (details.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = stringResource(
                            if (expanded) {
                                R.string.metadata_hide_action
                            } else {
                                R.string.metadata_details_action
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        if (expanded) {
            details.forEach { detail ->
                MetadataRow(label = detail.first, value = detail.second)
            }
        }
    }
}

@Composable
private fun metadataSummary(metadata: VideoMetadata): String? {
    val video = metadata.video ?: return null
    val frameRate = frameRateValue(video.frameRate)?.let { value ->
        stringResource(R.string.metadata_frame_rate_value, value)
    }
    val parts = listOfNotNull(
        resolutionValue(video.width, video.height),
        frameRate,
        videoCodecValue(video.codecMimeType, video.codecName),
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(SUMMARY_SEPARATOR)
}

@Composable
private fun metadataDetails(
    metadata: VideoMetadata,
    durationMs: Long?,
    unknown: String,
): List<Pair<String, String>> = buildList {
    metadata.video?.let { video ->
        add(metadataRow(R.string.metadata_label_resolution, resolutionValue(video.width, video.height) ?: unknown))
        add(metadataRow(R.string.metadata_label_frame_rate, frameRateDetail(video.frameRate, unknown)))
        add(
            metadataRow(
                R.string.metadata_label_video_codec,
                videoCodecValue(video.codecMimeType, video.codecName) ?: unknown,
            ),
        )
        video.decoderName?.let { decoder ->
            add(metadataRow(R.string.metadata_label_decoder, decoder))
        }
        bitrateQuantity(video.bitrateBitsPerSecond)?.let { bitrate ->
            add(metadataRow(R.string.metadata_label_bitrate, quantityText(bitrate)))
        }
        colorDetail(video.color)?.let { colour ->
            add(metadataRow(R.string.metadata_label_colour, colour))
        }
        video.rotationDegrees?.takeIf { it != 0 }?.let { rotation ->
            add(
                metadataRow(
                    R.string.metadata_label_rotation,
                    stringResource(R.string.metadata_rotation_value, rotation),
                ),
            )
        }
    }

    add(metadataRow(R.string.metadata_label_duration, durationValue(durationMs) ?: unknown))
    fileSizeQuantity(metadata.fileSizeBytes)?.let { size ->
        add(metadataRow(R.string.metadata_label_file_size, quantityText(size)))
    }
    add(metadataRow(R.string.metadata_label_audio, audioDetail(metadata.audio, unknown)))
}

@Composable
private fun metadataRow(@StringRes labelRes: Int, value: String): Pair<String, String> {
    val label = stringResource(labelRes)
    return label to value
}

@Composable
private fun frameRateDetail(frameRate: FrameRateInfo, unknown: String): String {
    val value = frameRateValue(frameRate) ?: return unknown
    val text = stringResource(R.string.metadata_frame_rate_value, value)
    return if (frameRate.isVariableFrameRate == true) {
        stringResource(R.string.metadata_frame_rate_variable, text)
    } else {
        text
    }
}

@Composable
private fun audioDetail(audio: AudioTrackMetadata?, unknown: String): String {
    if (audio == null) return stringResource(R.string.metadata_no_audio)

    val channels = audio.channelCount?.let { count ->
        stringResource(R.string.metadata_audio_channels_value, count)
    }
    val sampleRate = sampleRateQuantity(audio.sampleRateHz)?.let { quantityText(it) }
    val parts = listOfNotNull(
        audioCodecValue(audio.mimeType, audio.codecName),
        channels,
        sampleRate,
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(SUMMARY_SEPARATOR) ?: unknown
}

@Composable
private fun colorDetail(color: HdrInfo?): String? {
    if (color == null) return null

    val bitDepth = color.bitDepth?.let { depth ->
        stringResource(R.string.metadata_bit_depth_value, depth)
    }
    val parts = listOfNotNull(
        color.transfer?.let { colorTransferLabel(it) },
        color.colorSpace?.let { colorSpaceLabel(it) },
        bitDepth,
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(SUMMARY_SEPARATOR)
}

@Composable
private fun quantityText(quantity: MetadataQuantity): String =
    stringResource(quantity.unitRes, quantity.amount)

/**
 * What the display is doing, next to what the video contains.
 *
 * The two quantities are labelled apart deliberately: "Video 23.976 FPS" is the cadence of the
 * content and "Display 24 Hz" is how often the panel refreshes. A panel refreshing twice as often
 * shows each frame twice; it does not create frames, and nothing here implies that it does.
 */
@Composable
private fun RefreshRateDiagnostics(
    model: RefreshRateDiagnosticsModel,
    onSetAutomatic: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MotionFlowTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = refreshRateSummary(model),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SecondaryControlButton(
                label = stringResource(R.string.player_refresh_action),
                value = null,
                isActive = model.automaticEnabled,
                enabled = true,
                onClick = { onSetAutomatic(!model.automaticEnabled) },
            )
        }

        refreshRateReason(model)?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun refreshRateSummary(model: RefreshRateDiagnosticsModel): String {
    val videoFrameRate = model.videoFps?.let { fps ->
        stringResource(R.string.refresh_video_fps, formatFps(fps))
    }
    val displayRate = model.displayRefreshRateHz?.let { rate ->
        stringResource(R.string.refresh_display_hz, formatFps(rate))
    }
    val parts = listOfNotNull(videoFrameRate, displayRate, refreshStatusLabel(model.status))
    return parts.joinToString(SUMMARY_SEPARATOR)
}

@Composable
private fun refreshStatusLabel(status: RefreshRateStatus): String = stringResource(
    when (status) {
        RefreshRateStatus.MATCHED -> R.string.refresh_status_matched
        RefreshRateStatus.FALLBACK -> R.string.refresh_status_fallback
        RefreshRateStatus.UNCHANGED -> R.string.refresh_status_unchanged
        RefreshRateStatus.UNKNOWN -> R.string.refresh_status_unknown
        RefreshRateStatus.UNSUPPORTED -> R.string.refresh_status_unsupported
        RefreshRateStatus.MANUAL -> R.string.refresh_status_manual
    },
)

/** A reason is only shown when the outcome needs explaining; the good cases speak for themselves. */
@Composable
private fun refreshRateReason(model: RefreshRateDiagnosticsModel): String? = when (model.reason) {
    RefreshRateReason.BEST_EFFORT -> stringResource(R.string.refresh_reason_best_effort)
    RefreshRateReason.NO_SUITABLE_MODE -> stringResource(R.string.refresh_reason_no_suitable_mode)
    RefreshRateReason.CAPABILITIES_UNKNOWN -> stringResource(R.string.refresh_reason_capabilities_unknown)
    RefreshRateReason.PLATFORM_REJECTED -> stringResource(R.string.refresh_reason_platform_rejected)
    RefreshRateReason.VARIABLE_FRAME_RATE -> stringResource(R.string.refresh_reason_variable_frame_rate)
    RefreshRateReason.LOW_CONFIDENCE -> stringResource(R.string.refresh_reason_low_confidence)
    else -> null
}

/**
 * What is rendering the video, and whether a processing stage is attached.
 *
 * "Rendering" is the path frames take — Media3's own renderer, the only one this application has ever
 * built — and "processing" is a stage that would sit in front of the display. The two are kept apart
 * on purpose, and the processing half says only whether something is attached: no state here means
 * frames are being generated, copied more cheaply, or produced at a different rate.
 */
@Composable
private fun RenderingSection(
    diagnostics: RenderingDiagnostics,
    processing: ProcessingDiagnostics,
    pipeline: ProcessingPerformanceMode,
    modifier: Modifier = Modifier,
) {
    val spacing = MotionFlowTheme.spacing
    // A note explains what the pipeline is, or why the path is unavailable, inactive or failed.
    val note = processingNoteFor(pipeline, processing)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        Text(
            text = renderingSummary(diagnostics, processing, pipeline),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        note?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun renderingSummary(
    diagnostics: RenderingDiagnostics,
    processing: ProcessingDiagnostics,
    pipeline: ProcessingPerformanceMode,
): String {
    val firstFrame = diagnostics.metrics.firstFrameLatencyMs?.let { latency ->
        stringResource(R.string.rendering_first_frame, latency.toString())
    }
    val parts = listOfNotNull(
        stringResource(
            R.string.rendering_label,
            stringResource(R.string.rendering_mode_native_media3),
        ),
        stringResource(R.string.processing_label, processingValue(pipeline, processing)),
        firstFrame,
    )
    return parts.joinToString(SUMMARY_SEPARATOR)
}

/**
 * What the processing line says, decided by the pipeline the player was *built* with.
 *
 * The pipeline is the only thing that describes the running player, so it decides this row. A player
 * constructed with Media3's identity effect has a processing path in it, and calling that "inactive"
 * would contradict the pipeline the user selected — which is exactly how a working effect pipeline came
 * to look like a selection that never arrived.
 *
 * Under the native pipeline the Phase 6 states still apply unchanged, so nothing that subsystem reports
 * becomes unreachable, and "inactive" remains the honest word for a player with no processing path.
 */
private fun processingValue(
    pipeline: ProcessingPerformanceMode,
    processing: ProcessingDiagnostics,
): String = stringResource(
    when (pipeline) {
        ProcessingPerformanceMode.EFFECT_PIPELINE -> R.string.processing_value_active_identity
        ProcessingPerformanceMode.FAILED -> R.string.processing_value_failed
        ProcessingPerformanceMode.NATIVE -> when (processing.mode) {
            ProcessingMode.NATIVE -> R.string.processing_value_native
            ProcessingMode.PROCESSING_INACTIVE -> R.string.processing_value_inactive
            ProcessingMode.PROCESSING_UNAVAILABLE -> R.string.processing_value_unavailable
            ProcessingMode.PROCESSING_ACTIVE -> R.string.processing_value_active
            ProcessingMode.PROCESSING_FAILED -> R.string.processing_value_failed
        }
    },
)

/**
 * Why the processing line says what it says.
 *
 * For a player built with the effect pipeline the explanation is the identity effect itself: it is
 * attached to the renderer before playback starts, it is Media3's, and it changes nothing about the
 * picture and generates no frame. For the native pipeline the Phase 6 reasons apply as they always did.
 */
@Composable
private fun processingNoteFor(
    pipeline: ProcessingPerformanceMode,
    processing: ProcessingDiagnostics,
): String? = when (pipeline) {
    ProcessingPerformanceMode.EFFECT_PIPELINE -> stringResource(R.string.processing_note_identity_effect)
    ProcessingPerformanceMode.FAILED -> stringResource(R.string.processing_note_pipeline_failed)
    ProcessingPerformanceMode.NATIVE ->
        if (processing.mode == ProcessingMode.NATIVE) null else processingNote(processing)
}

/**
 * What was measured, on which pipeline, and what could not be measured at all.
 *
 * A development surface, and an honest one: every number here was measured during a session the user
 * started, every gap says "not measured" rather than showing a zero, and the metrics no Android version
 * publishes are named rather than omitted. There is no speed, rate or verdict anywhere — a rendered-frame
 * count divided by a duration is not a frame rate, and the panel says only what was counted.
 *
 * The source's rate and the display's rate are deliberately *not* repeated here: the refresh, pacing and
 * processing rows above already report them, and a second copy could disagree with the first.
 */
@Composable
private fun PerformanceSection(
    report: PerformanceCommandResult,
    comparison: PerformanceComparison,
    archive: PerformanceArchive,
    onStart: (PerformanceSessionLength) -> Unit,
    onStop: () -> Unit,
    characterizationText: () -> String,
    modifier: Modifier = Modifier,
) {
    val spacing = MotionFlowTheme.spacing
    val diagnostics = report.diagnostics
    val snapshot = diagnostics.snapshot
    val session = diagnostics.session

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        Text(
            text = stringResource(
                R.string.performance_label,
                if (diagnostics.isMeasuring) {
                    stringResource(
                        R.string.performance_running,
                        session?.length?.let { "${it.seconds} s" }.orEmpty(),
                    )
                } else {
                    stringResource(R.string.performance_idle)
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(
                R.string.performance_pipeline,
                stringResource(processingModeValue(diagnostics.mode)),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        measuredLine(R.string.performance_first_frame, snapshot.firstFrameLatencyMs?.let { "$it ms" })
        measuredLine(R.string.performance_rendered, snapshot.renderedFrames?.toString())
        measuredLine(R.string.performance_dropped, snapshot.droppedFrames?.toString())
        measuredLine(
            R.string.performance_processing_offset,
            snapshot.averageFrameProcessingOffsetMs?.let { "${"%.2f".format(it)} ms" },
        )
        measuredLine(
            R.string.performance_session,
            session?.measuredDurationMs?.let { "${it / PerformanceSessionLength.MILLIS_PER_SECOND} s" },
        )
        measuredLine(
            R.string.performance_decoder_init,
            snapshot.decoderInitializationMs?.let { "$it ms" },
        )
        measuredLine(R.string.performance_cpu, snapshot.cpuTimeMs?.let { "$it ms" })
        measuredLine(R.string.performance_memory, snapshot.processPssKb?.let { "$it KB" })
        measuredLine(R.string.performance_thermal, snapshot.thermalStatusPeak?.toString())

        if (diagnostics.support.unsupported.isNotEmpty()) {
            // The labels are resolved first: `joinToString` is not an inline function, so a composable
            // call cannot live inside it. `map` is inline, which is why the reading happens here.
            val unsupportedLabels = diagnostics.support.unsupported.map { stringResource(metricLabel(it)) }
            Text(
                text = stringResource(
                    R.string.performance_unavailable_platform,
                    unsupportedLabels.joinToString(UNLISTED_METRIC_SEPARATOR),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        measurementNote(report, diagnostics.mode)

        // What the display did for the most recent run, which is the only place a hardware test can
        // learn that a refresh-rate request was ignored rather than refused.
        archive.runs.lastOrNull()?.condition?.display?.let { display ->
            Text(
                text = stringResource(
                    R.string.performance_display_outcome,
                    display.requestedRefreshRateHz?.toString()
                        ?: stringResource(R.string.performance_not_requested),
                    display.appliedRefreshRateHz?.toString()
                        ?: stringResource(R.string.performance_not_measured),
                    displayOutcomeValue(display.outcome),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (archive.runs.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.performance_runs_recorded,
                    archive.seriesFor(ProcessingPerformanceMode.NATIVE).sumOf { it.usableRuns.size },
                    archive.seriesFor(ProcessingPerformanceMode.EFFECT_PIPELINE).sumOf { it.usableRuns.size },
                    archive.runs.count { !it.isUsable },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PerformanceComparisonSection(comparison)

        ExportCharacterization(onClick = characterizationText)

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
            if (diagnostics.isMeasuring) {
                Button(onClick = onStop) {
                    Text(
                        text = stringResource(R.string.performance_stop_action),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else {
                MEASUREMENT_LENGTHS.forEach { length ->
                    Button(onClick = { onStart(length) }) {
                        Text(
                            text = stringResource(R.string.performance_start_action, length.seconds),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shares the characterization as text, when a person asks for it.
 *
 * A share sheet is the whole of the export: the text is handed to whichever application the user
 * chooses, and MotionFlow has no network permission, no analytics dependency and no uploader. Nothing is
 * sent anywhere by this application — a person taking their own measurements somewhere is a different
 * act, and it is theirs to take.
 */
@Composable
private fun ExportCharacterization(onClick: () -> String) {
    val context = LocalContext.current
    val title = stringResource(R.string.performance_export_title)

    Button(
        onClick = {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, onClick())
            }
            context.startActivity(Intent.createChooser(intent, title))
        },
    ) {
        Text(
            text = stringResource(R.string.performance_export_action),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * How a refresh-rate request ended, in the terms the platform actually supports.
 *
 * "Not applied" is deliberately not "refused": Android reports an error when a request fails and says
 * nothing when a request is ignored, so a rate that differs with no error is recorded as not applied.
 */
@Composable
private fun displayOutcomeValue(outcome: DisplayRequestOutcome): String = stringResource(
    when (outcome) {
        DisplayRequestOutcome.NOT_REQUESTED -> R.string.performance_display_not_requested
        DisplayRequestOutcome.HONOURED -> R.string.performance_display_honoured
        DisplayRequestOutcome.REFUSED -> R.string.performance_display_refused
        DisplayRequestOutcome.NOT_APPLIED -> R.string.performance_display_not_applied
        DisplayRequestOutcome.UNKNOWN -> R.string.performance_display_unknown
    },
)

/** One measurement row, or "not measured" — never a zero standing in for a reading that does not exist. */
@Composable
private fun measuredLine(label: Int, value: String?) {
    Text(
        text = stringResource(label, value ?: stringResource(R.string.performance_not_measured)),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Why there is no measurement, when there is not one.
 *
 * A refusal, an unreachable session and a failed pipeline are three different findings, and the panel
 * says which one applies rather than leaving a reader to guess from an empty field.
 */
@Composable
private fun measurementNote(report: PerformanceCommandResult, mode: ProcessingPerformanceMode): String? = when {
    report.unreachable -> stringResource(R.string.performance_note_unreachable)
    mode == ProcessingPerformanceMode.FAILED -> stringResource(R.string.performance_note_failed)
    report.refusal != null -> stringResource(R.string.performance_note_refused)
    report.diagnostics.session == null -> stringResource(R.string.performance_note_no_media)
    else -> null
}

/**
 * The two pipelines side by side: differences only, with no winner.
 *
 * A delta appears only when both baselines measured it, and the format says which direction it points,
 * because "the effect pipeline presented its first frame 75 ms later" is a finding while "effect
 * pipeline: worse" would be an opinion this application has no basis for.
 */
@Composable
private fun PerformanceComparisonSection(comparison: PerformanceComparison) {
    val spacing = MotionFlowTheme.spacing

    Text(
        text = stringResource(R.string.performance_comparison_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (!comparison.hasBothBaselines) {
        Text(
            text = stringResource(R.string.performance_comparison_missing),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.extraSmall)) {
        comparison.firstFrameLatencyDeltaMs?.let {
            measuredLine(R.string.performance_comparison_first_frame, "${it} ms")
        }
        comparison.renderedFramesDelta?.let {
            measuredLine(R.string.performance_comparison_rendered, it.toString())
        }
        comparison.droppedFramesDelta?.let {
            measuredLine(R.string.performance_comparison_dropped, it.toString())
        }
        comparison.cpuTimeDeltaMs?.let {
            measuredLine(R.string.performance_comparison_cpu, "${it} ms")
        }
        comparison.processPssDeltaKb?.let {
            measuredLine(R.string.performance_comparison_pss, "${it} KB")
        }
    }
}

private fun processingModeValue(mode: ProcessingPerformanceMode): Int = when (mode) {
    ProcessingPerformanceMode.NATIVE -> R.string.processing_mode_native
    ProcessingPerformanceMode.EFFECT_PIPELINE -> R.string.processing_mode_effect_pipeline
    ProcessingPerformanceMode.FAILED -> R.string.processing_mode_failed
}

private fun metricLabel(metric: PerformanceMetric): Int = when (metric) {
    PerformanceMetric.GPU_UTILISATION -> R.string.performance_metric_gpu
    PerformanceMetric.THERMAL_HEADROOM -> R.string.performance_metric_thermal_headroom
    PerformanceMetric.BATTERY_DRAIN -> R.string.performance_metric_battery
    // Every other metric is measurable on the platforms this application supports, so the list above
    // is the whole of it; a label for the rest would be unreachable wording.
    else -> R.string.performance_not_measured
}

/** The controlled windows. A measurement that can be any length is hard to compare with the next one. */
private val MEASUREMENT_LENGTHS = listOf(
    PerformanceSessionLength.TEN,
    PerformanceSessionLength.THIRTY,
    PerformanceSessionLength.SIXTY,
)

private const val UNLISTED_METRIC_SEPARATOR = ", "

/**
 * The reason, when there is one to read.
 *
 * A note appears for a refused request and for an unavailable path, and never for the default state:
 * "native" means nothing has been reported, and explaining a state the report does not claim would be
 * saying more than was established. Every reason the coordinator can pair with a mode is answerable.
 */
@Composable
private fun processingNote(processing: ProcessingDiagnostics): String? = when (processing.reason) {
    ProcessingReason.NO_SURFACE -> stringResource(R.string.processing_note_no_surface)

    ProcessingReason.NO_STAGE_IMPLEMENTED -> stringResource(R.string.processing_note_no_stage)

    ProcessingReason.EFFECTS_MODULE_ABSENT ->
        stringResource(R.string.processing_note_effects_module_absent)

    ProcessingReason.REQUEST_REFUSED -> stringResource(R.string.processing_note_refused)

    ProcessingReason.COMMAND_UNAVAILABLE ->
        stringResource(R.string.processing_note_command_unavailable)

    ProcessingReason.TRANSPORT_FAILED -> stringResource(R.string.processing_note_transport_failed)

    null -> null
}

/**
 * How the display's cadence relates to the video's, and what was done about it.
 *
 * "Cadence" names a relationship between two rates — the video's and the display's — and "pacing"
 * says whether anything acted on it. Nothing in the application can, so the second line reads
 * "diagnostic only" and the note explains what an uneven cadence means. Nothing here suggests a
 * display is producing frames the video does not contain.
 */
@Composable
private fun FramePacingDiagnostics(state: FramePacingState, modifier: Modifier = Modifier) {
    val spacing = MotionFlowTheme.spacing
    val note = pacingNote(state)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
    ) {
        Text(
            text = listOf(
                stringResource(R.string.pacing_cadence_label, cadenceValue(state)),
                stringResource(R.string.pacing_mechanism_label, pacingMechanismValue(state)),
            ).joinToString(SUMMARY_SEPARATOR),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        note?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun cadenceValue(state: FramePacingState): String {
    val decision = state.decision
    val ratio = state.diagnostics?.ratio

    val value = when {
        decision.mode == FramePacingMode.NATIVE_CADENCE ->
            stringResource(R.string.pacing_cadence_native)

        decision.mode == FramePacingMode.INTEGER_MULTIPLE -> ratio?.let {
            stringResource(R.string.pacing_cadence_integer, it.refreshesPerFrame)
        } ?: stringResource(R.string.pacing_cadence_unknown)

        decision.mode == FramePacingMode.CADENCE_MISMATCH && ratio != null && ratio.patternLabel.isNotEmpty() ->
            stringResource(R.string.pacing_cadence_pattern, ratio.patternLabel)

        decision.mode == FramePacingMode.CADENCE_MISMATCH ->
            stringResource(R.string.pacing_cadence_unresolved)

        decision.mode == FramePacingMode.UNSUPPORTED ->
            stringResource(R.string.pacing_cadence_display_too_slow)

        else -> stringResource(R.string.pacing_cadence_unknown)
    }

    // A rate read from a container header can still be classified, but the diagnosis is worth less.
    return if (decision.isReliable) {
        value
    } else {
        stringResource(R.string.pacing_cadence_uncertain, value)
    }
}

@Composable
private fun pacingMechanismValue(state: FramePacingState): String = stringResource(
    when (state.decision.mechanism) {
        FramePacingMechanism.NONE -> R.string.pacing_mechanism_diagnostic_only
    },
)

/** A note only appears when the cadence needs explaining. */
@Composable
private fun pacingNote(state: FramePacingState): String? {
    val decision = state.decision
    val requestRefused = state.diagnostics?.display?.requestRefused == true

    return when {
        requestRefused && decision.isMismatched ->
            stringResource(R.string.pacing_note_request_refused)

        decision.reason == FramePacingReason.UNRESOLVED_PATTERN ->
            stringResource(R.string.pacing_note_unresolved)

        decision.reason == FramePacingReason.SHORT_REPEATING_PATTERN ||
            decision.reason == FramePacingReason.LONG_REPEATING_PATTERN ->
            stringResource(R.string.pacing_note_uneven_pattern)

        decision.reason == FramePacingReason.DISPLAY_TOO_SLOW ->
            stringResource(R.string.pacing_note_display_too_slow)

        decision.reason == FramePacingReason.VARIABLE_FRAME_RATE ->
            stringResource(R.string.pacing_note_variable_frame_rate)

        else -> null
    }
}

@Composable
private fun MetadataRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(LABEL_WEIGHT),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(VALUE_WEIGHT),
        )
    }
}

private val TRANSPORT_ICON_SIZE = 32.dp
private val CHROME_ICON_SIZE = 24.dp
private const val STROKE_RATIO = 0.10f
private const val NOTIFICATION_TAG = "MotionFlowPlayback"

/** Bullet separator between facts on one line. A typographic mark, so it is not translated. */
private const val SUMMARY_SEPARATOR = " · "

private const val LABEL_WEIGHT = 0.42f
private const val VALUE_WEIGHT = 0.58f

/**
 * Builds a rendering state for previews: a surface is bound and Media3 is rendering, with no
 * processing stage — which is the steady state of the application.
 */
private fun renderingPreview(firstFrameLatencyMs: Long? = null): RenderingDiagnostics =
    RenderingDiagnostics(
        surface = RenderingSurface(type = SurfaceType.SURFACE_VIEW, bound = true),
        metrics = RenderingMetrics(
            firstFrameLatencyMs = firstFrameLatencyMs,
            surfaceAttachCount = 1,
        ),
    )

/**
 * Builds a measurement report for previews: a completed session on the native pipeline, with the
 * numbers a real one would carry. Nothing here is a rate, a speed or a verdict — only counts and
 * durations, which is all the panel can show.
 */
private fun performancePreview(): PerformanceCommandResult = PerformanceCommandResult(
    diagnostics = PerformanceDiagnostics(
        mode = ProcessingPerformanceMode.NATIVE,
        session = null,
        snapshot = FramePerformanceSnapshot(
            renderedFrames = 1_438,
            droppedFrames = 2,
            firstFrameLatencyMs = 412L,
            decoderInitializationMs = 120L,
            playbackPositionMs = 30_000L,
            measurementDurationMs = 30_000L,
            videoWidth = 1920,
            videoHeight = 1080,
            cpuTimeMs = 4_120L,
            processPssKb = 184_320L,
        ),
        support = PerformanceMeasurementSupport.forApiLevel(36),
        device = DeviceCharacteristics(apiLevel = 36, primaryAbi = "arm64-v8a", memoryClassMb = 512),
    ),
)

/** Builds a comparison for previews: differences with no winner, as the model insists. */
private fun comparisonPreview(): PerformanceComparison = PerformanceComparison(
    native = FramePerformanceSnapshot(firstFrameLatencyMs = 412L, renderedFrames = 1_438, droppedFrames = 2),
    effectPipeline = FramePerformanceSnapshot(firstFrameLatencyMs = 487L, renderedFrames = 1_436, droppedFrames = 7),
)

/**
 * Builds a processing state for previews: a surface is bound, a stage could be attached, and none is
 * — the steady state of the application. The active state is never previewed, because nothing in this
 * version can reach it.
 */
private fun processingPreview(
    mode: ProcessingMode = ProcessingMode.PROCESSING_INACTIVE,
    reason: ProcessingReason? = ProcessingReason.NO_STAGE_IMPLEMENTED,
    requestCount: Int = 0,
): ProcessingDiagnostics = ProcessingDiagnostics(
    mode = mode,
    reason = reason,
    capabilities = ProcessingCapabilities(surfaceBound = true, canHostEffect = true),
    effectAttached = false,
    lastAttachmentSucceeded = null,
    attachCount = 0,
    detachCount = 0,
    requestCount = requestCount,
    videoFps = 23.976f,
    displayRefreshRateHz = 24f,
)

/**
 * Builds a pacing state for previews by running the real analysis, so a preview cannot show a
 * cadence the engine would never produce.
 */
private fun pacingPreview(videoFps: Float?, displayHz: Float?): FramePacingState {
    val diagnostics = FramePacingPolicy.analyse(
        video = VideoCadence(fps = videoFps, confidence = MetadataConfidence.HIGH),
        display = DisplayCadence(refreshRateHz = displayHz),
    )
    return FramePacingState(
        diagnostics = diagnostics,
        decision = FramePacingDecision(
            mode = diagnostics.mode,
            reason = diagnostics.reason,
            isReliable = true,
        ),
    )
}

@Preview(name = "Player", showBackground = true, backgroundColor = 0xFF08090C)
@Composable
private fun PlayerContentPreview() {
    MotionFlowTheme {
        PlayerContent(
            uiState = PlayerUiState(
                playerState = PlayerState.READY,
                isPlaying = true,
                durationMs = 634_000L,
                positionMs = 128_000L,
                playbackSpeed = 1.25f,
                videoTitle = "Sample clip.mp4",
                metadata = MetadataResult.Success(
                    VideoMetadata(
                        sourceUri = "content://sample/clip.mp4",
                        title = "Sample clip.mp4",
                        durationMs = 634_000L,
                        video = VideoTrackMetadata(
                            width = 1920,
                            height = 1080,
                            frameRate = FrameRateInfo.measured(
                                fps = 23.976f,
                                isVariableFrameRate = null,
                                confidence = MetadataConfidence.HIGH,
                            ),
                            codecMimeType = "video/avc",
                        ),
                    ),
                ),
            ),
            player = null,
            refreshRate = RefreshRateDiagnosticsModel(
                videoFps = 23.976f,
                displayRefreshRateHz = 24f,
                status = RefreshRateStatus.MATCHED,
                reason = RefreshRateReason.EXACT_MODE,
                automaticEnabled = true,
            ),
            framePacing = pacingPreview(videoFps = 23.976f, displayHz = 24f),
            rendering = renderingPreview(firstFrameLatencyMs = 412L),
            processing = processingPreview(),
            performance = performancePreview(),
            comparison = comparisonPreview(),
            archive = PerformanceArchive.Empty,
            onSurfaceChange = {},
            onNavigateBack = {},
            onPlayPause = {},
            onSeek = {},
            onStartMeasurement = {},
            onStopMeasurement = {},
            onCharacterizationText = { "" },
            onCycleSpeed = {},
            onToggleRepeat = {},
            onSetAutomaticRefreshRate = {},
            onRetry = {},
        )
    }
}

@Preview(name = "Player error", showBackground = true, backgroundColor = 0xFF08090C)
@Composable
private fun PlayerErrorPreview() {
    MotionFlowTheme {
        PlayerContent(
            uiState = PlayerUiState(
                error = PlayerError(
                    kind = PlayerErrorKind.UNSUPPORTED_FORMAT,
                    technicalDetail = "preview",
                ),
            ),
            player = null,
            refreshRate = RefreshRateDiagnosticsModel(
                videoFps = 24f,
                displayRefreshRateHz = 60f,
                status = RefreshRateStatus.FALLBACK,
                reason = RefreshRateReason.BEST_EFFORT,
                automaticEnabled = true,
            ),
            framePacing = pacingPreview(videoFps = 24f, displayHz = 60f),
            rendering = renderingPreview(),
            processing = processingPreview(
                mode = ProcessingMode.PROCESSING_UNAVAILABLE,
                reason = ProcessingReason.NO_SURFACE,
            ),
            performance = PerformanceCommandResult(
                diagnostics = PerformanceDiagnostics.Idle,
            ),
            comparison = PerformanceComparison(),
            archive = PerformanceArchive.Empty,
            onSurfaceChange = {},
            onNavigateBack = {},
            onPlayPause = {},
            onSeek = {},
            onStartMeasurement = {},
            onStopMeasurement = {},
            onCharacterizationText = { "" },
            onCycleSpeed = {},
            onToggleRepeat = {},
            onSetAutomaticRefreshRate = {},
            onRetry = {},
        )
    }
}
