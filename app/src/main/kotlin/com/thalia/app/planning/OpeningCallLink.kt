package com.thalia.app.planning

import com.thalia.app.planning.data.CallAuthorization
import com.thalia.app.planning.data.CallTarget
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.voicecheckins.schedule.CheckInConfirmer
import com.thalia.app.voicecheckins.schedule.ConfirmResult
import com.thalia.app.voicecheckins.schedule.OccurrenceScheduler
import com.thalia.app.voicecheckins.schedule.OccurrenceStore
import com.thalia.app.voicecheckins.schedule.ScheduleResult
import com.thalia.planning.ActiveDay
import com.thalia.voicecheckins.domain.CheckInSchedule
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import com.thalia.voicecheckins.domain.occurrenceAt
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** What the link last did for a plan's opening call. A log: it authorizes nothing, except that only REQUESTED is retried. */
internal enum class CallStatus { REQUESTED, CONFIRMED, REFUSED, NEEDS_ATTENTION, NOT_LINKED }

/** Why. Never free text, titles or M2's message. ADR 0009 slice 3 amendment. */
internal enum class CallDetail {
    SLOT_OFF,
    ZONE_CHANGED,
    TIME_NOT_ALLOWED,
    IN_QUIET_PERIOD,
    TIME_PASSED,
    PERMISSION_REFUSED,
    OTHER_TIME_CONFIRMED,
    CALL_CANCELLED,
    CALL_ENDED,
    CALL_IN_PROGRESS,
    M2_UNAVAILABLE,
}

internal data class CallVerdict(
    val status: CallStatus,
    val detail: CallDetail? = null,
)

/** The opening call of one active day: its time, in the plan's zone and with the plan's rollover. */
internal data class OpeningCall(
    val day: ActiveDay,
    val zone: ZoneId,
    val rollover: LocalTime,
    val time: LocalTime,
) {
    /** The calendar date of M2's slot, which is the active day's own date. */
    val date get() = day.date
}

/** What M2's slot for the day holds, as it matters to the link. Read, never written, by the classification. */
internal sealed interface SlotState {
    data object Empty : SlotState

    data object ScheduledAtTime : SlotState

    data class ScheduledElsewhere(
        val time: LocalTime,
    ) : SlotState

    /** Ended without ever ringing: cancelled, suppressed or missed unrung. The slot can take a new revision. */
    data object EndedUnrung : SlotState

    data object InProgress : SlotState

    /** Rang, or ended after ringing. One call per slot, so nothing is offered. */
    data object Ended : SlotState
}

/** What the user may explicitly ask for when the slot is not as planned. Offered only from a fresh classification. */
internal enum class Recovery { MOVE, NEW_CALL }

internal fun recoveryOf(state: SlotState): Recovery? =
    when (state) {
        is SlotState.ScheduledElsewhere -> Recovery.MOVE
        SlotState.EndedUnrung -> Recovery.NEW_CALL
        else -> null
    }

/** What the preview shows, read-only: the first precondition that fails, or else the slot's state. */
internal data class CallPreview(
    val refusal: CallDetail?,
    val slot: SlotState?,
)

/** What an explicit action did. Nothing was written to M2 or recorded for [NotTicked], [NotEligible] and [Stale]. */
internal sealed interface CallAttempt {
    data object NotTicked : CallAttempt

    /** The plan is not the latest revision, its day has passed, or the call is not in the status this action needs. */
    data object NotEligible : CallAttempt

    /** The slot is no longer as the user saw it; [now] is what may be offered instead, if anything. */
    data class Stale(
        val now: Recovery?,
    ) : CallAttempt

    data class Done(
        val verdict: CallVerdict,
    ) : CallAttempt
}

/** How many calls a launch retry looked at, and how many threw (the call stays REQUESTED and is tried on the next launch). */
internal data class RetryReport(
    val processed: Int,
    val failed: Int,
    /** REQUESTED calls whose day had passed, now recorded as REFUSED, TIME_PASSED. No call was scheduled for them. */
    val expired: Int = 0,
    /** REFUSED or NEEDS_ATTENTION calls whose displayed status was brought up to date from M2, without any M2 write. */
    val reconciled: Int = 0,
)

/**
 * The link between an approved plan's opening time and M2's check-in slot. ADR 0009 slice 3 amendment.
 *
 * It is built on M2's public types and changes none of them. It reads M2 without creating anything
 * (never [OccurrenceStore.plan]), writes M2 only by [OccurrenceScheduler.schedule] into an **empty** slot, and
 * only [recover] uses [CheckInConfirmer.reschedule], with the user's tick. It never moves, cancels or revives
 * a call by itself.
 *
 * Nothing here trusts a screen. Every action reads the stored call and plan again and acts only if the call is in the
 * status the action needs, is the latest revision of its day, and the day is today or later by the clock now.
 */
