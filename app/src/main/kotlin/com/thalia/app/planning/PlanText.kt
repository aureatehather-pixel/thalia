package com.thalia.app.planning

import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.ItemKind
import com.thalia.planning.LeftOutReason
import com.thalia.planning.Placement
import com.thalia.planning.PlanErrorKind
import com.thalia.planning.PlanProposal
import com.thalia.planning.PlanWarningKind
import com.thalia.planning.Planner
import com.thalia.planning.Priority

/**
 * The words of a plan preview, kept apart from the screen so they are tested. Overload is stated
 * plainly and never as scheduled: "N minutes of Must work do not fit in this window". ADR 0009 decision 5.
 */
internal data class PlanText(
    val summary: List<String>,
    val pinned: List<String>,
    val queue: List<String>,
    val leftOut: List<String>,
    val warnings: List<String>,
    val errors: List<String>,
)

internal fun previewText(
    proposal: PlanProposal,
    titleOf: (String) -> String,
): PlanText {
    val window = proposal.inputs.window
    val summary =
        if (proposal.windowMinutes == 0) {
            emptyList()
        } else {
            listOf(
                "Window ${window.start} to ${window.end} (${proposal.windowMinutes} min), ${proposal.inputs.zone.id}",
                "Pinned ${proposal.pinnedMinutes} min. Free time ${proposal.freeMinutes} min. " +
                    "Target ${proposal.targetMinutes} min (${targetBasis(proposal)}).",
            ) + dayLoopSummary(proposal)
        }
    val items = proposal.items
    return PlanText(
        summary = if (proposal.isRestDay) summary + "Rest day: nothing is selected, so nothing is planned." else summary,
        pinned = items.filter { it.kind == ItemKind.PINNED }.map { "${it.start}  ${titleOf(it.actionId)} (${it.minutes} min)" },
        queue =
            items.filter { it.kind == ItemKind.QUEUED }.map {
                "${it.position + 1}. ${titleOf(it.actionId)} (${it.minutes} min)" + it.placement.suffix()
            },
        leftOut =
            items.filter { it.kind == ItemKind.LEFT_OUT }.map {
                "${titleOf(it.actionId)} (${it.minutes} min): ${it.reason.words(targetName(proposal))}"
            },
        warnings =
            proposal.warnings.map { warning ->
                when (warning.kind) {
                    PlanWarningKind.MUST_USES_BUFFER -> "Must work uses ${warning.minutes} minutes ${bufferName(proposal)}."
                    PlanWarningKind.MUST_DOES_NOT_FIT ->
                        "${warning.minutes} minutes of Must work do not fit in this window. " +
                            "Widen the window, unpin or shorten something, lower a priority, or remove an item."
                    PlanWarningKind.MUST_PROJECT_NOT_ACTIVE ->
                        "A Must action is selected but its project is not active, so it is not planned: " +
                            warning.actionIds.joinToString { titleOf(it) } + ". Make the project active or unselect it."
                    PlanWarningKind.MUST_SPANS_MANY_PROJECTS -> "Must work spans ${warning.minutes} projects. All of it is planned."
                    PlanWarningKind.PIN_OVERLAPS_COMMITMENT ->
                        "A pin overlaps a fixed commitment, and the time is counted once: " +
                            warning.actionIds.joinToString {
                                titleOf(
                                    it,
                                )
                            } +
                            "."
                }
            },
        errors =
            proposal.errors.map { error ->
                val names = error.actionIds.joinToString { titleOf(it) }
                when (error.kind) {
                    PlanErrorKind.ACTION_UNAVAILABLE -> "Not open any more, or not found: $names."
                    PlanErrorKind.PIN_FOR_UNSELECTED_ACTION -> "A pin for an action that is not selected: $names."
                    PlanErrorKind.PIN_OUTSIDE_WINDOW -> "A pinned action starts before, or ends after, the window: $names."
                    PlanErrorKind.PIN_OVERLAP -> "Pinned actions overlap: $names."
                    PlanErrorKind.TIME_DOES_NOT_EXIST ->
                        "A time does not exist on that date because the clocks skip it" + if (names.isEmpty()) "." else ": $names."
                    PlanErrorKind.WINDOW_EMPTY -> "The window is empty, or runs past the time the day turns over, on that date."
                }
            },
    )
}

