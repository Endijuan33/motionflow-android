package com.motionflow.player.core.media.performance

/**
 * The application-facing seam for a measurement session.
 *
 * One method per action, and no way to reach a player, a surface or an `AnalyticsListener`: a screen may
 * *ask* for a measurement, and the numbers come back as a value. The interface exists so the state a
 * screen presents can be driven by a fake in a test — the part of this phase that cannot be exercised
 * off-device is then the transport itself and nothing else.
 *
 * Implementations are expected to be asynchronous and must never block a frame. A thrown exception is
 * treated as an unreachable session, never as a playback failure: nothing about measuring may disturb
 * what is playing.
 */
interface PerformanceController {

    /** Asks for a session to start. The answer reports whether it did. */
    suspend fun start(request: PerformanceSessionRequest): PerformanceCommandResult

    /** Asks for the running session to close, and returns its final measurement. */
    suspend fun stop(): PerformanceCommandResult

    /** Asks for the current picture, changing nothing. */
    suspend fun read(): PerformanceCommandResult
}

/**
 * What a measurement command produced.
 *
 * [refusal] is a value rather than an exception, because "no" is a legitimate answer that a screen has
 * to render: a session can be refused for having nothing to measure, or for one already running.
 */
data class PerformanceCommandResult(
    val diagnostics: PerformanceDiagnostics = PerformanceDiagnostics.Idle,
    val refusal: PerformanceSessionRefusal? = null,
    val unreachable: Boolean = false,
) {

    /** True when the request was answered, whether by starting a session or by declining one. */
    val answered: Boolean get() = !unreachable

    companion object {

        /**
         * The session did not answer.
         *
         * Distinct from a refusal on purpose: a refusal means the player said no, and this means nobody
         * was asked. A panel that showed them the same way would blame the pipeline for a missing
         * service.
         */
        val Unreachable = PerformanceCommandResult(unreachable = true)
    }
}
