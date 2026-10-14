package com.thalia.voicecheckins.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Moving an occurrence through its life, and the shapes a record is refused. */
class ScheduledOccurrenceTransitionTest {
    private val due = Instant.parse("2026-09-20T02:00:00Z")
    private val fired = due.plusMillis(150)
    private val done = due.plusSeconds(40)

    private fun scheduled() =
        ScheduledOccurrence(
            id = "2026-09-20-morning",
            type = CheckInType.OPENING,
            scheduledAt = due,
            zone = ZoneId.of("Asia/Kuala_Lumpur"),
        )

    @Test
    fun `a new occurrence is scheduled, not fired, not resolved, with no reason`() {
        val o = scheduled()
        assertEquals(CheckInStatus.SCHEDULED, o.status)
        assertNull(o.firedAt)
        assertNull(o.resolvedAt)
        assertNull(o.reason)
    }

    @Test
    fun `ringing stamps the fired time and leaves it open`() {
        val ringing = scheduled().transitionTo(CheckInStatus.RINGING, fired)

        assertEquals(CheckInStatus.RINGING, ringing.status)
        assertEquals(fired, ringing.firedAt)
        assertNull(ringing.resolvedAt)
    }

    @Test
    fun `answering keeps the fired time and is still open`() {
        val answered = scheduled().transitionTo(CheckInStatus.RINGING, fired).transitionTo(CheckInStatus.ANSWERED, fired.plusSeconds(3))

        assertEquals(fired, answered.firedAt)
        assertNull(answered.resolvedAt)
    }

    @Test
    fun `a completed call stamps the resolved time and keeps the fired time, with no reason`() {
        val completed =
            scheduled()
                .transitionTo(CheckInStatus.RINGING, fired)
                .transitionTo(CheckInStatus.ANSWERED, fired.plusSeconds(3))
                .transitionTo(CheckInStatus.COMPLETED, done)

        assertEquals(CheckInStatus.COMPLETED, completed.status)
        assertEquals(fired, completed.firedAt)
        assertEquals(done, completed.resolvedAt)
        assertNull(completed.reason)
    }

    @Test
    fun `a declined call ends with a fired time, a resolved time and no reason`() {
        val declined = scheduled().transitionTo(CheckInStatus.RINGING, fired).transitionTo(CheckInStatus.DECLINED, done)

        assertEquals(CheckInStatus.DECLINED, declined.status)
        assertEquals(fired, declined.firedAt)
        assertEquals(done, declined.resolvedAt)
        assertNull(declined.reason)
    }

    @Test
    fun `a call that rang out is missed with its reason and both times`() {
        val missed =
            scheduled()
                .transitionTo(CheckInStatus.RINGING, fired)
                .transitionTo(CheckInStatus.MISSED, done, OccurrenceReason.RING_TIMEOUT)

        assertEquals(OccurrenceReason.RING_TIMEOUT, missed.reason)
        assertEquals(fired, missed.firedAt)
        assertEquals(done, missed.resolvedAt)
    }

    @Test
    fun `an occurrence that never rang can be missed with no fired time`() {
        val missed = scheduled().transitionTo(CheckInStatus.MISSED, done, OccurrenceReason.ALARM_MISSING)

        assertNull(missed.firedAt)
        assertEquals(done, missed.resolvedAt)
        assertEquals(OccurrenceReason.ALARM_MISSING, missed.reason)
    }

    @Test
    fun `a scheduled occurrence can be cancelled with a reason`() {
        val cancelled = scheduled().transitionTo(CheckInStatus.CANCELLED, done, OccurrenceReason.PLAN_DISABLED)

        assertEquals(CheckInStatus.CANCELLED, cancelled.status)
        assertNull(cancelled.firedAt)
    }

    @Test
    fun `an answered call that breaks is failed with its reason`() {
        val failed =
            scheduled()
                .transitionTo(CheckInStatus.RINGING, fired)
                .transitionTo(CheckInStatus.ANSWERED, fired.plusSeconds(3))
                .transitionTo(CheckInStatus.FAILED, done, OccurrenceReason.SESSION_FAILED)

        assertEquals(OccurrenceReason.SESSION_FAILED, failed.reason)
        assertEquals(fired, failed.firedAt)
    }

