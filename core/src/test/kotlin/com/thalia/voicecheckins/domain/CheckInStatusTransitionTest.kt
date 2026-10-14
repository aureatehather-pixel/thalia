package com.thalia.voicecheckins.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The life of an occurrence, pinned move by move.
 *
 * The full table is asserted, not just a few legal and illegal examples, so that adding a status
 * or a transition without deciding it here fails a test.
 */
class CheckInStatusTransitionTest {
    private val expected: Map<CheckInStatus, Set<CheckInStatus>> =
        mapOf(
            CheckInStatus.SCHEDULED to
                setOf(CheckInStatus.RINGING, CheckInStatus.MISSED, CheckInStatus.CANCELLED, CheckInStatus.SUPPRESSED),
            CheckInStatus.RINGING to setOf(CheckInStatus.ANSWERED, CheckInStatus.DECLINED, CheckInStatus.MISSED),
            CheckInStatus.ANSWERED to setOf(CheckInStatus.COMPLETED, CheckInStatus.FAILED),
            CheckInStatus.COMPLETED to emptySet(),
            CheckInStatus.DECLINED to emptySet(),
            CheckInStatus.MISSED to emptySet(),
            CheckInStatus.FAILED to emptySet(),
            CheckInStatus.CANCELLED to emptySet(),
            CheckInStatus.SUPPRESSED to emptySet(),
        )

    @Test
    fun `the table covers every status`() {
        assertEquals(CheckInStatus.entries.toSet(), expected.keys)
    }

    @Test
    fun `every pair of statuses is legal exactly when the table says so`() {
        for (from in CheckInStatus.entries) {
            for (to in CheckInStatus.entries) {
                assertEquals(
                    to in expected.getValue(from),
                    from.canTransitionTo(to),
                    "$from -> $to",
                )
            }
        }
    }

    @Test
    fun `nothing leaves a terminal status`() {
        CheckInStatus.entries.filter { it.isTerminal }.forEach { terminal ->
            assertTrue(terminal.legalNext.isEmpty(), "$terminal must be final")
        }
    }

    @Test
    fun `every open status can still move on`() {
        CheckInStatus.entries.filterNot { it.isTerminal }.forEach { open ->
            assertTrue(open.legalNext.isNotEmpty(), "$open would be a dead end")
        }
    }

    @Test
    fun `no status becomes itself`() {
        CheckInStatus.entries.forEach { assertFalse(it.canTransitionTo(it), "$it -> $it") }
    }

    @Test
    fun `a call cannot be answered without ringing, or completed without being answered`() {
        assertFalse(CheckInStatus.SCHEDULED.canTransitionTo(CheckInStatus.ANSWERED))
        assertFalse(CheckInStatus.SCHEDULED.canTransitionTo(CheckInStatus.DECLINED))
        assertFalse(CheckInStatus.RINGING.canTransitionTo(CheckInStatus.COMPLETED))
        assertFalse(CheckInStatus.RINGING.canTransitionTo(CheckInStatus.FAILED))
    }

    @Test
    fun `an occurrence that has not rung can end without ringing, but a ringing one cannot be cancelled`() {
        assertTrue(CheckInStatus.SCHEDULED.canTransitionTo(CheckInStatus.MISSED))
        assertTrue(CheckInStatus.SCHEDULED.canTransitionTo(CheckInStatus.CANCELLED))
        assertFalse(CheckInStatus.RINGING.canTransitionTo(CheckInStatus.CANCELLED))
    }

    @Test
    fun `the statuses that need a reason are the ones that end an occurrence without a normal outcome`() {
        val needing = CheckInStatus.entries.filter { it.needsReason }.toSet()
        assertEquals(setOf(CheckInStatus.MISSED, CheckInStatus.CANCELLED, CheckInStatus.FAILED, CheckInStatus.SUPPRESSED), needing)
    }

    @Test
    fun `a suppression is its own ending, reachable only from scheduled, and explained only by a quiet period or another call`() {
        assertTrue(CheckInStatus.SUPPRESSED.isTerminal)
        assertEquals(setOf(CheckInStatus.SCHEDULED), CheckInStatus.entries.filter { it.canTransitionTo(CheckInStatus.SUPPRESSED) }.toSet())
        assertEquals(
            setOf(OccurrenceReason.QUIET_PERIOD, OccurrenceReason.CALL_IN_PROGRESS),
            OccurrenceReason.entries.filter { it.appliesTo(CheckInStatus.SUPPRESSED) }.toSet(),
        )
        assertFalse(OccurrenceReason.QUIET_PERIOD.appliesTo(CheckInStatus.MISSED), "a quiet period is never a missed call")
    }

    @Test
    fun `every reason explains at least one status, and only statuses that need a reason`() {
        OccurrenceReason.entries.forEach { reason ->
            val statuses = CheckInStatus.entries.filter { reason.appliesTo(it) }
            assertTrue(statuses.isNotEmpty(), "$reason explains nothing")
            statuses.forEach { assertTrue(it.needsReason, "$reason must not attach to $it") }
        }
    }

    @Test
    fun `every status that needs a reason has at least one reason to give`() {
        CheckInStatus.entries.filter { it.needsReason }.forEach { status ->
            assertTrue(OccurrenceReason.entries.any { it.appliesTo(status) }, "$status has no reason")
        }
    }
}
