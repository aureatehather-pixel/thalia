package com.thalia.planning

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** ADR 0009 decision 3: the active day, its boundary, and daylight-saving gaps and overlaps. */
class ActiveDayTest {
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private val newYork = ZoneId.of("America/New_York")
    private val rollover = LocalTime.of(4, 0)

    private fun day(
        local: String,
        zone: ZoneId = kl,
        rollover: LocalTime = this.rollover,
    ) = ActiveDay.containing(LocalDateTime.parse(local).atZone(zone).toInstant(), zone, rollover)

    @Test
    fun `03_59 on 3 October is active day 2 October and 04_00 is 3 October`() {
        assertEquals(ActiveDay(LocalDate.of(2026, 10, 2)), day("2026-10-03T03:59"))
        assertEquals(ActiveDay(LocalDate.of(2026, 10, 3)), day("2026-10-03T04:00"))
        assertEquals(ActiveDay(LocalDate.of(2026, 10, 2)), day("2026-10-03T00:45"))
        assertEquals(ActiveDay(LocalDate.of(2026, 10, 3)), day("2026-10-03T23:59"))
    }

    @Test
    fun `the same moment is a different active day under a different rollover`() {
        assertEquals(ActiveDay(LocalDate.of(2026, 10, 2)), day("2026-10-03T05:30", rollover = LocalTime.of(6, 0)))
        assertEquals(ActiveDay(LocalDate.of(2026, 10, 3)), day("2026-10-03T05:30", rollover = LocalTime.of(5, 0)))
    }

    @Test
    fun `a time after midnight but before the rollover belongs to the next calendar date`() {
        val d = ActiveDay(LocalDate.of(2026, 10, 2))
        assertEquals(LocalDateTime.parse("2026-10-02T22:00"), d.localOf(rollover, LocalTime.of(22, 0)))
        assertEquals(LocalDateTime.parse("2026-10-03T00:30"), d.localOf(rollover, LocalTime.of(0, 30)))
        assertEquals(LocalDateTime.parse("2026-10-02T04:00"), d.localOf(rollover, LocalTime.of(4, 0)))
        assertEquals(LocalDateTime.parse("2026-10-03T03:59"), d.localOf(rollover, LocalTime.of(3, 59)))
    }

    @Test
    fun `a time in the spring-forward gap is refused, never shifted`() {
        val gap = LocalDateTime.parse("2026-03-08T02:30")
        val refused = assertFailsWith<NonexistentLocalTimeException> { ActiveDay.resolve(gap, newYork) }
        assertEquals(gap, refused.local)
        // The minutes either side of it exist.
        assertEquals(Instant.parse("2026-03-08T06:59:00Z"), ActiveDay.resolve(LocalDateTime.parse("2026-03-08T01:59"), newYork))
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), ActiveDay.resolve(LocalDateTime.parse("2026-03-08T03:00"), newYork))
    }

    @Test
    fun `a time that happens twice in the fall-back overlap is always the first one`() {
        // 01:30 on 1 November 2026 in New York is 05:30Z (EDT) and again 06:30Z (EST). The earlier is chosen, every time.
        val ambiguous = LocalDateTime.parse("2026-11-01T01:30")
        repeat(3) { assertEquals(Instant.parse("2026-11-01T05:30:00Z"), ActiveDay.resolve(ambiguous, newYork)) }
        assertEquals(Instant.parse("2026-11-01T04:59:00Z"), ActiveDay.resolve(LocalDateTime.parse("2026-11-01T00:59"), newYork))
        assertEquals(Instant.parse("2026-11-01T07:00:00Z"), ActiveDay.resolve(LocalDateTime.parse("2026-11-01T02:00"), newYork))
    }

    @Test
    fun `the active day of a moment is read from the wall clock, also inside the overlap`() {
        // 06:30Z on 1 November is the second 01:30, wall clock 01:30: still before the 04:00 rollover.
        val secondOneThirty = Instant.parse("2026-11-01T06:30:00Z")
        assertEquals(ActiveDay(LocalDate.of(2026, 10, 31)), ActiveDay.containing(secondOneThirty, newYork, rollover))
    }
}
