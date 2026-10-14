package com.thalia.planning

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0011: the working-day resolver's documented examples, capacity arithmetic and re-entry. */
class WorkingDayTest {
    private val zone = ZoneId.of("Asia/Kuala_Lumpur")
    private val rollover = LocalTime.of(4, 0)

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun day(text: String) = ActiveDay(LocalDate.parse(text))

    private fun resolve(
        now: String,
        vararg rows: WorkingDayRecord,
    ) = WorkingDays.resolve(at(now), zone, rollover, rows.toList())

    @Test
    fun `work continuing through 05 00 asks once to continue, and continuing keeps the same day`() {
        val open = WorkingDayRecord(day("2026-10-06"), openedAt = at("2026-10-06T13:00"))
        assertEquals(WorkingDayState.Current(day("2026-10-06")), resolve("2026-10-07T03:59", open))

        val choose = assertIs<WorkingDayState.Choose>(resolve("2026-10-07T05:00", open))
        assertEquals(day("2026-10-06"), choose.open)
        assertEquals(day("2026-10-07"), choose.startable)

        val continued = open.copy(continuedOn = day("2026-10-07"))
        assertEquals(WorkingDayState.Current(day("2026-10-06")), resolve("2026-10-07T05:00", continued))
        assertEquals(WorkingDayState.Current(day("2026-10-06")), resolve("2026-10-07T23:00", continued))
        assertIs<WorkingDayState.Choose>(resolve("2026-10-08T05:00", continued))
    }

    @Test
    fun `closing at 01 00 and opening at 03 30 opens the real calendar date, never a derived one`() {
        val closed = WorkingDayRecord(day("2026-10-06"), openedAt = at("2026-10-06T13:00"), closedAt = at("2026-10-07T01:00"))
        val none = assertIs<WorkingDayState.NoneOpen>(resolve("2026-10-07T03:30", closed))
        assertEquals(day("2026-10-06"), none.clockDay)
        assertEquals(day("2026-10-07"), none.openable)
        assertEquals(false, none.reopens)

        val early = WorkingDayRecord(day("2026-10-07"), openedAt = at("2026-10-07T03:30"))
        assertEquals(WorkingDayState.Current(day("2026-10-07")), resolve("2026-10-07T03:45", closed, early))
        assertEquals(day("2026-10-07"), WorkingDays.outcomeDay(WorkingDayState.Current(day("2026-10-07")), day("2026-10-06")))
    }

    @Test
    fun `closing and reopening twice on one calendar date reopens the same working day`() {
        val closedOnce = WorkingDayRecord(day("2026-10-06"), openedAt = at("2026-10-06T09:00"), closedAt = at("2026-10-06T13:00"))
        val atThree = assertIs<WorkingDayState.NoneOpen>(resolve("2026-10-06T15:00", closedOnce))
        assertEquals(day("2026-10-06"), atThree.openable)
        assertTrue(atThree.reopens)

        val closedTwice = closedOnce.copy(closedAt = at("2026-10-06T23:00"))
        val late = assertIs<WorkingDayState.NoneOpen>(resolve("2026-10-06T23:30", closedTwice))
        assertEquals(day("2026-10-06"), late.openable)
        assertTrue(late.reopens)
    }

    @Test
    fun `returning after three days without closing offers a choice and recommends nothing from the old day`() {
        val open = WorkingDayRecord(day("2026-10-06"), openedAt = at("2026-10-06T13:00"))
        val choose = assertIs<WorkingDayState.Choose>(resolve("2026-10-09T14:00", open))
        assertEquals(day("2026-10-06"), choose.open)
        assertEquals(day("2026-10-09"), choose.startable)
        assertEquals(day("2026-10-09"), choose.clockDay)
        // Outcomes stay recordable for the old day, which is earlier than the clock's.
        assertEquals(day("2026-10-09"), WorkingDays.outcomeDay(choose, day("2026-10-09")))
    }

    @Test
    fun `with nothing ever opened, opening offers the clock's active day`() {
        val none = assertIs<WorkingDayState.NoneOpen>(resolve("2026-10-06T02:00"))
        assertEquals(day("2026-10-05"), none.openable)
    }

    // Capacity

