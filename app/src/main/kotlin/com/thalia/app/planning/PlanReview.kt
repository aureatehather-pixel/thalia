package com.thalia.app.planning

import com.thalia.planning.ActiveDay
import com.thalia.planning.Capacity
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.FixedCommitment
import com.thalia.planning.ItemKind
import com.thalia.planning.Placement
import com.thalia.planning.PlanProposal
import com.thalia.planning.Priority
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/** How an item of a proposal compares with the approved plan it would replace. */
internal enum class Change { UNCHANGED, MOVED, LENGTH, NEW }

/** A timed block: a pinned item at its pinned time, or a fixed commitment. A queued item has no time and is never given one. */
internal data class ReviewBlock(
    val key: String,
    val title: String,
    val fixed: Boolean,
    /** "HH:mm" for a pinned item; the commitment's own span text for a fixed one. */
    val timeText: String,
    val endText: String?,
    val priority: Priority?,
    val minutes: Int,
    val change: Change?,
    /** What it was in the approved plan, when it moved: "Was 15:00" or "Was 2nd in the queue". */
    val before: String?,
    /** Minutes from the day's rollover, to order the timeline. */
    val order: Long,
)

internal data class ReviewQueued(
    val key: String,
    val position: Int,
    val title: String,
    val priority: Priority,
    val minutes: Int,
    val placement: Placement?,
    val change: Change?,
    val before: String?,
)

/** A planned item of the approved plan that the proposal no longer plans. */
internal data class ReviewRemoved(
    val title: String,
    val minutes: Int,
)

internal data class ChangeCounts(
    val unchanged: Int,
    val moved: Int,
    val length: Int,
    val added: Int,
    val removed: Int,
)

/**
 * What the Plan tab shows for a plan, a pending proposal or an approved one, from the store's own data. It states
 * only what the planner decided: pinned items and fixed commitments have times, the queue is in order with none.
 *
 * [planned] is the pinned plus queued minutes. [compared] is true when a proposal is shown against an approved plan, which
 * makes [counts] and each row's change meaningful.
 */
internal data class PlanReview(
    val timed: List<ReviewBlock>,
    val queue: List<ReviewQueued>,
    val removed: List<ReviewRemoved>,
    /** Pinned plus queued minutes. */
    val planned: Int,
    val target: Int,
    val free: Int,
    val compared: Boolean,
    val counts: ChangeCounts?,
    /** Queued minutes. The target is for these only, as the planner places them. */
    val queued: Int,
    /** Minutes of pinned items and of fixed commitments, each as listed; they overlap, so [occupied] can be less than their sum. */
    val pinnedMinutes: Int,
    val fixedMinutes: Int,
    /** Window minutes taken by pins and commitments together, overlaps counted once. Free time is [window] minus this. */
    val occupied: Int,
    val window: Int,
) {
    /** Queue minutes beyond the target, or 0. Only Must work goes past it, into the buffer, and the planner's warnings say so. */
    val overTarget: Int get() = (queued - target).coerceAtLeast(0)
}

private fun clockOf(time: LocalTime) = time.toString()

/** The review of a proposal, compared with [approved], the day's plan in force, when there is one. */
internal fun reviewOfProposal(
    proposal: PlanProposal,
    approved: DayPlanWithItems?,
): PlanReview {
    val byAction = proposal.actions.associateBy { it.action.id }
    val zone = proposal.inputs.zone
    val day = proposal.inputs.day
    val rollover = proposal.inputs.rollover
    val before =
        approved
            ?.items
            ?.filter { it.kind != ItemKind.LEFT_OUT }
            ?.associateBy { it.actionId }
            .orEmpty()
    val compared = approved != null

    fun compare(
        actionId: String,
        kind: ItemKind,
        start: LocalTime?,
        position: Int,
        minutes: Int,
    ): Pair<Change?, String?> {
        if (!compared) return null to null
        val old = before[actionId] ?: return Change.NEW to null
        val wasText = if (old.kind == ItemKind.PINNED) "Was ${clockOf(old.start!!)}" else "Was ${ordinal(old.position + 1)} in the queue"
        return when {
            old.kind != kind || (kind == ItemKind.PINNED && old.start != start) || (kind == ItemKind.QUEUED && old.position != position) ->
                Change.MOVED to wasText
            old.minutes != minutes -> Change.LENGTH to "Was ${old.minutes} min"
            else -> Change.UNCHANGED to null
        }
    }

    val timed = mutableListOf<ReviewBlock>()
    val queue = mutableListOf<ReviewQueued>()
    for (item in proposal.items) {
        val action = byAction[item.actionId]?.action
        val title = action?.title ?: "an action"
        val priority = action?.priority ?: Priority.NORMAL
        when (item.kind) {
            ItemKind.PINNED -> {
                val (change, was) = compare(item.actionId, item.kind, item.start, item.position, item.minutes)
                val start = checkNotNull(item.start)
                timed +=
                    ReviewBlock(
                        "item:${item.actionId}",
                        title,
                        false,
                        clockOf(start),
                        clockOf(start.plusMinutes(item.minutes.toLong())),
                        priority,
                        item.minutes,
                        change,
                        was,
                        ActiveDay.minutesAfterRollover(rollover, start).toLong(),
                    )
            }
            ItemKind.QUEUED -> {
                val (change, was) = compare(item.actionId, item.kind, null, item.position, item.minutes)
                queue += ReviewQueued("item:${item.actionId}", item.position, title, priority, item.minutes, item.placement, change, was)
            }
            ItemKind.LEFT_OUT -> Unit
        }
    }
    proposal.inputs.commitments.forEach { timed += fixedBlock(it, zone, day, rollover) }
    val planned = proposal.items.filter { it.kind != ItemKind.LEFT_OUT }.sumOf { it.minutes }
    val stillPlanned =
        proposal.items
            .filter { it.kind != ItemKind.LEFT_OUT }
            .map { it.actionId }
            .toSet()
    val removed =
        before.values
            .filter { it.actionId !in stillPlanned }
            .sortedWith(compareBy({ it.kind }, { it.position }))
            .map { ReviewRemoved(it.title, it.minutes) }
    val counts =
        if (!compared) {
            null
        } else {
            val changes = (timed.filter { !it.fixed }.map { it.change } + queue.map { it.change })
            ChangeCounts(
                changes.count { it == Change.UNCHANGED },
                changes.count { it == Change.MOVED },
                changes.count { it == Change.LENGTH },
                changes.count { it == Change.NEW },
                removed.size,
            )
        }
    return PlanReview(
        timed.sortedWith(compareBy({ it.order }, { it.key })),
        queue.sortedBy { it.position },
        removed,
        planned,
        proposal.targetMinutes,
        proposal.freeMinutes,
        compared,
        counts,
        queue.sumOf { it.minutes },
        timed.filter { !it.fixed }.sumOf { it.minutes },
        timed.filter { it.fixed }.sumOf { it.minutes },
        proposal.occupiedMinutes,
        proposal.windowMinutes,
    )
}

