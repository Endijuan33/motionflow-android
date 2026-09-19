package com.motionflow.player.core.media.session

import android.os.Bundle
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.motionflow.player.core.media.player.MotionFlowPlayer
import com.motionflow.player.core.media.processing.ProcessingReason
import com.motionflow.player.core.media.processing.ProcessingResult

/**
 * Declares the processing command to a controller, and answers it at the player.
 *
 * ## Why the command has to be declared
 *
 * A `MediaController` may only send a command its connection result offers it. The platform's default
 * set — `ConnectionResult.DEFAULT_SESSION_COMMANDS` — contains the predefined session commands, and a
 * custom command is not one of them, so without this callback the request would be refused before any
 * handler ran. [onConnectAsync] adds the two processing actions for a **trusted** controller, which
 * includes this application's own UI, and leaves every other controller exactly on the default set it
 * would otherwise have had: the system's media controls keep the read-only commands they are entitled
 * to and are not offered a way to change the rendering path.
 *
 * ## Why it answers instead of attaching
 *
 * [onCustomCommand] forwards to [MotionFlowPlayer.applyProcessing], which is where a stage would be
 * attached and which currently refuses every enable for a verified reason. See
 * `PlayerProcessingEndpoint` for the two Media3 1.11.1 facts behind that — the absent effects module
 * and the requirement that the pipeline be armed before `prepare()`. The failure path here is
 * deliberately closed: a handler that throws cannot become a playback failure, and cannot leave a
 * controller waiting for an answer that never comes.
 */
class ProcessingSessionCallback(

    /**
     * The engine, read at call time rather than captured, so the callback never keeps a released
     * player alive and needs no ordering guarantee against the service's own lifecycle.
     */
    private val engine: () -> MotionFlowPlayer?,
) : MediaSession.Callback {

    /**
     * Accepts the connection, adding the processing commands for a trusted controller.
     *
     * ## Why this is the async callback
     *
     * `MediaSession.Callback` has both a deprecated `onConnect` and this one, and the deprecated form
     * takes precedence if it is overridden. This class does not override it, so the platform's
     * "not implemented" marker is returned and this method is used — the documented, non-deprecated
     * path. The connection is accepted immediately: nothing here needs to wait for anything, and
     * blocking a controller's connect would be a good way to make the media notification late.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onConnectAsync(
        session: MediaSession,
        controller: ControllerInfo,
    ): ListenableFuture<ConnectionResult> {
        val builder = ConnectionResult.AcceptedResultBuilder(session, controller)
        if (controller.isTrusted) {
            builder.setAvailableSessionCommands(
                ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(ProcessingSessionContract.enableCommand)
                    .add(ProcessingSessionContract.disableCommand)
                    .build(),
            )
        }
        return Futures.immediateFuture(builder.build())
    }

    /**
     * Answers a processing request.
     *
     * Called on the application thread, which is the thread the `ExoPlayer` requires, so the request
     * reaches the engine on the thread that owns it. An action this contract does not define is
     * refused rather than ignored: a controller asking for something unknown should be told so.
     */
    override fun onCustomCommand(
        session: MediaSession,
        controller: ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val request = ProcessingSessionContract.requestFor(customCommand)
            ?: return Futures.immediateFuture(ProcessingSessionContract.unsupportedResult())

        val player = engine()
        if (player == null) {
            // No engine means no answer about one; the client reports this as unreachable.
            return Futures.immediateFuture(
                ProcessingSessionContract.encode(
                    ProcessingResult.unreachable(ProcessingReason.TRANSPORT_FAILED),
                ),
            )
        }

        val result = runCatching { player.applyProcessing(request) }.getOrElse { failure ->
            // A player-side failure is a refusal, never a playback error: processing is optional by
            // construction, and nothing about this answer may disturb what is playing.
            Log.w(TAG, "Processing request could not be handled", failure)
            ProcessingResult.refused(ProcessingReason.REQUEST_REFUSED)
        }

        return Futures.immediateFuture(ProcessingSessionContract.encode(result))
    }

    private companion object {
        const val TAG = "MotionFlowProcessing"
    }
}
