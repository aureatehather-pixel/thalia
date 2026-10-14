package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import com.thalia.voicecheckins.domain.nextRevisionAt
import com.thalia.voicecheckins.domain.occurrenceAt
import com.thalia.voicecheckins.domain.occurrenceOn
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Confirms, moves and cancels one opening or closing check-in on one day. A check-in rings only
 * because a confirmed occurrence was recorded and armed for that day. The stored plan supplies a
 * time, never a call.
 *
 * ### The rules
 *
 * A day and a type make a slot, and a slot keeps a history of occurrences, oldest first, called
 * revisions. Nothing is ever deleted or overwritten. Exactly one revision at a time can be eligible,
 * meaning scheduled, ringing or in a call, and the newest revision is the one that counts.
 *
 * | The slot's newest revision | Confirm | Move to a time | Cancel |
 * | --- | --- | --- | --- |
 * | none | records revision 1 | records revision 1 | nothing to cancel |
 * | scheduled | already confirmed | retires it as superseded and records the next revision, or does nothing if the time is the same | cancels it |
 * | ringing or answered | in progress | in progress | in progress |
 * | ended without ever ringing (cancelled, suppressed, or missed unrung) | records the next revision | records the next revision | nothing to cancel |
 * | ended after ringing (answered, declined, completed, failed, missed after ringing) | already confirmed | already confirmed | nothing to cancel |
 *
 * A slot gets at most one call, so a day that rang cannot be confirmed again. A day that never rang
 * can, and its history stays.
 *
 * Every action is safe to repeat. A repeated confirm finds the live revision. A repeated move to the
 * same time changes nothing. A repeated cancel finds nothing to cancel. Nothing is recorded for a
 * refusal known beforehand: a slot switched off, a time inside the quiet period, or a time not in
 * the future. The quiet period is checked again when the alarm arrives, so changing it after a day is
 * confirmed still keeps that call quiet.
 *
 * A stale alarm cannot ring a retired revision. Each revision has its own id and its own alarm, and
 * an alarm can only claim an occurrence that is still scheduled, so the alarm of a cancelled or
 * superseded revision claims nothing.
 */
class CheckInConfirmer(
    private val store: OccurrenceStore,
    private val scheduler: OccurrenceScheduler,
    private val clock: () -> Long,
) {
    /** Confirm the slot at the plan's time. Does nothing if the slot already has a live or used-up call. */
    suspend fun confirm(
        type: CheckInType,
        date: LocalDate,
    ): ConfirmResult = place(type, date, time = null)

    /**
     * Put the slot at [time], moving a scheduled revision or recording a new one where the slot has
     * none live and none used up.
     */
    suspend fun reschedule(
        type: CheckInType,
        date: LocalDate,
        time: LocalTime,
    ): ConfirmResult = place(type, date, time)

    /** Withdraw the slot's scheduled revision. The day can be confirmed again afterwards. */
    suspend fun cancel(
        type: CheckInType,
        date: LocalDate,
    ): CancelResult {
        val latest = latest(type, date) ?: return CancelResult.NothingToCancel
        if (!latest.isEligible) return CancelResult.NothingToCancel
        if (latest.status != CheckInStatus.SCHEDULED) return CancelResult.InProgress(latest.status)

        if (scheduler.cancel(latest.id)) return CancelResult.Cancelled(latest)
        // It moved between the read and the cancel: the alarm rang, or another action got there first.
        val now = store.get(latest.id)?.status
        return if (now == null || now.isTerminal) CancelResult.NothingToCancel else CancelResult.InProgress(now)
    }

    private suspend fun latest(
        type: CheckInType,
        date: LocalDate,
    ): ScheduledOccurrence? = store.slot(store.plan()[type].occurrenceOn(date).slot).lastOrNull()

    private suspend fun place(
        type: CheckInType,
        date: LocalDate,
        time: LocalTime?,
    ): ConfirmResult {
        val schedule = store.plan()[type]
        if (!schedule.enabled) return ConfirmResult.SlotOff

        val target = time ?: schedule.localTime
        val latest = latest(type, date)

        return when {
            latest == null -> create(schedule.occurrenceAt(date, target, revision = 1))

            latest.isEligible ->
                when {
                    latest.status != CheckInStatus.SCHEDULED -> ConfirmResult.InProgress(latest.status)
                    time == null -> ConfirmResult.AlreadyConfirmed(latest.status)
                    latest.localDateTime.toLocalTime() == time -> ConfirmResult.Unchanged(latest)
                    else -> move(latest, latest.nextRevisionAt(time))
                }

            latest.isRecoverable -> create(schedule.occurrenceAt(date, target, revision = latest.revision + 1))

            else -> ConfirmResult.AlreadyConfirmed(latest.status)
        }
    }

    private suspend fun create(occurrence: ScheduledOccurrence): ConfirmResult {
        if (isQuiet(occurrence)) return ConfirmResult.InQuietPeriod(occurrence)
        if (!occurrence.scheduledAt.isAfter(Instant.ofEpochMilli(clock()))) return ConfirmResult.TimePassed(occurrence)

        return when (scheduler.schedule(occurrence)) {
            is ScheduleResult.Scheduled -> ConfirmResult.Confirmed(occurrence)
            ScheduleResult.NotPermitted -> ConfirmResult.NotPermitted
            // The moment arrived between the check above and the arming.
            ScheduleResult.NotInTheFuture -> ConfirmResult.TimePassed(occurrence)
            // Another action recorded this revision between the read and the insert.
            ScheduleResult.AlreadyRecorded -> ConfirmResult.AlreadyConfirmed(CheckInStatus.SCHEDULED)
        }
    }

    /** Whether the stored quiet period covers [occurrence]'s wall-clock time. */
    private suspend fun isQuiet(occurrence: ScheduledOccurrence): Boolean =
        store.quietPeriod().covers(occurrence.localDateTime.toLocalTime())

    private suspend fun move(
        old: ScheduledOccurrence,
        replacement: ScheduledOccurrence,
    ): ConfirmResult =
        if (isQuiet(replacement)) {
            ConfirmResult.InQuietPeriod(replacement)
        } else {
            reschedule(old, replacement)
        }

    private suspend fun reschedule(
        old: ScheduledOccurrence,
        replacement: ScheduledOccurrence,
    ): ConfirmResult =
        when (scheduler.reschedule(old, replacement)) {
            is RescheduleResult.Rescheduled -> ConfirmResult.Rescheduled(replacement)
            RescheduleResult.NotPermitted -> ConfirmResult.NotPermitted
            RescheduleResult.NotInTheFuture -> ConfirmResult.TimePassed(replacement)
            // It rang, ended or was moved by someone else between the read and the move.
            RescheduleResult.NotScheduled -> ConfirmResult.InProgress(store.get(old.id)?.status ?: old.status)
        }
}