    @Test
    fun `an illegal move is refused`() {
        assertFailsWith<IllegalArgumentException> { scheduled().transitionTo(CheckInStatus.ANSWERED, fired) }
        assertFailsWith<IllegalArgumentException> { scheduled().transitionTo(CheckInStatus.COMPLETED, fired) }
    }

    @Test
    fun `a resolved occurrence cannot be moved again`() {
        val missed = scheduled().transitionTo(CheckInStatus.MISSED, done, OccurrenceReason.ALARM_MISSING)

        CheckInStatus.entries.forEach { next ->
            assertFailsWith<IllegalArgumentException>("missed -> $next") {
                missed.transitionTo(next, done.plusSeconds(1), OccurrenceReason.INTERRUPTED)
            }
        }
    }

    @Test
    fun `a status that needs a reason is refused without one`() {
        assertFailsWith<IllegalArgumentException> { scheduled().transitionTo(CheckInStatus.MISSED, done) }
        assertFailsWith<IllegalArgumentException> { scheduled().transitionTo(CheckInStatus.CANCELLED, done) }
    }

    @Test
    fun `a status that needs no reason is refused with one`() {
        val ringing = scheduled().transitionTo(CheckInStatus.RINGING, fired)

        assertFailsWith<IllegalArgumentException> { ringing.transitionTo(CheckInStatus.DECLINED, done, OccurrenceReason.RING_TIMEOUT) }
        assertFailsWith<IllegalArgumentException> { ringing.transitionTo(CheckInStatus.ANSWERED, done, OccurrenceReason.RING_TIMEOUT) }
    }

    @Test
    fun `a reason that does not fit the status is refused`() {
        // Ringing out is a way to be missed, not a way to be cancelled.
        assertFailsWith<IllegalArgumentException> {
            scheduled().transitionTo(CheckInStatus.CANCELLED, done, OccurrenceReason.RING_TIMEOUT)
        }
        assertFailsWith<IllegalArgumentException> {
            scheduled().transitionTo(CheckInStatus.MISSED, done, OccurrenceReason.PLAN_DISABLED)
        }
    }

    @Test
    fun `a record cannot be built in a shape that contradicts its status`() {
        val base = scheduled()

        // Resolved but not terminal, and terminal but not resolved.
        assertFailsWith<IllegalArgumentException> { base.copy(resolvedAt = done) }
        assertFailsWith<IllegalArgumentException> {
            base.copy(status = CheckInStatus.MISSED, reason = OccurrenceReason.ALARM_MISSING)
        }
        // Scheduled but already fired, and ringing but never fired.
        assertFailsWith<IllegalArgumentException> { base.copy(firedAt = fired) }
        assertFailsWith<IllegalArgumentException> { base.copy(status = CheckInStatus.RINGING) }
        // A cancelled occurrence never rang.
        assertFailsWith<IllegalArgumentException> {
            base.copy(status = CheckInStatus.CANCELLED, reason = OccurrenceReason.USER_CANCELLED, firedAt = fired, resolvedAt = done)
        }
        // Nor did a suppressed one.
        assertFailsWith<IllegalArgumentException> {
            base.copy(status = CheckInStatus.SUPPRESSED, reason = OccurrenceReason.QUIET_PERIOD, firedAt = fired, resolvedAt = done)
        }
    }

    @Test
    fun `a suppressed occurrence ends unfired with its reason, and its day can be confirmed again`() {
        val suppressed = scheduled().transitionTo(CheckInStatus.SUPPRESSED, done, OccurrenceReason.QUIET_PERIOD)

        assertEquals(CheckInStatus.SUPPRESSED, suppressed.status)
        assertEquals(OccurrenceReason.QUIET_PERIOD, suppressed.reason)
        assertNull(suppressed.firedAt)
        assertEquals(done, suppressed.resolvedAt)
        assertTrue(suppressed.isRecoverable, "it never rang, so the slot's one call is not used up")
        assertFailsWith<IllegalArgumentException> {
            scheduled().transitionTo(CheckInStatus.SUPPRESSED, done, OccurrenceReason.RING_TIMEOUT)
        }
    }

    @Test
    fun `transitionTo returns a new record and leaves the original alone`() {
        val original = scheduled()
        original.transitionTo(CheckInStatus.RINGING, fired)

        assertEquals(CheckInStatus.SCHEDULED, original.status)
        assertNull(original.firedAt)
    }
}
