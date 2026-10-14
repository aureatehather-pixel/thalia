package com.thalia.voicecheckins.domain

/**
 * What became of a single scheduled check-in.
 *
 * This is the record-keeping status of an occurrence, not the live state of a voice
 * session. The live state lives in [com.thalia.voicecheckins.voice.VoiceSessionState].
 */
enum class CheckInStatus {
    /** Placed on the calendar, not yet due. */
    SCHEDULED,

    /** Due now, the phone is presenting the incoming-call surface. */
    RINGING,

    /** Answered, a voice session is running. */
    ANSWERED,

    /**
     * Answered and the call interaction ended normally. That is all it says: for a call handed to the text conversation
     * (Slice 4C) it does not mean the day was opened or closed, a plan was accepted or any outcome was reconciled.
     */
    COMPLETED,

    /** The user actively declined the incoming call. */
    DECLINED,

    /** Rang out with no answer, or never rang because the device was unavailable. */
    MISSED,

    /** Answered, but the session could not run or broke mid-conversation. */
    FAILED,

    /** Withdrawn before ringing, for example because the user rescheduled it. */
    CANCELLED,

    /**
     * Withheld by Thalia itself when it came due, without ringing and without anything shown: a quiet
     * period covered it, or another call already had the phone. A system decision, not a missed call.
     */
    SUPPRESSED,
    ;

    /** True once no further transition is expected for this occurrence. */
    val isTerminal: Boolean
        get() = this in TERMINAL

    /**
     * The statuses this one may legally become.
     *
     * The whole life of an occurrence is here. It is scheduled, then rings, then is answered or
     * declined or missed, and an answered one ends completed or failed. A scheduled occurrence
     * can also end without ever ringing: missed, cancelled or suppressed. Nothing leaves a
     * terminal status, and nothing skips a step: a call cannot be answered without ringing, and
     * cannot be completed without being answered.
     */
    val legalNext: Set<CheckInStatus>
        get() =
            when (this) {
                SCHEDULED -> setOf(RINGING, MISSED, CANCELLED, SUPPRESSED)
                RINGING -> setOf(ANSWERED, DECLINED, MISSED)
                ANSWERED -> setOf(COMPLETED, FAILED)
                COMPLETED, DECLINED, MISSED, FAILED, CANCELLED, SUPPRESSED -> emptySet()
            }

    /** Whether an occurrence in this status may become [next]. */
    fun canTransitionTo(next: CheckInStatus): Boolean = next in legalNext

    /** True for the statuses that must say why they ended, because the ending needs explaining. */
    val needsReason: Boolean
        get() = this == MISSED || this == CANCELLED || this == FAILED || this == SUPPRESSED

    private companion object {
        val TERMINAL = setOf(COMPLETED, DECLINED, MISSED, FAILED, CANCELLED, SUPPRESSED)
    }
}
