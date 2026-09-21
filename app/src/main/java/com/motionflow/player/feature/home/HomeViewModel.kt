package com.motionflow.player.feature.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motionflow.player.BuildConfig
import com.motionflow.player.MotionFlowApplication
import com.motionflow.player.core.media.performance.ProcessingPerformanceMode
import com.motionflow.player.core.media.player.PlaybackConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Holds the home destination's state, and owns one decision the rest of the application depends on.
 *
 * The playback pipeline is chosen here rather than on the player screen for a reason that comes from
 * Media3: the effects pipeline has to exist before `prepare()`, so the choice has to be made *before* a
 * video is opened. Home is the screen a user is on at that moment, and putting the control here makes
 * the required order the natural one instead of a rule a user has to remember.
 *
 * It holds no player, no session and no controller: it records a request, and the media session service
 * builds the engine from it.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val configurationStore = (application as MotionFlowApplication).playbackConfigurationStore

    private val _uiState = MutableStateFlow(
        HomeUiState(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        ),
    )

    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** The pipeline the next engine will be built for. */
    val processingMode: StateFlow<ProcessingPerformanceMode> = configurationStore.requested
        .map { it.processingMode }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = configurationStore.requested.value.processingMode,
        )

    /**
     * Records the pipeline to measure.
     *
     * Nothing is switched here: the service reacts to the change by releasing its engine so the next one
     * is built for the new pipeline, which is why a switch stops playback. The screen says so before it
     * is pressed rather than after.
     */
    fun selectProcessingMode(mode: ProcessingPerformanceMode) {
        if (!mode.isMeasurable) return
        configurationStore.request(PlaybackConfiguration(mode))
    }
}
