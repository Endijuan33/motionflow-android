package com.motionflow.player.core.media.processing.android

import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.motionflow.player.core.media.processing.ProcessingController
import com.motionflow.player.core.media.processing.ProcessingReason
import com.motionflow.player.core.media.processing.ProcessingRequest
import com.motionflow.player.core.media.processing.ProcessingResult
import com.motionflow.player.core.media.session.ProcessingSessionContract
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Carries a processing request to the media session, and back.
 *
 * The `MediaController` is the session connection the player screen already holds; this class borrows
 * it and never owns it. No `ExoPlayer` is created here, no `Surface` is touched, and no rendering
 * pipeline is built: the request travels to the process-owned player through the session, which is the
 * only route an application-owned screen has.
 *
 * ## Threading
 *
 * `MediaController` methods assert that they run on the application thread, so the whole request is
 * confined to it with `withContext(Dispatchers.Main.immediate)`. Confining here rather than trusting
 * the caller means a future call site cannot get this wrong.
 *
 * ## When the command is not there
 *
 * A session offers a controller only the commands in its connection result, so a session that does not
 * offer the processing command cannot answer the request. The answer is produced locally as
 * [ProcessingReason.COMMAND_UNAVAILABLE] instead of waiting for a reply that will not come. The
 * capability is re-read per request rather than cached, which is also how a stale connection is
 * handled: it is discovered at the moment it matters.
 */
class MediaSessionProcessingController(
    private val controller: MediaController,
) : ProcessingController {

    override suspend fun request(request: ProcessingRequest): ProcessingResult =
        withContext(Dispatchers.Main.immediate) { send(request) }

    private suspend fun send(request: ProcessingRequest): ProcessingResult {
        val command = ProcessingSessionContract.commandFor(request)
        if (!controller.isSessionCommandAvailable(command)) {
            return ProcessingResult.unreachable(ProcessingReason.COMMAND_UNAVAILABLE)
        }

        // Sending is synchronous and may fail on the spot; awaiting is not, and its cancellation is
        // the caller's business rather than a transport failure, so the two are kept apart.
        val pending = runCatching { controller.sendCustomCommand(command, Bundle.EMPTY) }
            .getOrElse { return ProcessingResult.unreachable(ProcessingReason.TRANSPORT_FAILED) }

        val answer = await(pending)
            ?: return ProcessingResult.unreachable(ProcessingReason.TRANSPORT_FAILED)

        return ProcessingSessionContract.decode(
            resultCode = answer.resultCode,
            outcome = answer.extras.getString(ProcessingSessionContract.KEY_OUTCOME),
            reason = answer.extras.getString(ProcessingSessionContract.KEY_REASON),
        )
    }

    /**
     * Awaits the session's answer without blocking the thread that owns the player.
     *
     * The listener only completes a continuation, which is cheap, so there is no executor to own and no
     * thread to leak. Returns `null` when the future failed; cancellation propagates as itself.
     */
    private suspend fun await(pending: ListenableFuture<SessionResult>): SessionResult? =
        suspendCancellableCoroutine { continuation ->
            pending.addListener(
                { continuation.resume(runCatching { pending.get() }.getOrNull()) },
                Executor { it.run() },
            )
        }
}
