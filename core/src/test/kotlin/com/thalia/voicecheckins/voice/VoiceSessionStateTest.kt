package com.thalia.voicecheckins.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceSessionStateTest {
    @Test
    fun `a session can only begin by connecting`() {
        assertEquals(setOf(VoiceSessionState.CONNECTING), VoiceSessionState.IDLE.allowedNext)
    }

    @Test
    fun `connecting may fail without ever becoming active`() {
        assertTrue(VoiceSessionState.CONNECTING.canTransitionTo(VoiceSessionState.ENDED))
        assertTrue(VoiceSessionState.CONNECTING.canTransitionTo(VoiceSessionState.ACTIVE))
    }

    @Test
    fun `ended is terminal`() {
        assertTrue(VoiceSessionState.ENDED.isTerminal)
        assertEquals(emptySet(), VoiceSessionState.ENDED.allowedNext)

        VoiceSessionState.entries.forEach { target ->
            assertFalse(
                VoiceSessionState.ENDED.canTransitionTo(target),
                "ENDED must not reach $target",
            )
        }
    }

    @Test
    fun `a session never moves backwards`() {
        assertFalse(VoiceSessionState.ACTIVE.canTransitionTo(VoiceSessionState.CONNECTING))
        assertFalse(VoiceSessionState.ACTIVE.canTransitionTo(VoiceSessionState.IDLE))
        assertFalse(VoiceSessionState.ENDING.canTransitionTo(VoiceSessionState.ACTIVE))
    }

    @Test
    fun `a session never stays put`() {
        VoiceSessionState.entries.forEach { state ->
            assertFalse(state.canTransitionTo(state), "$state must not transition to itself")
        }
    }

    @Test
    fun `only ended is reachable from ending`() {
        assertEquals(setOf(VoiceSessionState.ENDED), VoiceSessionState.ENDING.allowedNext)
    }
}
