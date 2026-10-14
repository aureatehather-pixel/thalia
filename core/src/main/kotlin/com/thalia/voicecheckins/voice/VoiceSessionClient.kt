package com.thalia.voicecheckins.voice

import kotlinx.coroutines.flow.Flow

/**
 * The seam between Thalia and whichever service actually talks.
 *
 * Gemini Live is the intended first real implementation, but nothing above this
 * interface may know that. Only concepts the product owns appear here: a check-in, a
 * transcript, a state machine, a result. No audio buffers, no sockets, no keys.
 *
 * Implementations must not require Android. Microphone capture and playback live inside
 * a provider implementation in the Android module; this contract stays plain Kotlin so
 * it can be exercised in ordinary JVM unit tests and in Compose previews.
 */
interface VoiceSessionClient {
    /**
     * Prepare one conversation. Nothing happens until [VoiceSessionHandle.events] is
     * collected, so the caller controls when the session actually starts.
     */
    fun start(config: VoiceSessionConfig): VoiceSessionHandle
}

/**
 * A running, or about to run, conversation.
 *
 * The handle exists so the UI has something to hang End Call on. Cancelling the
 * collecting coroutine would also stop the session, but it would discard the transcript
 * and produce no result, which is the wrong behaviour for a deliberate hang-up.
 */
interface VoiceSessionHandle {
    /**
     * Cold stream of session events, ending in [VoiceSessionEvent.Finished].
     *
     * Collect exactly once.
     */
    val events: Flow<VoiceSessionEvent>

    /**
     * Ask the session to stop and settle as [VoiceSessionResult.Cancelled].
     *
     * Safe to call at any time, including before collection starts and after the session
     * has already finished. Repeat calls are ignored.
     */
    fun endSession(reason: CancellationReason = CancellationReason.ENDED_BY_USER)
}
