package com.motionflow.player.core.media.session

import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.motionflow.player.core.media.processing.ProcessingOutcome
import com.motionflow.player.core.media.processing.ProcessingReason
import com.motionflow.player.core.media.processing.ProcessingRequest
import com.motionflow.player.core.media.processing.ProcessingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the wire contract: which action carries which request, and how an answer is read back.
 *
 * The answer's own `Bundle` is *not* exercised here. `android.os.Bundle` is a stub off-device, so a
 * value written into one cannot be read out again in a JVM test; what is tested is the decision —
 * which result code and which names mean which outcome — and that the same decisions invert cleanly.
 * The bundle plumbing between them is three lines of field access on each side, checked by the
 * compiler and exercised by the on-device path.
 */
class ProcessingSessionContractTest {

    @Test
    fun `each request has its own action`() {
        assertEquals(
            ProcessingSessionContract.ACTION_ENABLE,
            ProcessingSessionContract.commandFor(ProcessingRequest.ENABLE).customAction,
        )
        assertEquals(
            ProcessingSessionContract.ACTION_DISABLE,
            ProcessingSessionContract.commandFor(ProcessingRequest.DISABLE).customAction,
        )
    }

    @Test
    fun `each action resolves back to its request`() {
        assertEquals(
            ProcessingRequest.ENABLE,
            ProcessingSessionContract.requestFor(ProcessingSessionContract.enableCommand),
        )
        assertEquals(
            ProcessingRequest.DISABLE,
            ProcessingSessionContract.requestFor(ProcessingSessionContract.disableCommand),
        )
    }

    @Test
    fun `a command that is not a processing request is not one`() {
        val other = SessionCommand("com.motionflow.player.OTHER", Bundle())

        assertNull(ProcessingSessionContract.requestFor(other))
    }

    @Test
    fun `the two actions are distinct, so one cannot be mistaken for the other`() {
        val enable = ProcessingSessionContract.commandFor(ProcessingRequest.ENABLE)
        val disable = ProcessingSessionContract.commandFor(ProcessingRequest.DISABLE)

        assertNotEquals(enable.customAction, disable.customAction)
    }

    @Test
    fun `a confirmed attachment and a confirmed detach are both successes`() {
        assertEquals(
            SessionResult.RESULT_SUCCESS,
            ProcessingSessionContract.encode(ProcessingResult(ProcessingOutcome.ATTACHED)).resultCode,
        )
        assertEquals(
            SessionResult.RESULT_SUCCESS,
            ProcessingSessionContract.encode(ProcessingResult.Detached).resultCode,
        )
    }

    @Test
    fun `a refusal is reported as unsupported rather than as a failure`() {
        assertEquals(
            SessionResult.RESULT_ERROR_NOT_SUPPORTED,
            ProcessingSessionContract.encode(
                ProcessingResult.refused(ProcessingReason.EFFECTS_MODULE_ABSENT),
            ).resultCode,
        )
    }

    @Test
    fun `an action this contract does not define is refused in the same code as a refusal`() {
        // The zero-argument form the callback answers an unknown action with. It has to be the same
        // result code as a refusal, or a client would read a malformed request as a transport failure.
        val unsupported = ProcessingSessionContract.unsupportedResult()

        assertEquals(SessionResult.RESULT_ERROR_NOT_SUPPORTED, unsupported.resultCode)
        assertEquals(
            ProcessingResult.refused(ProcessingReason.REQUEST_REFUSED),
            ProcessingSessionContract.decode(
                resultCode = unsupported.resultCode,
                outcome = null,
                reason = null,
            ),
        )
    }

    @Test
    fun `a success decodes to the outcome it names`() {
        assertEquals(
            ProcessingResult(ProcessingOutcome.ATTACHED),
            ProcessingSessionContract.decode(
                resultCode = SessionResult.RESULT_SUCCESS,
                outcome = ProcessingOutcome.ATTACHED.name,
                reason = null,
            ),
        )
        assertEquals(
            ProcessingResult.Detached,
            ProcessingSessionContract.decode(
                resultCode = SessionResult.RESULT_SUCCESS,
                outcome = ProcessingOutcome.DETACHED.name,
                reason = null,
            ),
        )
    }

