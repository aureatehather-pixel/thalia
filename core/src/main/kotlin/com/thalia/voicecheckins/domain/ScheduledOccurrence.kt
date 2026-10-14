package com.thalia.voicecheckins.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One concrete check-in on one concrete day.
 *
 * A [CheckInSchedule] is a rule; an occurrence is the single event that rule produces
 * for a given day. Everything downstream (the alarm, the ringing UI, the stored
 * transcript) keys off an occurrence, never off the rule, so that rescheduling one
 * opening does not disturb any other day.
 *
 * The three moments are [scheduledAt], when it is due, [firedAt], when it was claimed to ring,
 * and [resolvedAt], when it reached a terminal status. The record cannot be built in a shape
 * that contradicts its status, so a stored row that does is refused rather than trusted.
 *
 * - A scheduled occurrence has not fired. Ringing, answered, declined, completed and failed ones
 *   have, because each follows a ring. A missed one may or may not have.
 * - [resolvedAt] is set exactly when the status is terminal.
 * - [reason] is set exactly when the status needs one, and is one that fits that status.
 *
 * Change one only through [transitionTo], which refuses an illegal move.
 */
data class ScheduledOccurrence(
    val id: String,
    val type: CheckInType,
    val scheduledAt: Instant,
    val zone: ZoneId,
    val status: CheckInStatus = CheckInStatus.SCHEDULED,
    val reason: OccurrenceReason? = null,
    val firedAt: Instant? = null,
    val resolvedAt: Instant? = null,
    /**
     * The day-and-type this occurrence belongs to, such as `2026-09-21-opening`. A slot holds any
     * number of occurrences over time, called revisions, and at most one of them is eligible.
     */
    val slot: String = id,
    /** Which occurrence of its [slot] this is, counting from 1. Only a newer revision ever replaces one. */
    val revision: Int = 1,
) {
    init {
        require(id.isNotBlank()) { "occurrence id must not be blank" }
        require(slot.isNotBlank()) { "slot must not be blank" }
        require(revision >= 1) { "revision starts at 1, got $revision" }
        require(id == revisionId(slot, revision)) { "id $id is not revision $revision of slot $slot" }
        require(status.needsReason == (reason != null)) {
            if (status.needsReason) "$status needs a reason" else "$status must not carry a reason, got $reason"
        }
        reason?.let { require(it.appliesTo(status)) { "reason $it does not explain $status" } }
        require(status.isTerminal == (resolvedAt != null)) {
            if (status.isTerminal) "$status needs a resolved time" else "$status must not have a resolved time"
        }
        when (status) {
            in FIRED_STATUSES -> require(firedAt != null) { "$status must have a fired time" }
            // A missed occurrence may have rung and rung out, or never rung at all.
            CheckInStatus.MISSED -> Unit
            else -> require(firedAt == null) { "$status cannot have a fired time" }
        }
    }

    /**
     * Whether this occurrence is the slot's live one: it can still ring, is ringing, or is in a call.
     * A slot has at most one.
     */
    val isEligible: Boolean get() = !status.isTerminal

    /**
     * Whether this ended without ever being claimed to ring, so its slot may be given a new
     * revision. A call that rang, whatever became of it, used up the slot's one call.
     */
    val isRecoverable: Boolean get() = status.isTerminal && firedAt == null

    /** The wall-clock time the user will experience this occurrence at. */
    val localDateTime: LocalDateTime get() = LocalDateTime.ofInstant(scheduledAt, zone)

    /**
     * This occurrence after moving to [next] at [at], or an exception if that move is illegal.
     *
     * Ringing stamps [firedAt], a terminal status stamps [resolvedAt], and [reason] is required
     * for exactly the statuses that need one.
     */
    fun transitionTo(
        next: CheckInStatus,
        at: Instant,
        reason: OccurrenceReason? = null,
    ): ScheduledOccurrence {
        require(status.canTransitionTo(next)) { "$status cannot become $next" }
        return copy(
            status = next,
            reason = reason,
            firedAt = if (next == CheckInStatus.RINGING) at else firedAt,
            resolvedAt = if (next.isTerminal) at else null,
        )
    }

    private companion object {
        /** The statuses that can only be reached after the occurrence has been claimed to ring. */
        val FIRED_STATUSES =
            setOf(
                CheckInStatus.RINGING,
                CheckInStatus.ANSWERED,
                CheckInStatus.DECLINED,
                CheckInStatus.COMPLETED,
                CheckInStatus.FAILED,
            )
    }
}

