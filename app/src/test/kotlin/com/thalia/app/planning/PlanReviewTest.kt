package com.thalia.app.planning

import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.FixedCommitment
import com.thalia.planning.PlanInputs
import com.thalia.planning.PlanProposal
import com.thalia.planning.PlannedAction
import com.thalia.planning.Planner
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import com.thalia.planning.toApprovedRows
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Plan tab's blocks, changes and capacity come from the real planner and real approved rows, never from samples. */
class PlanReviewTest {
    private val zone = ZoneId.of("UTC")
    private val day = ActiveDay(LocalDate.of(2026, 10, 12))
    private val window = PlanningWindow(LocalTime.of(9, 0), LocalTime.of(19, 0))

    private fun action(
        id: String,
        priority: Priority = Priority.NORMAL,
        minutes: Int = 30,
    ) = Action(id, "T-$id", null, priority, minutes)

    private fun propose(
        actions: List<Action>,
        selected: Set<String> = actions.map { it.id }.toSet(),
        pins: Map<String, LocalTime> = emptyMap(),
        commitments: List<FixedCommitment> = emptyList(),
    ): PlanProposal =
        Planner.propose(
            PlanInputs(day, zone, LocalTime.of(4, 0), window, true, selected, pins, 0, commitments),
            actions.mapIndexed { i, a -> PlannedAction(a, i.toLong()) },
            emptyList(),
        )

    private fun approve(proposal: PlanProposal): DayPlanWithItems {
        var n = 0
        return proposal.toApprovedRows("plan-1", 1, 1L) { "i${n++}" }
    }

    private val a = action("a")
    private val b = action("b", Priority.IMPORTANT)
    private val c = action("c", minutes = 45)
    private val all = listOf(a, b, c)

    @Test
    fun `a first proposal has timed blocks for pins and commitments and an untimed queue`() {
        val fixed = FixedCommitment("f1", "Team call", Instant.parse("2026-10-12T11:00:00Z"), Instant.parse("2026-10-12T11:30:00Z"), zone)
        val review = reviewOfProposal(propose(all, pins = mapOf("a" to LocalTime.of(14, 0)), commitments = listOf(fixed)), null)
        assertEquals(listOf("Team call", "T-a"), review.timed.map { it.title }, "time order: 11:00 then 14:00")
        assertTrue(review.timed.first().fixed)
        assertEquals("14:00", review.timed.last().timeText)
        assertEquals("14:30", review.timed.last().endText)
        assertEquals(listOf("T-b", "T-c"), review.queue.map { it.title }.sorted())
        assertTrue(review.queue.indices.all { review.queue[it].position == it }, "the queue keeps its planner order and has no times")
        assertFalse(review.compared)
        assertNull(review.counts)
        assertTrue(review.timed.all { it.change == null } && review.queue.all { it.change == null })
    }

    @Test
    fun `capacity is the planned minutes against the planner's own target and free time`() {
        val p = propose(all)
        val review = reviewOfProposal(p, null)
        assertEquals(105, review.planned)
        assertEquals(p.targetMinutes, review.target)
        assertEquals(p.freeMinutes, review.free)
        assertEquals(0, review.overTarget)
    }

    @Test
    fun `the target is measured against the queue, with pinned and fixed time shown apart`() {
        val fixed = FixedCommitment("f1", "Team call", Instant.parse("2026-10-12T11:00:00Z"), Instant.parse("2026-10-12T11:30:00Z"), zone)
        val p = propose(all, pins = mapOf("a" to LocalTime.of(14, 0)), commitments = listOf(fixed))
        val review = reviewOfProposal(p, null)
        assertEquals(75, review.queued, "b 30 plus c 45 are queued; the pinned 30 min are not")
        assertEquals(105, review.planned)
        assertEquals(30, review.pinnedMinutes)
        assertEquals(30, review.fixedMinutes)
        assertEquals(p.occupiedMinutes, review.occupied)
        assertEquals(p.windowMinutes, review.window)
        assertEquals(review.window - review.occupied, review.free)
        assertEquals(0, review.overTarget, "75 queued min is under the planner's target, whatever is pinned")
        val lines = capacityLines(review).joinToString("\n")
        assertTrue(
            "Of the 600 min window, pinned items take 30 min and fixed commitments take 30 min. " +
                "Together they use 60 min, overlaps counted once. That leaves 540 min free." in lines,
            lines,
        )
        assertTrue("The target covers the queue only" in lines, lines)
    }

