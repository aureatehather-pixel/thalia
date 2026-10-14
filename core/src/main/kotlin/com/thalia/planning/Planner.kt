package com.thalia.planning

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** A project as the planner sees it: only what eligibility and display depend on. */
data class ProjectRef(
    val id: String,
    val title: String,
    val state: ProjectState,
)

/** An action with its creation time, which breaks ties ("oldest first"). Part of what a proposal depends on. */
data class PlannedAction(
    val action: Action,
    val createdAtMillis: Long,
)

/**
 * What the user chose for one day, and the environment the choice was made in. ADR 0009 decisions 3 to 5.
 *
 * A proposal is made from exactly this and the stored actions and projects it names, so the approval
 * can make the same proposal again from fresh data and compare. [basedOnRevision] is the latest approved
 * revision for [day] when it was made, 0 if none.
 */
data class PlanInputs(
    val day: ActiveDay,
    val zone: ZoneId,
    val rollover: LocalTime,
    val window: PlanningWindow,
    /** True when [window] is the saved default, so a changed default makes the preview stale. */
    val windowIsDefault: Boolean,
    val selected: Set<String>,
    /** Per-day pins: action id to the local start time, never stored on the action. */
    val pins: Map<String, LocalTime>,
    val basedOnRevision: Int,
    /**
     * The active fixed commitments that overlap this active day, ordered by start then id. ADR 0011. Part of the inputs, so
     * a changed, added or cancelled commitment makes a preview stale even when the free minutes come out the same.
     */
    val commitments: List<FixedCommitment> = emptyList(),
    /** The person's focused-work capacity for the day, if they gave one. It caps the target; it is not clock time. */
    val focusMinutes: Int? = null,
)

enum class ItemKind { PINNED, QUEUED, LEFT_OUT }

/** Where a Must item ends in the queue: inside the 80% target, in the 20% buffer, or past free time. */
enum class Placement { WITHIN_TARGET, IN_BUFFER, DOES_NOT_FIT }

enum class LeftOutReason { PROJECT_LIMIT, OVER_TARGET, PROJECT_NOT_ACTIVE }

data class ProposedItem(
    val actionId: String,
    val kind: ItemKind,
    val minutes: Int,
    /** Pinned items only. */
    val start: LocalTime?,
    /** Order among items of the same kind, from 0. */
    val position: Int,
    /** Queued items only. */
    val placement: Placement?,
    /** Left-out items only. */
    val reason: LeftOutReason?,
)

enum class PlanErrorKind {
    /** A selected action does not exist, or is done or dropped. */
    ACTION_UNAVAILABLE,
    PIN_FOR_UNSELECTED_ACTION,
    PIN_OUTSIDE_WINDOW,
    PIN_OVERLAP,

    /** A wall-clock time of the window or a pin does not exist in the zone on that date (spring forward). */
    TIME_DOES_NOT_EXIST,
    WINDOW_EMPTY,
}

/** Blocks approval until fixed. [actionIds] names the items involved. */
data class PlanError(
    val kind: PlanErrorKind,
    val actionIds: List<String> = emptyList(),
)

enum class PlanWarningKind {
    /** Must work uses [PlanWarning.minutes] of the 20% buffer. */
    MUST_USES_BUFFER,

    /** [PlanWarning.minutes] of Must work do not fit in the window at all. Approval needs the separate acknowledgement. */
    MUST_DOES_NOT_FIT,

    /** A Must action was selected but its project is not active, and the planner does not override that. */
    MUST_PROJECT_NOT_ACTIVE,

    /** Must work spans more than two projects, which are all placed anyway. */
    MUST_SPANS_MANY_PROJECTS,

    /** A pin overlaps a fixed commitment. The time is counted once; the clash is the person's to resolve. */
    PIN_OVERLAPS_COMMITMENT,
}

data class PlanWarning(
    val kind: PlanWarningKind,
    val minutes: Int = 0,
    val actionIds: List<String> = emptyList(),
)

/**
 * The deterministic result of [Planner.propose]. The same inputs always give an equal proposal, so
 * approval can recompute it from fresh data and refuse any difference. It carries the actions and
 * projects it was made from, so equality covers every dependency of what is shown.
 */