/**
 * The next moment this schedule fires, strictly after [instant].
 *
 * Pure date arithmetic with no platform scheduling. Turning the returned instant into a
 * real alarm is M2's job and belongs in the Android module.
 *
 * Strictly-after matters: recomputing at the exact moment an alarm fires must yield
 * tomorrow, not the alarm that just went off.
 */
fun CheckInSchedule.nextOccurrenceAfter(instant: Instant): ScheduledOccurrence {
    val today = instant.atZone(zone).toLocalDate()
    val candidate = occurrenceOn(today)
    if (candidate.scheduledAt.isAfter(instant)) return candidate
    // plusDays keeps the wall-clock time across a DST boundary, which is what
    // "the same time tomorrow" means to a person.
    return occurrenceOn(today.plusDays(1))
}

/**
 * The single occurrence this schedule's time produces on [date], in the schedule's zone.
 *
 * Its id is derived from the date and type alone, so asking again for the same day gives the same
 * id and a stored record is matched rather than duplicated. This only describes the occurrence: it
 * is not scheduled until something confirms it, and a schedule never confirms a day by itself.
 */
fun CheckInSchedule.occurrenceOn(date: LocalDate): ScheduledOccurrence = occurrenceAt(date, localTime, revision = 1)

/**
 * A scheduled occurrence of the slot for [date] at [time], as revision [revision].
 *
 * Revision 1 has the slot as its id, which is what every occurrence made before revisions existed
 * already has. A later revision gets its own id, so an alarm armed for an earlier revision can never
 * be mistaken for it.
 */
fun CheckInSchedule.occurrenceAt(
    date: LocalDate,
    time: LocalTime,
    revision: Int,
): ScheduledOccurrence {
    val slot = slotKey(date.format(DateTimeFormatter.ISO_LOCAL_DATE), type)
    return ScheduledOccurrence(
        id = revisionId(slot, revision),
        type = type,
        scheduledAt = date.atTime(time).atZone(zone).toInstant(),
        zone = zone,
        slot = slot,
        revision = revision,
    )
}

/**
 * The next revision of this occurrence's slot, at [time] on the same day and in the same zone.
 *
 * It is a new occurrence, scheduled and unfired. This one is left as it is: retiring it is the
 * store's job, in the same step that records the new one.
 */
fun ScheduledOccurrence.nextRevisionAt(time: LocalTime): ScheduledOccurrence =
    ScheduledOccurrence(
        id = revisionId(slot, revision + 1),
        type = type,
        scheduledAt =
            localDateTime
                .toLocalDate()
                .atTime(time)
                .atZone(zone)
                .toInstant(),
        zone = zone,
        slot = slot,
        revision = revision + 1,
    )

/**
 * The earliest enabled check-in after [instant], or null when both slots are disabled.
 */
fun CheckInPlan.nextOccurrenceAfter(instant: Instant): ScheduledOccurrence? =
    listOf(opening, closing)
        .filter { it.enabled }
        .map { it.nextOccurrenceAfter(instant) }
        .minByOrNull { it.scheduledAt }

/**
 * Stable, human-readable identity: one id per calendar day per check-in type.
 *
 * Deriving the id rather than generating a random one means recomputing a schedule
 * after a reboot produces the same id, so a stored record is matched rather than
 * duplicated.
 */
internal fun slotKey(
    isoDate: String,
    type: CheckInType,
): String = "$isoDate-${type.name.lowercase()}"

/** Revision 1 is the slot itself, and revision n after it is `<slot>-r<n>`. */
internal fun revisionId(
    slot: String,
    revision: Int,
): String = if (revision == 1) slot else "$slot-r$revision"
