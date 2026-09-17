package com.motionflow.player.feature.home

import androidx.compose.runtime.Immutable

/**
 * State rendered by the home destination.
 */
@Immutable
data class HomeUiState(
    val versionName: String,
    val versionCode: Int,
)