    @Test
    fun `a success with no readable outcome is not an answer`() {
        val missing = ProcessingSessionContract.decode(
            resultCode = SessionResult.RESULT_SUCCESS,
            outcome = null,
            reason = null,
        )
        val unknown = ProcessingSessionContract.decode(
            resultCode = SessionResult.RESULT_SUCCESS,
            outcome = "SOMETHING_FROM_A_LATER_VERSION",
            reason = null,
        )

        assertEquals(
            ProcessingResult.unreachable(ProcessingReason.TRANSPORT_FAILED),
            missing,
        )
        assertEquals(
            ProcessingResult.unreachable(ProcessingReason.TRANSPORT_FAILED),
            unknown,
        )
    }

    @Test
    fun `a refusal carries the player's reason through`() {
        assertEquals(
            ProcessingResult.refused(ProcessingReason.EFFECTS_MODULE_ABSENT),
            ProcessingSessionContract.decode(
                resultCode = SessionResult.RESULT_ERROR_NOT_SUPPORTED,
                outcome = ProcessingOutcome.REFUSED.name,
                reason = ProcessingReason.EFFECTS_MODULE_ABSENT.name,
            ),
        )
    }

    @Test
    fun `a refusal with no reason still says what happened`() {
        val withoutReason = ProcessingSessionContract.decode(
            resultCode = SessionResult.RESULT_ERROR_NOT_SUPPORTED,
            outcome = ProcessingOutcome.REFUSED.name,
            reason = null,
        )
        val unknownReason = ProcessingSessionContract.decode(
            resultCode = SessionResult.RESULT_ERROR_NOT_SUPPORTED,
            outcome = ProcessingOutcome.REFUSED.name,
            reason = "SOMETHING_FROM_A_LATER_VERSION",
        )

        assertEquals(ProcessingResult.refused(ProcessingReason.REQUEST_REFUSED), withoutReason)
        assertEquals(ProcessingResult.refused(ProcessingReason.REQUEST_REFUSED), unknownReason)
    }

    @Test
    fun `an answer the session layer produced is reported as unreachable`() {
        // A code the player's own handler never returns is a request that did not get through, so it
        // must not be read as a decision about the rendering path.
        listOf(
            SessionResult.RESULT_ERROR_INVALID_STATE,
            SessionResult.RESULT_ERROR_UNKNOWN,
        ).forEach { code ->
            assertEquals(
                "code $code",
                ProcessingResult.unreachable(ProcessingReason.TRANSPORT_FAILED),
                ProcessingSessionContract.decode(resultCode = code, outcome = null, reason = null),
            )
        }
    }

    @Test
    fun `every answer a player can produce survives the trip out and back`() {
        // Only outcomes the player's own handler can return. `UNREACHABLE` is not one of them: it
        // describes a request that never arrived, so it is decided on the client and never travels —
        // and if it were sent, a refusal and an unreachable answer would be the same bits, which is
        // exactly the confusion the separate outcomes exist to prevent.
        val answers = listOf(
            ProcessingResult(ProcessingOutcome.ATTACHED),
            ProcessingResult.Detached,
            ProcessingResult.refused(ProcessingReason.EFFECTS_MODULE_ABSENT),
            ProcessingResult.refused(ProcessingReason.NO_STAGE_IMPLEMENTED),
        )

        answers.forEach { answer ->
            val encoded = ProcessingSessionContract.encode(answer)
            val decoded = ProcessingSessionContract.decode(
                resultCode = encoded.resultCode,
                outcome = answer.outcome.name,
                reason = answer.reason?.name,
            )
            assertEquals(answer, decoded)
        }
    }

    @Test
    fun `an attached answer and a detached one are told apart by the outcome, not the code`() {
        // Both are successes, so the outcome name is the only thing that distinguishes them. If the
        // codec ever dropped it, an attachment would read as a detachment.
        val attached = ProcessingSessionContract.encode(ProcessingResult(ProcessingOutcome.ATTACHED))
        val detached = ProcessingSessionContract.encode(ProcessingResult.Detached)

        assertEquals(attached.resultCode, detached.resultCode)
        assertEquals(
            ProcessingResult(ProcessingOutcome.ATTACHED),
            ProcessingSessionContract.decode(attached.resultCode, ProcessingOutcome.ATTACHED.name, null),
        )
        assertEquals(
            ProcessingResult.Detached,
            ProcessingSessionContract.decode(detached.resultCode, ProcessingOutcome.DETACHED.name, null),
        )
    }
}
