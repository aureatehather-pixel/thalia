package com.thalia.app.planning

import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.PlanInputs
import com.thalia.planning.PlanProposal
import com.thalia.planning.PlannedAction
import com.thalia.planning.Planner
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import com.thalia.planning.ProjectRef
import com.thalia.planning.ProjectState
import com.thalia.planning.toApprovedRows
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Plan explanation is built from the real planner's proposal and the real comparison, never from model text. */
class PlanExplanationTest {
    private val zone = ZoneId.of("UTC")
    private val day = ActiveDay(LocalDate.of(2026, 10, 12))
    private val window = PlanningWindow(LocalTime.of(9, 0), LocalTime.of(11, 0))

    private fun action(
        id: String,
        priority: Priority = Priority.NORMAL,
        minutes: Int = 30,
        project: String? = null,
    ) = Action(id, "T-$id", project, priority, minutes)

    private fun propose(
        actions: List<Action>,
        selected: Set<String> = actions.map { it.id }.toSet(),
        pins: Map<String, LocalTime> = emptyMap(),
        projects: List<ProjectRef> = emptyList(),
        focus: Int? = null,
        win: PlanningWindow = window,
    ): PlanProposal =
        Planner.propose(
            PlanInputs(day, zone, LocalTime.of(4, 0), win, true, selected, pins, 0, emptyList(), focus),
            actions.mapIndexed { i, a -> PlannedAction(a, i.toLong()) },
            projects,
        )

    private fun approve(p: PlanProposal): DayPlanWithItems {
        var n = 0
        return p.toApprovedRows("plan-1", 1, 1L) { "i${n++}" }
    }

    private fun explain(
        p: PlanProposal,
        approved: DayPlanWithItems? = null,
        byYou: Int = 0,
    ): String {
        val titles = p.actions.associate { it.action.id to it.action.title }
        return explainProposal(p, reviewOfProposal(p, approved), approved, byYou) { titles[it] ?: "an action" }.joinToString("\n")
    }

    @Test
    fun `a first proposal states the window, free time, target, counts and pins from the planner's figures`() {
        val a = action("a")
        val b = action("b", Priority.MUST)
        val c = action("c", Priority.IMPORTANT)
        val p = propose(listOf(a, b, c), pins = mapOf("a" to LocalTime.of(9, 0)))
        val text = explain(p)
        assertTrue("Your window is 120 min. Pins and fixed commitments take 30 min" in text, text)
        assertTrue("so 90 min are free" in text && "target for flexible work is 72 min, 80% of free time" in text, text)
        assertTrue("Planned: 90 min in 3 items (1 pinned, 2 in the queue)." in text, text)
        assertTrue("Pinned at the times you chose: T-a at 09:00." in text, text)
        assertTrue("The queue takes 60 min against the 72 min target. Pinned time is not part of the target." in text, text)
        assertTrue("ordered Must, Important, Normal, Optional" in text, text)
        assertFalse("Compared with" in text)
    }

    @Test
    fun `left-out items are counted once and their reasons are left to the Left out lists`() {
        val projects =
            listOf("p1", "p2", "p3").map { ProjectRef(it, "P-$it", ProjectState.ACTIVE) } + ProjectRef("p4", "P-p4", ProjectState.PLANNED)
        val a1 = action("a", Priority.MUST, 10, "p1")
        val a2 = action("b", Priority.MUST, 10, "p2")
        val a3 = action("c", Priority.NORMAL, 10, "p3")
        val a4 = action("d", Priority.NORMAL, 10, "p4")
        val big = action("e", Priority.OPTIONAL, 90)
        val p = propose(listOf(a1, a2, a3, a4, big), projects = projects)
        val text = explain(p)
        assertTrue("The planner left out 3 items. Each reason is listed under Left out above." in text, text)
        assertFalse(
            "project is not active" in text || "at most two projects" in text || "would pass" in text,
            "no reason is repeated here: $text",
        )
        // The reasons themselves stay visible in the planner's own list.
        val reasons =
            previewText(p) { id ->
                p.actions
                    .first { it.action.id == id }
                    .action.title
            }.leftOut.joinToString("\n")
        assertTrue("project limit" in reasons && "its project is not active" in reasons && "over the 80% target" in reasons, reasons)
    }

