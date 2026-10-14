package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInPlan
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.domain.QuietPeriod
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * The durable record of what Thalia scheduled and what became of it.
 *
 * Every change to an occurrence is a conditional update that names the status it expects to find,
 * and reports whether it found it. That is what makes the store safe when several things act at
 * once: the alarm receiver, the reconciliation that runs when the app starts, and the call itself
 * can all try to move the same occurrence, and exactly one of them wins each move. A caller that
 * loses is told, and does nothing further.
 *
 * Nothing here checks whether a move makes sense for the situation, only whether it is legal for
 * the status. Deciding what to do is the caller's job.
 */
interface OccurrenceStore {
    /** Record a new occurrence. False, and nothing changes, if that id is already recorded. */
    suspend fun insert(occurrence: ScheduledOccurrence): Boolean

    suspend fun get(id: String): ScheduledOccurrence?

    /** Every occurrence that has not reached a terminal status, earliest first. */
    suspend fun open(): List<ScheduledOccurrence>

    /** The most recent occurrences, latest first, updated as they change. For the debug screen. */
    fun observeRecent(limit: Int): Flow<List<ScheduledOccurrence>>

    /**
     * Claim [id] to ring, moving it from scheduled to ringing and stamping [at] as its fired time,
     * but only if no other occurrence is ringing or answered.
     *
     * This is the atomic claim that stops an occurrence ringing twice, and stops two calls holding the
     * phone at once. It is one transaction, so of any number of occurrences claimed together at most
     * one is ever ringing or answered:
     *
     * - [ClaimResult.CLAIMED]: it was scheduled and no other call was ringing or answered. It is now
     *   ringing. This happens for exactly one caller, ever, for a given occurrence.
     * - [ClaimResult.SUPPRESSED]: it was scheduled but another occurrence was ringing or answered. The
     *   same transaction ended it as [CheckInStatus.SUPPRESSED], [OccurrenceReason.CALL_IN_PROGRESS],
     *   with [at] as its resolved time, so it can never ring later.
     * - [ClaimResult.NOT_CLAIMED]: it is unknown or is not scheduled, whether it is already ringing,
     *   already ended or never existed. Nothing changed.
     */
    suspend fun claimForRinging(
        id: String,
        at: Instant,
    ): ClaimResult

    /**
     * Move [id] from [from] to [to], only if it is currently in [from]. True if it moved.
     *
     * Refuses an illegal move, and a reason that does not fit [to], before touching anything.
     * Also refuses [to] as [CheckInStatus.RINGING]: [claimForRinging] is the only path into
     * ringing, because it is the one that stamps the fired time atomically with the move.
     */
    suspend fun transition(
        id: String,
        from: CheckInStatus,
        to: CheckInStatus,
        at: Instant,
        reason: OccurrenceReason? = null,
    ): Boolean

    /** Every occurrence of [slot], oldest revision first. Empty if the slot has never been confirmed. */
    suspend fun slot(slot: String): List<ScheduledOccurrence>

    /**
     * Retire the scheduled occurrence [oldId] and record [replacement] as its successor, together.
     *
     * Both happen or neither does, so a slot never has two eligible occurrences and never loses its
     * only one. True if it happened. False, with nothing changed, if [oldId] is not scheduled any
     * more, because it is ringing, has ended or never existed, or if [replacement]'s id is taken.
     * The old one ends cancelled as [OccurrenceReason.SUPERSEDED] with [at] as its resolved time.
     *
     * The replacement must be a scheduled occurrence in the same slot as [oldId], one revision on.
     */
    suspend fun supersede(
        oldId: String,
        replacement: ScheduledOccurrence,
        at: Instant,
    ): Boolean

    /** The stored plan, created from the default on first use. */
    suspend fun plan(): CheckInPlan

    suspend fun savePlan(plan: CheckInPlan)

    /** The stored quiet period, [QuietPeriod.DEFAULT] until one is saved. */
    suspend fun quietPeriod(): QuietPeriod

    suspend fun saveQuietPeriod(quietPeriod: QuietPeriod)
}

/** What became of one attempt to claim an occurrence to ring. See [OccurrenceStore.claimForRinging]. */
enum class ClaimResult {
    CLAIMED,
    SUPPRESSED,
    NOT_CLAIMED,
    ;

    /** Whether the caller won the claim and must now start the call. */
    val claimed: Boolean get() = this == CLAIMED
}

/**
 * The checks every store applies to a move before it touches anything, so that the real store and
 * the test store refuse exactly the same things.
 */
internal fun requireLegalMove(
    from: CheckInStatus,
    to: CheckInStatus,
    reason: OccurrenceReason?,
) {
    require(to != CheckInStatus.RINGING) { "RINGING can only be reached through claimForRinging" }
    require(from.canTransitionTo(to)) { "$from cannot become $to" }
    require(to.needsReason == (reason != null)) {
        if (to.needsReason) "$to needs a reason" else "$to must not carry a reason, got $reason"
    }
    reason?.let { require(it.appliesTo(to)) { "reason $it does not explain $to" } }
}
