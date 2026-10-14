package com.thalia.voicecheckins.gemini

import kotlinx.coroutines.flow.Flow

/**
 * What the Gemini client needs from the platform's microphone and speaker.
 *
 * The client stays plain Kotlin; the Android implementation, on `AudioRecord` and `AudioTrack`,
 * is a later M3A slice in `:app`'s debug source set. Audio focus belongs to that implementation:
 * when focus is lost, the app ends the session with
 * [com.thalia.voicecheckins.voice.CancellationReason.ENDED_BY_SYSTEM].
 */
interface AudioIo {
    /**
     * Open the microphone and playback for one session.
     *
     * Called only after Gemini has confirmed the session, so a refused or unreachable connection
     * never opens the microphone. Throwing, for any reason, ends the session as
     * [com.thalia.voicecheckins.voice.FailureReason.AUDIO_UNAVAILABLE].
     */
    fun open(): AudioSession
}

/** The microphone and playback of one session. */
interface AudioSession {
    /**
     * Microphone audio: 16 kHz, mono, 16-bit little-endian PCM, in chunks.
     *
     * Collected once, and cancelled when the session ends. It must respond to cancellation
     * promptly. A failure, or completing while the session is still live, ends the session as
     * [com.thalia.voicecheckins.voice.FailureReason.AUDIO_UNAVAILABLE].
     */
    val capture: Flow<ByteArray>

    /** Queue model audio, 24 kHz, mono, 16-bit little-endian PCM. Must not block for long. */
    fun play(pcm: ByteArray)

    /** Stop what is playing and discard everything queued. Used when the user talks over the model. */
    fun flushPlayback()

    /**
     * Release the microphone and playback.
     *
     * Called once per opened session, after capture has stopped, on every way a session ends that
     * the process survives. Process death is not one of them.
     */
    fun release()
}

/** The microphone or playback could not be used. */
class AudioUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
