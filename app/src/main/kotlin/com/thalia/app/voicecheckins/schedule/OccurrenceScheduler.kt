package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import java.time.Instant

/**
 * Schedules an occurrence: writes it down, then arms its alarm.
 *
 * The order is the point. The record comes first, so an alarm can never exist for an occurrence
 * that is not recorded. If the process dies between the two steps the record is left without an
 * alarm, and the next reconciliation either arms it or, if its time has passed, marks it missed.
 * The other order would leave an alarm that rings a call with nothing to say what it was.
 *
 * An occurrence that could not be armed is not left claiming to be scheduled. It is cancelled
 * with the reason, so the stored record stays true.
 */
class OccurrenceScheduler(
    private val store: OccurrenceStore,
    private val alarms: AlarmScheduler,
    private val clock: () -> Long,
) {
    /** Record [occurrence] and arm its alarm. It must be a new, scheduled occurrence. */
    suspend fun schedule(occurrence: ScheduledOccurrence): ScheduleResult {
        require(occurrence.status == CheckInStatus.SCHEDULED) { "only a scheduled occurrence can be scheduled, got ${occurrence.status}" }

        if (!store.insert(occurrence)) return ScheduleResult.AlreadyRecorded

        val result = alarms.schedule(occurrence)
        val refusal =
            when (result) {
                is ScheduleResult.Scheduled, ScheduleResult.AlreadyRecorded -> null
                ScheduleResult.NotPermitted -> OccurrenceReason.NOT_PERMITTED
                ScheduleResult.NotInTheFuture -> OccurrenceReason.NOT_IN_THE_FUTURE
            }
        if (refusal != null) cancelRecord(occurrence.id, refusal)
        return result
    }

    /**
     * Withdraw a scheduled occurrence: cancel its record, then disarm its alarm. True if the record
     * was still scheduled and is now cancelled, false if it had already rung or ended or never
     * existed. The alarm is disarmed either way.
     *
     * The record goes first. If the process dies between the two steps the alarm is left armed for a
     * cancelled occurrence, which loses the claim and rings nothing. The other order could leave a
     * scheduled record with no alarm, which the next reconciliation would arm again.
     */
    suspend fun cancel(
        occurrenceId: String,
        reason: OccurrenceReason = OccurrenceReason.USER_CANCELLED,
    ): Boolean {
        val cancelled = cancelRecord(occurrenceId, reason)
        alarms.cancel(occurrenceId)
        return cancelled
    }

    /**
     * Move [old], which must be the slot's scheduled occurrence, to [replacement], its next revision.
     *
     * The order is the point. Whether the move is possible is settled first, without changing
     * anything. Then the old occurrence is retired and the new one recorded in one step, so there is
     * never a moment with two eligible occurrences or none. Only then is the old alarm disarmed and
     * the new one armed. A stale alarm for the old occurrence that fires in between, or after a
     * crash, loses the claim on a cancelled record and rings nothing.
     *
     * If the phone loses the ability to arm between the check and the arming, the new occurrence is
     * cancelled with the reason and the slot is left with none, which is true and can be confirmed
     * again.
     */
    suspend fun reschedule(
        old: ScheduledOccurrence,
        replacement: ScheduledOccurrence,
    ): RescheduleResult {
        require(replacement.slot == old.slot && replacement.revision == old.revision + 1) {
            "${replacement.id} is not the next revision of ${old.id}"
        }
        if (!alarms.canScheduleExactAlarms()) return RescheduleResult.NotPermitted
        if (replacement.scheduledAt.toEpochMilli() <= clock()) return RescheduleResult.NotInTheFuture

        if (!store.supersede(old.id, replacement, Instant.ofEpochMilli(clock()))) return RescheduleResult.NotScheduled

        alarms.cancel(old.id)
        return when (alarms.schedule(replacement)) {
            is ScheduleResult.Scheduled, ScheduleResult.AlreadyRecorded -> RescheduleResult.Rescheduled(replacement)
            ScheduleResult.NotPermitted -> {
                cancelRecord(replacement.id, OccurrenceReason.NOT_PERMITTED)
                RescheduleResult.NotPermitted
            }
            ScheduleResult.NotInTheFuture -> {
                cancelRecord(replacement.id, OccurrenceReason.NOT_IN_THE_FUTURE)
                RescheduleResult.NotInTheFuture
            }
        }
    }

    private suspend fun cancelRecord(
        occurrenceId: String,
        reason: OccurrenceReason,
    ): Boolean =
        store.transition(
            id = occurrenceId,
            from = CheckInStatus.SCHEDULED,
            to = CheckInStatus.CANCELLED,
            at = Instant.ofEpochMilli(clock()),
            reason = reason,
        )
}

/** What became of a request to move a scheduled occurrence to another time. */
sealed interface RescheduleResult {
    /** [occurrence] is now the slot's scheduled occurrence and its alarm is armed. */
    data class Rescheduled(
        val occurrence: ScheduledOccurrence,
    ) : RescheduleResult

    /** The old occurrence was no longer scheduled, because it is ringing, ended or gone. Nothing changed. */
    data object NotScheduled : RescheduleResult

    /** Android is not allowing exact alarms. If this came before the move, nothing changed. */
    data object NotPermitted : RescheduleResult

    /** The new time is not in the future. If this came before the move, nothing changed. */
    data object NotInTheFuture : RescheduleResult
}
