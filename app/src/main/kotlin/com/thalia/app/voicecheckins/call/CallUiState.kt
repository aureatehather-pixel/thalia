package com.thalia.app.voicecheckins.call

import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.voice.TranscriptLine
import com.thalia.voicecheckins.voice.VoiceSessionState

/**
 * What the call surface is showing.
 *
 * This is the Android-side view of a check-in, and it covers the whole arc the user
 * sees: ringing, talking, over. The provider's own lifecycle is the narrower
 * [VoiceSessionState] inside [Active], and it exists only once the call is answered.
 */
sealed interface CallUiState {
    /** No check-in in progress. The service should not be running. */
    data object Idle : CallUiState

    /** The phone is ringing. Nothing has been answered and no session exists yet. */
    data class Ringing(
        val checkInType: CheckInType,
        val callerName: String = THALIA,
        /** Slice 4C: answering this call hands off to the conversation screen instead of starting a simulated session. */
        val handoff: Boolean = false,
    ) : CallUiState

    /** Answered. A session is running and the transcript is filling in. */
    data class Active(
        val checkInType: CheckInType,
        val sessionState: VoiceSessionState,
        val transcript: List<TranscriptLine>,
        val callerName: String = THALIA,
    ) : CallUiState

    /** Over, one way or another. Held briefly so the user sees how it ended. */
    data class Ended(
        val checkInType: CheckInType,
        val status: CheckInStatus,
        val summary: String,
        val transcript: List<TranscriptLine>,
    ) : CallUiState

    companion object {
        const val THALIA: String = "Thalia"
    }
}

/**
 * What the foreground service should be presenting for a call state.
 *
 * The service exists to carry a call, so [None] is also the answer to "should the
 * service still be running". Only ringing and active calls need it; a finished call is
 * shown by the activity and needs no process protection.
 */
internal sealed interface CallPresentation {
    /** Nothing to carry. The service should not be running. */
    data object None : CallPresentation

    /** The phone is ringing: incoming-call notification, ringtone and vibration. */
    data class Ringing(
        val checkInType: CheckInType,
    ) : CallPresentation

    /** An answered call: ongoing-call notification, silent. */
    data class Ongoing(
        val checkInType: CheckInType,
    ) : CallPresentation

    /**
     * A development test call is armed and will ring at [fireAtMillis]. Silent, and only
     * ever shown when no call is ringing or active, which take precedence.
     */
    data class Armed(
        val checkInType: CheckInType,
        val fireAtMillis: Long,
    ) : CallPresentation
}

internal fun CallUiState.toPresentation(): CallPresentation =
    when (this) {
        is CallUiState.Ringing -> CallPresentation.Ringing(checkInType)
        is CallUiState.Active -> CallPresentation.Ongoing(checkInType)
        is CallUiState.Ended, CallUiState.Idle -> CallPresentation.None
    }

/** True while the foreground service is entitled to run. */
val CallUiState.needsForegroundService: Boolean
    get() = toPresentation() != CallPresentation.None
