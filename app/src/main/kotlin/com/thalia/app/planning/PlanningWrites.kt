package com.thalia.app.planning

import com.thalia.app.planning.data.OutcomeRecording
import com.thalia.app.planning.data.PlanningRefusal
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.UndoResult
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.OutcomeResult

/**
 * The one way the Planning screen's sections write, so that every successful write tells the whole screen to reload.
 *
 * The editor and the day section each show a copy of the actions, and a status can change from either side: an outcome
 * closes or reopens an action, and an edit changes it by hand. Each section reloads when [onChanged] runs, so neither
 * copy is left showing what the other changed. A refusal or a failure changes nothing and reports nothing.
 */
internal class PlanningWrites(
    private val access: PlanningAccess,
    private val onChanged: () -> Unit,
) {
    /** Runs [write] under the planning lock. If [succeeded] says it did what was asked, the screen is told, after the commit. */
    suspend fun <T> run(
        write: suspend (PlanningStore) -> T,
        succeeded: (T) -> Boolean,
    ): T {
        val result = access.withStore(write)
        if (succeeded(result)) onChanged()
        return result
    }

    /** Saves [action]. [expectedStatus] is the status the editor was opened with; a different stored status is refused. */
    suspend fun saveAction(
        action: Action,
        expectedStatus: ActionStatus?,
    ): PlanningRefusal? = run({ it.saveAction(action, expectedStatus) }) { it == null }

    suspend fun recordOutcome(
        itemId: String,
        result: OutcomeResult,
        today: ActiveDay,
    ): OutcomeRecording = run({ it.recordOutcome(itemId, result, today) }) { it is OutcomeRecording.Recorded }

    suspend fun undoApproval(planId: String): UndoResult = run({ it.undoApproval(planId) }) { it is UndoResult.Restored }
}
