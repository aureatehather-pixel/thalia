package com.thalia.voicecheckins.voice

import com.thalia.voicecheckins.domain.CheckInType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Everything a provider needs to run one check-in conversation.
 *
 * Note what is absent: no API key, no endpoint, no model name, no audio format. Those
 * are per-provider configuration and belong to the provider's own construction, not to
 * this call. That absence is what keeps the contract swappable.
 */
data class VoiceSessionConfig(
    /** The occurrence this conversation belongs to. Ties the transcript to a day and slot. */
    val occurrenceId: String,
    val checkInType: CheckInType,
    /** BCP-47 tag used for speech in and out. */
    val locale: String = "en-US",
    /** Hard ceiling. A check-in that will not end is worse than one that ends early. */
    val maxDuration: Duration = 5.minutes,
    /**
     * Plain-language context handed to the provider: current goals, today's schedule,
     * what the last check-in said. Assembled by the caller; M4 gives it real content.
     */
    val contextNotes: List<String> = emptyList(),
) {
    init {
        require(occurrenceId.isNotBlank()) { "occurrenceId must not be blank" }
        require(maxDuration > Duration.ZERO) { "maxDuration must be positive, got $maxDuration" }
    }
}
