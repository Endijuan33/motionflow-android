package com.motionflow.player.core.media.session

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.motionflow.player.MainActivity
import com.motionflow.player.core.media.player.MotionFlowPlayer

/**
 * Hosts the application's single playback engine and publishes it to Android.
 *
 * Media3 owns the hard parts here: the session exposes playback state to system media controls
 * (lock screen, Bluetooth, headset buttons), and the service is promoted to the foreground with a
 * media notification while playback is running. Playback therefore belongs to the process rather
 * than to a screen, which is what makes it survive configuration changes and screen navigation.
 *
 * The service creates the engine in [onCreate] and releases it in [onDestroy]; no other component
 * creates a player.
 */
class MotionFlowMediaSessionService : MediaSessionService() {

    private var engine: MotionFlowPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Session service created")

        val player = MotionFlowPlayer(this)
        engine = player
        mediaSession = MediaSession.Builder(this, player.player)
            .setSessionActivity(openAppIntent())
            .build()
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
        mediaSession?.release()
        mediaSession = null
        engine?.release()
        engine = null
        super.onDestroy()
    }

    /** Brings the user back to the player surface when the media notification is tapped. */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        /* requestCode = */ 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "MotionFlowMediaSession"
    }
}
