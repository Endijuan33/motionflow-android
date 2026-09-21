package com.motionflow.player.feature.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.motionflow.player.R
import com.motionflow.player.core.designsystem.theme.MotionFlowTheme
import com.motionflow.player.core.media.performance.ProcessingPerformanceMode

/**
 * Home destination.
 *
 * For this phase it is a branded entry point whose one real job is starting the local media flow.
 * It is replaced by the media library surface later.
 */
@Composable
fun HomeScreen(
    onOpenVideo: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val processingMode by viewModel.processingMode.collectAsStateWithLifecycle()

    // The system document picker is the only way media enters MotionFlow. It returns a document
    // URI with a read grant, never a filesystem path, so nothing downstream may assume a path.
    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { onOpenVideo(it.toString()) } },
    )

    HomeContent(
        uiState = uiState,
        processingMode = processingMode,
        onProcessingModeSelected = viewModel::selectProcessingMode,
        onOpenVideoClick = { openDocument.launch(VIDEO_MIME_TYPES) },
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    processingMode: ProcessingPerformanceMode,
    onProcessingModeSelected: (ProcessingPerformanceMode) -> Unit,
    onOpenVideoClick: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = MotionFlowTheme.spacing.large),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(MotionFlowTheme.spacing.small))
        Text(
            text = stringResource(R.string.home_tagline),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(MotionFlowTheme.spacing.extraLarge))
        Text(
            text = stringResource(R.string.home_status),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MotionFlowTheme.spacing.extraSmall))
        Text(
            text = stringResource(
                R.string.home_version_format,
                uiState.versionName,
                uiState.versionCode,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MotionFlowTheme.spacing.large))

        ProcessingModeSelector(
            selected = processingMode,
            onSelected = onProcessingModeSelected,
        )

        Spacer(modifier = Modifier.height(MotionFlowTheme.spacing.large))
        Button(onClick = onOpenVideoClick) {
            Text(
                text = stringResource(R.string.home_open_video_action),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(modifier = Modifier.height(MotionFlowTheme.spacing.small))
        TextButton(onClick = onOpenSettings) {
            Text(
                text = stringResource(R.string.home_settings_action),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * Chooses which pipeline the next video is played through.
 *
 * It is here, before a video is opened, because Media3 requires the effects pipeline to exist before
 * `prepare()`. The note underneath is not a warning about a bug: switching pipelines releases the engine,
 * so a switch stops playback, and saying so beforehand is the difference between a documented limitation
 * and a surprise.
 */
@Composable
private fun ProcessingModeSelector(
    selected: ProcessingPerformanceMode,
    onSelected: (ProcessingPerformanceMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.processing_mode_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MotionFlowTheme.spacing.extraSmall))
        Row(horizontalArrangement = Arrangement.spacedBy(MotionFlowTheme.spacing.small)) {
            MEASURABLE_MODES.forEach { mode ->
                val isSelected = mode == selected
                Button(
                    onClick = { onSelected(mode) },
                    colors = if (isSelected) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                ) {
                    Text(
                        text = stringResource(processingModeLabel(mode)),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(MotionFlowTheme.spacing.extraSmall))
        Text(
            text = stringResource(R.string.processing_mode_note),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The two pipelines this phase compares. A failed measurement is not a mode a user can choose. */
private val MEASURABLE_MODES = listOf(
    ProcessingPerformanceMode.NATIVE,
    ProcessingPerformanceMode.EFFECT_PIPELINE,
)

private fun processingModeLabel(mode: ProcessingPerformanceMode): Int = when (mode) {
    ProcessingPerformanceMode.NATIVE -> R.string.processing_mode_native
    ProcessingPerformanceMode.EFFECT_PIPELINE -> R.string.processing_mode_effect_pipeline
    ProcessingPerformanceMode.FAILED -> R.string.processing_mode_failed
}

/** Common container types, offered explicitly so the picker filters to playable documents. */
private val VIDEO_MIME_TYPES = arrayOf(
    "video/*",
    "video/mp4",
    "video/x-matroska",
    "video/webm",
)

@Preview(name = "Home", showBackground = true, backgroundColor = 0xFF08090C)
@Composable
private fun HomeContentPreview() {
    MotionFlowTheme {
        HomeContent(
            uiState = HomeUiState(versionName = "0.1.0", versionCode = 1),
            processingMode = ProcessingPerformanceMode.NATIVE,
            onProcessingModeSelected = {},
            onOpenVideoClick = {},
            onOpenSettings = {},
        )
    }
}
