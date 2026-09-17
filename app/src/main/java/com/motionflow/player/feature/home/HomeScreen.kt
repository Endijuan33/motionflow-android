package com.motionflow.player.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

/**
 * Branded placeholder for the home destination.
 *
 * It is replaced by the media library and player surfaces in the playback phases.
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeContent(
        uiState = uiState,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
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
        TextButton(onClick = onOpenSettings) {
            Text(
                text = stringResource(R.string.home_settings_action),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Preview(name = "Home", showBackground = true, backgroundColor = 0xFF08090C)
@Composable
private fun HomeContentPreview() {
    MotionFlowTheme {
        HomeContent(
            uiState = HomeUiState(versionName = "0.1.0", versionCode = 1),
            onOpenSettings = {},
        )
    }
}