data class PlanProposal(
    val inputs: PlanInputs,
    val actions: List<PlannedAction>,
    val projects: List<ProjectRef>,
    val windowMinutes: Int,
    val pinnedMinutes: Int,
    val freeMinutes: Int,
    val targetMinutes: Int,
    val items: List<ProposedItem>,
    val overloadMinutes: Int,
    val warnings: List<PlanWarning>,
    val errors: List<PlanError>,
    /** Window minutes taken by pins and commitments together, overlaps counted once. Free time is the window minus this. */
    val occupiedMinutes: Int = 0,
) {
    val canApprove: Boolean get() = errors.isEmpty()

    /** Nothing was selected: a rest day. It is a valid plan, but approving it needs its own explicit acknowledgement. */
    val isRestDay: Boolean get() = inputs.selected.isEmpty()

    /** Approval then needs the separate "Approve with N minutes unresolved". */
    val needsUnresolvedAcknowledgement: Boolean get() = overloadMinutes > 0
}

/**
 * The planner of ADR 0009 decision 5, as a pure function. It never reads a clock, a database or a
 * random source, and orders everything totally, so a proposal is reproducible.
 */
object Planner {
    /** The flexible-work target is 80% of free time, rounded down to whole minutes. A constant of the minimal loop. */
    const val TARGET_PERCENT = 80
    const val MAX_PROJECTS = 2

