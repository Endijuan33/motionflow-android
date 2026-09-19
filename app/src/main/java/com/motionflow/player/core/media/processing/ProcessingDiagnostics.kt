package com.motionflow.player.core.media.processing

/**
 * Everything that can be said about processing without touching a frame.
 *
 * The counters describe *attachment*, and nothing else: how often a player confirmed that a stage was
 * attached, how often it confirmed that one was gone, and how many times processing was asked for.
 * They are counts of answers received, so they cannot claim a stage that no player ever reported.
 *
 * [videoFps] and [displayRefreshRateHz] are carried because they are already known — the metadata
 * engine reads one and the refresh engine the other — and a processing report that omitted them would
 * make a reader join three sources to answer one question. They are copies of observed facts, never
 * derived from processing, and nothing here computes an output rate: an effect that copies a frame is
 * not a frame rate, and this model has no field for one.
 *
 * Nothing here identifies a file, a person or a location, nothing is logged per frame, and nothing is
 * polled.
 */
data class ProcessingDiagnostics(

    /** What is happening to decoded frames. */
    val mode: ProcessingMode = ProcessingMode.NATIVE,

    /** Why [mode] is not [ProcessingMode.PROCESSING_ACTIVE], when a reason is known. */
    val reason: ProcessingReason? = null,

    /** What the environment can accept. */
    val capabilities: ProcessingCapabilities = ProcessingCapabilities.Unknown,

    /** True only because a player said a stage is attached. */
    val effectAttached: Boolean = false,

    /**
     * The answer to the last enable request: `true` if a stage attached, `false` if it did not, and
     * `null` while no enable request has been answered in this session.
     */
    val lastAttachmentSucceeded: Boolean? = null,

    /** How often a player reported that a stage had attached. */
    val attachCount: Int = 0,

    /** How often a stage stopped being attached, whether released or removed. */
    val detachCount: Int = 0,

    /** How many processing requests this session has made. */
    val requestCount: Int = 0,

    /** The source's measured frame rate, when it is known. A copy of a metadata fact. */
    val videoFps: Float? = null,

    /** The display's current refresh rate, when it is known. A copy of a display fact. */
    val displayRefreshRateHz: Float? = null,
) {

    /** True when the environment could host a stage but none is attached. */
    val isInactiveButAvailable: Boolean
        get() = mode == ProcessingMode.PROCESSING_INACTIVE && capabilities.canAttach

    companion object {

        /** Before anything has been reported. */
        val Native = ProcessingDiagnostics()
    }
}
