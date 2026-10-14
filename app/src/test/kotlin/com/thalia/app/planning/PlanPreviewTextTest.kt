package com.thalia.app.planning

import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.PlanInputs
import com.thalia.planning.PlannedAction
import com.thalia.planning.Planner
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import com.thalia.planning.ProjectRef
import com.thalia.planning.ProjectState
import com.thalia.planning.toApprovedRows
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The preview says plainly what the planner decided. It never presents unresolved work as scheduled. */
class PlanPreviewTextTest {
    private fun propose(
        vararg actions: Action,
        focus: Int? = null,
    ): com.thalia.planning.PlanProposal {
        val planned = actions.mapIndexed { i, a -> PlannedAction(a, i.toLong()) }
        val inputs =
            PlanInputs(
                ActiveDay(LocalDate.of(2026, 10, 3)),
                ZoneId.of("UTC"),
                LocalTime.of(4, 0),
                PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0)),
                true,
                actions.map { it.id }.toSet(),
                emptyMap(),
                0,
                focusMinutes = focus,
            )
        return Planner.propose(
            inputs,
            planned,
            listOf(ProjectRef("p", "Paused", ProjectState.PAUSED), ProjectRef("a", "Active", ProjectState.ACTIVE)),
        )
    }

    @Test
    fun `must work that does not fit is stated with its minutes and labelled, never as scheduled`() {
        val proposal = propose(Action("m1", "Draft", "a", Priority.MUST, 400), Action("m2", "Review", "a", Priority.MUST, 300))
        val text = previewText(proposal) { if (it == "m1") "Draft" else "Review" }

        assertTrue("100 minutes of Must work do not fit in this window." in text.warnings.single { "do not fit" in it })
        assertEquals(listOf("1. Draft (400 min)", "2. Review (300 min)  [DOES NOT FIT]"), text.queue)
        assertEquals(
            listOf("Window 12:00 to 22:00 (600 min), UTC", "Pinned 0 min. Free time 600 min. Target 480 min (80% of free time)."),
            text.summary,
        )
    }

    @Test
    fun `focus capacity that sets the target is named, not 80 percent of free time`() {
        val actions = arrayOf(Action("m", "Draft", "a", Priority.MUST, 400), Action("n", "Extra", null, Priority.NORMAL, 100))
        val text = previewText(propose(*actions, focus = 300)) { if (it == "m") "Draft" else "Extra" }

        assertEquals("Pinned 0 min. Free time 600 min. Target 300 min (your focus capacity).", text.summary[1])
        assertTrue(text.summary.none { "80%" in it })
        assertEquals("Must work uses 100 minutes between your focus capacity and free time.", text.warnings.single())
        assertEquals(listOf("Extra (100 min): over your focus capacity"), text.leftOut)
    }

    @Test
    fun `80 percent of free time is named when it sets the target, even with a larger focus capacity`() {
        val actions = arrayOf(Action("m", "Draft", "a", Priority.MUST, 500), Action("n", "Extra", null, Priority.NORMAL, 100))
        val text = previewText(propose(*actions, focus = 540)) { if (it == "m") "Draft" else "Extra" }

        assertEquals("Pinned 0 min. Free time 600 min. Target 480 min (80% of free time).", text.summary[1])
        assertEquals("Must work uses 20 minutes of the 20% buffer.", text.warnings.single())
        assertEquals(listOf("Extra (100 min): over the 80% target"), text.leftOut)
    }

    @Test
    fun `left out items give their reason and a must action in a paused project is flagged`() {
        val proposal = propose(Action("m", "Must in paused", "p", Priority.MUST, 60), Action("n", "Normal", null, Priority.NORMAL, 30))
        val text = previewText(proposal) { if (it == "m") "Must in paused" else "Normal" }

        assertEquals(listOf("Must in paused (60 min): its project is not active"), text.leftOut)
        assertTrue(
            text.warnings.single().startsWith(
                "A Must action is selected but its project is not active, so it is not planned: Must in paused.",
            ),
        )
        assertEquals(listOf("1. Normal (30 min)"), text.queue)
    }

    @Test
    fun `an approved plan reads from its own snapshot`() {
        val proposal = propose(Action("n", "Normal", null, Priority.NORMAL, 30))
        val plan = proposal.toApprovedRows("plan", 2, 0) { "i" }
        val text = approvedText(plan)
        assertEquals("Plan approved, 2026-10-03, revision 2", text.summary.first())
        assertEquals(listOf("1. Normal (30 min)"), text.queue)
        assertTrue(text.warnings.isEmpty())
    }

    @Test
    fun `errors name what to fix`() {
        val done = Action("d", "Already done", status = com.thalia.planning.ActionStatus.DONE)
        val text = previewText(propose(done)) { "Already done" }
        assertEquals(listOf("Not open any more, or not found: Already done."), text.errors)
    }

    @Test
    fun `a rest day says so, in the preview and when approved`() {
        val rest = propose()
        assertTrue(previewText(rest) { it }.summary.contains("Rest day: nothing is selected, so nothing is planned."))
        assertTrue(approvedText(rest.toApprovedRows("plan", 1, 0) { "i" }).summary.contains("Rest day: nothing planned."))
    }
}
