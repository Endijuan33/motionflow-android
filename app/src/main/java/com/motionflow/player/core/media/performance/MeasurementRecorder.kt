package com.motionflow.player.core.media.performance

/**
 * The measurement collector, as the session handler needs to see it.
 *
 * A seam, so the completion path can be driven on the JVM without Media3's `AnalyticsListener` or a
 * device. The production collector — `PerformanceRecorder` — is that listener; this interface is only
 * the handful of lifecycle calls the handler makes on it, which is what a test needs to reproduce a
 * session starting, running past its window, and being finalized.
 */
interface MeasurementRecorder {

    /** True while a session's events are being accumulated. */
    val isRecording: Boolean

    /** True when the player reported an error while the current session was recording. */
    val hasFailed: Boolean

    /** Opens a session and starts accumulating from a clean state. */
    fun begin()

    /** Closes the session and returns its final measurement. */
    fun finish(): FramePerformanceSnapshot

    /** The measurement so far, without closing the session. */
    fun live(): FramePerformanceSnapshot
}

/**
 * The platform facts the handler records with a session, as a seam for the same reason.
 *
 * The production probe holds a `PowerManager`; this interface is the two pure questions the handler
 * asks it, so the handler's session lifecycle can be tested without one.
 */
interface MeasurementProbe {

    /** What this platform can measure at all. */
    fun support(): PerformanceMeasurementSupport

    /** The device facts a measurement should be read against, given the display's own modes. */
    fun deviceCharacteristics(supportedDisplayRefreshRatesHz: List<Float>): DeviceCharacteristics
}