/** The review of an approved plan as it was approved, with the day's fixed [commitments] as they are now. */
internal fun reviewOfApproved(
    plan: DayPlanWithItems,
    commitments: List<FixedCommitment>,
): PlanReview {
    val p = plan.plan
    val timed = mutableListOf<ReviewBlock>()
    val queue = mutableListOf<ReviewQueued>()
    for (item in plan.items) {
        when (item.kind) {
            ItemKind.PINNED -> {
                val start = checkNotNull(item.start)
                timed +=
                    ReviewBlock(
                        "item:${item.id}",
                        item.title,
                        false,
                        clockOf(start),
                        clockOf(start.plusMinutes(item.minutes.toLong())),
                        item.priority,
                        item.minutes,
                        null,
                        null,
                        ActiveDay.minutesAfterRollover(p.rollover, start).toLong(),
                    )
            }
            ItemKind.QUEUED ->
                queue += ReviewQueued("item:${item.id}", item.position, item.title, item.priority, item.minutes, item.placement, null, null)
            ItemKind.LEFT_OUT -> Unit
        }
    }
    commitments.forEach { timed += fixedBlock(it, p.zone, p.activeDay, p.rollover) }
    // The window as it is now, from the plan's own snapshot; free time is what it recorded.
    val window =
        runCatching {
            Capacity.windowOf(p.activeDay, p.rollover, p.window, p.zone).let { (from, to) ->
                Duration.between(from, to).toMinutes().toInt()
            }
        }.getOrDefault(p.freeMinutes)
    val occupied = (window - p.freeMinutes).coerceAtLeast(0)
    return PlanReview(
        timed.sortedWith(compareBy({ it.order }, { it.key })),
        queue.sortedBy { it.position },
        emptyList(),
        plan.items.filter { it.kind != ItemKind.LEFT_OUT }.sumOf { it.minutes },
        p.targetMinutes,
        p.freeMinutes,
        false,
        null,
        queue.sumOf { it.minutes },
        timed.filter { !it.fixed }.sumOf { it.minutes },
        timed.filter { it.fixed }.sumOf { it.minutes },
        occupied,
        window,
    )
}

private fun fixedBlock(
    c: FixedCommitment,
    zone: ZoneId,
    day: ActiveDay,
    rollover: LocalTime,
): ReviewBlock {
    val start = c.start.atZone(zone)
    val end = c.end.atZone(zone)
    val offset = Duration.between(day.localAt(rollover, 0), start.toLocalDateTime()).toMinutes()
    val sameDay = end.toLocalDate() == start.toLocalDate()
    return ReviewBlock(
        key = "fixed:${c.id}",
        title = c.title,
        fixed = true,
        timeText = clockOf(start.toLocalTime().withSecond(0).withNano(0)),
        endText = clockOf(end.toLocalTime().withSecond(0).withNano(0)) + if (sameDay) "" else " next day",
        priority = null,
        minutes = Duration.between(c.start, c.end).toMinutes().toInt(),
        change = null,
        before = null,
        order = offset.coerceAtLeast(0),
    )
}

internal fun ordinal(n: Int): String =
    when {
        n % 100 in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"
        n % 10 == 2 -> "${n}nd"
        n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }

/** The two sets one adjustment changes: what is selected for the proposal and what was left out of it. */
internal data class Adjustment(
    val selected: Set<String>,
    val leftOut: Set<String>,
)

/** Leaves [id] out of the proposal. Its pin text is kept elsewhere, so including it again restores it. Nothing is saved. */
internal fun Adjustment.leaveOut(id: String): Adjustment = Adjustment(selected - id, leftOut + id)

/** Puts [id] back into the proposal. */
internal fun Adjustment.include(id: String): Adjustment = Adjustment(selected + id, leftOut - id)
