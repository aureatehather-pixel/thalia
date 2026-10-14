package com.thalia.voicecheckins.voice

import com.thalia.voicecheckins.domain.CheckInStatus

/** Why a session stopped without finishing on its own terms. */
enum class CancellationReason {
    /** The user pressed End Call. */
    ENDED_BY_USER,

    /** Android or Thalia stopped it: an incoming phone call, a shutdown, a policy limit. */
    ENDED_BY_SYSTEM,

    /** [VoiceSessionConfig.maxDuration] was reached. */
    TIMED_OUT,
}

/** Why a session could not run. */
enum class FailureReason {
    NO_NETWORK,
    PROVIDER_UNAVAILABLE,
    NOT_AUTHENTICATED,
    AUDIO_UNAVAILABLE,
    UNKNOWN,
}

/**
 * How one conversation finished.
 *
 * Every outcome carries whatever transcript was captured before it ended, because a
 * half-finished check-in is still worth keeping.
 */
sealed interface VoiceSessionResult {
    val transcript: List<TranscriptLine>

    /** The conversation ran to its natural end. */
    data class Completed(
        override val transcript: List<TranscriptLine>,
    ) : VoiceSessionResult

    /** The conversation started but was stopped. */
    data class Cancelled(
        val reason: CancellationReason,
        override val transcript: List<TranscriptLine>,
    ) : VoiceSessionResult

    /** The conversation could not run, or broke partway. */
    data class Failed(
        val reason: FailureReason,
        val message: String,
        override val transcript: List<TranscriptLine>,
    ) : VoiceSessionResult
}

/**
 * How an answered call's outcome is written back onto its occurrence.
 *
 * A cancelled session still counts as COMPLETED: the user answered and chose to stop,
 * which is a check-in that happened, not one that failed. DECLINED and MISSED cannot
 * originate here, because both mean no session was ever started.
 */
fun VoiceSessionResult.toCheckInStatus(): CheckInStatus =
    when (this) {
        is VoiceSessionResult.Completed -> CheckInStatus.COMPLETED
        is VoiceSessionResult.Cancelled -> CheckInStatus.COMPLETED
        is VoiceSessionResult.Failed -> CheckInStatus.FAILED
    }
