package com.motionflow.player.core.media.performance

/**
 * Identifies one measurement session, for the length of one process.
 *
 * Generated locally by a counter, never derived from anything about the device or the person using it,
 * never written to disk, and never sent anywhere. Its whole purpose is to let a stop request be
 * matched to the start it belongs to; when the process ends, it is gone.
 */
data class PerformanceSessionId(val value: Long)

/**
 * How long a measurement runs.
 *
 * A closed set rather than a free number of seconds: a measurement that can be any length is hard to
 * compare with the next one, and the point of a controlled session is that two runs differ in exactly
 * one thing — the pipeline.
 */
enum class PerformanceSessionLength(val seconds: Int) {

    TEN(10),
    THIRTY(30),
    SIXTY(60);

    val durationMs: Long get() = seconds * MILLIS_PER_SECOND

    companion object {

        const val MILLIS_PER_SECOND = 1_000L

        /** The length [seconds] names, or `null` when it is not one of the controlled windows. */
        fun ofSeconds(seconds: Int): PerformanceSessionLength? = entries.firstOrNull { it.seconds == seconds }
    }
}

/**
 * What a screen asks for when it starts a measurement.
 *
 * The two rates travel with the request because the engines that own them live on the screen's side:
 * the metadata engine measures the source's frame rate and the refresh engine owns the display, and
 * this phase consumes both rather than re-deriving either. They are recorded as *reported by the
 * client*, which is why they are nullable — a session may legitimately begin before the metadata read
 * has finished, and a missing rate is recorded as missing.
 */
data class PerformanceSessionRequest(
    val length: PerformanceSessionLength,
    val mode: ProcessingPerformanceMode,
    val sourceFps: Float? = null,
    val displayRefreshRateHz: Float? = null,
)

/**
 * One measurement session: when it ran, under what condition, and with what the client knew.
 *
 * Timestamps are supplied by the caller rather than read here. That is what keeps this model pure and
 * its tests exact: a clock read inside the type would make every assertion about a duration depend on
 * how long the test itself took.
 */
data class PerformanceSession(
    val id: PerformanceSessionId,
    val mode: ProcessingPerformanceMode,
    val length: PerformanceSessionLength,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val sourceFps: Float? = null,
    val displayRefreshRateHz: Float? = null,
) {

    /** True while the session is still collecting. */
    val isRunning: Boolean get() = endedAtMs == null

    /** How long it ran, or `null` while it is still running. */
    val measuredDurationMs: Long? get() = endedAtMs?.let { it - startedAtMs }

    /**
     * True once the requested window has passed.
     *
     * A running session stops accumulating once this is true, so a session whose stop request never
     * arrives closes itself rather than collecting until the process ends. That is a duration bound,
     * not a poll: it is evaluated when an event arrives, and nothing schedules anything.
     */
    fun hasExpired(nowMs: Long): Boolean = nowMs - startedAtMs >= length.durationMs

    /** The same session, closed at [nowMs], with [mode] left as it was unless a failure overrode it. */
    fun ended(nowMs: Long, mode: ProcessingPerformanceMode = this.mode): PerformanceSession =
        copy(endedAtMs = nowMs, mode = mode)
}

/** Why a measurement request produced no session, or no end. */
enum class PerformanceSessionRefusal {

    /**
     * Nothing is loaded, so a measurement would collect zeroes that look like findings.
     *
     * Recording "0 dropped frames" for a player that was not playing anything would be worse than
     * recording nothing: it is the shape of a result without being one.
     */
    NO_MEDIA,

    /** A session is already running. Starting a second one would silently replace the first. */
    ALREADY_RUNNING,

    /** No session is running, so there is nothing to stop. */
    NOT_RUNNING,

    /**
     * The request could not be read.
     *
     * Distinct from a refusal a player makes: this says the request itself was not in the shape the
     * contract defines — a missing length, or a length that is not one of the controlled windows — so a
     * session was never a possibility. A client that sees this is looking at its own bug, which is worth
     * being told rather than being handed a default.
     */
    UNREADABLE_REQUEST,
}

/** What a start or stop request produced. */
data class PerformanceSessionOutcome(
    val session: PerformanceSession? = null,
    val refusal: PerformanceSessionRefusal? = null,
) {

    val accepted: Boolean get() = refusal == null

    companion object {

        fun accepted(session: PerformanceSession): PerformanceSessionOutcome =
            PerformanceSessionOutcome(session = session)

        fun refused(refusal: PerformanceSessionRefusal): PerformanceSessionOutcome =
            PerformanceSessionOutcome(refusal = refusal)
    }
}
