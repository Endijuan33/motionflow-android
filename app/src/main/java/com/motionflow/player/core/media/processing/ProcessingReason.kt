package com.motionflow.player.core.media.processing

/**
 * Why processing is not active, stated precisely enough to act on.
 *
 * Each value names something that was *established*, never something assumed. A reason is what keeps
 * an inactive state from being a shrug: the diagnostics can say which of the several possible causes
 * actually applies.
 */
enum class ProcessingReason {

    /** No video surface is bound, so there is nothing for a stage to render through. */
    NO_SURFACE,

    /**
     * This build does not link `androidx.media3:media3-effect`.
     *
     * A build fact rather than a device fact. Media3 1.11.1's `ExoPlayerImpl.setVideoEffects` begins
     * with `Class.forName("androidx.media3.effect.SingleInputVideoGraph$Factory")` and throws
     * `IllegalStateException("Could not find required lib-effect dependencies.")` when the lookup
     * fails, so on a classpath without that module the call cannot be made at all — not even to clear
     * the effect list.
     *
     * Phase 7 linked the module for the measured effect-pipeline baseline, so this is no longer the
     * reason an interactive processing request is refused: the endpoint reports its build fact, and
     * today that fact is `true`. The value is kept because it is the honest answer if the dependency is
     * ever removed, and because a panel should be able to name a missing prerequisite rather than a
     * missing stage when a prerequisite is what a build is missing.
     */
    EFFECTS_MODULE_ABSENT,

    /** The renderer could host a stage, and this application has none to give it. */
    NO_STAGE_IMPLEMENTED,

    /** The player was asked to attach a stage and declined. */
    REQUEST_REFUSED,

    /** The media session does not offer the processing command to this controller. */
    COMMAND_UNAVAILABLE,

    /** The request never reached the player: no session, no answer, or an answer that could not be read. */
    TRANSPORT_FAILED,
}
