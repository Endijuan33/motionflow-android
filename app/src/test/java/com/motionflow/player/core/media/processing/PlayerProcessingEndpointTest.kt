package com.motionflow.player.core.media.processing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the player-side end of the request path.
 *
 * The endpoint is the only processing code beside the player, so its answers are what the whole
 * feature rests on, and they are deliberately boring: refuse, name the reason, change nothing.
 */
class PlayerProcessingEndpointTest {

    @Test
    fun `an enable request is refused because this build has no effects module`() {
        val endpoint = PlayerProcessingEndpoint()

        val result = endpoint.onRequest(ProcessingRequest.ENABLE)

        assertEquals(ProcessingOutcome.REFUSED, result.outcome)
        assertEquals(ProcessingReason.EFFECTS_MODULE_ABSENT, result.reason)
        assertTrue("a refusal never reports an attachment", !result.attached)
    }

    @Test
    fun `with the module linked, an enable is refused for the missing stage instead`() {
        val endpoint = PlayerProcessingEndpoint(effectsModuleLinked = true)

        val result = endpoint.onRequest(ProcessingRequest.ENABLE)

        assertEquals(ProcessingOutcome.REFUSED, result.outcome)
        assertEquals(
            "the reason names the next thing to build, not the last one to fix",
            ProcessingReason.NO_STAGE_IMPLEMENTED,
            result.reason,
        )
    }

    @Test
    fun `a disable request confirms the state that already holds`() {
        val endpoint = PlayerProcessingEndpoint()

        val result = endpoint.onRequest(ProcessingRequest.DISABLE)

        assertEquals(ProcessingOutcome.DETACHED, result.outcome)
        assertNull("there is nothing to explain about the native path", result.reason)
        assertTrue(result.detached)
    }

    @Test
    fun `the request cannot make an effect appear`() {
        val endpoint = PlayerProcessingEndpoint()

        repeat(5) {
            assertTrue(!endpoint.onRequest(ProcessingRequest.ENABLE).attached)
        }
    }

    @Test
    fun `an enable followed by a disable leaves the player on the native path`() {
        val endpoint = PlayerProcessingEndpoint()

        endpoint.onRequest(ProcessingRequest.ENABLE)
        val result = endpoint.onRequest(ProcessingRequest.DISABLE)

        assertEquals(ProcessingOutcome.DETACHED, result.outcome)
        assertTrue(result.detached)
    }

    @Test
    fun `repeating a disable is still the native path, not a failure`() {
        val endpoint = PlayerProcessingEndpoint()

        repeat(3) {
            val result = endpoint.onRequest(ProcessingRequest.DISABLE)
            assertEquals(ProcessingOutcome.DETACHED, result.outcome)
            assertNull(result.reason)
        }
    }
}
