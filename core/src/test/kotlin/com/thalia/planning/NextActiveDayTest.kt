package com.thalia.planning

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The next active day follows the working-day rules. It is never simply the calendar date plus one. */
class NextActiveDayTest {
    private val zone = ZoneId.of("UTC")
    private val rollover = LocalTime.of(4, 0)

    private fun day(
        month: Int,
        d: Int,
    ) = ActiveDay(LocalDate.of(2026, month, d))

    private fun planningDay(
        now: String,
        rows: List<WorkingDayRecord> = emptyList(),
    ): Pair<WorkingDayState, ActiveDay> {
        val at = Instant.parse(now)
        val state = WorkingDays.resolve(at, zone, rollover, rows)
        return state to ((state as? WorkingDayState.Current)?.day ?: ActiveDay.containing(at, zone, rollover))
    }

    @Test
    fun `in the evening the next active day is tomorrow`() {
        val (state, planning) = planningDay("2026-10-12T21:30:00Z")
        assertEquals(day(10, 12), planning)
        assertEquals(day(10, 13), WorkingDays.nextActiveDay(state, planning))
    }

    @Test
    fun `between midnight and the rollover the next active day is not the calendar date plus one`() {
        // 01:00 on 13 October is still active day 12 October. Calendar plus one would say 14 October and skip a day.
        val at = Instant.parse("2026-10-13T01:00:00Z")
        val (state, planning) = planningDay("2026-10-13T01:00:00Z")
        assertEquals(day(10, 12), planning)
        val next = WorkingDays.nextActiveDay(state, planning)
        assertEquals(day(10, 13), next)
        assertEquals(LocalDate.of(2026, 10, 14), at.atZone(zone).toLocalDate().plusDays(1), "the naive answer is a different day")
    }

    @Test
    fun `after a closed day at midnight the next day is still the day after the planning day`() {
        val rows = listOf(WorkingDayRecord(day(10, 12), Instant.parse("2026-10-12T12:00:00Z"), Instant.parse("2026-10-13T00:30:00Z")))
        val (state, planning) = planningDay("2026-10-13T01:00:00Z", rows)
        assertIs<WorkingDayState.NoneOpen>(state)
        assertEquals(day(10, 13), WorkingDays.nextActiveDay(state, planning))
    }

    @Test
    fun `a current working day continued past its own label gives the day after that label`() {
        val rows =
            listOf(
                WorkingDayRecord(day(10, 11), Instant.parse("2026-10-11T12:00:00Z"), continuedOn = day(10, 12)),
            )
        val (state, planning) = planningDay("2026-10-12T10:00:00Z", rows)
        assertEquals(WorkingDayState.Current(day(10, 11)), state)
        assertEquals(day(10, 12), WorkingDays.nextActiveDay(state, planning))
    }

    @Test
    fun `no next day is named while an earlier working day awaits a choice`() {
        val rows = listOf(WorkingDayRecord(day(10, 10), Instant.parse("2026-10-10T12:00:00Z")))
        val (state, planning) = planningDay("2026-10-12T10:00:00Z", rows)
        assertIs<WorkingDayState.Choose>(state)
        assertNull(WorkingDays.nextActiveDay(state, planning))
    }
}
