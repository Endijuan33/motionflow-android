package com.motionflow.player.core.media.pacing

/**
 * Applies pacing decisions, where a mechanism exists that can.
 *
 * ## Why there is no implementation in the application
 *
 * This is a deliberate omission, not unfinished work. Changing *when* a decoded frame is presented
 * means controlling frame release inside Media3's video renderer: `MediaCodecVideoRenderer` releases
 * output buffers itself, and the only app-facing hook — `VideoFrameMetadataListener` — is *told* the
 * release time rather than being able to change it. Intervening would mean supplying a custom
 * renderer, which replaces the rendering path this project has deliberately left to Media3.
 *
 * What can be done without that is already being done by other layers:
 *
 * - **Media3** calls `Surface.setFrameRate(...)` on API 30+ from its own `VideoFrameReleaseHelper`,
 *   with `FRAME_RATE_COMPATIBILITY_FIXED_SOURCE` for a steady source and a
 *   change-frame-rate-only-if-seamless strategy. A second engine setting the same hint would
 *   duplicate it.
 * - **Phase 3** asks the platform for a display mode whose rate suits the cadence, and restores it
 *   when the player leaves.
 *
 * So the pacing engine diagnoses, and this interface is the seam for the phase that owns a renderer
 * and can act on the diagnosis. Until one is bound, every decision reports `isApplied = false`,
 * which is why the diagnostics say "diagnostic only" rather than claiming a fix.
 */
interface FramePacingController {

    /**
     * Hands a decision to the mechanism.
     *
     * Only called for a cadence that cannot be shown evenly — a mismatch or an unsupported pairing.
     * Nothing needs pacing when the display already matches the video, or when there is no cadence to
     * analyse, so those decisions are never handed over.
     *
     * Implementations must not throw: a mechanism that cannot act reports it through
     * [FramePacingApplication], because a pacing failure must never reach playback.
     */
    fun apply(decision: FramePacingDecision): FramePacingApplication
}