/** What became of a request to confirm or move one check-in. */
sealed interface ConfirmResult {
    /** Recorded and armed. It will ring at [occurrence]'s time. */
    data class Confirmed(
        val occurrence: ScheduledOccurrence,
    ) : ConfirmResult

    /** The slot's scheduled revision was retired and [occurrence] recorded and armed in its place. */
    data class Rescheduled(
        val occurrence: ScheduledOccurrence,
    ) : ConfirmResult

    /** The slot is already at the requested time. Nothing changed. */
    data class Unchanged(
        val occurrence: ScheduledOccurrence,
    ) : ConfirmResult

    /** The slot already has a live or used-up call, in [status]. Nothing changed and nothing was armed. */
    data class AlreadyConfirmed(
        val status: CheckInStatus,
    ) : ConfirmResult

    /** The slot's call is ringing or in progress, in [status], so it cannot be moved. Nothing changed. */
    data class InProgress(
        val status: CheckInStatus,
    ) : ConfirmResult

    /** The plan has this slot switched off. Nothing was recorded. */
    data object SlotOff : ConfirmResult

    /**
     * [occurrence]'s time falls inside the quiet period. Nothing was recorded, and a scheduled
     * revision was not moved.
     */
    data class InQuietPeriod(
        val occurrence: ScheduledOccurrence,
    ) : ConfirmResult

    /** The requested time is not in the future. Nothing rings late, and a scheduled revision was not moved. */
    data class TimePassed(
        val occurrence: ScheduledOccurrence,
    ) : ConfirmResult

    /**
     * Android is not allowing exact alarms. Before a move that is checked first and nothing changes.
     * For a new revision it was recorded and cancelled for that reason, and the day can be confirmed again.
     */
    data object NotPermitted : ConfirmResult
}

/** What became of a request to cancel one check-in. */
sealed interface CancelResult {
    /** [occurrence] was scheduled and is now cancelled, with its alarm disarmed. */
    data class Cancelled(
        val occurrence: ScheduledOccurrence,
    ) : CancelResult

    /** The slot has no scheduled revision: it was never confirmed, or its call already ended. */
    data object NothingToCancel : CancelResult

    /** The slot's call is ringing or in progress, in [status], so it cannot be cancelled. */
    data class InProgress(
        val status: CheckInStatus,
    ) : CancelResult
}
