package com.thalia.app.planning

import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.DayPlanWithItems
import java.time.LocalTime

/**
 * The starting point of an Adjust: the approved plan's choices, minus the actions that can no longer be planned.
 * An action that is done, dropped or gone is not offered in the selection, so it could not be unticked by hand
 * and would block the proposal. [excludedTitles] are the approved snapshot's titles, in plan order.
 */
internal data class AdjustPrefill(
    val selected: Set<String>,
    val pins: Map<String, LocalTime>,
    val excludedTitles: List<String>,
)

internal fun adjustPrefill(
    approved: DayPlanWithItems,
    actions: List<Action>,
): AdjustPrefill {
    val open = actions.filter { it.status == ActionStatus.OPEN }.map { it.id }.toSet()
    val excluded = approved.items.filter { it.actionId !in open }
    return AdjustPrefill(
        selected = approved.selected.filter { it in open }.toSet(),
        pins = approved.pins.filterKeys { it in open },
        excludedTitles = excluded.map { it.title }.distinct(),
    )
}

internal fun adjustMessage(
    revision: Int,
    prefill: AdjustPrefill,
): String =
    "Adjusting revision $revision. The approved plan stays in force until you approve a new one." +
        if (prefill.excludedTitles.isEmpty()) {
            ""
        } else {
            " Left out because they are no longer open: ${prefill.excludedTitles.joinToString(", ")}."
        }
