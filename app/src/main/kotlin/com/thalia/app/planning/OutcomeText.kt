package com.thalia.app.planning

import com.thalia.app.planning.data.OutcomeRefusal
import com.thalia.app.planning.data.UndoRefusal
import com.thalia.app.planning.data.UndoResult
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanOutcome
import com.thalia.planning.UnfinishedEntry
import com.thalia.planning.adjustmentLocked
import com.thalia.planning.canRecordOutcome

/**
 * The words and rows of the outcomes section, kept apart from the screen so they are tested. ADR 0009,
 * slice 3 amendment. Titles are the approved snapshot's, so editing an action later never rewrites them.
 */
internal data class OutcomeRow(
    val itemId: String,
    val title: String,
    /** The current result, or null when none is recorded (an explicit UNKNOWN counts as none). */
    val result: OutcomeResult?,
    /** The action's status now, which an outcome may have changed. Null if the action cannot be found. */
    val actionStatus: ActionStatus?,
)

/**
 * The rows that can take an outcome on [today]: pinned and queued items of [latest], the day's latest
 * revision, and only when its active day is today or earlier. Left-out items and later days give none.
 */
internal fun outcomeRows(
    latest: DayPlanWithItems,
    outcomes: Map<String, PlanOutcome>,
    actions: List<Action>,
    today: ActiveDay,
): List<OutcomeRow> {
    val statusOf = actions.associate { it.id to it.status }
    return latest.items
        .filter { canRecordOutcome(it, latest.plan, latest.plan.revision, today) }
        .map { item ->
            OutcomeRow(
                item.id,
                item.title,
                outcomes[item.id]?.takeIf { it.isRecorded }?.result,
                statusOf[item.actionId],
            )
        }
}

/** Whether the day's current revision has an effective outcome, which refuses Adjust and a new approval. The wording never tells the person to clear outcomes. */
internal fun adjustLocked(
    latest: DayPlanWithItems,
    outcomes: Map<String, PlanOutcome>,
): Boolean = adjustmentLocked(latest.items.mapNotNull { outcomes[it.id] })

internal const val ADJUST_LOCKED_NOTE =
    "Adjust is unavailable because outcomes have been recorded for this plan. Recorded outcomes are kept as they are. " +
        "Keep original is still available."

/** Shown in place of the Undo button once an outcome is recorded on the plan just approved. It names the reason and promises nothing was changed. */
internal const val UNDO_LOCKED_NOTE =
    "Undo is no longer available for this approval, because outcomes have been recorded for this plan. They are kept as they are."

internal const val STAYS_DONE_NOTE = "The action stays done. Reopen it in Actions if it is not."

internal fun resultWords(result: OutcomeResult?): String = result?.name?.lowercase() ?: "no result recorded"

internal fun outcomeRowText(row: OutcomeRow): String =
    "${row.title}: ${resultWords(row.result)}" +
        when (row.actionStatus) {
            ActionStatus.DONE -> " (action done)"
            ActionStatus.DROPPED -> " (action dropped)"
            else -> ""
        }

internal fun outcomeRefusalText(reason: OutcomeRefusal): String =
    when (reason) {
        OutcomeRefusal.ITEM_NOT_FOUND -> "That item is no longer in the plan. Nothing was saved."
        OutcomeRefusal.NOT_ELIGIBLE ->
            "An outcome can be recorded only for a planned item of the latest revision, on or after its day. Nothing was saved."
    }

/** The message after a saved outcome: what was recorded, then the stays-done line if it applies. */
internal fun recordedText(
    result: OutcomeResult,
    staysDone: Boolean,
): String = "Recorded ${resultWords(result)}." + if (staysDone) " $STAYS_DONE_NOTE" else ""

/** The Unfinished choice's label, annotated with its item's result on the latest earlier day. */
internal fun unfinishedLabel(entry: UnfinishedEntry): String =
    "Unfinished (" + (entry.lastResult?.let { "last time: ${resultWords(it)}" } ?: "no result recorded") + "): "

internal fun undoText(result: UndoResult): String =
    when (result) {
        is UndoResult.Restored ->
            "Approval undone. Revision ${result.plan.plan.revision} restores the earlier plan. The undone revision is kept."
        is UndoResult.Refused ->
            when (result.reason) {
                UndoRefusal.STALE -> "This plan is no longer the latest, so nothing was undone."
                UndoRefusal.NO_PREVIOUS -> "There is no earlier plan to restore. Nothing was changed."
                UndoRefusal.OUTCOMES_RECORDED ->
                    "This plan has recorded outcomes, so it cannot be undone. Nothing was changed."
            }
    }