    @Test
    fun `a Must item in the buffer or past free time is named with the figures`() {
        val m1 = action("a", Priority.MUST, 70)
        val m2 = action("b", Priority.MUST, 40)
        val buffer = explain(propose(listOf(m1, m2)))
        assertTrue("T-b is a Must item. It goes past the 96 min target, into the buffer, but within free time." in buffer, buffer)
        val m3 = action("c", Priority.MUST, 60)
        val over = explain(propose(listOf(m1, m3)))
        assertTrue(
            "T-c is a Must item. It is still listed, but it does not fit in the 120 free min." in over || "does not fit" in over,
            over,
        )
    }

    @Test
    fun `a stated focus capacity that sets the target is named as the reason`() {
        val text = explain(propose(listOf(action("a")), focus = 40))
        assertTrue("target for flexible work is 40 min, capped at your focus capacity of 40 min" in text, text)
        val roomy = explain(propose(listOf(action("a")), focus = 500))
        assertTrue("80% of free time" in roomy, roomy)
    }

    @Test
    fun `a replacement says what moved, changed length, is new, was removed and how capacity changed`() {
        val a = action("a")
        val b = action("b", Priority.IMPORTANT)
        val approved = approve(propose(listOf(a, b), pins = mapOf("a" to LocalTime.of(9, 0))))
        val longer = a.copy(estimateMinutes = 45)
        val c = action("c")
        val text =
            explain(
                propose(
                    listOf(longer, c),
                    pins = mapOf("a" to LocalTime.of(9, 30)),
                    win = PlanningWindow(LocalTime.of(9, 0), LocalTime.of(12, 0)),
                ),
                approved,
            )
        assertTrue("Compared with revision 1:" in text, text)
        assertTrue("Free time is 135 min (was 90)." in text && "The target is 108 min (was 72)." in text, text)
        assertTrue("T-a moved to 09:30. Was 09:00." in text, text)
        assertTrue("T-c is new in the plan, 1st in the queue." in text, text)
        assertTrue("1 item is no longer planned. It is listed under Removed from the plan above." in text, text)
        assertFalse("T-b" in text.substringAfter("T-c is new"), "the removed item is named once, in its own line on screen: $text")
        // A figure that did not change is not mentioned.
        val sameFree = explain(propose(listOf(a, b), pins = mapOf("a" to LocalTime.of(9, 0)), focus = 30), approved)
        assertFalse("Free time is" in sameFree, sameFree)
        assertTrue("The target is 30 min (was 72)." in sameFree, sameFree)
        val same = explain(propose(listOf(a, b), pins = mapOf("a" to LocalTime.of(9, 0))), approved)
        assertTrue("No block changed and nothing was removed." in same, same)
    }

    @Test
    fun `leaving a block out with its control changes the explanation and names it`() {
        val a = action("a")
        val b = action("b", Priority.IMPORTANT)
        val both = propose(listOf(a, b))
        val fewer = propose(listOf(a, b), selected = setOf("a"))
        val before = explain(both)
        val after = explain(fewer, byYou = 1)
        assertTrue("Planned: 60 min in 2 items" in before)
        assertTrue("Planned: 30 min in 1 item (0 pinned, 1 in the queue)." in after, after)
        assertTrue("You left out 1 item of this draft. Each has an Include control above." in after, after)
        assertFalse("You left out" in before)
    }

    @Test
    fun `a rest day and an unusable window say so plainly`() {
        val rest = explain(propose(listOf(action("a")), selected = emptySet()))
        assertTrue("Nothing is selected, so nothing is planned." in rest, rest)
        val bad = explain(propose(listOf(action("a"))).copy(windowMinutes = 0))
        assertEquals("There is no plan to explain yet: the problem shown above has to be fixed first.", bad)
    }

    @Test
    fun `the explanation is plain Kotlin with no model, conversation or storage in its source`() {
        val source = File("src/main/kotlin/com/thalia/app/planning/PlanExplanation.kt").readText()
        listOf("com.thalia.app.ai", "com.thalia.app.conversation", "PlanningStore", "ProviderRequest", "access.", "withStore").forEach {
            assertFalse(it in source, "PlanExplanation.kt must not mention $it")
        }
        assertTrue("—" !in source)
    }
}
