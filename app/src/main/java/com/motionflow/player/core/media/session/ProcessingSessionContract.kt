package com.motionflow.player.core.media.session

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.motionflow.player.core.media.processing.ProcessingOutcome
import com.motionflow.player.core.media.processing.ProcessingReason
import com.motionflow.player.core.media.processing.ProcessingRequest
import com.motionflow.player.core.media.processing.ProcessingResult

/**
 * The wire contract for the processing command: two actions, one answer shape.
 *
 * A custom session command is used because it is the only official way for a screen to reach the
 * process-owned player. `ExoPlayer.setVideoEffects` is on `ExoPlayer` and *not* on `Player`, so it is
 * not reachable through a `MediaController` at all: with the media session owning the engine, a
 * command is the difference between asking the player's owner and reaching into it. Both ends of this
 * contract live in this repository.
 *
 * ## Why the session must declare it
 *
 * A session offers a controller the commands in its connection result, and
 * `ConnectionResult.DEFAULT_SESSION_COMMANDS` contains the predefined session commands only — a custom
 * command is *not* among them. Sending an undeclared custom command is therefore refused before any
 * callback runs, which is a feature: the command is opt-in per controller, and
 * [ProcessingSessionCallback] opts only a trusted one in. The client checks availability first and
 * reports its own [ProcessingReason.COMMAND_UNAVAILABLE], so the refusal is diagnosed at the point it
 * can be explained rather than after a round trip.
 *
 * Two actions rather than one action with an argument, so that neither half of an
 * enable/disable pair can be reached by a malformed argument: a request either names its intent
 * exactly or it is not this command at all.
 */
object ProcessingSessionContract {

    /** Sent to put a processing stage into the path. */
    const val ACTION_ENABLE = "com.motionflow.player.processing.ENABLE"

    /** Sent to take the processing stage out of the path. */
    const val ACTION_DISABLE = "com.motionflow.player.processing.DISABLE"

    /** The key holding the [ProcessingOutcome] name in a result's extras. */
    const val KEY_OUTCOME = "com.motionflow.player.processing.OUTCOME"

    /** The key holding the [ProcessingReason] name in a result's extras. */
    const val KEY_REASON = "com.motionflow.player.processing.REASON"

    /**
     * The commands a session must declare for a controller to be allowed to send them.
     *
     * Extras are empty: the action *is* the request, so there is nothing to carry. Built as
     * `Bundle()` rather than `Bundle.EMPTY` because these are properties, so they are constructed when
     * this object is first touched — and a framework constant is `null` off-device, where the stub
     * `android.jar` has no static initialisers. A null there fails class initialisation, which surfaces
     * as every test in the file erroring out for a reason that has nothing to do with the code.
     */
    val enableCommand = SessionCommand(ACTION_ENABLE, Bundle())
    val disableCommand = SessionCommand(ACTION_DISABLE, Bundle())

    /** The command that carries [request]. */
    fun commandFor(request: ProcessingRequest): SessionCommand =
        if (request.enabled) enableCommand else disableCommand

    /** The request [command] carries, or `null` when it is not a processing command. */
    fun requestFor(command: SessionCommand): ProcessingRequest? = when (command.customAction) {
        ACTION_ENABLE -> ProcessingRequest.ENABLE
        ACTION_DISABLE -> ProcessingRequest.DISABLE
        else -> null
    }

    /** Puts [result] on the wire. */
    // SessionError is Media3's "unstable" surface: its codes are supported, the class is not frozen.
    // The annotation is written fully qualified because Kotlin also has a `kotlin.OptIn`.
    @androidx.annotation.OptIn(UnstableApi::class)
    fun encode(result: ProcessingResult): SessionResult {
        val extras = Bundle()
        extras.putString(KEY_OUTCOME, result.outcome.name)
        result.reason?.let { extras.putString(KEY_REASON, it.name) }
        return when (result.outcome) {
            ProcessingOutcome.ATTACHED, ProcessingOutcome.DETACHED ->
                SessionResult(SessionResult.RESULT_SUCCESS, extras)

            ProcessingOutcome.REFUSED, ProcessingOutcome.UNREACHABLE ->
                SessionResult(SessionError.ERROR_NOT_SUPPORTED, extras)
        }
    }

    /**
     * The bare refusal for a request this contract does not define.
     *
     * Spelled through `SessionError.ERROR_NOT_SUPPORTED` rather than through its alias
     * `SessionResult.RESULT_ERROR_NOT_SUPPORTED`, which is the same code: the `SessionResult`
     * constructor's `@IntDef` lists the `SessionError` names, so the alias is rejected as an argument.
     * No extras travel with it, because an unknown action has no outcome to report; a client reads it
     * as a refusal of the request it sent.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun unsupportedResult(): SessionResult = SessionResult(SessionError.ERROR_NOT_SUPPORTED)

    /**
     * Reads an answer.
     *
     * Takes primitives rather than a [SessionResult] so the decoding rule can be tested without an
     * Android `Bundle`, which is a stub off-device. Anything this cannot read is reported as
     * unreachable rather than guessed at: an answer that was not understood is not an answer.
     */
    fun decode(resultCode: Int, outcome: String?, reason: String?): ProcessingResult =
        when (resultCode) {
            // A success that does not name an outcome is not usable: "something worked" is not a state
            // a diagnostics panel can show.
            SessionResult.RESULT_SUCCESS -> outcomeFrom(outcome)
                ?.let { ProcessingResult(outcome = it) }
                ?: ProcessingResult.unreachable(ProcessingReason.TRANSPORT_FAILED)

            // The player was asked and declined. The reason it gave is carried through.
            SessionResult.RESULT_ERROR_NOT_SUPPORTED ->
                ProcessingResult.refused(reasonFrom(reason) ?: ProcessingReason.REQUEST_REFUSED)

            // Any other code comes from the session layer rather than from the player's answer: the
            // request did not produce a decision, so it did not arrive.
            else -> ProcessingResult.unreachable(ProcessingReason.TRANSPORT_FAILED)
        }

    /**
     * Resolves an outcome name, or `null` when the name is absent or not one this build knows.
     * Compared by name rather than looked up with `valueOf`, so a name from a future build is refused
     * instead of throwing.
     */
    private fun outcomeFrom(name: String?): ProcessingOutcome? =
        name?.let { candidate -> ProcessingOutcome.entries.firstOrNull { it.name == candidate } }

    private fun reasonFrom(name: String?): ProcessingReason? =
        name?.let { candidate -> ProcessingReason.entries.firstOrNull { it.name == candidate } }
}
