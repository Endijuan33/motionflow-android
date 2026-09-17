package com.motionflow.player.feature.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.motionflow.player.core.media.player.PlayerError
import com.motionflow.player.core.media.player.PlayerErrorKind
import com.motionflow.player.core.media.player.PlayerState
import com.motionflow.player.core.media.session.MotionFlowMediaSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives playback through the application's media session.
 *
 * The screen holds no `ExoPlayer`: it connects a [MediaController] to
 * [MotionFlowMediaSessionService], which owns the single engine for the process. That is what makes
 * rotation and screen navigation safe — the view model and its controller are torn down and
 * rebuilt, while the session and its player carry on — and it is what keeps the player out of the
 * composition, where it would be recreated and leaked.
 *
 * Every control here is a request to that player. Nothing about decoding, rendering or buffering is
 * decided in this class.
 */
class PlayerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

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

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) = syncState()

        override fun onIsPlayingChanged(isPlaying: Boolean) = syncState()

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = syncState()

        override fun onRepeatModeChanged(repeatMode: Int) = syncState()

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = syncState()

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

    /** Clears the failure and asks the player to try the current item again. */
    fun retry() {
        val controller = controller
        if (controller == null) {
            if (controllerFuture == null) connectToSession()
            return
        }
        _uiState.update { it.copy(error = null) }
        controller.prepare()
    }

    override fun onCleared() {
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

        val future = MediaController.Builder(context, sessionToken).buildAsync()
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
        loadSource(controller)
        syncState()
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
                )
            }
            return
        }

        viewModelScope.launch {
            // The display name comes from the document provider, so it needs a real lookup off the
            // main thread. It is only ever a label for the UI and the media notification.
            val title = withContext(Dispatchers.IO) { documentTitleOf(sourceUri) }
            controller.setMediaItem(mediaItem(sourceUri, title))
            controller.prepare()
            controller.play()
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

    private fun documentTitleOf(sourceUri: String): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(Uri.parse(sourceUri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
            }
    }.getOrNull()

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
    }
}
