package com.thalia.voicecheckins.domain

/**
 * Why an occurrence ended the way it did, for the statuses that need explaining.
 *
 * A reason states what Thalia observed, and no more. The ones that follow from something
 * Android did not tell us about, [ALARM_MISSING] and [OVERDUE], say so plainly and do not
 * guess a cause. In particular nothing here claims the user force-stopped the app: a force-stop,
 * a restart and a revoked permission all leave the same trace, an alarm that is not there.
 *
 * Every reason belongs to the statuses in [appliesTo]. A status that ends an occurrence
 * without one, [CheckInStatus.DECLINED] and [CheckInStatus.COMPLETED], carries none.
 */
enum class OccurrenceReason {
    /** It rang for the full ring timeout and nobody answered. */
    RING_TIMEOUT,

    /** Its alarm was delivered more than the lateness limit after its moment, so it did not ring. */
    TOO_LATE,

    /**
     * Its moment had come and Android had no alarm registered for it.
     *
     * The cause is not established. A restart of the phone, a force-stop and a revoked
     * exact-alarm permission all produce exactly this.
     */
    ALARM_MISSING,

    /**
     * Its moment passed by more than the lateness limit and it never rang, although an alarm
     * still appeared to be registered. The cause is not established.
     */
    OVERDUE,

    /** Android refused to start the call service, so it never rang. */
    START_REFUSED,

    /** It was ringing or in progress when whatever carried it was lost, such as the process. */
    INTERRUPTED,

    /** The call was answered and the voice session could not run or broke. */
    SESSION_FAILED,

    /**
     * Another call was already ringing or in progress, so this one did not ring.
     *
     * With [CheckInStatus.SUPPRESSED] it is the claim that refused it: nothing was started or shown.
     * With [CheckInStatus.MISSED] it had been claimed and its call service started before the call
     * already on the phone was found. Every scheduled call goes through the claim, so that can only
     * happen when a development-only call started by a button, which the store cannot see, raced it.
     */
    CALL_IN_PROGRESS,

    /** Its moment, or the moment its alarm arrived, fell inside the user's quiet period, so it did not ring. */
    QUIET_PERIOD,

    /** Its check-in slot was switched off before it rang. */
    PLAN_DISABLED,

    /** It could not be armed because Android was not allowing exact alarms. */
    NOT_PERMITTED,

    /** It could not be armed because its moment was not in the future. */
    NOT_IN_THE_FUTURE,

    /** It was withdrawn before it rang. The day may be confirmed again, as a new revision. */
    USER_CANCELLED,

    /** It was moved to another time before it rang. A newer revision of the same slot replaced it. */
    SUPERSEDED,
    ;

    /** Whether this reason can explain an occurrence that ended as [status]. */
    fun appliesTo(status: CheckInStatus): Boolean =
        when (status) {
            CheckInStatus.MISSED -> this in MISSED_REASONS
            CheckInStatus.CANCELLED -> this in CANCELLED_REASONS
            CheckInStatus.FAILED -> this in FAILED_REASONS
            CheckInStatus.SUPPRESSED -> this in SUPPRESSED_REASONS
            else -> false
        }

    private companion object {
        val MISSED_REASONS = setOf(RING_TIMEOUT, TOO_LATE, ALARM_MISSING, OVERDUE, START_REFUSED, INTERRUPTED, CALL_IN_PROGRESS)
        val CANCELLED_REASONS = setOf(PLAN_DISABLED, NOT_PERMITTED, NOT_IN_THE_FUTURE, USER_CANCELLED, SUPERSEDED)
        val FAILED_REASONS = setOf(SESSION_FAILED, INTERRUPTED)
        val SUPPRESSED_REASONS = setOf(QUIET_PERIOD, CALL_IN_PROGRESS)
    }
}
