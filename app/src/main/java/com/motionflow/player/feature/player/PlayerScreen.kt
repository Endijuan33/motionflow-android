package com.motionflow.player.feature.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
import com.motionflow.player.core.media.player.PlayerError
import com.motionflow.player.core.media.player.PlayerErrorKind
import com.motionflow.player.core.media.player.PlayerState
import com.motionflow.player.core.media.refresh.RefreshRateReason
import com.motionflow.player.core.media.refresh.RefreshRateState
import com.motionflow.player.core.media.refresh.RefreshRateStatus
import com.motionflow.player.core.media.refresh.android.AndroidDisplayCapabilityProvider
import com.motionflow.player.core.media.refresh.android.AndroidRefreshRateController

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

    RequestMediaNotificationPermission()
    AttachRefreshRateEnvironment(viewModel)

    PlayerContent(
        uiState = uiState,
        player = player,
        refreshRate = refreshRate.toDiagnosticsModel(),
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
            VideoStage(player = player, modifier = Modifier.fillMaxSize())

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
    }
}

/**
 * Hosts the Media3 `PlayerView`.
 *
 * `PlayerView` builds and owns a `SurfaceView`, keeps the video's aspect ratio and handles the
 * surface's creation and destruction itself, which is exactly the part that leaks when it is done
 * by hand. Its own controller is switched off — MotionFlow draws its own controls — leaving the
 * view as a pure video stage. Moving to a custom rendering pipeline means replacing this
 * composable and nothing else.
 */
@Composable
private fun VideoStage(player: Player?, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                setUseController(false)
            }
        },
        update = { view ->
            view.setPlayer(player)
        },
        onRelease = { view ->
            // Detach before the view goes away so the surface is never held by a dead player.
            view.setPlayer(null)
        },
    )
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
            onNavigateBack = {},
            onPlayPause = {},
            onSeek = {},
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
            onNavigateBack = {},
            onPlayPause = {},
            onSeek = {},
            onCycleSpeed = {},
            onToggleRepeat = {},
            onSetAutomaticRefreshRate = {},
            onRetry = {},
        )
    }
}
