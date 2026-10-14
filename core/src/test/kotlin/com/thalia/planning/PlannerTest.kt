package com.thalia.planning

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0009 decision 5 and the slice 2 clarifications: the deterministic planner. */
class PlannerTest {
    private val utc = ZoneId.of("UTC")
    private val day = ActiveDay(LocalDate.of(2026, 10, 2))
    private val rollover = LocalTime.of(4, 0)
    private val window = PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))

    private val projects =
        listOf("A", "B", "C", "D").map { ProjectRef(it, "Project $it", ProjectState.ACTIVE) }

    private var created = 0L

    private fun act(
        id: String,
        priority: Priority,
        minutes: Int,
        project: String? = null,
        due: LocalDate? = null,
        createdAt: Long = created++,
        status: ActionStatus = ActionStatus.OPEN,
    ) = PlannedAction(Action(id, id, project, priority, minutes, due, status), createdAt)

    private fun inputs(
        selected: List<PlannedAction>,
        pins: Map<String, LocalTime> = emptyMap(),
        window: PlanningWindow = this.window,
        zone: ZoneId = utc,
        day: ActiveDay = this.day,
        rollover: LocalTime = this.rollover,
    ) = PlanInputs(day, zone, rollover, window, false, selected.map { it.action.id }.toSet(), pins, 0)

    private fun propose(
        selected: List<PlannedAction>,
        pins: Map<String, LocalTime> = emptyMap(),
        window: PlanningWindow = this.window,
        zone: ZoneId = utc,
        projects: List<ProjectRef> = this.projects,
        day: ActiveDay = this.day,
        rollover: LocalTime = this.rollover,
    ) = Planner.propose(inputs(selected, pins, window, zone, day, rollover), selected, projects).also {
        // Every proposal the planner makes passes the independent checks, whatever the scenario.
        assertEquals(emptyList(), PlanValidator.validate(it))
    }

    private fun PlanProposal.item(id: String) = items.single { it.actionId == id }

    private fun PlanProposal.queue() = items.filter { it.kind == ItemKind.QUEUED }.map { it.actionId }

    private fun PlanProposal.leftOut() = items.filter { it.kind == ItemKind.LEFT_OUT }.associate { it.actionId to it.reason }

    @Test
    fun `the decision 5 example gives 540 free, a 432 target and the listed queue and exclusions`() {
        val all =
            listOf(
                act("dentist", Priority.NORMAL, 60),
                act("draft", Priority.MUST, 180, "A"),
                act("review", Priority.IMPORTANT, 90, "B"),
                act("gym", Priority.NORMAL, 45, "C"),
                act("flights", Priority.NORMAL, 30),
                act("tidy", Priority.OPTIONAL, 150, "A"),
                act("bank", Priority.OPTIONAL, 15),
            )
        val p = propose(all, pins = mapOf("dentist" to LocalTime.of(14, 0)))

        assertTrue(p.errors.isEmpty())
        assertEquals(600, p.windowMinutes)
        assertEquals(60, p.pinnedMinutes)
        assertEquals(540, p.freeMinutes)
        assertEquals(432, p.targetMinutes)
        assertEquals(listOf("draft", "review", "flights", "bank"), p.queue())
        assertEquals(mapOf("gym" to LeftOutReason.PROJECT_LIMIT, "tidy" to LeftOutReason.OVER_TARGET), p.leftOut())
        assertEquals(ItemKind.PINNED, p.item("dentist").kind)
        assertEquals(LocalTime.of(14, 0), p.item("dentist").start)
        assertEquals(Placement.WITHIN_TARGET, p.item("draft").placement)
        // 432 - (180 + 90 + 30 + 15) = 117 of the target is left.
        assertEquals(117, p.targetMinutes - p.items.filter { it.kind == ItemKind.QUEUED }.sumOf { it.minutes })
        assertEquals(0, p.overloadMinutes)
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun `the overload example labels the buffer and the item that does not fit, and states 60 unresolved minutes`() {
        val all =
            listOf(
                act("dentist", Priority.NORMAL, 60),
                act("m1", Priority.MUST, 300, "A"),
                act("m2", Priority.MUST, 150, "A"),
                act("m3", Priority.MUST, 150, "A"),
                act("other", Priority.NORMAL, 15),
            )
        val p = propose(all, pins = mapOf("dentist" to LocalTime.of(14, 0)))

        assertEquals(Placement.WITHIN_TARGET, p.item("m1").placement)
        assertEquals(Placement.IN_BUFFER, p.item("m2").placement) // ends at 450: 18 minutes into the buffer
        assertEquals(Placement.DOES_NOT_FIT, p.item("m3").placement) // ends at 600, 60 past the 540 free
        assertEquals(60, p.overloadMinutes)
        assertTrue(p.needsUnresolvedAcknowledgement)
        assertEquals(mapOf("other" to LeftOutReason.OVER_TARGET), p.leftOut())
        assertEquals(
            listOf(
                PlanWarning(PlanWarningKind.MUST_USES_BUFFER, 108),
                PlanWarning(PlanWarningKind.MUST_DOES_NOT_FIT, 60, listOf("m3")),
            ),
            p.warnings,
        )
        assertTrue(p.canApprove) // approving needs the separate acknowledgement, not a fix
    }

    @Test
    fun `must work beyond the target but inside free time warns about the buffer and needs no acknowledgement`() {
        val p = propose(listOf(act("m", Priority.MUST, 500)))
        assertEquals(Placement.IN_BUFFER, p.item("m").placement)
        assertEquals(listOf(PlanWarning(PlanWarningKind.MUST_USES_BUFFER, 500 - 480)), p.warnings)
        assertFalse(p.needsUnresolvedAcknowledgement)
    }

    @Test
    fun `the target rounds down to whole minutes`() {
        val p = propose(listOf(act("a", Priority.NORMAL, 30)), window = PlanningWindow(LocalTime.of(12, 0), LocalTime.of(21, 1)))
        assertEquals(541, p.freeMinutes)
        assertEquals(432, p.targetMinutes)
    }

    @Test
    fun `a non-must action that does not fit is left out and a later smaller one can still fit`() {
        val p = propose(listOf(act("big", Priority.NORMAL, 400), act("huge", Priority.NORMAL, 100), act("small", Priority.OPTIONAL, 20)))
        assertEquals(listOf("big", "small"), p.queue())
        assertEquals(mapOf("huge" to LeftOutReason.OVER_TARGET), p.leftOut())
    }

    @Test
    fun `overlapping pins block approval, naming both, while back-to-back pins are fine`() {
        val a = act("a", Priority.NORMAL, 60)
        val b = act("b", Priority.NORMAL, 60)
        val overlap = propose(listOf(a, b), pins = mapOf("a" to LocalTime.of(13, 0), "b" to LocalTime.of(13, 59)))
        assertEquals(listOf(PlanError(PlanErrorKind.PIN_OVERLAP, listOf("a", "b"))), overlap.errors)
        assertFalse(overlap.canApprove)

        val adjacent = propose(listOf(a, b), pins = mapOf("a" to LocalTime.of(13, 0), "b" to LocalTime.of(14, 0)))
        assertTrue(adjacent.errors.isEmpty())
        assertEquals(480, adjacent.freeMinutes) // 600 minus two pinned hours
    }

    @Test
    fun `a pin that starts before, ends after or straddles the window edge blocks approval`() {
        val a = act("a", Priority.NORMAL, 60)
        listOf(LocalTime.of(11, 30), LocalTime.of(21, 30), LocalTime.of(22, 0), LocalTime.of(3, 0)).forEach { start ->
            val p = propose(listOf(a), pins = mapOf("a" to start))
            assertEquals(listOf(PlanError(PlanErrorKind.PIN_OUTSIDE_WINDOW, listOf("a"))), p.errors, "$start")
        }
        assertTrue(propose(listOf(a), pins = mapOf("a" to LocalTime.of(21, 0))).errors.isEmpty()) // ends exactly at 22:00
        assertTrue(propose(listOf(a), pins = mapOf("a" to LocalTime.of(12, 0))).errors.isEmpty())
    }

    @Test
    fun `pins that fill the window leave no free time, so must work does not fit and the rest is over target`() {
        val full = PlanningWindow(LocalTime.of(12, 0), LocalTime.of(14, 0))
        val p =
            propose(
                listOf(act("pin", Priority.NORMAL, 120), act("must", Priority.MUST, 30), act("opt", Priority.OPTIONAL, 5)),
                pins = mapOf("pin" to LocalTime.of(12, 0)),
                window = full,
            )
        assertEquals(0, p.freeMinutes)
        assertEquals(0, p.targetMinutes)
        assertEquals(Placement.DOES_NOT_FIT, p.item("must").placement)
        assertEquals(30, p.overloadMinutes)
        assertEquals(LeftOutReason.OVER_TARGET, p.item("opt").reason)
    }

    @Test
    fun `at most two projects count, pinned ones first, and standalone actions never count`() {
        val p =
            propose(
                listOf(
                    act("pinned", Priority.NORMAL, 30, "A"),
                    act("b1", Priority.IMPORTANT, 30, "B"),
                    act("c1", Priority.IMPORTANT, 30, "C"),
                    act("a1", Priority.NORMAL, 30, "A"),
                    act("solo", Priority.OPTIONAL, 30),
                ),
                pins = mapOf("pinned" to LocalTime.of(12, 0)),
            )
        assertEquals(listOf("b1", "a1", "solo"), p.queue())
        assertEquals(mapOf("c1" to LeftOutReason.PROJECT_LIMIT), p.leftOut())
    }

    @Test
    fun `must actions are always placed and their projects use up the two`() {
        val p =
            propose(
                listOf(
                    act("m1", Priority.MUST, 30, "A"),
                    act("m2", Priority.MUST, 30, "B"),
                    act("m3", Priority.MUST, 30, "C"),
                    act("n1", Priority.NORMAL, 30, "D"),
                    act("n2", Priority.NORMAL, 30, "A"),
                ),
            )
        assertEquals(listOf("m1", "m2", "m3", "n2"), p.queue())
        assertEquals(mapOf("n1" to LeftOutReason.PROJECT_LIMIT), p.leftOut())
        assertEquals(PlanWarning(PlanWarningKind.MUST_SPANS_MANY_PROJECTS, 3), p.warnings.single())
    }

    @Test
    fun `only standalone actions and actions in an active project are eligible, and the rest are shown with the reason`() {
        val states = ProjectState.entries.filter { it != ProjectState.ACTIVE }
        val refs = states.map { ProjectRef("p-${it.name}", it.name, it) } + ProjectRef("act", "Active", ProjectState.ACTIVE)
        val ineligible = states.map { act("n-${it.name}", Priority.NORMAL, 10, "p-${it.name}") }
        val ok = listOf(act("in-active", Priority.NORMAL, 10, "act"), act("standalone", Priority.NORMAL, 10))
        val p = propose(ineligible + ok, projects = refs)

        assertEquals(listOf("in-active", "standalone").sorted(), p.queue().sorted())
        assertEquals(ineligible.map { it.action.id }.toSet(), p.leftOut().keys)
        assertTrue(p.leftOut().values.all { it == LeftOutReason.PROJECT_NOT_ACTIVE })
        assertTrue(p.canApprove) // visible exclusions are not errors
    }

    @Test
    fun `a must action in a project that is not active is not placed and the conflict is flagged`() {
        val refs = listOf(ProjectRef("paused", "Paused", ProjectState.PAUSED))
        val p = propose(listOf(act("m", Priority.MUST, 60, "paused"), act("n", Priority.NORMAL, 30)), projects = refs)
        assertEquals(LeftOutReason.PROJECT_NOT_ACTIVE, p.item("m").reason)
        assertEquals(listOf("n"), p.queue())
        assertEquals(PlanWarning(PlanWarningKind.MUST_PROJECT_NOT_ACTIVE, actionIds = listOf("m")), p.warnings.single())
        assertEquals(0, p.overloadMinutes) // it was never scheduled, so it is not "work that does not fit"
    }

    @Test
    fun `an action whose project is unknown is not eligible, and a pin on an ineligible action is left out too`() {
        val p =
            propose(
                listOf(act("x", Priority.NORMAL, 30, "ghost"), act("pinned", Priority.NORMAL, 30, "paused")),
                pins = mapOf("pinned" to LocalTime.of(13, 0)),
                projects = listOf(ProjectRef("paused", "Paused", ProjectState.PAUSED)),
            )
        assertEquals(mapOf("x" to LeftOutReason.PROJECT_NOT_ACTIVE, "pinned" to LeftOutReason.PROJECT_NOT_ACTIVE), p.leftOut())
        assertEquals(0, p.pinnedMinutes)
    }

    @Test
    fun `ties break by priority, due date, no due date, oldest, then id`() {
        val d1 = LocalDate.of(2026, 10, 3)
        val d2 = LocalDate.of(2026, 10, 9)
        val p =
            propose(
                listOf(
                    act("opt", Priority.OPTIONAL, 5, createdAt = 1),
                    act("nodue-new", Priority.NORMAL, 5, createdAt = 9),
                    act("due-late", Priority.NORMAL, 5, due = d2, createdAt = 8),
                    act("nodue-old", Priority.NORMAL, 5, createdAt = 2),
                    act("due-soon", Priority.NORMAL, 5, due = d1, createdAt = 7),
                    act("z-same", Priority.IMPORTANT, 5, createdAt = 4),
                    act("a-same", Priority.IMPORTANT, 5, createdAt = 4),
                    act("must", Priority.MUST, 5, createdAt = 99),
                ),
            )
        assertEquals(listOf("must", "a-same", "z-same", "due-soon", "due-late", "nodue-old", "nodue-new", "opt"), p.queue())
        assertEquals(p.queue().indices.toList(), p.items.filter { it.kind == ItemKind.QUEUED }.map { it.position })
    }

    @Test
    fun `the same inputs give an identical proposal however the lists are ordered`() {
        val all =
            listOf(
                act("a", Priority.MUST, 120, "A"),
                act("b", Priority.NORMAL, 45, "B"),
                act("c", Priority.OPTIONAL, 30, "C"),
                act("d", Priority.NORMAL, 45),
            )
        val first = propose(all)
        repeat(5) { assertEquals(first, propose(all)) }
        assertEquals(first, Planner.propose(inputs(all), all.reversed(), projects.reversed()))
    }

    @Test
    fun `only the selected actions are planned, others stored in the backlog never appear`() {
        val selected = act("picked", Priority.NORMAL, 30)
        val backlog = act("backlog", Priority.MUST, 30)
        val p = Planner.propose(inputs(listOf(selected)), listOf(selected, backlog), projects)
        assertEquals(listOf("picked"), p.items.map { it.actionId })
        assertEquals(listOf("picked"), p.actions.map { it.action.id })
    }

    @Test
    fun `an empty selection is a valid rest day, a done or missing action and a pin on an unselected action are blocking errors`() {
        val rest = propose(emptyList())
        assertTrue(rest.errors.isEmpty() && rest.canApprove)
        assertTrue(rest.isRestDay)
        assertEquals(emptyList(), rest.items)
        assertEquals(600, rest.freeMinutes)
        assertEquals(0, rest.overloadMinutes)
        assertFalse(propose(listOf(act("a", Priority.NORMAL, 30))).isRestDay)

        val done = act("done", Priority.NORMAL, 30, status = ActionStatus.DONE)
        val p = Planner.propose(inputs(listOf(done)).copy(selected = setOf("done", "ghost")), listOf(done), projects)
        assertEquals(listOf(PlanError(PlanErrorKind.ACTION_UNAVAILABLE, listOf("done", "ghost"))), p.errors)

        val a = act("a", Priority.NORMAL, 30)
        val stray = Planner.propose(inputs(listOf(a), pins = mapOf("other" to LocalTime.of(13, 0))), listOf(a), projects)
        assertEquals(PlanError(PlanErrorKind.PIN_FOR_UNSELECTED_ACTION, listOf("other")), stray.errors.single())
    }

    @Test
    fun `a window crossing midnight, and one ending at the rollover, count their real length and pins after midnight`() {
        val night = PlanningWindow(LocalTime.of(18, 0), LocalTime.of(1, 0))
        val a = act("a", Priority.NORMAL, 30)
        val p = propose(listOf(a), pins = mapOf("a" to LocalTime.of(0, 30)), window = night)
        assertEquals(420, p.windowMinutes)
        assertTrue(p.errors.isEmpty())
        assertEquals(
            PlanError(PlanErrorKind.PIN_OUTSIDE_WINDOW, listOf("a")),
            propose(listOf(a), pins = mapOf("a" to LocalTime.of(1, 0)), window = night).errors.single(),
        )

        assertEquals(360, propose(listOf(a), window = PlanningWindow(LocalTime.of(22, 0), LocalTime.of(4, 0))).windowMinutes)
    }

    @Test
    fun `a day's window that runs past the rollover is refused as empty, never wrapped`() {
        val a = act("a", Priority.NORMAL, 30)
        val p = propose(listOf(a), window = PlanningWindow(LocalTime.of(2, 0), LocalTime.of(6, 0)))
        assertEquals(listOf(PlanError(PlanErrorKind.WINDOW_EMPTY)), p.errors)
        assertEquals(0, p.windowMinutes)
    }

    @Test
    fun `spring forward shortens the window by the skipped hour`() {
        val ny = ZoneId.of("America/New_York")
        // Active day 7 March: 22:00 on the 7th to 03:00 on the 8th is five wall-clock hours but four real ones.
        val p =
            propose(
                listOf(act("a", Priority.NORMAL, 30)),
                window = PlanningWindow(LocalTime.of(22, 0), LocalTime.of(3, 0)),
                zone = ny,
                day = ActiveDay(LocalDate.of(2026, 3, 7)),
            )
        assertEquals(240, p.windowMinutes)
    }

    @Test
    fun `a window end or pin inside the spring-forward gap is refused, never shifted`() {
        val ny = ZoneId.of("America/New_York")
        val d = ActiveDay(LocalDate.of(2026, 3, 7))
        val a = act("a", Priority.NORMAL, 30)
        val windowInGap = propose(listOf(a), window = PlanningWindow(LocalTime.of(22, 0), LocalTime.of(2, 30)), zone = ny, day = d)
        assertEquals(listOf(PlanError(PlanErrorKind.TIME_DOES_NOT_EXIST)), windowInGap.errors)
        assertEquals(0, windowInGap.windowMinutes)

        val pinInGap =
            propose(
                listOf(a),
                pins = mapOf("a" to LocalTime.of(2, 30)),
                window = PlanningWindow(LocalTime.of(22, 0), LocalTime.of(3, 30)),
                zone = ny,
                day = d,
            )
        assertEquals(PlanError(PlanErrorKind.TIME_DOES_NOT_EXIST, listOf("a")), pinInGap.errors.single())
        assertFalse(pinInGap.canApprove)
    }

    @Test
    fun `fall back lengthens the window and an ambiguous end resolves to the first occurrence`() {
        val ny = ZoneId.of("America/New_York")
        val d = ActiveDay(LocalDate.of(2026, 10, 31))
        val a = act("a", Priority.NORMAL, 30)
        // 22:00 EDT to 02:30 EST: four and a half wall-clock hours, five and a half real ones.
        assertEquals(
            330,
            propose(listOf(a), window = PlanningWindow(LocalTime.of(22, 0), LocalTime.of(2, 30)), zone = ny, day = d).windowMinutes,
        )
        // 01:30 happens twice; the end is the first, 05:30Z, so the window is 22:00 EDT (02:00Z) to 05:30Z.
        assertEquals(
            210,
            propose(listOf(a), window = PlanningWindow(LocalTime.of(22, 0), LocalTime.of(1, 30)), zone = ny, day = d).windowMinutes,
        )
        // A pin of 30 minutes at 01:00 ends at the first 01:30, the window end, so it fits exactly; one at 01:01 does not.
        val fits =
            propose(
                listOf(a),
                pins = mapOf("a" to LocalTime.of(1, 0)),
                window = PlanningWindow(LocalTime.of(22, 0), LocalTime.of(1, 30)),
                zone = ny,
                day = d,
            )
        assertTrue(fits.errors.isEmpty())
        val over =
            propose(
                listOf(a),
                pins = mapOf("a" to LocalTime.of(1, 1)),
                window = PlanningWindow(LocalTime.of(22, 0), LocalTime.of(1, 30)),
                zone = ny,
                day = d,
            )
        assertEquals(PlanError(PlanErrorKind.PIN_OUTSIDE_WINDOW, listOf("a")), over.errors.single())
    }

    @Test
    fun `a proposal carries every dependency, so a changed action, project or setting makes it unequal`() {
        val a = act("a", Priority.NORMAL, 30, "A")
        val base = propose(listOf(a))
        assertEquals(base, propose(listOf(a)))
        assertTrue(base != propose(listOf(a.copy(action = a.action.copy(title = "renamed")))))
        assertTrue(base != propose(listOf(a), projects = projects.map { if (it.id == "A") it.copy(title = "Renamed") else it }))
        assertTrue(base != propose(listOf(a), projects = projects.map { if (it.id == "A") it.copy(state = ProjectState.PAUSED) else it }))
        assertTrue(base != propose(listOf(a), rollover = LocalTime.of(5, 0)))
        assertTrue(base != propose(listOf(a), zone = ZoneId.of("Asia/Kuala_Lumpur")))
        assertNull(base.items.single().reason)
    }
}
