package com.thalia.app.planning

import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.DayPlan
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.FixedCommitment
import com.thalia.planning.ItemKind
import com.thalia.planning.OutcomeResult
import com.thalia.planning.Placement
import com.thalia.planning.PlanItem
import com.thalia.planning.PlanOutcome
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
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

/** Today's view is built from the approved plan, outcomes and commitments only. These tests use real domain objects, never samples. */
class TodayViewTest {
    private val zone = ZoneId.of("UTC")
    private val day = ActiveDay(LocalDate.of(2026, 10, 12))

    private fun at(hhmm: String): Instant = Instant.parse("2026-10-12T$hhmm:00Z")

    private val plan =
        DayPlan("p1", day, 1, zone, LocalTime.of(4, 0), PlanningWindow(LocalTime.of(9, 0), LocalTime.of(20, 0)), 540, 432, 0, 1L)

    private fun pinned(
        id: String,
        start: String,
        minutes: Int,
        priority: Priority = Priority.NORMAL,
        position: Int = 0,
    ) = PlanItem(
        id,
        "p1",
        "a-$id",
        ItemKind.PINNED,
        LocalTime.parse(start),
        minutes,
        position,
        null,
        null,
        "T-$id",
        priority,
        null,
        null,
        null,
    )

    private fun queued(
        id: String,
        position: Int,
        priority: Priority = Priority.NORMAL,
    ) = PlanItem(id, "p1", "a-$id", ItemKind.QUEUED, null, 15, position, Placement.WITHIN_TARGET, null, "T-$id", priority, null, null, null)

    private fun build(
        now: String,
        items: List<PlanItem>,
        outcomes: List<PlanOutcome> = emptyList(),
        commitments: List<FixedCommitment> = emptyList(),
        actions: List<Action> = emptyList(),
        today: ActiveDay = day,
    ) = buildTodayView(at(now), today, DayPlanWithItems(plan, items), outcomes.associateBy { it.planItemId }, actions, commitments)

    @Test
    fun `the block in progress is Now with real time left and the rest is up next`() {
        val v = build("15:25", listOf(pinned("read", "15:00", 60), pinned("vendor", "18:30", 30)))
        val now = assertNotNull(v.now)
        assertEquals("T-read", now.title)
        assertEquals(35, minutesLeft(now, at("15:25")))
        assertEquals(listOf("T-vendor"), v.upNext.map { it.title })
        assertTrue(v.earlier.isEmpty())
    }

    @Test
    fun `a partly started minute still reads as a minute left, and the end is exclusive`() {
        val v = build("15:59", listOf(pinned("read", "15:00", 60)))
        assertEquals(1, minutesLeft(assertNotNull(v.now), at("15:59")))
        val over = build("16:00", listOf(pinned("read", "15:00", 60)))
        assertNull(over.now)
        assertEquals(listOf("T-read"), over.earlier.map { it.title })
    }

    @Test
    fun `a block ending asks about it once and never marks it`() {
        val items = listOf(pinned("demo", "09:00", 60), pinned("report", "10:00", 60), pinned("read", "15:00", 60))
        val v = build("12:00", items)
        assertEquals("T-report", v.toRecord?.title, "the most recently ended block with no outcome")
        assertEquals(2, v.earlierToRecord)
        assertTrue(v.earlier.all { it.result == null }, "ending a block records nothing")
    }

    @Test
    fun `a recorded result is shown, and an explicit Unknown counts as none`() {
        val items = listOf(pinned("demo", "09:00", 60), pinned("report", "10:00", 60))
        val outcomes = listOf(PlanOutcome("demo", OutcomeResult.DONE, 5L), PlanOutcome("report", OutcomeResult.UNKNOWN, 6L))
        val v = build("12:00", items, outcomes)
        assertEquals(OutcomeResult.DONE, v.earlier.first { it.itemId == "demo" }.result)
        assertNull(v.earlier.first { it.itemId == "report" }.result)
        assertEquals("T-report", v.toRecord?.title)
        assertEquals(1, v.earlierToRecord)
    }

    @Test
    fun `fixed commitments sit on the timeline, take no outcome and are never asked about`() {
        val fixed = FixedCommitment("c1", "Team call", at("11:00"), at("11:30"), zone)
        val v = build("12:00", listOf(pinned("demo", "09:00", 60)), commitments = listOf(fixed))
        val row = v.earlier.first { it.fixed }
        assertEquals("Team call", row.title)
        assertNull(row.itemId)
        assertFalse(row.canRecord)
        assertEquals("T-demo", v.toRecord?.title)
        val during = build("11:10", emptyList(), commitments = listOf(fixed))
        assertEquals("Team call", during.now?.title)
    }

