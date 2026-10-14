package com.thalia.voicecheckins.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The identity and life rules of a slot: one day and one type, with a history of revisions. */
class OccurrenceRevisionTest {
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private val plan = CheckInPlan.default()
    private val day = LocalDate.of(2026, 9, 21)
    private val at = Instant.parse("2026-09-21T01:00:00Z")

    @Test
    fun `revision 1 keeps the id every earlier occurrence has`() {
        val first = plan.opening.occurrenceOn(day)

        assertEquals("2026-09-21-opening", first.id)
        assertEquals("2026-09-21-opening", first.slot)
        assertEquals(1, first.revision)
    }

    @Test
    fun `a later revision has its own id in the same slot`() {
        val second = plan.opening.occurrenceOn(day).nextRevisionAt(LocalTime.of(11, 30))

        assertEquals("2026-09-21-opening-r2", second.id)
        assertEquals("2026-09-21-opening", second.slot)
        assertEquals(2, second.revision)
        assertEquals("2026-09-21-opening-r3", second.nextRevisionAt(LocalTime.of(12, 0)).id)
    }

    @Test
    fun `a new revision is a fresh scheduled occurrence at the new time on the same day`() {
        val first = plan.closing.occurrenceOn(day).transitionTo(CheckInStatus.RINGING, at)
        val next = first.nextRevisionAt(LocalTime.of(16, 15))

        assertEquals(CheckInStatus.SCHEDULED, next.status)
        assertEquals(null, next.firedAt)
        assertEquals(null, next.reason)
        assertEquals(day.atTime(16, 15), next.localDateTime)
        assertEquals(kl, next.zone)
        assertEquals(CheckInType.CLOSING, next.type)
    }

    @Test
    fun `the same day and type never share an id with the other type or another day`() {
        val ids =
            listOf(
                plan.opening.occurrenceOn(day),
                plan.closing.occurrenceOn(day),
                plan.opening.occurrenceOn(day.plusDays(1)),
                plan.opening.occurrenceOn(day).nextRevisionAt(LocalTime.NOON),
            ).map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `an occurrence cannot claim a revision its id does not name`() {
        assertFailsWith<IllegalArgumentException> {
            ScheduledOccurrence("2026-09-21-opening", CheckInType.OPENING, at, kl, slot = "2026-09-21-opening", revision = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            ScheduledOccurrence("2026-09-21-opening-r2", CheckInType.OPENING, at, kl, slot = "2026-09-21-opening", revision = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            ScheduledOccurrence("x", CheckInType.OPENING, at, kl, slot = "x", revision = 0)
        }
    }

    @Test
    fun `only the statuses that can still ring or are in a call are eligible`() {
        val eligible = CheckInStatus.entries.filter { s -> !s.isTerminal }

        assertEquals(setOf(CheckInStatus.SCHEDULED, CheckInStatus.RINGING, CheckInStatus.ANSWERED), eligible.toSet())
        assertTrue(plan.opening.occurrenceOn(day).isEligible)
        val cancelled = plan.opening.occurrenceOn(day).transitionTo(CheckInStatus.CANCELLED, at, OccurrenceReason.USER_CANCELLED)
        assertFalse(cancelled.isEligible)
    }

    @Test
    fun `a slot is recoverable only when its last occurrence ended without ever being claimed to ring`() {
        val fresh = plan.opening.occurrenceOn(day)

        assertFalse(fresh.isRecoverable, "still scheduled")
        assertTrue(fresh.transitionTo(CheckInStatus.CANCELLED, at, OccurrenceReason.USER_CANCELLED).isRecoverable)
        assertTrue(fresh.transitionTo(CheckInStatus.CANCELLED, at, OccurrenceReason.SUPERSEDED).isRecoverable)
        assertTrue(fresh.transitionTo(CheckInStatus.CANCELLED, at, OccurrenceReason.NOT_PERMITTED).isRecoverable)
        assertTrue(fresh.transitionTo(CheckInStatus.MISSED, at, OccurrenceReason.ALARM_MISSING).isRecoverable, "never rang")

        val rang = fresh.transitionTo(CheckInStatus.RINGING, at)
        assertFalse(rang.isRecoverable, "ringing")
        assertFalse(rang.transitionTo(CheckInStatus.MISSED, at, OccurrenceReason.RING_TIMEOUT).isRecoverable, "rang and nobody answered")
        assertFalse(rang.transitionTo(CheckInStatus.DECLINED, at).isRecoverable)
        assertFalse(rang.transitionTo(CheckInStatus.MISSED, at, OccurrenceReason.START_REFUSED).isRecoverable, "it was claimed")
    }

    @Test
    fun `superseded is a cancelled reason and nothing else`() {
        CheckInStatus.entries.forEach { status ->
            assertEquals(status == CheckInStatus.CANCELLED, OccurrenceReason.SUPERSEDED.appliesTo(status), "$status")
        }
        assertNotEquals(OccurrenceReason.USER_CANCELLED, OccurrenceReason.SUPERSEDED)
    }
}
