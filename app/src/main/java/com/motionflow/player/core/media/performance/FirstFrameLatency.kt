package com.motionflow.player.core.media.performance

/**
 * Converts Media3's first-frame time into a duration for one measurement session.
 *
 * ## What Media3 actually reports
 *
 * `AnalyticsListener.onRenderedFirstFrame(eventTime, output, renderTimeMs)` documents its third
 * parameter as:
 *
 * > `renderTimeMs` — `SystemClock.elapsedRealtime()` when the first frame was rendered.
 *
 * It is a **timestamp on the monotonic clock**, not a duration. Storing it as a latency is the defect
 * this type exists to make impossible: on a device up for four days it reads as hundreds of millions of
 * milliseconds, and it is a plausible-looking number rather than an error.
 *
 * ## The definition this phase uses
 *
 * **First-frame latency = the render timestamp minus the measurement session's start, on the same
 * clock.** It is a duration relative to the current session, and nothing else:
 *
 * - Same clock on both sides, so the subtraction is meaningful. `System.nanoTime()` and
 *   `SystemClock.elapsedRealtime()` are both monotonic but have different epochs, and mixing them
 *   produces a confident nonsense.
 * - A frame that arrived *before* the session began is not this session's measurement. It is reported
 *   as not measured rather than as a negative or a stale number, so a session that starts after
 *   playback has begun honestly says it captured no first frame.
 * - The value belongs to the window it was measured in, and the accumulator clears it at every session
 *   start, so a second session cannot inherit the first one's.
 *
 * The playback startup cost that is available regardless of when a session starts — `prepare()` to
 * first frame — is measured separately by the rendering foundation, for the item being played. It is
 * deliberately *not* folded in here: it would be a value from outside the window, attributed to a
 * pipeline it was not taken on.
 */
object FirstFrameLatency {

    /**
     * The latency of a first frame, or `null` when it cannot be stated for this session.
     *
     * Returns `null` when either reading is missing, and when the frame predates the session start —
     * which is not a latency of zero, but the absence of a measurement.
     */
    fun of(renderTimeMs: Long?, sessionStartMs: Long?): Long? {
        if (renderTimeMs == null || sessionStartMs == null) return null

        return (renderTimeMs - sessionStartMs).takeIf { it >= 0 }
    }
}