    private val utc = ZoneId.of("UTC")
    private val planDay = ActiveDay(LocalDate.of(2026, 10, 2))
    private val window = PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))

    private fun utcAt(time: String): Instant = LocalDateTime.parse("2026-10-02T$time").atZone(utc).toInstant()

    private fun commitment(
        id: String,
        from: String,
        to: String,
    ) = FixedCommitment(id, "Fixed $id", utcAt(from), utcAt(to), utc)

    private fun propose(
        commitments: List<FixedCommitment> = emptyList(),
        focus: Int? = null,
        pins: Map<String, LocalTime> = emptyMap(),
    ): PlanProposal {
        val actions =
            listOf(
                PlannedAction(Action("a", "a", null, Priority.NORMAL, 60), 1),
                PlannedAction(Action("b", "b", null, Priority.MUST, 60), 2),
            )
        val inputs = PlanInputs(planDay, utc, LocalTime.of(4, 0), window, false, setOf("a", "b"), pins, 0, commitments, focus)
        return Planner.propose(inputs, actions, emptyList()).also { assertEquals(emptyList(), PlanValidator.validate(it)) }
    }

    @Test
    fun `overlapping commitments and pins are counted once, and time outside the window not at all`() {
        val plain = propose()
        assertEquals(600, plain.freeMinutes)
        val overlapping =
            propose(listOf(commitment("x", "13:00", "15:00"), commitment("y", "14:00", "16:00"), commitment("z", "08:00", "12:30")))
        assertEquals(30 + 180, overlapping.occupiedMinutes)
        assertEquals(600 - 210, overlapping.freeMinutes)

        val pinInside = propose(listOf(commitment("x", "13:00", "15:00")), pins = mapOf("a" to LocalTime.of(14, 0)))
        assertEquals(120, pinInside.occupiedMinutes)
        assertEquals(480, pinInside.freeMinutes)
        assertTrue(pinInside.warnings.any { it.kind == PlanWarningKind.PIN_OVERLAPS_COMMITMENT && it.actionIds == listOf("a") })
    }

    @Test
    fun `focus capacity caps the target without changing free clock time`() {
        val capped = propose(focus = 90)
        assertEquals(600, capped.freeMinutes)
        assertEquals(90, capped.targetMinutes)
        assertEquals(480, propose(focus = 600).targetMinutes)
        assertEquals(480, propose().targetMinutes)
    }

    @Test
    fun `a moved commitment or a changed focus makes a proposal differ even when free minutes are the same`() {
        val before = propose(listOf(commitment("x", "13:00", "14:00")))
        val moved = propose(listOf(commitment("x", "15:00", "16:00")))
        assertEquals(before.freeMinutes, moved.freeMinutes)
        assertNotEquals(before, moved)
        assertNotEquals(propose(focus = 500), propose(focus = 520))
        assertEquals(propose(focus = 500).freeMinutes, propose(focus = 520).freeMinutes)
    }

    // Re-entry

    private fun plan(vararg items: Pair<String, Int>): DayPlanWithItems {
        val plan = DayPlan("plan", planDay, 1, utc, LocalTime.of(4, 0), window, 600, 480, 0, 0)
        val rows =
            items.mapIndexed { index, (id, minutes) ->
                PlanItem(
                    "i-$id",
                    "plan",
                    id,
                    ItemKind.QUEUED,
                    null,
                    minutes,
                    index,
                    Placement.WITHIN_TARGET,
                    null,
                    id,
                    Priority.NORMAL,
                    null,
                    null,
                    null,
                )
            }
        return DayPlanWithItems(plan, rows)
    }

    private fun outcome(
        id: String,
        result: OutcomeResult,
    ) = "i-$id" to PlanOutcome("i-$id", result, 1)

    private val open = mapOf("a" to ActionStatus.OPEN, "b" to ActionStatus.OPEN, "c" to ActionStatus.OPEN)

    @Test
    fun `when everything still fits, no question is asked and the next approved action is shown`() {
        val reEntry = ReEntries.compute(plan("a" to 60, "b" to 60), emptyMap(), open, emptyList(), null, utcAt("13:00"), declined = false)
        assertEquals("a", reEntry.next?.actionId)
        assertNull(reEntry.question)
        assertEquals(540, reEntry.remainingFreeMinutes)
    }

    @Test
    fun `an unknown is asked about only when it decides whether the rest fits, and never again after don't know`() {
        // 21:00: 60 minutes left. a unknown (60) and b partial (60): 120 to do if a is not done, 60 if it is.
        val outcomes = mapOf(outcome("b", OutcomeResult.PARTIAL))
        val reEntry = ReEntries.compute(plan("a" to 60, "b" to 60), outcomes, open, emptyList(), null, utcAt("21:00"), declined = false)
        assertEquals("a", reEntry.question?.actionId)
        assertEquals("a", reEntry.next?.actionId)
        assertEquals(60, reEntry.overrunMinutes)
        assertNull(
            ReEntries.compute(plan("a" to 60, "b" to 60), outcomes, open, emptyList(), null, utcAt("21:00"), declined = true).question,
        )
    }

    @Test
    fun `no question when the answer cannot change feasibility, and a commitment reduces the time left`() {
        // 20:00 with a commitment 20:00-21:00: 60 minutes left; b partial alone already needs 90.
        val outcomes = mapOf(outcome("b", OutcomeResult.PARTIAL))
        val busy = listOf(commitment("x", "20:00", "21:00"))
        val reEntry = ReEntries.compute(plan("a" to 30, "b" to 90), outcomes, open, busy, null, utcAt("20:00"), declined = false)
        assertEquals(60, reEntry.remainingFreeMinutes)
        assertNull(reEntry.question)
        assertEquals(60, reEntry.overrunMinutes)
        assertEquals(540, reEntry.freeNowMinutes)
    }

    @Test
    fun `completed, skipped and blocked items are not next, and order is the approved one`() {
        val outcomes = mapOf(outcome("a", OutcomeResult.DONE), outcome("b", OutcomeResult.BLOCKED))
        val status = open + ("a" to ActionStatus.DONE)
        val reEntry =
            ReEntries.compute(
                plan("a" to 30, "b" to 30, "c" to 30),
                outcomes,
                status,
                emptyList(),
                20,
                utcAt("13:00"),
                declined = false,
            )
        assertEquals("c", reEntry.next?.actionId)
        assertEquals(20, reEntry.focusBelowPlanMinutes)
    }

    @Test
    fun `after the window ends nothing is asked`() {
        val reEntry = ReEntries.compute(plan("a" to 60), emptyMap(), open, emptyList(), null, utcAt("22:30"), declined = false)
        assertTrue(reEntry.windowOver)
        assertNull(reEntry.question)
        assertEquals("a", reEntry.next?.actionId)
    }
}
