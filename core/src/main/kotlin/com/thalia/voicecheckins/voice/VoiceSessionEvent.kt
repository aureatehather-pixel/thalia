package com.thalia.voicecheckins.voice

/**
 * What a running session reports as it goes.
 *
 * A session's event stream always ends with exactly one [Finished].
 */
sealed interface VoiceSessionEvent {
    /** A legal move in the [VoiceSessionState] machine just happened. */
    data class StateChanged(
        val from: VoiceSessionState,
        val to: VoiceSessionState,
    ) : VoiceSessionEvent

    /** A line settled. The active-call screen appends it. */
    data class TranscriptAppended(
        val line: TranscriptLine,
    ) : VoiceSessionEvent

    /** Terminal event. Always the last thing emitted. */
    data class Finished(
        val result: VoiceSessionResult,
    ) : VoiceSessionEvent
}
