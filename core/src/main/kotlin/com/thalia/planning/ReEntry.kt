package com.thalia.planning

import java.time.Duration
import java.time.Instant

/**
 * What returning to an approved plan shows. ADR 0011, "Re-entry". Computed when a screen opens; nothing of it is stored.
 *
 * [next] is the first item, in the approved order, that is still available. [question] is the one unknown outcome worth
 * asking about, or null. Nothing here is inferred from elapsed time or from an item's place in the queue.
 */
data class ReEntry(
    val next: PlanItem?,
    val question: PlanItem?,
    /** Free clock minutes from now to the window's end, commitments removed. Null when the window cannot be resolved. */
    val remainingFreeMinutes: Int?,
    /** Estimated minutes of the items still available, unknown ones included. */
    val remainingLoadMinutes: Int,
    /** How far the available work exceeds the time left, or 0. */
    val overrunMinutes: Int,
    val windowOver: Boolean,
    /** The whole window's free minutes with today's commitments, when they differ from the approved plan's. */
    val freeNowMinutes: Int?,
    /** The stated focus capacity, when the plan queues more than it. */
    val focusBelowPlanMinutes: Int?,
)

object ReEntries {
    /**
     * @param declined true once the person answered "don't know" in this interaction: no further question is asked.
     */
    fun compute(
        plan: DayPlanWithItems,
        outcomes: Map<String, PlanOutcome>,
        actionStatus: Map<String, ActionStatus>,
        commitments: List<FixedCommitment>,
        focusMinutes: Int?,
        now: Instant,
        declined: Boolean,
    ): ReEntry {
        val placed = plan.items.filter { it.kind != ItemKind.LEFT_OUT }

        fun result(item: PlanItem) = outcomes[item.id]?.result?.takeIf { it != OutcomeResult.UNKNOWN }
        val available =
            placed.filter { item ->
                actionStatus[item.actionId] == ActionStatus.OPEN && result(item).let { it == null || it == OutcomeResult.PARTIAL }
            }
        val unknown = available.filter { result(it) == null }
        val load = available.sumOf { it.minutes }
        val unknownLoad = unknown.sumOf { it.minutes }

        val fixed = commitments.filter { it.status == CommitmentStatus.ACTIVE }.map { it.start to it.end }
        val window =
            try {
                Capacity.windowOf(plan.plan.activeDay, plan.plan.rollover, plan.plan.window, plan.plan.zone)
            } catch (e: NonexistentLocalTimeException) {
                null
            }
        val remaining =
            window?.let { (start, end) ->
                val from = maxOf(now, start)
                if (!end.isAfter(from)) 0 else Duration.between(from, end).toMinutes().toInt() - Capacity.occupiedMinutes(from, end, fixed)
            }
        val windowOver = window != null && !now.isBefore(window.second)
        val overrun = if (remaining == null) 0 else maxOf(0, load - remaining)
        // An unknown is worth one question only when the unknowns alone decide whether the rest fits: it does not fit if
        // they are all still to do, and would if they were all done. Otherwise either answer leads to the same next step.
        val material = remaining != null && !windowOver && load > remaining && load - unknownLoad <= remaining
        val freeNow =
            window?.let { (start, end) ->
                val pins = plan.items.filter { it.kind == ItemKind.PINNED }.mapNotNull { item -> item.start?.let { item to it } }
                val pinIntervals =
                    pins.mapNotNull { (item, time) ->
                        try {
                            val from = ActiveDay.resolve(plan.plan.activeDay.localOf(plan.plan.rollover, time), plan.plan.zone)
                            from to from.plusSeconds(item.minutes * 60L)
                        } catch (e: NonexistentLocalTimeException) {
                            null
                        }
                    }
                val free = Duration.between(start, end).toMinutes().toInt() - Capacity.occupiedMinutes(start, end, pinIntervals + fixed)
                maxOf(0, free).takeIf { it != plan.plan.freeMinutes }
            }
        val queued = placed.filter { it.kind == ItemKind.QUEUED }.sumOf { it.minutes }
        return ReEntry(
            next = available.firstOrNull(),
            question = if (material && !declined) unknown.firstOrNull() else null,
            remainingFreeMinutes = remaining?.let { maxOf(0, it) },
            remainingLoadMinutes = load,
            overrunMinutes = overrun,
            windowOver = windowOver,
            freeNowMinutes = freeNow,
            focusBelowPlanMinutes = focusMinutes?.takeIf { it < queued },
        )
    }
}
