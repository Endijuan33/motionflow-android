package com.motionflow.player.core.media.session

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.motionflow.player.MainActivity
import com.motionflow.player.MotionFlowApplication
import com.motionflow.player.core.media.performance.PerformanceSessionCoordinator
import com.motionflow.player.core.media.performance.ProcessingPerformanceMode
import com.motionflow.player.core.media.performance.android.AndroidPerformanceProbe
import com.motionflow.player.core.media.performance.android.PerformanceRecorder
import com.motionflow.player.core.media.player.MotionFlowPlayer
import com.motionflow.player.core.media.player.PlaybackConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Hosts the application's single playback engine and publishes it to Android.
 *
 * Media3 owns the hard parts here: the session exposes playback state to system media controls
 * (lock screen, Bluetooth, headset buttons), and the service is promoted to the foreground with a
 * media notification while playback is running. Playback therefore belongs to the process rather
 * than to a screen, which is what makes it survive configuration changes and screen navigation.
 *
 * The service creates the engine in [onCreate] and releases it in [onDestroy]; no other component
 * creates a player. It also hosts [MotionFlowSessionCallback], which is where a request to change the
 * rendering path or to measure it is answered — both must be handled here rather than in a screen,
 * because the `ExoPlayer` and Media3's analytics belong to this process's service.
 *
 * ## The engine is built for a pipeline
 *
 * Media3 requires the effects pipeline to exist before `prepare()`, so the pipeline cannot be chosen
 * once playback has started. The engine is therefore built from the application's requested
 * [PlaybackConfiguration], and *changing* the pipeline is an explicit, deliberate restart:
 *
 * 1. the requested configuration changes (from the Home screen, which owns the choice);
 * 2. this service releases the session and the engine, and stops itself;
 * 3. the next engine is built from the new configuration — before anything can prepare it.
 *
 * That is a measurement limitation as much as a design: switching pipelines stops playback, and the
 * media item is not restored. It is documented in `README.md` and in the diagnostic note a user sees,
 * because a switch that quietly kept playing would be claiming a pipeline change that had not
 * happened.
 */
class MotionFlowMediaSessionService : MediaSessionService() {

    private var engine: MotionFlowPlayer? = null
    private var mediaSession: MediaSession? = null
    private var recorder: PerformanceRecorder? = null
    private var performanceHandler: PerformanceCommandHandler? = null

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var configurationWatch: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Session service created")

        val configuration = applicationConfigurationStore().requested.value
        buildEngineAndSession(configuration)
        watchRequestedConfiguration()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // Nothing worth keeping the service alive for: playback is stopped or finished, so let the
        // process clean itself up instead of holding a foreground service open forever.
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Session service destroyed")
        configurationWatch?.cancel()
        configurationWatch = null
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        engine?.release()
        engine = null
        recorder = null
        performanceHandler = null
        super.onDestroy()
    }

    /** Builds the engine for [configuration], with its measurement recorder already attached. */
    private fun buildEngineAndSession(configuration: PlaybackConfiguration) {
        val player = MotionFlowPlayer(this, configuration)

        val probe = AndroidPerformanceProbe(
            powerManager = getSystemService(PowerManager::class.java),
            // The playback thread, so that a thermal callback and Media3's callbacks touch the
            // accumulator on the same thread and no lock is needed.
            executor = ContextCompat.getMainExecutor(this),
            memoryClassMb = memoryClassMb(),
        )
        val measurement = PerformanceRecorder(
            probe = probe,
            playbackPositionMs = { player.player.currentPosition.takeIf { it >= 0 } },
            nowMs = SystemClock::elapsedRealtime,
        )
        player.addAnalyticsListener(measurement)

        val handler = PerformanceCommandHandler(
            recorder = measurement,
            coordinator = PerformanceSessionCoordinator(probe.support()),
            probe = probe,
            mediaLoaded = { player.player.mediaItemCount > 0 },
            nowMs = SystemClock::elapsedRealtime,
        )
        handler.onEngineBuilt(configuration.processingMode)
        player.player.addListener(FailureWatcher(configuration.processingMode, handler))

        engine = player
        recorder = measurement
        performanceHandler = handler
        mediaSession = MediaSession.Builder(this, player.player)
            .setCallback(MotionFlowSessionCallback(engine = { engine }, performance = performanceHandler))
            .setSessionActivity(openAppIntent())
            .build()
    }

    /**
     * Releases the engine when the user asks for a different pipeline.
     *
     * Deliberately not a rebuild in place: building the replacement here would mean a second place that
     * creates a player, and the invariant worth keeping is that the engine is created in [onCreate] and
     * nowhere else. Stopping the service makes the next start build the requested pipeline, which is
     * exactly the "configuration → build → prepare" order Media3 requires.
     */
    private fun watchRequestedConfiguration() {
        configurationWatch = scope.launch {
            applicationConfigurationStore().requested
                .drop(1)
                .collect { requested ->
                    if (requested == engine?.configuration) return@collect

                    Log.d(TAG, "Playback pipeline changed to ${requested.processingMode}; restarting the engine")
                    mediaSession?.release()
                    mediaSession = null
                    engine?.release()
                    engine = null
                    recorder = null
                    performanceHandler = null
                    stopSelf()
                }
        }
    }

    /** Brings the user back to the player surface when the media notification is tapped. */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        /* requestCode = */ 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun applicationConfigurationStore() =
        (application as MotionFlowApplication).playbackConfigurationStore

    private fun memoryClassMb(): Int? = runCatching {
        (getSystemService(ActivityManager::class.java)).memoryClass
    }.getOrNull()

    /**
     * Reports the one failure this phase can attribute to a pipeline.
     *
     * A player error while the effect pipeline was configured means the pipeline could not run it, which
     * is a finding rather than an ordinary playback error — and it is the only way
     * [ProcessingPerformanceMode.FAILED] is reached. Nothing is changed about playback by observing it.
     */
    private class FailureWatcher(
        private val mode: ProcessingPerformanceMode,
        private val handler: PerformanceCommandHandler,
    ) : Player.Listener {

        override fun onPlayerError(error: PlaybackException) {
            if (mode == ProcessingPerformanceMode.EFFECT_PIPELINE) handler.onPipelineFailed()
        }
    }

    private companion object {
        const val TAG = "MotionFlowMediaSession"
    }
}
