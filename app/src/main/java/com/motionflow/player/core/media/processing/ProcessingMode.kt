package com.motionflow.player.core.media.processing

/**
 * What is happening to the frames Media3 decodes, as a state a person could be shown.
 *
 * Every value describes *attachment*, never quality and never a frame rate. No mode means frames were
 * generated, duplicated or dropped, and no mode claims a speed-up: a stage that alters pictures would
 * still not add frames to the source.
 *
 * The distinction between [NATIVE] and [PROCESSING_INACTIVE] is one of knowledge, not of behaviour:
 *
 * - [NATIVE] is the state before anything has been reported — the path is Media3's own, and nothing
 *   is known that could change it.
 * - [PROCESSING_INACTIVE] is the state once a surface is known, so a stage *could* sit in front of
 *   it, and none does.
 *
 * Neither is a failure. A failure is [PROCESSING_FAILED], which is only ever reached by asking for
 * something that could not be delivered.
 */
enum class ProcessingMode {

    /** Media3 renders the decoded frames directly, and nothing has been reported that could change that. */
    NATIVE,

    /**
     * No stage can be attached here, because there is nothing for one to render through.
     *
     * The state the application reports before the player screen has a video surface.
     */
    PROCESSING_UNAVAILABLE,

    /** A stage could sit in front of the surface and none is attached. */
    PROCESSING_INACTIVE,

    /** A stage is attached. Only ever reported because a player said so, never because one was asked for. */
    PROCESSING_ACTIVE,

    /** The last request could not be honoured, and nothing is attached. Playback is unaffected. */
    PROCESSING_FAILED,
}