internal class OpeningCallLink(
    private val zones: CheckInZoneSource,
    private val m2: OccurrenceStore,
    private val scheduler: OccurrenceScheduler,
    private val confirmer: CheckInConfirmer,
    private val clock: () -> Long,
) {
    private fun first(call: OpeningCall): ScheduledOccurrence =
        CheckInSchedule(CheckInType.OPENING, call.time, call.zone).occurrenceAt(call.date, call.time, revision = 1)

    private fun today(
        zone: ZoneId,
        rollover: LocalTime,
    ) = ActiveDay.containing(Instant.ofEpochMilli(clock()), zone, rollover)

    /** The preconditions in the ADR's order, read-only. The first that fails, or null. */
    suspend fun refusal(call: OpeningCall): CallDetail? {
        when (val stored = zones.read()) {
            is CheckInZone.Unusable -> return CallDetail.M2_UNAVAILABLE
            is CheckInZone.Known -> {
                if (stored.zone != call.zone) return CallDetail.ZONE_CHANGED
                val exists =
                    call.zone.rules
                        .getValidOffsets(call.date.atTime(call.time))
                        .isNotEmpty()
                if (call.time < call.rollover || !exists) return CallDetail.TIME_NOT_ALLOWED
                if (!stored.openingEnabled) return CallDetail.SLOT_OFF
            }
        }
        if (m2.quietPeriod().covers(call.time)) return CallDetail.IN_QUIET_PERIOD
        if (!first(call).scheduledAt.isAfter(Instant.ofEpochMilli(clock()))) return CallDetail.TIME_PASSED
        return null
    }

    private suspend fun slotState(call: OpeningCall): SlotState {
        val latest = m2.slot(first(call).slot).lastOrNull() ?: return SlotState.Empty
        return when {
            latest.status == CheckInStatus.SCHEDULED ->
                if (latest.localDateTime.toLocalTime() ==
                    call.time
                ) {
                    SlotState.ScheduledAtTime
                } else {
                    SlotState.ScheduledElsewhere(latest.localDateTime.toLocalTime())
                }
            latest.isEligible -> SlotState.InProgress
            latest.isRecoverable -> SlotState.EndedUnrung
            else -> SlotState.Ended
        }
    }

    private fun verdictOf(state: SlotState): CallVerdict? =
        when (state) {
            SlotState.Empty -> null
            SlotState.ScheduledAtTime -> CallVerdict(CallStatus.CONFIRMED)
            is SlotState.ScheduledElsewhere -> CallVerdict(CallStatus.NEEDS_ATTENTION, CallDetail.OTHER_TIME_CONFIRMED)
            SlotState.EndedUnrung -> CallVerdict(CallStatus.NEEDS_ATTENTION, CallDetail.CALL_CANCELLED)
            SlotState.InProgress -> CallVerdict(CallStatus.NEEDS_ATTENTION, CallDetail.CALL_IN_PROGRESS)
            SlotState.Ended -> CallVerdict(CallStatus.NEEDS_ATTENTION, CallDetail.CALL_ENDED)
        }

    /** The call effect, read-only, for the screen: shown beside the stored status, never as its authority. */
    suspend fun preview(call: OpeningCall): CallPreview {
        refusal(call)?.let { return CallPreview(it, null) }
        return CallPreview(null, slotState(call))
    }

    /** The plan's call from a stored target. */
    private fun CallTarget.opening() = OpeningCall(plan.activeDay, plan.zone, plan.rollover, call.openingTime)

    /**
     * The stored call, read now, if it is in one of the [from] statuses, is the latest revision of its day,
     * and the day is today or later by the clock. Otherwise null, and the caller does nothing.
     */
    private suspend fun eligible(
        store: PlanningStore,
        planId: String,
        from: Set<CallStatus>,
    ): CallTarget? {
        val target = store.callTarget(planId) ?: return null
        val live =
            target.latest &&
                target.call.status in from.map { it.name } &&
                target.plan.activeDay >= today(target.plan.zone, target.plan.rollover)
        return target.takeIf { live }
    }

    private suspend fun record(
        store: PlanningStore,
        planId: String,
        from: Set<CallStatus>,
        verdict: CallVerdict,
    ) = store.recordCall(planId, from.map { it.name }.toSet(), verdict.status.name, verdict.detail?.name)

    /**
     * The creating step: create-if-absent into the slot, never anything else. Only an empty slot is written,
     * by revision 1's insert, which ignores a taken id, so a duplicate is impossible whatever interleaves.
     */
    private suspend fun createIfAbsent(call: OpeningCall): CallVerdict {
        val revisionOne = first(call)
        verdictOf(slotState(call))?.let { return it } // something is already there: classify, write nothing
        return when (scheduler.schedule(revisionOne)) {
            is ScheduleResult.Scheduled -> CallVerdict(CallStatus.CONFIRMED)
            // Another path got there between the read and the insert: classify what is there now.
            ScheduleResult.AlreadyRecorded ->
                verdictOf(slotState(call))
                    ?: CallVerdict(CallStatus.NEEDS_ATTENTION, CallDetail.M2_UNAVAILABLE)
            ScheduleResult.NotPermitted -> CallVerdict(CallStatus.NEEDS_ATTENTION, CallDetail.PERMISSION_REFUSED)
            ScheduleResult.NotInTheFuture -> CallVerdict(CallStatus.NEEDS_ATTENTION, CallDetail.TIME_PASSED)
        }
    }

    /** Preconditions, then create-if-absent, then the result recorded from [from]. Null if not eligible. */
    private suspend fun execute(
        store: PlanningStore,
        planId: String,
        from: Set<CallStatus>,
    ): CallVerdict? =
        // The fence keeps every planning writer out from the read of the authorizing state to the recorded result, so
        // an M2 side effect never follows a plan replaced, a call changed or an import landing after that read.
        store.fenced {
            val call = eligible(store, planId, from)?.opening() ?: return@fenced null
            val verdict = refusal(call)?.let { CallVerdict(CallStatus.REFUSED, it) } ?: createIfAbsent(call)
            record(store, planId, from, verdict)
            verdict
        }

    /**
     * The user's tick and press on a plan whose call is NOT_LINKED: authorize it (REQUESTED), then create it. Both
     * read the stored state and the clock now. A day that has passed, an earlier revision or another status is refused.
     */
    suspend fun confirm(
        store: PlanningStore,
        planId: String,
        ticked: Boolean,
    ): CallAttempt {
        if (!ticked) return CallAttempt.NotTicked
        val target = store.callTarget(planId) ?: return CallAttempt.NotEligible
        // The authorization commits in its own transaction, before any M2 effect, so a failure after that effect leaves
        // REQUESTED durable for the launch retry. The effect then runs fenced and re-reads the call, the revision and the
        // day, so a plan replaced in between supplies no authority.
        if (store.authorizeOpeningCall(planId, today(target.plan.zone, target.plan.rollover)) != CallAuthorization.AUTHORIZED) {
            return CallAttempt.NotEligible
        }
        return execute(store, planId, setOf(CallStatus.REQUESTED))?.let { CallAttempt.Done(it) } ?: CallAttempt.NotEligible
    }

    /** "Try again" for a REFUSED call, after the user fixed the cause: create-if-absent again, nothing else. */
    suspend fun tryAgain(
        store: PlanningStore,
        planId: String,
        ticked: Boolean,
    ): CallAttempt {
        if (!ticked) return CallAttempt.NotTicked
        return execute(store, planId, setOf(CallStatus.REFUSED))?.let { CallAttempt.Done(it) } ?: CallAttempt.NotEligible
    }

    /**
     * Explicit recovery for a NEEDS_ATTENTION call: the one place that may move a call or record a new revision,
     * through [CheckInConfirmer.reschedule]. [seen] is what the user was shown. It is checked against M2 as it is now,
     * and if the slot changed, nothing is called: an old preview supplies no authority. The preconditions are run
     * again, and a failing one records REFUSED without writing to M2.
     */
    suspend fun recover(
        store: PlanningStore,
        planId: String,
        seen: Recovery,
        ticked: Boolean,
    ): CallAttempt = if (!ticked) CallAttempt.NotTicked else store.fenced { recoverFenced(store, planId, seen) }

    private suspend fun recoverFenced(
        store: PlanningStore,
        planId: String,
        seen: Recovery,
    ): CallAttempt {
        val from = setOf(CallStatus.NEEDS_ATTENTION)
        val call = eligible(store, planId, from)?.opening() ?: return CallAttempt.NotEligible
        refusal(call)?.let { return CallAttempt.Done(CallVerdict(CallStatus.REFUSED, it).also { v -> record(store, planId, from, v) }) }
        val state = slotState(call)
        if (state ==
            SlotState.ScheduledAtTime
        ) {
            return CallAttempt.Done(CallVerdict(CallStatus.CONFIRMED).also { record(store, planId, from, it) })
        }
        val fresh = recoveryOf(state)
        if (fresh == null) {
            val verdict = verdictOf(state) ?: return CallAttempt.NotEligible
            record(store, planId, from, verdict)
            return CallAttempt.Done(verdict)
        }
        if (fresh != seen) return CallAttempt.Stale(fresh)
        val verdict =
            when (val result = confirmer.reschedule(CheckInType.OPENING, call.date, call.time)) {
                is ConfirmResult.Confirmed, is ConfirmResult.Rescheduled, is ConfirmResult.Unchanged -> CallVerdict(CallStatus.CONFIRMED)
                is ConfirmResult.AlreadyConfirmed ->
                    verdictOf(slotState(call))
                        ?: CallVerdict(CallStatus.NEEDS_ATTENTION, CallDetail.M2_UNAVAILABLE)
                is ConfirmResult.InProgress -> CallVerdict(CallStatus.NEEDS_ATTENTION, CallDetail.CALL_IN_PROGRESS)
                ConfirmResult.SlotOff -> CallVerdict(CallStatus.REFUSED, CallDetail.SLOT_OFF)
                is ConfirmResult.InQuietPeriod -> CallVerdict(CallStatus.REFUSED, CallDetail.IN_QUIET_PERIOD)
                is ConfirmResult.TimePassed -> CallVerdict(CallStatus.REFUSED, CallDetail.TIME_PASSED)
                ConfirmResult.NotPermitted -> CallVerdict(CallStatus.NEEDS_ATTENTION, CallDetail.PERMISSION_REFUSED)
            }
        record(store, planId, from, verdict)
        return CallAttempt.Done(verdict)
    }

    /**
     * Brings the displayed status of a REFUSED or NEEDS_ATTENTION call up to date from M2, and does nothing else. If the
     * slot is now scheduled at the plan's time and every precondition holds, the stale status becomes CONFIRMED, which
     * means only that the live slot matches the intended time, not that this plan created it. It reads the stored call,
     * the latest revision, the day and M2 afresh under the fence, writes nothing to M2, never grants or uses an
     * authorization, and leaves every other state and every other status (including NOT_LINKED) exactly as it was.
     */
    suspend fun reconcile(
        store: PlanningStore,
        planId: String,
    ): Boolean {
        val from = setOf(CallStatus.REFUSED, CallStatus.NEEDS_ATTENTION)
        return store.fenced {
            val call = eligible(store, planId, from)?.opening() ?: return@fenced false
            if (refusal(call) != null || slotState(call) != SlotState.ScheduledAtTime) return@fenced false
            record(store, planId, from, CallVerdict(CallStatus.CONFIRMED))
        }
    }

    /**
     * The launch retry, to run after M2's reconcile returns. It acts on REQUESTED calls only, each once, and only those
     * that are still the latest revision of a day that is today or later. NOT_LINKED (including every imported row),
     * CONFIRMED, REFUSED and NEEDS_ATTENTION are never touched. It never reschedules, revives or cancels.
     * A call that throws stays REQUESTED, to be tried on the next launch, and does not stop the others.
     */
    suspend fun retry(store: PlanningStore): RetryReport {
        var processed = 0
        var failed = 0
        val expired = expireOverdue(store)
        for (id in store.requestedCalls().map { it.plan.id }) {
            try {
                if (execute(store, id, setOf(CallStatus.REQUESTED)) != null) processed++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
        }
        var reconciled = 0
        for (target in store.unsettledCalls()) {
            try {
                if (reconcile(store, target.plan.id)) reconciled++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
        }
        return RetryReport(processed, failed, expired, reconciled)
    }

    /**
     * A REQUESTED call whose active day has passed can no longer ring, so it is recorded as REFUSED, TIME_PASSED instead of
     * staying REQUESTED for ever. This writes nothing to M2 and schedules nothing. Only a call still REQUESTED is changed.
     */
    private suspend fun expireOverdue(store: PlanningStore): Int =
        store.requestedCalls().count { target ->
            target.plan.activeDay < today(target.plan.zone, target.plan.rollover) &&
                store.recordCall(target.plan.id, setOf(CallStatus.REQUESTED.name), CallStatus.REFUSED.name, CallDetail.TIME_PASSED.name)
        }
}
