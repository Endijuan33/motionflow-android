package com.motionflow.player.core.media.session

import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Awaits a session's answer without blocking the thread that owns the player.
 *
 * The mechanism both transports share, and the only part worth sharing: the two differ in which
 * commands they send and how they read the answer, and agree on how to wait for one. The listener only
 * completes a continuation, which is cheap, so there is no executor to own and no thread to leak.
 *
 * Returns `null` when the future failed. Cancellation propagates as itself rather than being dressed up
 * as a failed answer, because a caller that went away was not refused.
 */
internal suspend fun awaitSessionResult(pending: ListenableFuture<SessionResult>): SessionResult? =
    suspendCancellableCoroutine { continuation ->
        pending.addListener(
            { continuation.resume(runCatching { pending.get() }.getOrNull()) },
            Executor { it.run() },
        )
    }
