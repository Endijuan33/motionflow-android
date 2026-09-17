package com.motionflow.player.feature.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.motionflow.player.R
import com.motionflow.player.core.designsystem.theme.MotionFlowTheme

/**
 * Placeholder for the settings destination.
 *
 * The destination exists so the navigation graph is real from the start; the preferences it will
 * own (playback, display and interpolation) arrive with the phases that implement them.
 */
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = MotionFlowTheme.spacing.large),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(MotionFlowTheme.spacing.small))
        Text(
            text = stringResource(R.string.settings_placeholder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MotionFlowTheme.spacing.large))
        TextButton(onClick = onNavigateBack) {
            Text(
                text = stringResource(R.string.settings_back_action),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Preview(name = "Settings", showBackground = true, backgroundColor = 0xFF08090C)
@Composable
private fun SettingsScreenPreview() {
    MotionFlowTheme {
        SettingsScreen(onNavigateBack = {})
    }
}
