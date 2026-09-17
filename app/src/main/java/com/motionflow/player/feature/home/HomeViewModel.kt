package com.motionflow.player.feature.home

import androidx.lifecycle.ViewModel
import com.motionflow.player.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the home destination's state.
 *
 * The state is exposed as a [StateFlow] so that the screen keeps observing a single stream once the
 * playback phases start publishing real work (recent media, refresh-rate capability, player
 * preparation) into it.
 */
class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(
        HomeUiState(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        ),
    )

    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
}
