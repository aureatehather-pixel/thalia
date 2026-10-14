package com.thalia.planning

import java.time.Duration
import java.time.Instant

enum class PlanViolationKind {
    /** The same action appears twice among the items, the proposal's actions, or is not a selected one. */
    SELECTION,

    /** An item names an action the proposal does not carry, or one that is not open, or a wrong estimate. */
    ACTION,

    /** An action in a project that is not active is placed, or an eligible one is left out as ineligible. */
    ELIGIBILITY,

    /** A pin is missing, extra, unparseable on its date, outside the window, or overlapping another. */
    PINS,

    /** The window, free time, target or pinned minutes do not add up. */
    ACCOUNTING,

    /** The queue is out of order, a position is wrong, or a placement or exclusion does not follow from the minutes. */
    QUEUE,

    /** More than two projects were counted for flexible work. */
    PROJECT_LIMIT,

    /** The overload minutes are not the Must work beyond free time. */
    OVERLOAD,

    /** A rest day carries items. */
    REST_DAY,
}

data class PlanViolation(
    val kind: PlanViolationKind,
    val detail: String,
)

/**
 * Checks a proposal against the rules of ADR 0009 decision 5 **without calling [Planner.propose]**, so a
 * proposal made by anything else, or altered after the planner made it, is judged on its own contents.
 *
 * It uses only what the proposal carries: its inputs, the actions and projects it snapshots, and its
 * numbers and items. It verifies that those are consistent with each other and with the rules: unique,
 * open and eligible selected actions; pins inside the window and not overlapping; the capacity figures;
 * the queue order; every placement and exclusion following from the running minutes and the project
 * limit; and the overload. It does not judge whether the proposal is the one the stored data now gives;
 * that is the approval's comparison with a fresh proposal.
 *
 * A proposal that already carries blocking errors is not examined further: it cannot be approved, and
 * its items are not meant to be complete. Returns the violations, empty when consistent.
 */
