package com.thalia.app.planning

import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.FixedCommitment
import com.thalia.planning.ItemKind
import com.thalia.planning.NonexistentLocalTimeException
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanItem
import com.thalia.planning.PlanOutcome
import com.thalia.planning.Priority
import com.thalia.planning.canRecordOutcome
import java.time.Duration
import java.time.Instant

/**
 * One block on today's timeline: a pinned plan item or a fixed commitment, at real instants. A fixed commitment is not an
 * action, so it has no item and takes no outcome ([itemId] is null).
 */
internal data class TodayBlock(
    val key: String,
    val itemId: String?,
    val title: String,
    val start: Instant,
    val end: Instant,
    val priority: Priority?,
    /** The current result, or null when none is recorded (an explicit UNKNOWN counts as none). */
    val result: OutcomeResult?,
    val actionStatus: ActionStatus?,
    /** Whether an outcome may be recorded for it now (the approved plan's rules, not the clock's). */
    val canRecord: Boolean,
) {
    val fixed: Boolean get() = itemId == null
}

/** A queued plan item: in order, no time. */
internal data class TodayQueued(
    val itemId: String,
    val title: String,
    val priority: Priority,
    val minutes: Int,
    val result: OutcomeResult?,
    val actionStatus: ActionStatus?,
    val canRecord: Boolean,
)

/**
 * What the Today tab shows, computed from the person's own approved plan, outcomes and commitments. Nothing here is a sample.
 *
 * [now] is the block in progress, [upNext] the rest that have not ended (soonest first), [earlier] those that ended (latest
 * first), [queue] the queued items in plan order, and [toRecord] the most recently ended block with no outcome yet, the one
 * the compact "how did it go?" row asks about. A block ending never marks anything done and nothing is ever recorded here.
 */
internal data class TodayView(
    val hasPlan: Boolean,
    val now: TodayBlock?,
    val upNext: List<TodayBlock>,
    val earlier: List<TodayBlock>,
    val queue: List<TodayQueued>,
    val toRecord: TodayBlock?,
) {
    /** Earlier blocks that could still take an outcome and have none. */
    val earlierToRecord: Int get() = earlier.count { it.canRecord && it.result == null }

    /** The block after [block], for "After this": the next up-next block, or null. */
    fun after(block: TodayBlock): TodayBlock? = (listOfNotNull(now) + upNext).filter { it.start >= block.end }.minByOrNull { it.start }
}

/** Whole minutes left in [block] at [at], rounded up so the last minute still reads "1 min left". */
internal fun minutesLeft(
    block: TodayBlock,
    at: Instant,
): Int = ((Duration.between(at, block.end).seconds + 59) / 60).toInt().coerceAtLeast(0)

/** How far through [block] [at] is, from 0 to 1. */
internal fun progressOf(
    block: TodayBlock,
    at: Instant,
): Float {
    val total = Duration.between(block.start, block.end).toMillis().coerceAtLeast(1)
    return (Duration.between(block.start, at).toMillis().toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Builds today's view at [at]. [plan] is the day's latest approved revision, or null; [commitments] are the active fixed
 * commitments overlapping the day. A pinned item whose time cannot be placed (a wall-clock time the clocks skip) is listed in the
 * queue instead of being dropped.
 */
internal fun buildTodayView(
    at: Instant,
    today: ActiveDay,
    plan: DayPlanWithItems?,
    outcomes: Map<String, PlanOutcome>,
    actions: List<Action>,
    commitments: List<FixedCommitment>,
): TodayView {
    val statusOf = actions.associate { it.id to it.status }

    fun resultOf(item: PlanItem) = outcomes[item.id]?.takeIf { it.isRecorded }?.result

    fun allowed(item: PlanItem) = plan != null && canRecordOutcome(item, plan.plan, plan.plan.revision, today)

    val blocks = mutableListOf<TodayBlock>()
    val queue = mutableListOf<TodayQueued>()

    fun queued(item: PlanItem) =
        TodayQueued(item.id, item.title, item.priority, item.minutes, resultOf(item), statusOf[item.actionId], allowed(item))
    if (plan != null) {
        for (item in plan.items.sortedBy { it.position }) {
            when (item.kind) {
                ItemKind.LEFT_OUT -> Unit
                ItemKind.QUEUED -> queue += queued(item)
                ItemKind.PINNED -> {
                    val start =
                        try {
                            ActiveDay.resolve(plan.plan.activeDay.localOf(plan.plan.rollover, checkNotNull(item.start)), plan.plan.zone)
                        } catch (e: NonexistentLocalTimeException) {
                            null
                        }
                    if (start == null) {
                        queue += queued(item)
                    } else {
                        blocks +=
                            TodayBlock(
                                key = "item:${item.id}",
                                itemId = item.id,
                                title = item.title,
                                start = start,
                                end = start.plusSeconds(item.minutes * 60L),
                                priority = item.priority,
                                result = resultOf(item),
                                actionStatus = statusOf[item.actionId],
                                canRecord = allowed(item),
                            )
                    }
                }
            }
        }
    }
    commitments.forEach {
        blocks += TodayBlock("fixed:${it.id}", null, it.title, it.start, it.end, null, null, null, false)
    }
    val ordered = blocks.sortedWith(compareBy({ it.start }, { it.key }))
    val current = ordered.firstOrNull { it.start <= at && at < it.end }
    val upNext = ordered.filter { it.end > at && it != current }
    val earlier = ordered.filter { it.end <= at }.sortedWith(compareByDescending<TodayBlock> { it.end }.thenBy { it.key })
    val toRecord = earlier.firstOrNull { it.canRecord && it.result == null }
    return TodayView(plan != null, current, upNext, earlier, queue, toRecord)
}

/**
 * Which outcome prompt Today shows, so two never ask at once (the quiet-prompt rule in the specification: one prompt, no
 * nagging). The compact "how did it go?" card at the top, about the latest ended block with no outcome, is the one prompt.
 * The working day's re-entry question ("Did X happen? It decides whether the rest fits") waits until that card is gone, and
 * comes back only if it is still material. Neither prompt records anything: a tap on a result is the person's own record.
 */
internal object PromptRule {
    /** True when the "how did it go?" card is on screen: a plan exists, a block is waiting for an outcome and no focus screen is open. */
    fun cardShown(
        view: TodayView,
        focusId: String?,
    ): Boolean = focusId == null && view.hasPlan && view.toRecord != null

    /** The re-entry question may be asked only while the card is not asking. */
    fun mayAskQuestion(cardShown: Boolean): Boolean = !cardShown
}
