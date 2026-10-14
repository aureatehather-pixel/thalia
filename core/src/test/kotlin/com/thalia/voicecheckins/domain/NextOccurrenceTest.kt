package com.thalia.voicecheckins.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextOccurrenceTest {
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private val plan = CheckInPlan.default()

    /** A wall-clock time in Kuala Lumpur, as an absolute instant. */
    private fun at(
        hour: Int,
        minute: Int = 0,
        day: Int = 19,
    ): Instant =
        LocalDate
            .of(2026, 9, day)
            .atTime(hour, minute)
            .atZone(kl)
            .toInstant()

    @Test
    fun `early morning resolves to today's morning check-in`() {
        val next = plan.nextOccurrenceAfter(at(hour = 7))

        assertEquals(CheckInType.OPENING, next?.type)
        assertEquals(at(hour = 10), next?.scheduledAt)
        assertEquals("2026-09-19-opening", next?.id)
    }

    @Test
    fun `midday resolves to today's afternoon check-in`() {
        val next = plan.nextOccurrenceAfter(at(hour = 12))

        assertEquals(CheckInType.CLOSING, next?.type)
        assertEquals(at(hour = 15), next?.scheduledAt)
        assertEquals("2026-09-19-closing", next?.id)
    }

    @Test
    fun `evening rolls over to tomorrow's morning check-in`() {
        val next = plan.nextOccurrenceAfter(at(hour = 21))

        assertEquals(CheckInType.OPENING, next?.type)
        assertEquals(at(hour = 10, day = 20), next?.scheduledAt)
        assertEquals("2026-09-20-opening", next?.id)
    }

    @Test
    fun `the exact scheduled instant resolves to the next day, not itself`() {
        // This is the reboot and post-alarm case: recomputing at fire time must move on.
        val next = plan.opening.nextOccurrenceAfter(at(hour = 10))

        assertEquals(at(hour = 10, day = 20), next.scheduledAt)
    }

    @Test
    fun `a disabled slot is skipped`() {
        val morningOff = plan.withEnabled(CheckInType.OPENING, enabled = false)
        val next = morningOff.nextOccurrenceAfter(at(hour = 7))

        assertEquals(CheckInType.CLOSING, next?.type)
        assertEquals(at(hour = 15), next?.scheduledAt)
    }

    @Test
    fun `no enabled slots means nothing is due`() {
        val allOff =
            plan
                .withEnabled(CheckInType.OPENING, enabled = false)
                .withEnabled(CheckInType.CLOSING, enabled = false)

        assertNull(allOff.nextOccurrenceAfter(at(hour = 7)))
    }

    @Test
    fun `a new occurrence starts out scheduled and knows its local time`() {
        val next = plan.nextOccurrenceAfter(at(hour = 7))!!

        assertEquals(CheckInStatus.SCHEDULED, next.status)
        assertEquals(LocalTime.of(10, 0), next.localDateTime.toLocalTime())
        assertEquals(kl, next.zone)
    }

    @Test
    fun `wall-clock time is preserved across a daylight saving shift`() {
        // Kuala Lumpur has no DST, so this invariant is proved somewhere that does.
        val london = ZoneId.of("Europe/London")
        val schedule = CheckInSchedule(CheckInType.OPENING, LocalTime.of(10, 0), london)

        // 2026-10-25 is the UK clock change. Ask on the 24th, expect 10:00 on the 25th.
        val before =
            LocalDate
                .of(2026, 10, 24)
                .atTime(11, 0)
                .atZone(london)
                .toInstant()
        val next = schedule.nextOccurrenceAfter(before)

        assertEquals(LocalTime.of(10, 0), next.localDateTime.toLocalTime())
        assertEquals(LocalDate.of(2026, 10, 25), next.localDateTime.toLocalDate())
    }

    @Test
    fun `terminal statuses are the ones that end an occurrence`() {
        assertTrue(CheckInStatus.COMPLETED.isTerminal)
        assertTrue(CheckInStatus.DECLINED.isTerminal)
        assertTrue(CheckInStatus.MISSED.isTerminal)
        assertTrue(CheckInStatus.FAILED.isTerminal)
        assertTrue(CheckInStatus.CANCELLED.isTerminal)

        assertTrue(!CheckInStatus.SCHEDULED.isTerminal)
        assertTrue(!CheckInStatus.RINGING.isTerminal)
        assertTrue(!CheckInStatus.ANSWERED.isTerminal)
    }

    @Test
    fun `occurrenceOn gives the same id for the same day and a different one for the other slot`() {
        val day = LocalDate.of(2026, 9, 20)

        val opening = plan.opening.occurrenceOn(day)

        assertEquals("2026-09-20-opening", opening.id)
        assertEquals(opening, plan.opening.occurrenceOn(day))
        assertEquals("2026-09-20-closing", plan.closing.occurrenceOn(day).id)
        assertEquals(day.atTime(10, 0).atZone(kl).toInstant(), opening.scheduledAt)
    }

    @Test
    fun `a stored or in-flight name from before the rename maps to the new type and nothing else does`() {
        assertEquals(CheckInType.OPENING, CheckInType.fromStoredName("MORNING"))
        assertEquals(CheckInType.CLOSING, CheckInType.fromStoredName("AFTERNOON"))
        assertEquals(CheckInType.OPENING, CheckInType.fromStoredName("OPENING"))
        assertEquals(CheckInType.CLOSING, CheckInType.fromStoredName("CLOSING"))
        assertNull(CheckInType.fromStoredName("morning"))
        assertNull(CheckInType.fromStoredName(null))
    }
}
