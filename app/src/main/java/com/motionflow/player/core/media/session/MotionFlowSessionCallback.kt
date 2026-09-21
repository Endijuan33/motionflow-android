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
 * The session's callback: it declares the commands a screen may send, and answers them where the player
 * lives.
 *
 * It was the processing callback until measurements needed the same route, and it is now the one place
 * that knows every command this application defines. Each family keeps its own contract and its own
 * handler; this class knows only how to declare them and how to hand an action to the right one.
 *
 * ## Why the commands have to be declared
 *
 * A `MediaController` may only send a command its connection result offers it. The platform's default
 * set — `ConnectionResult.DEFAULT_SESSION_COMMANDS` — contains the predefined session commands, and a
 * custom command is not one of them, so without this callback a request would be refused before any
 * handler ran. [onConnectAsync] adds the application's actions for a **trusted** controller, which
 * includes this application's own UI, and leaves every other controller on exactly the default set it
 * would otherwise have had: the system's media controls keep the read-only commands they are entitled
 * to and are offered no way to change the rendering path or to start a measurement.
 *
 * ## Why it answers rather than acting
 *
 * Processing is forwarded to [MotionFlowPlayer], which is where a stage would be attached; measurement
 * is forwarded to the handler that owns the recorder. Both failure paths are closed deliberately: a
 * handler that throws becomes a refusal, never a playback error, and never leaves a controller waiting
 * for an answer that will not come.
 */
class MotionFlowSessionCallback(

    /**
     * The engine, read at call time rather than captured, so the callback never keeps a released
     * player alive and needs no ordering guarantee against the service's own lifecycle.
     */
    private val engine: () -> MotionFlowPlayer?,

    /** The measurement handler, or `null` while no session service is hosting one. */
    private val performance: PerformanceCommandHandler? = null,
) : MediaSession.Callback {

    /**
     * Accepts the connection, adding this application's commands for a trusted controller.
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
            val commands = ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(ProcessingSessionContract.enableCommand)
                .add(ProcessingSessionContract.disableCommand)

            PerformanceSessionContract.commands.forEach { commands.add(it) }

            builder.setAvailableSessionCommands(commands.build())
        }
        return Futures.immediateFuture(builder.build())
    }

    /**
     * Answers a command.
     *
     * Called on the application thread, which is the thread the `ExoPlayer` requires, so a request
     * reaches the engine on the thread that owns it. An action no contract defines is refused rather
     * than ignored: a controller asking for something unknown should be told so.
     */
    override fun onCustomCommand(
        session: MediaSession,
        controller: ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        // The measurement family is answered without the engine: its recorder already holds the
        // counters, and a session must be answerable while the engine is being replaced.
        if (performance != null && performance.handles(customCommand)) {
            return Futures.immediateFuture(performance.handle(customCommand, args))
        }

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
        const val TAG = "MotionFlowSession"
    }
}
