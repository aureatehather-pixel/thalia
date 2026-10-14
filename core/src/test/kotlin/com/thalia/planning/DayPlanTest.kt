package com.thalia.planning

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The approved plan's rows, their snapshot of the action and project, and the Unfinished list. */
class DayPlanTest {
    private val day = ActiveDay(LocalDate.of(2026, 10, 2))
    private val project = ProjectRef("p1", "Book", ProjectState.ACTIVE)
    private val dentist = PlannedAction(Action("dentist", "Dentist", null, Priority.NORMAL, 60), 1)
    private val draft = PlannedAction(Action("draft", "Draft chapter", "p1", Priority.MUST, 120), 2)
    private val paused = PlannedAction(Action("paused", "Paused work", "p2", Priority.NORMAL, 30), 3)

    private fun proposal(): PlanProposal {
        val inputs =
            PlanInputs(
                day,
                ZoneId.of("UTC"),
                LocalTime.of(4, 0),
                PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0)),
                false,
                setOf("dentist", "draft", "paused"),
                mapOf("dentist" to LocalTime.of(14, 0)),
                0,
            )
        return Planner.propose(inputs, listOf(dentist, draft, paused), listOf(project, ProjectRef("p2", "Old", ProjectState.PAUSED)))
    }

    @Test
    fun `an approved plan snapshots the plan, every item and the project as they were`() {
        var n = 0
        val rows = proposal().toApprovedRows("plan-1", 3, 5_000) { "item-${n++}" }

        assertEquals(
            DayPlan(
                "plan-1",
                day,
                3,
                ZoneId.of("UTC"),
                LocalTime.of(4, 0),
                PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0)),
                540,
                432,
                0,
                5_000,
            ),
            rows.plan,
        )
        assertEquals(listOf("dentist", "draft", "paused"), rows.items.map { it.actionId })
        val pinned = rows.items[0]
        assertEquals(ItemKind.PINNED, pinned.kind)
        assertEquals(LocalTime.of(14, 0), pinned.start)
        assertNull(pinned.projectId)
        val queued = rows.items[1]
        assertEquals(Triple("Draft chapter", "Book", ProjectState.ACTIVE), Triple(queued.title, queued.projectTitle, queued.projectState))
        assertEquals(Placement.WITHIN_TARGET, queued.placement)
        val left = rows.items[2]
        assertEquals(LeftOutReason.PROJECT_NOT_ACTIVE, left.reason)
        assertEquals(ProjectState.PAUSED, left.projectState)
        assertEquals(setOf("dentist", "draft", "paused"), rows.selected)
        assertEquals(mapOf("dentist" to LocalTime.of(14, 0)), rows.pins)
    }

    @Test
    fun `a proposal with errors cannot be turned into a plan`() {
        val bad = proposal().copy(errors = listOf(PlanError(PlanErrorKind.PIN_OVERLAP)))
        assertFailsWith<IllegalStateException> { bad.toApprovedRows("p", 1, 0) { "i" } }
    }

    @Test
    fun `plan rows validate themselves`() {
        var n = 0
        val rows = proposal().toApprovedRows("plan-1", 1, 0) { "item-${n++}" }
        assertFailsWith<IllegalArgumentException> { rows.plan.copy(revision = 0) }
        assertFailsWith<IllegalArgumentException> { rows.plan.copy(targetMinutes = rows.plan.freeMinutes + 1) }
        assertFailsWith<IllegalArgumentException> { rows.items[0].copy(start = null) }
        assertFailsWith<IllegalArgumentException> { rows.items[1].copy(placement = null) }
        assertFailsWith<IllegalArgumentException> { rows.items[1].copy(reason = LeftOutReason.OVER_TARGET) }
        assertFailsWith<IllegalArgumentException> { rows.items[1].copy(projectTitle = null) }
    }

    @Test
    fun `unfinished actions are open ones an earlier day placed, never left out, never today's or later days`() {
        fun plan(
            planDay: ActiveDay,
            vararg placed: Pair<String, ItemKind>,
        ): DayPlanWithItems {
            val base = proposal().toApprovedRows("plan-$planDay", 1, 0) { "x" }.plan.copy(activeDay = planDay)
            val items =
                placed.mapIndexed { i, (id, kind) ->
                    val start = if (kind == ItemKind.PINNED) LocalTime.of(14, 0) else null
                    val placement = if (kind == ItemKind.QUEUED) Placement.WITHIN_TARGET else null
                    val reason = if (kind == ItemKind.LEFT_OUT) LeftOutReason.OVER_TARGET else null
                    PlanItem("i$i$planDay", base.id, id, kind, start, 30, i, placement, reason, id, Priority.NORMAL, null, null, null)
                }
            return DayPlanWithItems(base, items)
        }
        val actions =
            listOf("a", "b", "c", "d", "e").map { Action(it, it) } + Action("f", "f", status = ActionStatus.DONE)
        val yesterday = plan(day.plusDays(-1), "a" to ItemKind.QUEUED, "f" to ItemKind.PINNED, "b" to ItemKind.LEFT_OUT)
        val today = plan(day, "c" to ItemKind.QUEUED)
        val rest = plan(day.plusDays(-2))

        assertEquals(listOf("a"), unfinishedFor(day, listOf(yesterday, today, rest), actions).map { it.id })
        assertTrue(unfinishedFor(day.plusDays(-1), listOf(yesterday, today, rest), actions).isEmpty())
    }
}
