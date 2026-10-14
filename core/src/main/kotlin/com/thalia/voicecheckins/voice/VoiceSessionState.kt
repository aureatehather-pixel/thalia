package com.thalia.voicecheckins.voice

/**
 * The live state of one answered conversation.
 *
 * This starts *after* the user answers. Ringing, answering and declining are Android
 * call-surface concerns and are recorded on the occurrence as a
 * [com.thalia.voicecheckins.domain.CheckInStatus], not here. Keeping the two apart is
 * what lets the voice provider be swapped without touching the call UI.
 */
enum class VoiceSessionState {
    /** Nothing has happened yet. */
    IDLE,

    /** Reaching the provider. No audio is flowing. */
    CONNECTING,

    /** Conversation in progress. */
    ACTIVE,

    /** Winding down: the last audio is draining, the result is not final yet. */
    ENDING,

    /** Terminal. A [VoiceSessionResult] has been produced. */
    ENDED,
    ;

    /** States reachable in one step from this one. */
    val allowedNext: Set<VoiceSessionState>
        get() =
            when (this) {
                IDLE -> setOf(CONNECTING)
                // A connection can fail before it ever becomes a conversation.
                CONNECTING -> setOf(ACTIVE, ENDING, ENDED)
                ACTIVE -> setOf(ENDING, ENDED)
                ENDING -> setOf(ENDED)
                ENDED -> emptySet()
            }

    fun canTransitionTo(next: VoiceSessionState): Boolean = next in allowedNext

    val isTerminal: Boolean get() = this == ENDED
}
