package com.motionflow.player.core.media.performance.android

import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.motionflow.player.core.media.performance.PerformanceCommandResult
import com.motionflow.player.core.media.performance.PerformanceController
import com.motionflow.player.core.media.performance.PerformanceSessionRefusal
import com.motionflow.player.core.media.performance.PerformanceSessionRequest
import com.motionflow.player.core.media.session.PerformanceSessionContract
import com.motionflow.player.core.media.session.PerformanceWire
import com.motionflow.player.core.media.session.awaitSessionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Carries measurement commands to the media session, and the measurements back.
 *
 * The `MediaController` is the session connection the player screen already holds; this class borrows it
 * and never owns it. No `ExoPlayer`, no surface, no analytics listener: a measurement can only be *asked
 * for* from here, which is what keeps the numbers coming from one place.
 *
 * ## Threading
 *
 * `MediaController` methods assert that they run on the application thread, so every request is confined
 * to it with `withContext(Dispatchers.Main.immediate)`. Confining here rather than trusting the caller
 * means a future call site cannot get it wrong.
 *
 * ## When nothing answers
 *
 * A session that does not offer these commands cannot answer them, so the result is reported as
 * unreachable rather than awaited: a panel must be able to say "nobody was asked", which is not the same
 * as a pipeline that refused.
 */
class MediaSessionPerformanceController(
    private val controller: MediaController,
) : PerformanceController {

    override suspend fun start(request: PerformanceSessionRequest): PerformanceCommandResult =
        withContext(Dispatchers.Main.immediate) {
            val args = Bundle()
            PerformanceSessionContract.StartArguments(
                length = request.length,
                sourceFps = request.sourceFps,
                displayRefreshRateHz = request.displayRefreshRateHz,
            ).applyTo(args)

            send(PerformanceSessionContract.startCommand, args)
        }

    override suspend fun stop(): PerformanceCommandResult =
        withContext(Dispatchers.Main.immediate) { send(PerformanceSessionContract.stopCommand, Bundle()) }

    override suspend fun read(): PerformanceCommandResult =
        withContext(Dispatchers.Main.immediate) { send(PerformanceSessionContract.readCommand, Bundle()) }

    private suspend fun send(command: SessionCommand, args: Bundle): PerformanceCommandResult {
        if (!controller.isSessionCommandAvailable(command)) {
            return PerformanceCommandResult.Unreachable
        }

        val pending = runCatching { controller.sendCustomCommand(command, args) }
            .getOrElse { return PerformanceCommandResult.Unreachable }

        val answer = awaitSessionResult(pending) ?: return PerformanceCommandResult.Unreachable
        return decode(answer)
    }

    private fun decode(answer: SessionResult): PerformanceCommandResult {
        if (answer.resultCode != SessionResult.RESULT_SUCCESS) {
            // Any code the session layer produced is a request that did not produce a measurement.
            return PerformanceCommandResult.Unreachable
        }

        val wire = PerformanceWire.fromBundle(answer.extras)
        return PerformanceCommandResult(
            diagnostics = wire.toDiagnostics(),
            refusal = wire.refusal?.let { name ->
                PerformanceSessionRefusal.entries.firstOrNull { it.name == name }
            },
        )
    }
}