    @Test
    fun `queued items keep the plan's order and carry their outcome`() {
        val v =
            build(
                "08:00",
                listOf(queued("b", 1), queued("a", 0, Priority.IMPORTANT), pinned("x", "09:00", 30, position = 2)),
                listOf(PlanOutcome("b", OutcomeResult.PARTIAL, 3L)),
            )
        assertEquals(listOf("T-a", "T-b"), v.queue.map { it.title })
        assertEquals(OutcomeResult.PARTIAL, v.queue.last().result)
        assertEquals(Priority.IMPORTANT, v.queue.first().priority)
    }

    @Test
    fun `left out items appear nowhere, and a plan for a later day takes no outcome yet`() {
        val left =
            PlanItem(
                "l",
                "p1",
                "a-l",
                ItemKind.LEFT_OUT,
                null,
                30,
                3,
                null,
                com.thalia.planning.LeftOutReason.OVER_TARGET,
                "Left",
                Priority.OPTIONAL,
                null,
                null,
                null,
            )
        val v = build("12:00", listOf(pinned("demo", "09:00", 60), left), today = ActiveDay(LocalDate.of(2026, 10, 11)))
        assertTrue(v.earlier.none { it.title == "Left" } && v.queue.isEmpty())
        assertFalse(v.earlier.first().canRecord)
        assertNull(v.toRecord)
    }

    @Test
    fun `with no approved plan there is nothing but real commitments`() {
        val fixed = FixedCommitment("c1", "Dentist", at("17:00"), at("18:00"), zone)
        val v = buildTodayView(at("12:00"), day, null, emptyMap(), emptyList(), listOf(fixed))
        assertFalse(v.hasPlan)
        assertEquals(listOf("Dentist"), v.upNext.map { it.title })
        assertTrue(v.queue.isEmpty() && v.toRecord == null)
        assertTrue(
            buildTodayView(at("12:00"), day, null, emptyMap(), emptyList(), emptyList()).let { it.upNext.isEmpty() && it.now == null },
        )
    }

    @Test
    fun `the action's status comes from the action as it is now`() {
        val v = build("12:00", listOf(pinned("demo", "09:00", 60)), actions = listOf(Action("a-demo", "Demo", status = ActionStatus.DONE)))
        assertEquals(ActionStatus.DONE, v.earlier.single().actionStatus)
    }

    @Test
    fun `after this is the next block that starts once this one ends`() {
        val v = build("15:10", listOf(pinned("read", "15:00", 60), pinned("vendor", "18:30", 30), pinned("dentist", "17:00", 30)))
        assertEquals("T-dentist", v.after(assertNotNull(v.now))?.title)
    }

    @Test
    fun `progress runs from zero to one over the block`() {
        val v = build("15:30", listOf(pinned("read", "15:00", 60)))
        val block = assertNotNull(v.now)
        assertEquals(0.5f, progressOf(block, at("15:30")))
        assertEquals(0f, progressOf(block, at("14:00")))
        assertEquals(1f, progressOf(block, at("17:00")))
    }

    @Test
    fun `only one outcome prompt asks at a time, and the working-day question waits for the card`() {
        val items = listOf(pinned("demo", "09:00", 60), pinned("report", "10:00", 60))
        val v = build("12:00", items)
        assertTrue(PromptRule.cardShown(v, null), "an ended block with no outcome shows the card")
        assertFalse(PromptRule.mayAskQuestion(PromptRule.cardShown(v, null)), "so the question does not also ask")

        assertFalse(PromptRule.cardShown(v, "demo"), "the focus screen replaces the card")
        assertTrue(PromptRule.mayAskQuestion(PromptRule.cardShown(v, "demo")))

        val recorded =
            build("12:00", items, listOf(PlanOutcome("demo", OutcomeResult.DONE, 5L), PlanOutcome("report", OutcomeResult.SKIPPED, 6L)))
        assertFalse(PromptRule.cardShown(recorded, null), "nothing left to ask about")
        assertTrue(PromptRule.mayAskQuestion(PromptRule.cardShown(recorded, null)), "the question may ask once the card is gone")

        val noPlan = buildTodayView(at("12:00"), day, null, emptyMap(), emptyList(), emptyList())
        assertFalse(PromptRule.cardShown(noPlan, null))
    }

    @Test
    fun `past blocks stay visible and a fixed commitment is never counted as a block to record`() {
        val fixed = FixedCommitment("c1", "Team call", at("11:00"), at("11:30"), zone)
        val v = build("12:00", listOf(pinned("demo", "09:00", 60)), commitments = listOf(fixed))
        assertEquals(setOf("T-demo", "Team call"), v.earlier.map { it.title }.toSet(), "both ended blocks are still listed")
        assertEquals(1, v.earlierToRecord, "only the planned item awaits an outcome")
    }
}