    /**
     * @param actions the stored actions, at least the selected ones; others are ignored.
     * @param projects the stored projects, at least the ones the selected actions name.
     */
    fun propose(
        inputs: PlanInputs,
        actions: List<PlannedAction>,
        projects: List<ProjectRef>,
    ): PlanProposal {
        val actionById = actions.associateBy { it.action.id }
        val projectById = projects.associateBy { it.id }
        val errors = mutableListOf<PlanError>()

        val selectedIds = inputs.selected.sorted()
        val unavailable =
            selectedIds.filter { id -> actionById[id]?.action?.status != ActionStatus.OPEN }
        if (unavailable.isNotEmpty()) errors += PlanError(PlanErrorKind.ACTION_UNAVAILABLE, unavailable)
        val strayPins =
            inputs.pins.keys
                .filter { it !in inputs.selected }
                .sorted()
        if (strayPins.isNotEmpty()) errors += PlanError(PlanErrorKind.PIN_FOR_UNSELECTED_ACTION, strayPins)

        val used = selectedIds.mapNotNull { actionById[it] }
        val usedProjects =
            used
                .mapNotNull { it.action.projectId }
                .distinct()
                .sorted()
                .mapNotNull { projectById[it] }
        val available = used.filter { it.action.status == ActionStatus.OPEN }

        fun failed(): PlanProposal = PlanProposal(inputs, used, usedProjects, 0, 0, 0, 0, emptyList(), 0, emptyList(), errors.toList())

        // The window, in real elapsed time: a daylight-saving change inside it is counted.
        val windowStart: Instant
        val windowEnd: Instant
        try {
            val startOffset = ActiveDay.minutesAfterRollover(inputs.rollover, inputs.window.start)
            val endOffset =
                ActiveDay.minutesAfterRollover(inputs.rollover, inputs.window.end).let {
                    if (it ==
                        0
                    ) {
                        ActiveDay.MINUTES_PER_DAY
                    } else {
                        it
                    }
                }
            windowStart = ActiveDay.resolve(inputs.day.localAt(inputs.rollover, startOffset), inputs.zone)
            windowEnd = ActiveDay.resolve(inputs.day.localAt(inputs.rollover, endOffset), inputs.zone)
        } catch (e: NonexistentLocalTimeException) {
            errors += PlanError(PlanErrorKind.TIME_DOES_NOT_EXIST)
            return failed()
        }
        if (!windowEnd.isAfter(windowStart)) {
            errors += PlanError(PlanErrorKind.WINDOW_EMPTY)
            return failed()
        }
        val windowMinutes = Duration.between(windowStart, windowEnd).toMinutes().toInt()

        // Eligibility: standalone, or in an active project. Must priority never overrides it.
        fun eligible(planned: PlannedAction): Boolean {
            val projectId = planned.action.projectId ?: return true
            return projectById[projectId]?.state == ProjectState.ACTIVE
        }
        val (eligibleActions, ineligible) = available.partition(::eligible)

        // Pins: only eligible actions are placed, so only they can be pinned in the plan.
        val pinned = eligibleActions.filter { it.action.id in inputs.pins }

        data class Pin(
            val planned: PlannedAction,
            val start: LocalTime,
            val from: Instant,
            val to: Instant,
        )
        val pins = mutableListOf<Pin>()
        val nonexistent = mutableListOf<String>()
        for (planned in pinned) {
            val start = inputs.pins.getValue(planned.action.id)
            try {
                val from = ActiveDay.resolve(inputs.day.localOf(inputs.rollover, start), inputs.zone)
                pins += Pin(planned, start, from, from.plusSeconds(planned.action.estimateMinutes * 60L))
            } catch (e: NonexistentLocalTimeException) {
                nonexistent += planned.action.id
            }
        }
        if (nonexistent.isNotEmpty()) errors += PlanError(PlanErrorKind.TIME_DOES_NOT_EXIST, nonexistent.sorted())
        val outside = pins.filter { it.from.isBefore(windowStart) || it.to.isAfter(windowEnd) }.map { it.planned.action.id }.sorted()
        if (outside.isNotEmpty()) errors += PlanError(PlanErrorKind.PIN_OUTSIDE_WINDOW, outside)
        val byStart = pins.sortedWith(compareBy<Pin> { it.from }.thenBy { it.planned.action.id })
        val overlapping = sortedSetOf<String>()
        byStart.forEachIndexed { index, pin ->
            val next = byStart.getOrNull(index + 1) ?: return@forEachIndexed
            if (next.from.isBefore(pin.to)) {
                overlapping += pin.planned.action.id
                overlapping += next.planned.action.id
            }
        }
        if (overlapping.isNotEmpty()) errors += PlanError(PlanErrorKind.PIN_OVERLAP, overlapping.toList())

        val pinnedMinutes = pins.sumOf { it.planned.action.estimateMinutes }
        val fixed = inputs.commitments.filter { it.status == CommitmentStatus.ACTIVE }.map { it.start to it.end }
        val occupiedMinutes = Capacity.occupiedMinutes(windowStart, windowEnd, pins.map { it.from to it.to } + fixed)
        val freeMinutes = maxOf(0, windowMinutes - occupiedMinutes)
        val targetMinutes = Capacity.target(freeMinutes, inputs.focusMinutes)
        val clashing = pins.filter { pin -> fixed.any { (from, to) -> pin.from.isBefore(to) && from.isBefore(pin.to) } }

        // The queue: Must, Important, Normal, Optional; then earlier due date, no due date, oldest, id.
        val queueOrder =
            compareBy<PlannedAction>({
                it.action.priority.ordinal
            }, { it.action.dueDate == null }, { it.action.dueDate }, { it.createdAtMillis }, { it.action.id })
        val candidates = eligibleActions.filter { it.action.id !in inputs.pins }.sortedWith(queueOrder)

        val items = mutableListOf<ProposedItem>()
        byStart.forEachIndexed { index, pin ->
            items += ProposedItem(pin.planned.action.id, ItemKind.PINNED, pin.planned.action.estimateMinutes, pin.start, index, null, null)
        }

        // Projects count in order across placed actions: pinned first, then the queue. Standalone ones do not count.
        val placedProjects = pins.mapNotNull { it.planned.action.projectId }.toMutableSet()
        var queued = 0
        var position = 0
        var mustMinutes = 0
        val leftOut = mutableListOf<ProposedItem>()
        for (planned in candidates) {
            val action = planned.action
            val projectId = action.projectId
            if (action.priority == Priority.MUST) {
                queued += action.estimateMinutes
                mustMinutes += action.estimateMinutes
                projectId?.let(placedProjects::add)
                val placement =
                    when {
                        queued <= targetMinutes -> Placement.WITHIN_TARGET
                        queued <= freeMinutes -> Placement.IN_BUFFER
                        else -> Placement.DOES_NOT_FIT
                    }
                items += ProposedItem(action.id, ItemKind.QUEUED, action.estimateMinutes, null, position++, placement, null)
            } else if (projectId != null && projectId !in placedProjects && placedProjects.size >= MAX_PROJECTS) {
                leftOut +=
                    ProposedItem(
                        action.id,
                        ItemKind.LEFT_OUT,
                        action.estimateMinutes,
                        null,
                        leftOut.size,
                        null,
                        LeftOutReason.PROJECT_LIMIT,
                    )
            } else if (queued + action.estimateMinutes > targetMinutes) {
                leftOut +=
                    ProposedItem(action.id, ItemKind.LEFT_OUT, action.estimateMinutes, null, leftOut.size, null, LeftOutReason.OVER_TARGET)
            } else {
                queued += action.estimateMinutes
                projectId?.let(placedProjects::add)
                items += ProposedItem(action.id, ItemKind.QUEUED, action.estimateMinutes, null, position++, Placement.WITHIN_TARGET, null)
            }
        }
        // Ineligible selections stay visible, with the reason, after the others, in the same order.
        ineligible.sortedWith(queueOrder).forEach {
            leftOut +=
                ProposedItem(
                    it.action.id,
                    ItemKind.LEFT_OUT,
                    it.action.estimateMinutes,
                    null,
                    leftOut.size,
                    null,
                    LeftOutReason.PROJECT_NOT_ACTIVE,
                )
        }
        items += leftOut

        val overload = maxOf(0, mustMinutes - freeMinutes)
        val bufferUsed = maxOf(0, minOf(mustMinutes, freeMinutes) - targetMinutes)
        val warnings = mutableListOf<PlanWarning>()
        if (bufferUsed > 0) warnings += PlanWarning(PlanWarningKind.MUST_USES_BUFFER, bufferUsed)
        if (overload > 0) {
            val doesNotFit = items.filter { it.placement == Placement.DOES_NOT_FIT }.map { it.actionId }
            warnings += PlanWarning(PlanWarningKind.MUST_DOES_NOT_FIT, overload, doesNotFit)
        }
        val mustBlocked = ineligible.filter { it.action.priority == Priority.MUST }.map { it.action.id }.sorted()
        if (mustBlocked.isNotEmpty()) warnings += PlanWarning(PlanWarningKind.MUST_PROJECT_NOT_ACTIVE, actionIds = mustBlocked)
        val mustProjects = candidates.filter { it.action.priority == Priority.MUST }.mapNotNull { it.action.projectId }.distinct()
        if (mustProjects.size > MAX_PROJECTS) warnings += PlanWarning(PlanWarningKind.MUST_SPANS_MANY_PROJECTS, mustProjects.size)
        if (clashing.isNotEmpty()) {
            warnings += PlanWarning(PlanWarningKind.PIN_OVERLAPS_COMMITMENT, actionIds = clashing.map { it.planned.action.id }.sorted())
        }

        return PlanProposal(
            inputs = inputs,
            actions = used,
            projects = usedProjects,
            windowMinutes = windowMinutes,
            pinnedMinutes = pinnedMinutes,
            freeMinutes = freeMinutes,
            targetMinutes = targetMinutes,
            items = items,
            overloadMinutes = overload,
            warnings = warnings,
            errors = errors.toList(),
            occupiedMinutes = occupiedMinutes,
        )
    }
}

