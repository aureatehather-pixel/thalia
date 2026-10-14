package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Starts the existing call path. The receiver implements it with the foreground service. */
internal fun interface CallStarter {
    /**
     * Ask for a call to ring for [occurrenceId]. Returns false, rather than throwing, if
     * Android refused to start it.
     */
    fun startCall(
        checkInType: CheckInType,
        occurrenceId: String,
    ): Boolean
}

/** What the handler decided about one delivered alarm. */
internal sealed interface FireOutcome {
    /** The call was started. [latenessMillis] is how long after its instant the alarm arrived. */
    data class Ringing(
        val occurrenceId: String,
        val latenessMillis: Long,
    ) : FireOutcome

    /**
     * The alarm arrived too long after its instant, so it did not ring. [recorded] is whether this
     * delivery is the one that marked the occurrence missed, and false for a repeat.
     */
    data class TooLate(
        val occurrenceId: String,
        val latenessMillis: Long,
        val recorded: Boolean,
    ) : FireOutcome

    /**
     * Thalia withheld the call and recorded it suppressed for [reason]: a quiet period covered it, or
     * another call had the phone. Nothing was started or shown.
     */
    data class Suppressed(
        val occurrenceId: String,
        val reason: OccurrenceReason,
        val latenessMillis: Long,
    ) : FireOutcome

    /** The call should have rung but Android refused to start it. It is recorded as missed. */
    data class StartRefused(
        val occurrenceId: String,
        val latenessMillis: Long,
    ) : FireOutcome

    /**
     * Another delivery, or the reconciliation, already took this occurrence, or it was never
     * recorded. Nothing rang. [found] is the status it is in, or null if it does not exist.
     */
    data class NotClaimed(
        val occurrenceId: String,
        val found: CheckInStatus?,
    ) : FireOutcome

    /** The alarm did not say what it was for, so nothing rang. */
    data object Malformed : FireOutcome
}

/**
 * Decides what to do when an alarm is delivered, and does it through the store and [CallStarter].
 *
 * These rules decide whether a call rings, in this order. Every refusal is decided and recorded
 * before [CallStarter] is touched, so a refused call starts no service, posts no notification, plays
 * no sound, vibrates nothing and wakes no screen.
 *
 * - **Not too late.** A check-in that arrives more than [maxLateness] after its moment does not
 *   ring. A phone that was in deep sleep, or an alarm that Android held back, must not turn a
 *   10:00 check-in into one that interrupts whatever the user began at 10:20. It is recorded as
 *   missed and never retried.
 * - **Not in the quiet period.** If the user's quiet period covers the occurrence's moment, or the
 *   moment the alarm arrived, it is suppressed for [OccurrenceReason.QUIET_PERIOD]. This is checked
 *   again here, not only when the day is confirmed, so a quiet period set after confirming still holds.
 * - **One call at a time.** A call lost with its process is ended first, by the same rule the
 *   reconciliation uses, so it cannot block this one. Then, if a call is ringing or in progress in
 *   this process, or the store holds one, this occurrence is suppressed for
 *   [OccurrenceReason.CALL_IN_PROGRESS].
 * - **Claimed once.** The occurrence is claimed in the store, one transaction that moves it from
 *   scheduled to ringing only if no other call is ringing or answered, and otherwise suppresses it.
 *   Of two alarms delivered together exactly one wins. A second delivery of the same alarm, a
 *   delivery after the reconciliation marked it missed, and a delivery for an occurrence that was
 *   never recorded all lose the claim and ring nothing.
 *
 * There is no rule for an alarm that arrives early, because `AlarmManager` does not deliver an
 * alarm before its instant. A payload that looks early is rung, not lost.
 *
 * If Android then refuses to start the call, the occurrence is recorded missed rather than left
 * claiming to ring.
 *
 * @param callHere the occurrence a call in this process is ringing or talking for, or null when this
 *   process has no call. A call started by a development button has no stored occurrence, so the
 *   store cannot see it, and this is how it still counts.
 */
internal class CheckInFireHandler(
    private val clock: () -> Long,
    private val store: OccurrenceStore,
    private val callStarter: CallStarter,
    private val callHere: () -> String? = { null },
    private val maxLateness: Duration = MAX_LATENESS,
    private val lostCallGrace: Duration = Reconciler.IN_PROGRESS_GRACE,
) {
    suspend fun onAlarm(payload: AlarmPayload?): FireOutcome {
        if (payload == null) return FireOutcome.Malformed

        val now = clock()
        val at = Instant.ofEpochMilli(now)
        val latenessMillis = now - payload.scheduledAtMillis
        val id = payload.occurrenceId

        if (latenessMillis > maxLateness.inWholeMilliseconds) {
            val recorded = store.transition(id, CheckInStatus.SCHEDULED, CheckInStatus.MISSED, at, OccurrenceReason.TOO_LATE)
            return FireOutcome.TooLate(id, latenessMillis, recorded)
        }

        val quiet = store.quietPeriod()
        if (quiet.enabled) {
            val zone = store.plan().zone
            if (quiet.covers(Instant.ofEpochMilli(payload.scheduledAtMillis), zone) || quiet.covers(at, zone)) {
                return suppress(id, OccurrenceReason.QUIET_PERIOD, at, latenessMillis)
            }
        }

        endLostCalls(now, at)
        if (callHere() != null) return suppress(id, OccurrenceReason.CALL_IN_PROGRESS, at, latenessMillis)

        when (store.claimForRinging(id, at)) {
            ClaimResult.CLAIMED -> Unit
            ClaimResult.SUPPRESSED -> return FireOutcome.Suppressed(id, OccurrenceReason.CALL_IN_PROGRESS, latenessMillis)
            ClaimResult.NOT_CLAIMED -> return FireOutcome.NotClaimed(id, store.get(id)?.status)
        }

        if (callStarter.startCall(payload.checkInType, id)) return FireOutcome.Ringing(id, latenessMillis)

        store.transition(id, CheckInStatus.RINGING, CheckInStatus.MISSED, at, OccurrenceReason.START_REFUSED)
        return FireOutcome.StartRefused(id, latenessMillis)
    }

    private suspend fun suppress(
        id: String,
        reason: OccurrenceReason,
        at: Instant,
        latenessMillis: Long,
    ): FireOutcome =
        if (store.transition(id, CheckInStatus.SCHEDULED, CheckInStatus.SUPPRESSED, at, reason)) {
            FireOutcome.Suppressed(id, reason, latenessMillis)
        } else {
            FireOutcome.NotClaimed(id, store.get(id)?.status)
        }

    /** End every call the store holds as ringing or answered that no process is carrying any more. */
    private suspend fun endLostCalls(
        now: Long,
        at: Instant,
    ) {
        val carried = callHere()
        store
            .open()
            .filter { it.isLostCall(carried, now, lostCallGrace) }
            .forEach { store.transition(it.id, it.status, it.lostCallEnding(), at, OccurrenceReason.INTERRUPTED) }
    }

    companion object {
        /** Over this and the call is dropped. Exactly this still rings. */
        val MAX_LATENESS: Duration = 2.minutes
    }
}