object PlanValidator {
    fun validate(proposal: PlanProposal): List<PlanViolation> {
        if (proposal.errors.isNotEmpty()) return emptyList()
        val found = mutableListOf<PlanViolation>()

        fun add(
            kind: PlanViolationKind,
            detail: String,
        ) {
            found += PlanViolation(kind, detail)
        }

        val inputs = proposal.inputs
        val items = proposal.items
        val actionById = proposal.actions.associateBy { it.action.id }
        val projectById = proposal.projects.associateBy { it.id }

        // Selection: unique, and exactly what was selected.
        if (actionById.size != proposal.actions.size) add(PlanViolationKind.SELECTION, "an action is carried twice")
        if (items.map { it.actionId }.toSet().size != items.size) add(PlanViolationKind.SELECTION, "an action appears twice in the items")
        if (items.map { it.actionId }.toSet() != inputs.selected) add(PlanViolationKind.SELECTION, "the items are not the selected actions")
        if (actionById.keys != inputs.selected) add(PlanViolationKind.SELECTION, "the carried actions are not the selected actions")
        if (!inputs.pins.keys.all { it in inputs.selected }) add(PlanViolationKind.PINS, "a pin for an action that is not selected")
        if (inputs.selected.isEmpty() && items.isNotEmpty()) add(PlanViolationKind.REST_DAY, "a rest day with items")
        if (items.any { it.actionId !in actionById }) {
            add(PlanViolationKind.ACTION, "an item names an action the proposal does not carry")
            return found
        }
        items.forEach { item ->
            val action = actionById.getValue(item.actionId).action
            if (action.status != ActionStatus.OPEN) add(PlanViolationKind.ACTION, "an item for an action that is not open")
            if (item.minutes != action.estimateMinutes) add(PlanViolationKind.ACTION, "an item's minutes are not the action's estimate")
        }

        fun eligible(planned: PlannedAction): Boolean {
            val projectId = planned.action.projectId ?: return true
            return projectById[projectId]?.state == ProjectState.ACTIVE
        }

        // Eligibility and kinds.
        items.forEach { item ->
            val planned = actionById.getValue(item.actionId)
            val ok = eligible(planned)
            val excluded = item.kind == ItemKind.LEFT_OUT && item.reason == LeftOutReason.PROJECT_NOT_ACTIVE
            if (!ok && item.kind != ItemKind.LEFT_OUT) add(PlanViolationKind.ELIGIBILITY, "a placed action whose project is not active")
            if (!ok && !excluded) add(PlanViolationKind.ELIGIBILITY, "an ineligible action left out for another reason")
            if (ok && excluded) add(PlanViolationKind.ELIGIBILITY, "an eligible action left out as ineligible")
        }

        // The window, in real elapsed time.
        val windowStart: Instant
        val windowEnd: Instant
        try {
            val startOffset = ActiveDay.minutesAfterRollover(inputs.rollover, inputs.window.start)
            val endOffset =
                ActiveDay.minutesAfterRollover(inputs.rollover, inputs.window.end).let { if (it == 0) ActiveDay.MINUTES_PER_DAY else it }
            windowStart = ActiveDay.resolve(inputs.day.localAt(inputs.rollover, startOffset), inputs.zone)
            windowEnd = ActiveDay.resolve(inputs.day.localAt(inputs.rollover, endOffset), inputs.zone)
        } catch (e: NonexistentLocalTimeException) {
            add(PlanViolationKind.ACCOUNTING, "the window has a time that does not exist")
            return found
        }
        if (!windowEnd.isAfter(windowStart)) {
            add(PlanViolationKind.ACCOUNTING, "the window is empty")
            return found
        }
        val windowMinutes = Duration.between(windowStart, windowEnd).toMinutes().toInt()
        if (proposal.windowMinutes != windowMinutes) add(PlanViolationKind.ACCOUNTING, "window minutes are not the window's length")

        // Pins: exactly the eligible pinned actions, inside the window, not overlapping.
        val pinnedItems = items.filter { it.kind == ItemKind.PINNED }
        val shouldBePinned =
            proposal.actions
                .filter { eligible(it) && it.action.id in inputs.pins }
                .map { it.action.id }
                .toSet()
        if (pinnedItems.map { it.actionId }.toSet() !=
            shouldBePinned
        ) {
            add(PlanViolationKind.PINS, "the pinned items are not the eligible pinned actions")
        }
        val intervals = mutableListOf<Triple<Instant, Instant, String>>()
        pinnedItems.forEach { item ->
            val start = item.start
            if (start == null || inputs.pins[item.actionId] != start) {
                add(PlanViolationKind.PINS, "a pinned item's start is not its pin")
                return@forEach
            }
            try {
                val from = ActiveDay.resolve(inputs.day.localOf(inputs.rollover, start), inputs.zone)
                val to = from.plusSeconds(item.minutes * 60L)
                if (from.isBefore(windowStart) || to.isAfter(windowEnd)) add(PlanViolationKind.PINS, "a pin lies outside the window")
                intervals += Triple(from, to, item.actionId)
            } catch (e: NonexistentLocalTimeException) {
                add(PlanViolationKind.PINS, "a pin's time does not exist")
            }
        }
        intervals.sortedBy { it.first }.zipWithNext().forEach { (a, b) ->
            if (b.first.isBefore(a.second)) add(PlanViolationKind.PINS, "pins overlap")
        }
        val pinStarts = pinnedItems.mapNotNull { it.start }
        if (pinStarts.map { ActiveDay.minutesAfterRollover(inputs.rollover, it) } !=
            pinStarts.map { ActiveDay.minutesAfterRollover(inputs.rollover, it) }.sorted()
        ) {
            add(PlanViolationKind.PINS, "pinned items are not in order of start")
        }
        if (pinnedItems.map { it.position } !=
            pinnedItems.indices.toList()
        ) {
            add(PlanViolationKind.PINS, "pinned positions are not 0, 1, 2...")
        }

        // Capacity.
        val pinnedMinutes = pinnedItems.sumOf { it.minutes }
        val fixed = inputs.commitments.filter { it.status == CommitmentStatus.ACTIVE }.map { it.start to it.end }
        val occupied = Capacity.occupiedMinutes(windowStart, windowEnd, intervals.map { it.first to it.second } + fixed)
        val free = maxOf(0, windowMinutes - occupied)
        if (proposal.pinnedMinutes != pinnedMinutes) add(PlanViolationKind.ACCOUNTING, "pinned minutes are not the pinned items' minutes")
        if (proposal.occupiedMinutes !=
            occupied
        ) {
            add(PlanViolationKind.ACCOUNTING, "occupied minutes are not the union of pins and commitments")
        }
        if (proposal.freeMinutes != free) add(PlanViolationKind.ACCOUNTING, "free time is not the window minus the occupied minutes")
        val target = Capacity.target(free, inputs.focusMinutes)
        if (proposal.targetMinutes != target) add(PlanViolationKind.ACCOUNTING, "the target is not 80% of free time, capped by focus")

        // The queue. Order, positions, then every placement and exclusion against the running minutes.
        val order =
            compareBy<PlannedAction>(
                { it.action.priority.ordinal },
                { it.action.dueDate == null },
                { it.action.dueDate },
                { it.createdAtMillis },
                { it.action.id },
            )
        val queued = items.filter { it.kind == ItemKind.QUEUED }
        val left = items.filter { it.kind == ItemKind.LEFT_OUT }
        if (queued.map { it.position } != queued.indices.toList()) add(PlanViolationKind.QUEUE, "queue positions are not 0, 1, 2...")
        if (left.map { it.position } != left.indices.toList()) add(PlanViolationKind.QUEUE, "left-out positions are not 0, 1, 2...")
        if (items.map { it.kind.ordinal } !=
            items.map { it.kind.ordinal }.sorted()
        ) {
            add(PlanViolationKind.QUEUE, "items are not pinned, queued, then left out")
        }
        val queuedOrder = queued.map { actionById.getValue(it.actionId) }
        if (queuedOrder != queuedOrder.sortedWith(order)) add(PlanViolationKind.QUEUE, "the queue is out of order")

        val placedProjects = pinnedItems.mapNotNull { actionById.getValue(it.actionId).action.projectId }.toMutableSet()
        var running = 0
        var mustMinutes = 0
        val flexible = proposal.actions.filter { eligible(it) && it.action.id !in inputs.pins }.sortedWith(order)
        val byId = items.associateBy { it.actionId }
        for (planned in flexible) {
            val action = planned.action
            val item = byId[action.id] ?: continue
            val projectId = action.projectId
            if (action.priority == Priority.MUST) {
                if (item.kind != ItemKind.QUEUED) {
                    add(PlanViolationKind.QUEUE, "a Must action that is not queued")
                    continue
                }
                running += item.minutes
                mustMinutes += item.minutes
                projectId?.let(placedProjects::add)
                val expected =
                    when {
                        running <= target -> Placement.WITHIN_TARGET
                        running <= free -> Placement.IN_BUFFER
                        else -> Placement.DOES_NOT_FIT
                    }
                if (item.placement != expected) add(PlanViolationKind.QUEUE, "a Must placement is not where its minutes end")
            } else {
                val blockedByProjects = projectId != null && projectId !in placedProjects && placedProjects.size >= Planner.MAX_PROJECTS
                val overTarget = running + item.minutes > target
                when (item.kind) {
                    ItemKind.QUEUED -> {
                        if (blockedByProjects) add(PlanViolationKind.PROJECT_LIMIT, "a third project was queued")
                        if (overTarget) add(PlanViolationKind.QUEUE, "a flexible item was queued past the target")
                        if (item.placement !=
                            Placement.WITHIN_TARGET
                        ) {
                            add(PlanViolationKind.QUEUE, "a flexible item is not within the target")
                        }
                        running += item.minutes
                        projectId?.let(placedProjects::add)
                    }
                    ItemKind.LEFT_OUT -> {
                        val expected = if (blockedByProjects) LeftOutReason.PROJECT_LIMIT else LeftOutReason.OVER_TARGET
                        if (item.reason !=
                            expected
                        ) {
                            add(PlanViolationKind.QUEUE, "a left-out item's reason does not follow from the minutes")
                        }
                        if (expected == LeftOutReason.OVER_TARGET &&
                            !overTarget
                        ) {
                            add(PlanViolationKind.QUEUE, "left out as over target but it fits")
                        }
                    }
                    ItemKind.PINNED -> add(PlanViolationKind.PINS, "a flexible action marked pinned")
                }
            }
        }

        // Overload.
        val overload = maxOf(0, mustMinutes - free)
        if (proposal.overloadMinutes != overload) add(PlanViolationKind.OVERLOAD, "overload is not the Must work beyond free time")
        return found
    }
}