/** The capacity arithmetic the planner and the validator share. ADR 0011. */
object Capacity {
    /** The window of [day] as real instants, as the planner computes it. Throws for a wall-clock time that does not exist. */
    fun windowOf(
        day: ActiveDay,
        rollover: LocalTime,
        window: PlanningWindow,
        zone: ZoneId,
    ): Pair<Instant, Instant> {
        val startOffset = ActiveDay.minutesAfterRollover(rollover, window.start)
        val endOffset = ActiveDay.minutesAfterRollover(rollover, window.end).let { if (it == 0) ActiveDay.MINUTES_PER_DAY else it }
        return ActiveDay.resolve(day.localAt(rollover, startOffset), zone) to ActiveDay.resolve(day.localAt(rollover, endOffset), zone)
    }

    /** Whole minutes of [windowStart, windowEnd) covered by the union of [intervals]: overlapping time is counted once. */
    fun occupiedMinutes(
        windowStart: Instant,
        windowEnd: Instant,
        intervals: List<Pair<Instant, Instant>>,
    ): Int {
        val clipped =
            intervals
                .map { (from, to) -> maxOf(from, windowStart) to minOf(to, windowEnd) }
                .filter { (from, to) -> to.isAfter(from) }
                .sortedBy { it.first }
        var seconds = 0L
        var runStart: Instant? = null
        var runEnd: Instant? = null
        for ((from, to) in clipped) {
            if (runEnd != null && !from.isAfter(runEnd)) {
                if (to.isAfter(runEnd)) runEnd = to
            } else {
                if (runStart != null) seconds += Duration.between(runStart, runEnd).seconds
                runStart = from
                runEnd = to
            }
        }
        if (runStart != null) seconds += Duration.between(runStart, runEnd).seconds
        return (seconds / 60).toInt()
    }

    /** 80% of free time, rounded down, and no more than the stated focus capacity when there is one. */
    fun target(
        freeMinutes: Int,
        focusMinutes: Int?,
    ): Int {
        val share = freeMinutes * Planner.TARGET_PERCENT / 100
        return if (focusMinutes == null) share else minOf(share, focusMinutes)
    }
}
