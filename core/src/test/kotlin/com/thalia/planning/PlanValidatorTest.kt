package com.thalia.planning

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PlanValidator] judges a proposal on its own contents. Every proposal here is built by hand, as data,
 * and never by [Planner.propose], so a proposal from anywhere else, or altered after the planner made it,
 * is caught. The baseline is the decision 5 example written out literally.
 */
class PlanValidatorTest {
    private val utc = ZoneId.of("UTC")
    private val day = ActiveDay(LocalDate.of(2026, 10, 3))
    private val window = PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))
    private val active = listOf("A", "B", "C").map { ProjectRef(it, "Project $it", ProjectState.ACTIVE) }

    private fun planned(
        id: String,
        priority: Priority,
        minutes: Int,
        project: String?,
        created: Long,
    ) = PlannedAction(Action(id, id, project, priority, minutes), created)

    private val actions =
        listOf(
            planned("dentist", Priority.NORMAL, 60, null, 1),
            planned("draft", Priority.MUST, 180, "A", 2),
            planned("review", Priority.IMPORTANT, 90, "B", 3),
            planned("gym", Priority.NORMAL, 45, "C", 4),
            planned("flights", Priority.NORMAL, 30, null, 5),
            planned("tidy", Priority.OPTIONAL, 150, "A", 6),
            planned("bank", Priority.OPTIONAL, 15, null, 7),
        )

    private fun item(
        id: String,
        kind: ItemKind,
        minutes: Int,
        position: Int,
        start: LocalTime? = null,
        placement: Placement? = null,
        reason: LeftOutReason? = null,
    ) = ProposedItem(id, kind, minutes, start, position, placement, reason)

    private val baseline =
        PlanProposal(
            inputs =
                PlanInputs(
                    day,
                    utc,
                    LocalTime.of(4, 0),
                    window,
                    true,
                    actions.map { it.action.id }.toSet(),
                    mapOf("dentist" to LocalTime.of(14, 0)),
                    0,
                ),
            actions = actions.sortedBy { it.action.id },
            projects = active,
            windowMinutes = 600,
            pinnedMinutes = 60,
            occupiedMinutes = 60,
            freeMinutes = 540,
            targetMinutes = 432,
            items =
                listOf(
                    item("dentist", ItemKind.PINNED, 60, 0, start = LocalTime.of(14, 0)),
                    item("draft", ItemKind.QUEUED, 180, 0, placement = Placement.WITHIN_TARGET),
                    item("review", ItemKind.QUEUED, 90, 1, placement = Placement.WITHIN_TARGET),
                    item("flights", ItemKind.QUEUED, 30, 2, placement = Placement.WITHIN_TARGET),
                    item("bank", ItemKind.QUEUED, 15, 3, placement = Placement.WITHIN_TARGET),
                    item("gym", ItemKind.LEFT_OUT, 45, 0, reason = LeftOutReason.PROJECT_LIMIT),
                    item("tidy", ItemKind.LEFT_OUT, 150, 1, reason = LeftOutReason.OVER_TARGET),
                ),
            overloadMinutes = 0,
            warnings = emptyList(),
            errors = emptyList(),
        )

    private fun kinds(proposal: PlanProposal) = PlanValidator.validate(proposal).map { it.kind }.toSet()

    private fun PlanProposal.withItems(change: (List<ProposedItem>) -> List<ProposedItem>) = copy(items = change(items))

    private fun PlanProposal.replacingItem(
        id: String,
        change: (ProposedItem) -> ProposedItem,
    ) = withItems { all -> all.map { if (it.actionId == id) change(it) else it } }

    private fun PlanProposal.replacingAction(
        id: String,
        change: (PlannedAction) -> PlannedAction,
    ) = copy(actions = actions.map { if (it.action.id == id) change(it) else it })

    @Test
    fun `the hand-written baseline is consistent, and is what the planner makes`() {
        assertEquals(emptyList(), PlanValidator.validate(baseline))
        assertEquals(baseline, Planner.propose(baseline.inputs, actions, active))
    }

    @Test
    fun `a proposal that already carries errors is left to its errors`() {
        val withErrors = baseline.copy(errors = listOf(PlanError(PlanErrorKind.PIN_OVERLAP)), items = emptyList())
        assertEquals(emptyList(), PlanValidator.validate(withErrors))
    }

    @Test
    fun `the selected actions must be unique and exactly the items`() {
        assertEquals(setOf(PlanViolationKind.SELECTION), kinds(baseline.withItems { it + it.last().copy(position = 2) }))
        assertTrue(PlanViolationKind.SELECTION in kinds(baseline.withItems { it.filter { item -> item.actionId != "bank" } }))
        assertTrue(PlanViolationKind.SELECTION in kinds(baseline.copy(actions = baseline.actions + baseline.actions.first())))
        val stray = baseline.copy(inputs = baseline.inputs.copy(selected = baseline.inputs.selected + "ghost"))
        assertTrue(PlanViolationKind.SELECTION in kinds(stray))
        assertTrue(PlanViolationKind.ACTION in kinds(baseline.copy(actions = baseline.actions.filter { it.action.id != "bank" })))
    }

    @Test
    fun `an item must be an open action with its estimate`() {
        assertTrue(
            PlanViolationKind.ACTION in
                kinds(baseline.replacingAction("bank") { it.copy(action = it.action.copy(status = ActionStatus.DONE)) }),
        )
        assertTrue(PlanViolationKind.ACTION in kinds(baseline.replacingItem("bank") { it.copy(minutes = 20) }))
    }

    @Test
    fun `an action in a project that is not active cannot be placed, and an eligible one cannot be excluded as ineligible`() {
        val paused = baseline.copy(projects = active.map { if (it.id == "B") it.copy(state = ProjectState.PAUSED) else it })
        assertTrue(PlanViolationKind.ELIGIBILITY in kinds(paused)) // review is queued but its project is paused

        val missingProject = baseline.copy(projects = active.filter { it.id != "B" })
        assertTrue(PlanViolationKind.ELIGIBILITY in kinds(missingProject))

        val wrongExclusion = baseline.replacingItem("gym") { it.copy(reason = LeftOutReason.PROJECT_NOT_ACTIVE) }
        assertTrue(PlanViolationKind.ELIGIBILITY in kinds(wrongExclusion))

        // A paused Must action is not placed: left out with the reason, it is consistent.
        val mustPaused =
            baseline
                .copy(projects = active.map { if (it.id == "A") it.copy(state = ProjectState.PAUSED) else it })
                .withItems { all ->
                    all.filter { it.actionId != "draft" && it.actionId != "tidy" } +
                        item("draft", ItemKind.LEFT_OUT, 180, 2, reason = LeftOutReason.PROJECT_NOT_ACTIVE) +
                        item("tidy", ItemKind.LEFT_OUT, 150, 3, reason = LeftOutReason.PROJECT_NOT_ACTIVE)
                }
        // The queue then starts at review, so its positions and the target accounting still have to hold.
        val reordered =
            mustPaused.withItems { all ->
                val (queued, rest) = all.partition { it.kind == ItemKind.QUEUED }
                val fixed = queued.mapIndexed { i, it -> it.copy(position = i) }
                all.filter { it.kind == ItemKind.PINNED } + fixed + rest.filter { it.kind == ItemKind.LEFT_OUT }.sortedBy { it.position }
            }
        assertEquals(emptyList(), PlanValidator.validate(reordered).filter { it.kind == PlanViolationKind.ELIGIBILITY })
    }

    private fun twoPins(
        firstStart: LocalTime,
        secondStart: LocalTime,
    ): PlanProposal {
        val a = planned("a", Priority.NORMAL, 60, null, 1)
        val b = planned("b", Priority.NORMAL, 60, null, 2)
        // Occupied time is the union of the two pins inside the window, as the planner computes it.
        val rollover = LocalTime.of(4, 0)
        val (windowStart, windowEnd) = Capacity.windowOf(day, rollover, window, utc)
        val occupied =
            Capacity.occupiedMinutes(
                windowStart,
                windowEnd,
                listOf(firstStart, secondStart).map {
                    ActiveDay.resolve(day.localOf(rollover, it), utc).let { from ->
                        from to
                            from.plusSeconds(3600)
                    }
                },
            )
        return PlanProposal(
            inputs =
                PlanInputs(
                    day,
                    utc,
                    LocalTime.of(4, 0),
                    window,
                    true,
                    setOf("a", "b"),
                    mapOf("a" to firstStart, "b" to secondStart),
                    0,
                ),
            actions = listOf(a, b),
            projects = emptyList(),
            windowMinutes = 600,
            pinnedMinutes = 120,
            occupiedMinutes = occupied,
            freeMinutes = 600 - occupied,
            targetMinutes = (600 - occupied) * Planner.TARGET_PERCENT / 100,
            items =
                listOf(firstStart to "a", secondStart to "b").sortedBy { it.first }.mapIndexed { i, (start, id) ->
                    item(id, ItemKind.PINNED, 60, i, start = start)
                },
            overloadMinutes = 0,
            warnings = emptyList(),
            errors = emptyList(),
        )
    }

    @Test
    fun `pins must lie inside the window and must not overlap`() {
        assertEquals(emptyList(), PlanValidator.validate(twoPins(LocalTime.of(13, 0), LocalTime.of(14, 0))))
        assertEquals(setOf(PlanViolationKind.PINS), kinds(twoPins(LocalTime.of(13, 0), LocalTime.of(13, 30))))
        assertEquals(setOf(PlanViolationKind.PINS), kinds(twoPins(LocalTime.of(11, 30), LocalTime.of(14, 0))))
        assertEquals(setOf(PlanViolationKind.PINS), kinds(twoPins(LocalTime.of(13, 0), LocalTime.of(21, 30))))
        assertEquals(setOf(PlanViolationKind.PINS), kinds(twoPins(LocalTime.of(13, 0), LocalTime.of(3, 0)))) // past the window
    }

    @Test
    fun `a pinned item must match its pin, and a pin must belong to a selected action`() {
        assertTrue(PlanViolationKind.PINS in kinds(baseline.replacingItem("dentist") { it.copy(start = LocalTime.of(15, 0)) }))
        assertTrue(PlanViolationKind.PINS in kinds(baseline.replacingItem("dentist") { it.copy(start = null) }))
        assertTrue(
            PlanViolationKind.PINS in
                kinds(baseline.copy(inputs = baseline.inputs.copy(pins = baseline.inputs.pins + ("ghost" to LocalTime.NOON)))),
        )
        // A pinned action shown as queued, though it has a pin.
        assertTrue(
            PlanViolationKind.PINS in
                kinds(
                    baseline.replacingItem(
                        "dentist",
                    ) { it.copy(kind = ItemKind.QUEUED, start = null, placement = Placement.WITHIN_TARGET) },
                ),
        )
    }

    @Test
    fun `the window, pinned, free and target figures must add up`() {
        assertTrue(PlanViolationKind.ACCOUNTING in kinds(baseline.copy(windowMinutes = 601)))
        assertTrue(PlanViolationKind.ACCOUNTING in kinds(baseline.copy(pinnedMinutes = 0)))
        assertTrue(PlanViolationKind.ACCOUNTING in kinds(baseline.copy(freeMinutes = 541)))
        assertTrue(PlanViolationKind.ACCOUNTING in kinds(baseline.copy(targetMinutes = 433)))
        assertTrue(PlanViolationKind.ACCOUNTING in kinds(baseline.copy(targetMinutes = 540)))
        val emptyWindow = baseline.copy(inputs = baseline.inputs.copy(window = PlanningWindow(LocalTime.of(2, 0), LocalTime.of(6, 0))))
        assertTrue(PlanViolationKind.ACCOUNTING in kinds(emptyWindow))
    }

    @Test
    fun `the queue must be in order with its positions in sequence`() {
        val swapped =
            baseline.withItems { all ->
                all
                    .map {
                        when (it.actionId) {
                            "draft" -> it.copy(position = 1)
                            "review" -> it.copy(position = 0)
                            else -> it
                        }
                    }.sortedWith(compareBy({ it.kind.ordinal }, { it.position }))
            }
        assertTrue(PlanViolationKind.QUEUE in kinds(swapped))
        assertTrue(PlanViolationKind.QUEUE in kinds(baseline.replacingItem("bank") { it.copy(position = 7) }))
        assertTrue(PlanViolationKind.QUEUE in kinds(baseline.replacingItem("tidy") { it.copy(position = 5) }))
        assertTrue(PlanViolationKind.QUEUE in kinds(baseline.withItems { it.reversed() }))
    }

    @Test
    fun `every placement and exclusion must follow from the running minutes`() {
        assertTrue(PlanViolationKind.QUEUE in kinds(baseline.replacingItem("draft") { it.copy(placement = Placement.IN_BUFFER) }))
        // Tidy (150) queued after 315 minutes is 465 over the 432 target.
        val overTarget =
            baseline.withItems { all ->
                all
                    .filter { it.actionId != "tidy" }
                    .plus(item("tidy", ItemKind.QUEUED, 150, 4, placement = Placement.WITHIN_TARGET))
                    .sortedWith(compareBy({ it.kind.ordinal }, { it.position }))
            }
        assertTrue(PlanViolationKind.QUEUE in kinds(overTarget))
        assertTrue(PlanViolationKind.QUEUE in kinds(baseline.replacingItem("tidy") { it.copy(reason = LeftOutReason.PROJECT_LIMIT) }))
        // Bank (15) fits, so it cannot be left out as over target.
        val wrongExclusion =
            baseline.withItems { all ->
                all.filter { it.actionId != "bank" } + item("bank", ItemKind.LEFT_OUT, 15, 2, reason = LeftOutReason.OVER_TARGET)
            }
        assertTrue(PlanViolationKind.QUEUE in kinds(wrongExclusion))
    }

    @Test
    fun `a third project cannot be queued for flexible work`() {
        val third =
            baseline.withItems { all ->
                all
                    .filter { it.actionId != "gym" }
                    .plus(item("gym", ItemKind.QUEUED, 45, 4, placement = Placement.WITHIN_TARGET))
                    .sortedWith(compareBy({ it.kind.ordinal }, { it.position }))
            }
        assertTrue(PlanViolationKind.PROJECT_LIMIT in kinds(third))
    }

    @Test
    fun `a must action is always queued, and its label is where it ends`() {
        val dropped =
            baseline.withItems { all ->
                all.filter { it.actionId != "draft" } + item("draft", ItemKind.LEFT_OUT, 180, 2, reason = LeftOutReason.OVER_TARGET)
            }
        assertTrue(PlanViolationKind.QUEUE in kinds(dropped))
    }

    @Test
    fun `overload must be the Must work beyond free time, and acknowledged work must show it`() {
        assertEquals(setOf(PlanViolationKind.OVERLOAD), kinds(baseline.copy(overloadMinutes = 10)))

        val a = planned("m1", Priority.MUST, 300, null, 1)
        val b = planned("m2", Priority.MUST, 150, null, 2)
        val c = planned("m3", Priority.MUST, 210, null, 3)
        val overloaded =
            PlanProposal(
                inputs = PlanInputs(day, utc, LocalTime.of(4, 0), window, true, setOf("m1", "m2", "m3"), emptyMap(), 0),
                actions = listOf(a, b, c),
                projects = emptyList(),
                windowMinutes = 600,
                pinnedMinutes = 0,
                freeMinutes = 600,
                targetMinutes = 480,
                items =
                    listOf(
                        item("m1", ItemKind.QUEUED, 300, 0, placement = Placement.WITHIN_TARGET),
                        item("m2", ItemKind.QUEUED, 150, 1, placement = Placement.WITHIN_TARGET),
                        item("m3", ItemKind.QUEUED, 210, 2, placement = Placement.DOES_NOT_FIT),
                    ),
                overloadMinutes = 60,
                warnings = listOf(PlanWarning(PlanWarningKind.MUST_DOES_NOT_FIT, 60, listOf("m3"))),
                errors = emptyList(),
            )
        assertEquals(emptyList(), PlanValidator.validate(overloaded))
        // Hiding the overload, or labelling the item that does not fit as fitting, is caught.
        assertEquals(setOf(PlanViolationKind.OVERLOAD), kinds(overloaded.copy(overloadMinutes = 0)))
        assertEquals(setOf(PlanViolationKind.QUEUE), kinds(overloaded.replacingItem("m3") { it.copy(placement = Placement.IN_BUFFER) }))
        assertEquals(setOf(PlanViolationKind.OVERLOAD), kinds(overloaded.copy(overloadMinutes = 61)))
    }

    @Test
    fun `a rest day has no items`() {
        val rest =
            baseline.copy(
                inputs = baseline.inputs.copy(selected = emptySet(), pins = emptyMap()),
                actions = emptyList(),
                projects = emptyList(),
                pinnedMinutes = 0,
                occupiedMinutes = 0,
                freeMinutes = 600,
                targetMinutes = 480,
                items = emptyList(),
            )
        assertEquals(emptyList(), PlanValidator.validate(rest))
        assertTrue(
            PlanViolationKind.REST_DAY in kinds(rest.copy(items = baseline.items.take(1))) ||
                PlanViolationKind.SELECTION in kinds(rest.copy(items = baseline.items.take(1))),
        )
    }
}
