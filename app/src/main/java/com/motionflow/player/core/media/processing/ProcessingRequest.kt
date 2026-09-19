package com.motionflow.player.core.media.processing

/**
 * A request to turn processing on or off.
 *
 * Deliberately carries no settings. There is no target rate, no strength, no model, no mode: a
 * request says only whether a processing stage should be in the path. Anything else would be tuning
 * for an algorithm this application does not have, and a request that cannot be expressed cannot be
 * quietly invented later.
 */
enum class ProcessingRequest {

    /** Put a processing stage into the path, if one can be attached. */
    ENABLE,

    /** Take the processing stage out of the path, returning the player to Media3's own rendering. */
    DISABLE;

    /** The same request expressed as the flag a transport is likelier to carry. */
    val enabled: Boolean get() = this == ENABLE
}

/** What a request produced. */
enum class ProcessingOutcome {

    /** A stage is attached now. */
    ATTACHED,

    /** No stage is attached: either one was removed, or none was there to remove. */
    DETACHED,

    /** The player declined. Nothing changed. */
    REFUSED,

    /** The request never reached the player. Nothing changed. */
    UNREACHABLE,
}

/**
 * The answer to a [ProcessingRequest].
 *
 * [reason] is required whenever the outcome is not one the player could confirm, so a refusal is
 * never bare. It is `null` for [ProcessingOutcome.ATTACHED] and [ProcessingOutcome.DETACHED],
 * because a successful outcome needs no excuse.
 */
data class ProcessingResult(
    val outcome: ProcessingOutcome,
    val reason: ProcessingReason? = null,
) {

    /** True only when a player reported that a stage is attached. */
    val attached: Boolean get() = outcome == ProcessingOutcome.ATTACHED

    /** True when the player confirmed that nothing is attached. */
    val detached: Boolean get() = outcome == ProcessingOutcome.DETACHED

    companion object {

        /**
         * The player is running Media3's own path and nothing is attached.
         *
         * This is also the answer to a disable request that had nothing to remove, which is why it is
         * not a failure: the state being asked for is the state that holds.
         */
        val Detached = ProcessingResult(ProcessingOutcome.DETACHED)

        /** The player declined. */
        fun refused(reason: ProcessingReason): ProcessingResult =
            ProcessingResult(ProcessingOutcome.REFUSED, reason)

        /** The request never arrived, or its answer could not be read. */
        fun unreachable(reason: ProcessingReason): ProcessingResult =
            ProcessingResult(ProcessingOutcome.UNREACHABLE, reason)
    }
}