/** True when the stated focus capacity, not 80% of free time, sets the target. */
internal fun focusLimits(proposal: PlanProposal): Boolean {
    val focus = proposal.inputs.focusMinutes ?: return false
    return focus < proposal.freeMinutes * Planner.TARGET_PERCENT / 100
}

internal fun targetBasis(proposal: PlanProposal) = if (focusLimits(proposal)) "your focus capacity" else "80% of free time"

private fun targetName(proposal: PlanProposal) = if (focusLimits(proposal)) "your focus capacity" else "the 80% target"

private fun bufferName(proposal: PlanProposal) =
    if (focusLimits(proposal)) "between your focus capacity and free time" else "of the 20% buffer"

/** Fixed commitments and focus, said only when they apply, so a plan without them reads as before. ADR 0011. */
private fun dayLoopSummary(proposal: PlanProposal): List<String> {
    val lines = mutableListOf<String>()
    val fixed = proposal.inputs.commitments
    if (fixed.isNotEmpty()) {
        val names = fixed.joinToString { "${it.title} ${CommitmentText.span(it, proposal.inputs.zone)}" }
        lines += "Fixed: $names. Pins and fixed time take ${proposal.occupiedMinutes} min of the window, overlaps counted once."
    }
    proposal.inputs.focusMinutes?.let { focus ->
        lines +=
            if (focusLimits(proposal)) {
                "Target capped at your focus capacity, $focus min."
            } else {
                "Focus capacity $focus min."
            }
    }
    return lines
}

/** An approved plan as it was approved, from its own snapshot, so later edits never change it. */
internal fun approvedText(plan: DayPlanWithItems): PlanText {
    val p = plan.plan
    val items = plan.items
    return PlanText(
        summary =
            listOf(
                "Plan approved, ${p.activeDay}, revision ${p.revision}",
                "Window ${p.window.start} to ${p.window.end}, ${p.zone.id}.",
            ) + if (items.isEmpty()) listOf("Rest day: nothing planned.") else emptyList(),
        pinned = items.filter { it.kind == ItemKind.PINNED }.map { "${it.start}  ${it.title} (${it.minutes} min)" },
        queue =
            items.filter { it.kind == ItemKind.QUEUED }.map {
                "${it.position + 1}. ${it.title} (${it.minutes} min)" +
                    it.placement.suffix()
            },
        leftOut = items.filter { it.kind == ItemKind.LEFT_OUT }.map { "${it.title} (${it.minutes} min): ${it.reason.words()}" },
        warnings =
            buildList {
                if (p.overloadMinutes > 0) add("${p.overloadMinutes} minutes of Must work do not fit in this window.")
                val mustBlocked = items.filter { it.reason == LeftOutReason.PROJECT_NOT_ACTIVE && it.priority == Priority.MUST }
                if (mustBlocked.isNotEmpty()) {
                    add(
                        "A Must action was not planned because its project is not active: ${mustBlocked.joinToString { it.title }}.",
                    )
                }
            },
        errors = emptyList(),
    )
}

private fun Placement?.suffix(): String =
    when (this) {
        Placement.WITHIN_TARGET, null -> ""
        Placement.IN_BUFFER -> "  [in buffer]"
        Placement.DOES_NOT_FIT -> "  [DOES NOT FIT]"
    }

private fun LeftOutReason?.words(target: String = "the target"): String =
    when (this) {
        LeftOutReason.PROJECT_LIMIT -> "project limit (at most two projects a day)"
        LeftOutReason.OVER_TARGET -> "over $target"
        LeftOutReason.PROJECT_NOT_ACTIVE -> "its project is not active"
        null -> ""
    }
