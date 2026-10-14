package com.thalia.planning

/**
 * Outcomes and how they relate to an action's status. ADR 0009, slice 3 amendment.
 *
 * An action's status is the only record of whether it is open. An outcome is history, and may carry
 * one marker, [PlanOutcome.closedAction], meaning "this outcome is what closed the action". Nothing
 * here derives status when it is read. These functions are pure: storage runs them inside one
 * transaction and writes what they return.
 */
enum class OutcomeResult { DONE, PARTIAL, SKIPPED, BLOCKED, UNKNOWN }

/** The recorded result for one plan item. One per item; correcting replaces it. */
data class PlanOutcome(
    val planItemId: String,
    val result: OutcomeResult,
    val recordedAtMillis: Long,
    val closedAction: Boolean = false,
) {
    init {
        require(planItemId.isNotBlank()) { "an outcome needs a plan item id" }
        require(!closedAction || result == OutcomeResult.DONE) { "only a DONE outcome can have closed its action" }
    }

    /** An explicit UNKNOWN is a reset: it counts as no recorded outcome. */
    val isRecorded: Boolean get() = result != OutcomeResult.UNKNOWN
}

/**
 * What recording or correcting one outcome does. [status] is the action's status afterwards.
 * [staysDoneNote] asks the screen for "The action stays done. Reopen it in Actions if it is not."
 */
data class OutcomeEffect(
    val outcome: PlanOutcome,
    val status: ActionStatus,
    val staysDoneNote: Boolean,
)

/**
 * Records [result] for the item, or corrects its [previous] row; both are the same operation.
 *
 * [status] is the action's current status and [otherOutcomes] its outcomes at other items.
 * - DONE on an open action closes it and takes the marker. On a done or dropped action it is history:
 *   no marker moves and the status stays. Re-recording DONE on the item that holds the marker keeps it.
 * - A marked outcome corrected away from DONE loses its marker, and the action reopens only if no other
 *   outcome is an effective DONE. Any other result changes no status.
 */
fun applyOutcome(
    itemId: String,
    result: OutcomeResult,
    nowMillis: Long,
    status: ActionStatus,
    previous: PlanOutcome?,
    otherOutcomes: List<PlanOutcome>,
): OutcomeEffect {
    require(otherOutcomes.none { it.planItemId == itemId }) { "otherOutcomes must not include the item's own outcome" }
    val heldMarker = previous?.closedAction == true && status == ActionStatus.DONE
    var newStatus = status
    val marker: Boolean
    if (result == OutcomeResult.DONE) {
        marker = status == ActionStatus.OPEN || heldMarker
        if (status == ActionStatus.OPEN) newStatus = ActionStatus.DONE
    } else {
        marker = false
        if (heldMarker && otherOutcomes.none { it.result == OutcomeResult.DONE }) newStatus = ActionStatus.OPEN
    }
    val note = previous?.result == OutcomeResult.DONE && result != OutcomeResult.DONE && newStatus == ActionStatus.DONE
    return OutcomeEffect(PlanOutcome(itemId, result, nowMillis, marker), newStatus, note)
}

/**
 * The action's [outcomes] after a manual save changed its status from [stored] to [saved]: a differing
 * status clears every marker, because the closure is no longer the outcome's. A save that leaves the
 * status as it was touches nothing.
 */
fun outcomesAfterManualSave(
    stored: ActionStatus,
    saved: ActionStatus,
    outcomes: List<PlanOutcome>,
): List<PlanOutcome> = if (stored == saved) outcomes else outcomes.map { if (it.closedAction) it.copy(closedAction = false) else it }

/**
 * Whether [item] of [plan] can have an outcome on [today]: a pinned or queued item of the latest
 * revision ([latestRevision]) of a plan whose active day is today or earlier.
 */
fun canRecordOutcome(
    item: PlanItem,
    plan: DayPlan,
    latestRevision: Int,
    today: ActiveDay,
): Boolean = item.planId == plan.id && item.kind != ItemKind.LEFT_OUT && plan.revision == latestRevision && plan.activeDay <= today

/** A new revision of a day is refused while its current revision has an effective recorded outcome. */
fun adjustmentLocked(currentRevisionOutcomes: Collection<PlanOutcome>): Boolean = currentRevisionOutcomes.any { it.isRecorded }

/** An open action offered for a later day, with the result of its item on the latest earlier day, if one counts. */
data class UnfinishedEntry(
    val action: Action,
    val lastResult: OutcomeResult?,
)

enum class OutcomeViolation { DUPLICATE_FOR_ITEM, MISSING_ITEM, LEFT_OUT_ITEM, TWO_MARKERS_FOR_ACTION, MARKER_WHILE_NOT_DONE }

/**
 * The first rule that [outcomes] break, over the plan [items] they refer to and the action statuses, or null.
 * Shared by import and by the storage's own check after a write: one outcome per item, only for a pinned or
 * queued item, at most one marker per action, and a marker only while the action is done. (A marker on a
 * result other than DONE cannot be built.)
 */
fun outcomeInvariantViolation(
    outcomes: List<PlanOutcome>,
    items: List<PlanItem>,
    actionStatuses: Map<String, ActionStatus>,
): OutcomeViolation? {
    val itemById = items.associateBy { it.id }
    if (outcomes.map { it.planItemId }.toSet().size != outcomes.size) return OutcomeViolation.DUPLICATE_FOR_ITEM
    for (outcome in outcomes) {
        val item = itemById[outcome.planItemId] ?: return OutcomeViolation.MISSING_ITEM
        if (item.kind == ItemKind.LEFT_OUT) return OutcomeViolation.LEFT_OUT_ITEM
    }
    val markedByAction = outcomes.filter { it.closedAction }.groupBy { itemById.getValue(it.planItemId).actionId }
    for ((actionId, marked) in markedByAction) {
        if (marked.size > 1) return OutcomeViolation.TWO_MARKERS_FOR_ACTION
        if (actionStatuses[actionId] != ActionStatus.DONE) return OutcomeViolation.MARKER_WHILE_NOT_DONE
    }
    return null
}
