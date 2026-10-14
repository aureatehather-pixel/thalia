package com.thalia.app.planning

import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.ItemKind
import com.thalia.planning.Placement
import com.thalia.planning.PlanProposal

/**
 * The plain explanation under a Plan proposal, written by Kotlin from the proposal's own data: the window and capacity
 * figures, the items the planner included, left out or placed in the buffer, and how it differs from the approved plan.
 * Every sentence states a figure or an outcome that the planner or the comparison produced; none gives a motive the data does
 * not carry, and no model is involved. It is a pure function of what is shown, so it follows every change to the draft, and
 * reading it writes nothing. [leftOutByYou] counts the items the person left out of this draft with the per-block controls. The reasons for
 * left-out items are not repeated here: each one is listed with its reason in the "Left out" lists above, and this card only says how many.
 */
internal fun explainProposal(
    proposal: PlanProposal,
    review: PlanReview,
    approved: DayPlanWithItems?,
    leftOutByYou: Int,
    titleOf: (String) -> String,
): List<String> {
    if (proposal.windowMinutes == 0) {
        return listOf("There is no plan to explain yet: the problem shown above has to be fixed first.")
    }
    val lines = mutableListOf<String>()
    val target = proposal.targetMinutes
    val basis =
        if (focusLimits(proposal)) {
            "capped at your focus capacity of ${proposal.inputs.focusMinutes} min"
        } else {
            "${com.thalia.planning.Planner.TARGET_PERCENT}% of free time"
        }
    lines +=
        "Your window is ${proposal.windowMinutes} min. Pins and fixed commitments take ${proposal.occupiedMinutes} min " +
        "(overlaps counted once), so ${proposal.freeMinutes} min are free. The target for flexible work is $target min, $basis."

    if (proposal.isRestDay) {
        lines += "Nothing is selected, so nothing is planned."
    } else {
        val kept = proposal.items.filter { it.kind != ItemKind.LEFT_OUT }
        val pinned = review.timed.filter { !it.fixed }
        lines +=
            "Planned: ${review.planned} min in ${kept.size} item${if (kept.size == 1) "" else "s"} " +
            "(${pinned.size} pinned, ${review.queue.size} in the queue)."
        if (pinned.isNotEmpty()) {
            lines += "Pinned at the times you chose: " + pinned.joinToString { "${it.title} at ${it.timeText}" } + "."
        }
        if (review.queue.isNotEmpty()) {
            lines +=
                "The queue takes ${review.queue.sumOf {
                    it.minutes
                }} min against the $target min target. Pinned time is not part of the target."
        }
        if (review.queue.size >= 2) {
            lines +=
                "The queue has no times. It is ordered Must, Important, Normal, Optional; within a priority, " +
                "earlier due date first, then oldest first."
        }
        proposal.items
            .filter { it.placement == Placement.IN_BUFFER }
            .forEach {
                lines +=
                    "${titleOf(it.actionId)} is a Must item. It goes past the $target min target, into the buffer, but within free time."
            }
        proposal.items
            .filter { it.placement == Placement.DOES_NOT_FIT }
            .forEach {
                lines +=
                    "${titleOf(
                        it.actionId,
                    )} is a Must item. It is still listed, but it does not fit in the ${proposal.freeMinutes} free min."
            }
    }
    val byPlanner = proposal.items.count { it.kind == ItemKind.LEFT_OUT }
    if (byPlanner > 0) {
        lines += "The planner left out $byPlanner item${if (byPlanner == 1) "" else "s"}. Each reason is listed under Left out above."
    }
    if (leftOutByYou > 0) {
        lines += "You left out $leftOutByYou item${if (leftOutByYou == 1) "" else "s"} of this draft. Each has an Include control above."
    }

    if (approved != null) lines += compareWith(proposal, review, approved)
    return lines
}

private fun compareWith(
    proposal: PlanProposal,
    review: PlanReview,
    approved: DayPlanWithItems,
): List<String> {
    val p = approved.plan
    val lines = mutableListOf("Compared with revision ${p.revision}:")
    if (p.freeMinutes != proposal.freeMinutes) lines += "Free time is ${proposal.freeMinutes} min (was ${p.freeMinutes})."
    if (p.targetMinutes != proposal.targetMinutes) lines += "The target is ${proposal.targetMinutes} min (was ${p.targetMinutes})."
    var changed = false
    review.timed.filter { !it.fixed }.forEach {
        when (it.change) {
            Change.MOVED -> lines += "${it.title} moved to ${it.timeText}. ${it.before}."
            Change.LENGTH -> lines += "${it.title} is now ${it.minutes} min. ${it.before}."
            Change.NEW -> lines += "${it.title} is new in the plan, pinned at ${it.timeText}."
            else -> Unit
        }
        if (it.change != Change.UNCHANGED && it.change != null) changed = true
    }
    review.queue.forEach {
        when (it.change) {
            Change.MOVED -> lines += "${it.title} is now ${ordinal(it.position + 1)} in the queue. ${it.before}."
            Change.LENGTH -> lines += "${it.title} is now ${it.minutes} min. ${it.before}."
            Change.NEW -> lines += "${it.title} is new in the plan, ${ordinal(it.position + 1)} in the queue."
            else -> Unit
        }
        if (it.change != Change.UNCHANGED && it.change != null) changed = true
    }
    if (review.removed.isNotEmpty()) {
        val n = review.removed.size
        lines +=
            "$n item${if (n == 1) " is" else "s are"} no longer planned. ${if (n == 1) "It is" else "They are"} listed under Removed from the plan above."
        changed = true
    }
    if (!changed) lines += "No block changed and nothing was removed."
    return lines
}