    @Test
    fun `Must work past the target is the only way the queue is over it`() {
        val m1 = action("m1", Priority.MUST, 300)
        val m2 = action("m2", Priority.MUST, 200)
        val p = propose(listOf(m1, m2))
        val review = reviewOfProposal(p, null)
        assertEquals(500, review.queued)
        assertEquals(p.targetMinutes, review.target)
        assertEquals(500 - review.target, review.overTarget)
        assertTrue("Nothing is pinned or fixed, so all 600 min of the window are free." in capacityLines(review).first())
    }

    @Test
    fun `an approved plan states the same capacity figures from its own snapshot`() {
        val p = propose(all, pins = mapOf("a" to LocalTime.of(14, 0)))
        val review = reviewOfApproved(approve(p), emptyList())
        assertEquals(75, review.queued)
        assertEquals(p.windowMinutes, review.window)
        assertEquals(p.occupiedMinutes, review.occupied)
        assertEquals(p.freeMinutes, review.free)
    }

    @Test
    fun `an unchanged proposal against the approved plan is all unchanged`() {
        val first = propose(all, pins = mapOf("a" to LocalTime.of(14, 0)))
        val review = reviewOfProposal(propose(all, pins = mapOf("a" to LocalTime.of(14, 0))), approve(first))
        val counts = assertNotNull(review.counts)
        assertEquals(ChangeCounts(3, 0, 0, 0, 0), counts)
        assertTrue(review.removed.isEmpty())
    }

    @Test
    fun `moved, new and removed are told apart, with what it was before`() {
        val approved = approve(propose(listOf(a, b), pins = mapOf("a" to LocalTime.of(14, 0))))
        val d = action("d")
        val proposal = propose(listOf(a, c, d), pins = mapOf("a" to LocalTime.of(15, 0), "c" to LocalTime.of(10, 0)))
        val review = reviewOfProposal(proposal, approved)
        val moved = review.timed.first { it.title == "T-a" }
        assertEquals(Change.MOVED, moved.change)
        assertEquals("Was 14:00", moved.before)
        assertEquals(Change.NEW, review.timed.first { it.title == "T-c" }.change)
        assertEquals(Change.NEW, review.queue.first { it.title == "T-d" }.change)
        assertEquals(listOf("T-b"), review.removed.map { it.title })
        assertEquals(ChangeCounts(0, 1, 0, 2, 1), review.counts)
    }

    @Test
    fun `a queued item that becomes pinned reads as moved from its queue place`() {
        val approved = approve(propose(listOf(a, b)))
        val review = reviewOfProposal(propose(listOf(a, b), pins = mapOf("a" to LocalTime.of(14, 0))), approved)
        val row = review.timed.first { it.title == "T-a" }
        assertEquals(Change.MOVED, row.change)
        val was = assertNotNull(row.before)
        assertTrue(was.startsWith("Was ") && was.endsWith(" in the queue"), was)
    }

    @Test
    fun `a changed estimate is a length change, not a move`() {
        val approved = approve(propose(listOf(a), pins = mapOf("a" to LocalTime.of(14, 0))))
        val longer = a.copy(estimateMinutes = 60)
        val review = reviewOfProposal(propose(listOf(longer), pins = mapOf("a" to LocalTime.of(14, 0))), approved)
        assertEquals(Change.LENGTH, review.timed.single().change)
        assertEquals("Was 30 min", review.timed.single().before)
        assertEquals(ChangeCounts(0, 0, 1, 0, 0), review.counts)
    }

    @Test
    fun `an approved plan reads from its own snapshot with the day's commitments`() {
        val fixed = FixedCommitment("f1", "Dentist", Instant.parse("2026-10-12T17:00:00Z"), Instant.parse("2026-10-12T18:00:00Z"), zone)
        val approved = approve(propose(all, pins = mapOf("b" to LocalTime.of(10, 0))))
        val review = reviewOfApproved(approved, listOf(fixed))
        assertEquals(listOf("T-b", "Dentist"), review.timed.map { it.title })
        assertEquals(105, review.planned)
        assertEquals(approved.plan.targetMinutes, review.target)
        assertEquals(approved.plan.freeMinutes, review.free)
        assertFalse(review.compared)
    }

    @Test
    fun `a commitment that ends the next day says so and sorts by its start`() {
        val fixed = FixedCommitment("f1", "Night shift", Instant.parse("2026-10-12T22:00:00Z"), Instant.parse("2026-10-13T02:00:00Z"), zone)
        val review = reviewOfProposal(propose(listOf(a), pins = mapOf("a" to LocalTime.of(10, 0)), commitments = listOf(fixed)), null)
        assertEquals(listOf("T-a", "Night shift"), review.timed.map { it.title })
        assertEquals("02:00 next day", review.timed.last().endText)
        assertEquals(240, review.timed.last().minutes)
    }

    @Test
    fun `a rest day has nothing planned and no changes to compare`() {
        val review = reviewOfProposal(propose(all, selected = emptySet()), null)
        assertTrue(review.timed.isEmpty() && review.queue.isEmpty())
        assertEquals(0, review.planned)
    }

    @Test
    fun `leaving a block out and including it again recalculates capacity and changes, and the approved plan is never touched`() {
        val approved = approve(propose(all, pins = mapOf("a" to LocalTime.of(14, 0))))
        val before = reviewOfProposal(propose(all, pins = mapOf("a" to LocalTime.of(14, 0))), approved)
        assertEquals(ChangeCounts(3, 0, 0, 0, 0), before.counts)
        assertEquals(105, before.planned)

        var adj = Adjustment(all.map { it.id }.toSet(), emptySet()).leaveOut("c")
        assertEquals(setOf("a", "b"), adj.selected)
        assertEquals(setOf("c"), adj.leftOut)
        val without = reviewOfProposal(propose(all, selected = adj.selected, pins = mapOf("a" to LocalTime.of(14, 0))), approved)
        assertEquals(60, without.planned, "capacity follows the adjustment")
        assertEquals(listOf("T-c"), without.removed.map { it.title })
        assertEquals(ChangeCounts(2, 0, 0, 0, 1), without.counts)

        adj = adj.include("c")
        val back = reviewOfProposal(propose(all, selected = adj.selected, pins = mapOf("a" to LocalTime.of(14, 0))), approved)
        assertEquals(before.planned, back.planned)
        assertEquals(before.counts, back.counts)
        assertEquals(setOf("a", "b", "c"), adj.selected)
        assertTrue(adj.leftOut.isEmpty())

        assertEquals(setOf("a", "b", "c"), approved.selected, "the approved plan still holds all three")
        assertEquals(3, approved.items.size)
    }

    @Test
    fun `a pinned block left out and included again gets its pin back from the kept pin text`() {
        val pins = mapOf("a" to LocalTime.of(14, 0))
        val out = reviewOfProposal(propose(all, selected = setOf("b", "c"), pins = pins.filterKeys { it in setOf("b", "c") }), null)
        assertTrue(out.timed.none { it.title == "T-a" })
        val back = reviewOfProposal(propose(all, selected = setOf("a", "b", "c"), pins = pins), null)
        assertEquals("14:00", back.timed.single { it.title == "T-a" }.timeText)
        assertTrue(back.queue.none { it.title == "T-a" }, "a queued item never gets a time of its own")
    }
}
