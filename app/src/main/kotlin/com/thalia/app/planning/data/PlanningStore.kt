package com.thalia.app.planning.data

import androidx.room.withTransaction
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.CommitmentStatus
import com.thalia.planning.ContextField
import com.thalia.planning.DayPlan
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.Direction
import com.thalia.planning.DirectionRecord
import com.thalia.planning.FixedCommitment
import com.thalia.planning.Goal
import com.thalia.planning.GoalStatus
import com.thalia.planning.InboxItem
import com.thalia.planning.InboxState
import com.thalia.planning.ItemKind
import com.thalia.planning.LeftOutReason
import com.thalia.planning.OutcomeEffect
import com.thalia.planning.OutcomeResult
import com.thalia.planning.Placement
import com.thalia.planning.PlanInputs
import com.thalia.planning.PlanItem
import com.thalia.planning.PlanOutcome
import com.thalia.planning.PlanProposal
import com.thalia.planning.PlanValidator
import com.thalia.planning.PlannedAction
import com.thalia.planning.Planner
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningSnapshot
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import com.thalia.planning.Project
import com.thalia.planning.ProjectRef
import com.thalia.planning.ProjectState
import com.thalia.planning.Reminder
import com.thalia.planning.ReminderReason
import com.thalia.planning.ReminderRules
import com.thalia.planning.ReminderStatus
import com.thalia.planning.ReminderTargetType
import com.thalia.planning.Stamped
import com.thalia.planning.WorkingDayRecord
import com.thalia.planning.WorkingDays
import com.thalia.planning.adjustmentLocked
import com.thalia.planning.applyOutcome
import com.thalia.planning.canRecordOutcome
import com.thalia.planning.outcomeInvariantViolation
import com.thalia.planning.outcomesAfterManualSave
import com.thalia.planning.restoredRows
import com.thalia.planning.toApprovedRows
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

private const val CALL_REFUSED = "REFUSED"
private const val CALL_NEEDS_ATTENTION = "NEEDS_ATTENTION"

/** Why a save was refused. Nothing was written. */
enum class PlanningRefusal {
    GOAL_NOT_FOUND,
    PROJECT_NOT_FOUND,

    /** An action cannot be added to, or moved into, a completed, abandoned or archived project. */
    PROJECT_CLOSED,

    /**
     * The action's stored status is no longer the one the editor was opened with, for example because an outcome
     * closed or reopened it. Saving would silently reverse that, so nothing is written and the editor is opened again.
     */
    ACTION_CHANGED,

    /**
     * The stored project is no longer the one the editor was opened with, for example because the conversation changed
     * its context. Saving would overwrite that from an older copy, so nothing is written and the draft stays in the editor.
     */
    PROJECT_CHANGED,
}

/** Why an approval was refused. Nothing was written. */
enum class ApprovalRefusal {
    /**
     * The proposal is not the one the stored data now gives: an action, a project, the rollover, the default
     * window, the zone or the day's latest revision changed since it was made, or it was not made by the
     * planner at all. Propose again.
     */
    CHANGED,

    /** The proposal has blocking errors. */
    INVALID,

    /** The plan has Must work that does not fit, and the separate acknowledgement was not given. */
    UNRESOLVED_NOT_ACKNOWLEDGED,

    /** Nothing was selected, which is a rest day, and the separate acknowledgement was not given. */
    REST_DAY_NOT_ACKNOWLEDGED,

    /** The proposal breaks the planning rules on its own contents, whatever the stored data says. See [com.thalia.planning.PlanValidator]. */
    MALFORMED,

    /**
     * The day's current revision has an effective recorded outcome (a result other than UNKNOWN), so a new
     * revision is refused. Correcting every outcome back to UNKNOWN lifts it. ADR 0009 decision U1.
     */
    OUTCOMES_RECORDED,
}

/** Why an Undo of an approved plan was refused. Nothing was written. */
enum class UndoRefusal {
    /** The plan the person saw is no longer the day's latest revision (already undone, or replaced). */
    STALE,

    /** The plan is the day's first revision, so there is nothing earlier to restore. */
    NO_PREVIOUS,

    /** The plan has an effective recorded outcome. Same rule as a new revision (ADR 0009 decision U1); no outcome is touched. */
    OUTCOMES_RECORDED,
}

sealed interface UndoResult {
    data class Restored(
        val plan: DayPlanWithItems,
    ) : UndoResult

    data class Refused(
        val reason: UndoRefusal,
    ) : UndoResult
}

/** Why an outcome was refused. Nothing was written. */
enum class OutcomeRefusal {
    ITEM_NOT_FOUND,

    /** Only a pinned or queued item of the latest revision of a plan whose active day is today or earlier can have an outcome. */
    NOT_ELIGIBLE,
}

sealed interface OutcomeRecording {
    data class Recorded(
        val effect: OutcomeEffect,
    ) : OutcomeRecording

    data class Refused(
        val reason: OutcomeRefusal,
    ) : OutcomeRecording
}

/** The result of [PlanningStore.authorizeOpeningCall]. */
enum class CallAuthorization {
    /** The call is now REQUESTED, which is what a launch retry acts on. */
    AUTHORIZED,

    /** The plan has no opening time, or does not exist. */
    NO_OPENING_TIME,

    /** Only a NOT_LINKED call can be requested; this one already has another status. Nothing changed. */
    NOT_UNLINKED,

    /** Only the latest revision of a day that is today or later can be requested. */
    NOT_ELIGIBLE,
}

/** The result of [PlanningStore.createAction]. Anything but CREATED wrote nothing. */
enum class CreateActionResult {
    CREATED,

    /** An action with this id is already stored: the same creation was applied before. Nothing is overwritten. */
    ALREADY_EXISTS,

    /** An open action with the same title is already in that project, so a repeat of an interrupted save cannot duplicate it. */
    DUPLICATE_TITLE,
    PROJECT_NOT_FOUND,
    PROJECT_CLOSED,
}

/** The result of [PlanningStore.changeProjectContext]. Anything but CHANGED wrote nothing. */
enum class ContextChangeResult { CHANGED, STALE, PROJECT_NOT_FOUND }

/** The result of [PlanningStore.createCommitment]. Anything but CREATED wrote nothing. */
enum class CreateCommitmentResult {
    CREATED,

    /** This id is stored already: the same creation was applied before. */
    ALREADY_EXISTS,

    /** An active commitment with the same title, start and end exists. */
    DUPLICATE,
}

/** The result of [PlanningStore.captureItem]. Anything but CAPTURED wrote nothing. */
enum class CaptureResult {
    CAPTURED,

    /** This id is stored with the same text: the same capture was applied before. */
    ALREADY_CAPTURED,

    /** This id is stored with different text. Nothing is overwritten. */
    CONFLICT,
}

/** The result of [PlanningStore.resolveItem]. Anything but RESOLVED wrote nothing. */
enum class ResolveInboxResult {
    RESOLVED,

    /** The item is already in the state asked for, so a retry changes nothing. */
    ALREADY_RESOLVED,

    /** The item is no longer open, or is filed another way. */
    NOT_OPEN,
    NOT_FOUND,
}

/** The result of [PlanningStore.reopenItem]. Anything but REOPENED wrote nothing. */
enum class ReopenInboxResult { REOPENED, STALE, NOT_REOPENABLE, NOT_FOUND }

/** The result of [PlanningStore.editBody]. Anything but EDITED wrote nothing. */
sealed interface GoalDeletion {
    data object Deleted : GoalDeletion

    data object NotFound : GoalDeletion

    /** Projects still linked to the goal, by title. */
    data class Blocked(
        val projectTitles: List<String>,
    ) : GoalDeletion
}

sealed interface InboxDeletion {
    data class Deleted(
        val remindersKept: Int,
    ) : InboxDeletion

    data object NotFound : InboxDeletion

    data object StillOpen : InboxDeletion

    data object BecameAction : InboxDeletion
}

enum class NoteChange { DONE, STALE, NOT_FOUND }

enum class EditBodyResult { EDITED, STALE, NOT_EDITABLE, NOT_FOUND }

/** The result of [PlanningStore.convertToAction]. Anything but CONVERTED and ALREADY_CONVERTED wrote nothing. */
enum class ConvertInboxResult {
    CONVERTED,

    /** The item already became exactly this action: a retry of the same conversion. */
    ALREADY_CONVERTED,
    ITEM_NOT_FOUND,
    ITEM_NOT_OPEN,

    /** The item became a different action, or the action's id is taken by something else. Nothing was changed. */
    CONFLICT,
    DUPLICATE_TITLE,
    PROJECT_NOT_FOUND,
    PROJECT_CLOSED,
}

/** The result of [PlanningStore.createReminder]. Anything but CREATED and ALREADY_CREATED wrote nothing. */
enum class CreateReminderResult {
    CREATED,

    /** The same reminder is already stored: a retry. */
    ALREADY_CREATED,

    /** This id is stored with different content. Nothing is overwritten. */
    CONFLICT,
    TARGET_NOT_FOUND,
    TOO_MANY,
}

/** The result of the working-day transitions, ADR 0011. */
sealed interface WorkingDayChange {
    data class Opened(
        val day: ActiveDay,
        val reopened: Boolean,
    ) : WorkingDayChange

    /** Nothing written: another working day is open, and only Start or Continue applies. */
    data class AnotherOpen(
        val day: ActiveDay,
    ) : WorkingDayChange

    data class Closed(
        val day: ActiveDay,
    ) : WorkingDayChange

    data class Continued(
        val day: ActiveDay,
    ) : WorkingDayChange

    /** Nothing written: the day was not in the state the action needs. */
    data object NotApplicable : WorkingDayChange
}

/** The result of [PlanningStore.changeAction]. */
enum class ChangeActionResult {
    CHANGED,

    /** The stored action is no longer the one the change was made from, or is gone. Nothing was written. */
    STALE,
}

/** A plan, its opening call row, and whether the plan is the latest revision of its day, read together. */
internal data class CallTarget(
    val plan: DayPlan,
    val call: PlanCall,
    val latest: Boolean,
)

/** A plan's opening call as stored: a log of what the link last did, which authorizes nothing. */
data class PlanCall(
    val planId: String,
    val openingTime: LocalTime,
    val status: String,
    val detail: String?,
    val updatedAtMillis: Long,
)

sealed interface ApprovalResult {
    data class Approved(
        val plan: DayPlanWithItems,
    ) : ApprovalResult

    data class Refused(
        val reason: ApprovalRefusal,
    ) : ApprovalResult
}

/**
 * Reads and saves the planning information, enforcing the links between it. ADR 0009 slice 1A.
 *
 * Every save is one transaction and writes only what the user saved: nothing here creates, splits
 * or links a project or an action by itself. Values are validated when they are built, so only the
 * links between rows are checked here. Nothing is logged.
 */
internal class PlanningStore(
    private val database: PlanningDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.planning()

    suspend fun direction(): Direction? = dao.direction()?.let { Direction(it.text) }

    suspend fun saveDirection(direction: Direction) = dao.saveDirection(DirectionEntity(text = direction.text, updatedAtMillis = clock()))

    suspend fun goals(): List<Goal> = dao.goals().map { it.toDomain() }

    suspend fun projects(): List<Project> = dao.projects().map { it.toDomain() }

    suspend fun actions(): List<Action> = dao.actions().map { it.toDomain() }

    /** Creates the goal, or updates it if its id is already stored. */
    suspend fun saveGoal(goal: Goal): PlanningRefusal? =
        database.withTransaction {
            val now = clock()
            val existing = dao.goal(goal.id)
            if (existing == null) {
                dao.insertGoal(goal.toEntity(createdAt = now, updatedAt = now))
            } else {
                dao.updateGoal(goal.toEntity(createdAt = existing.createdAtMillis, updatedAt = now))
            }
            null
        }

    /**
     * Creates or updates the project. Its goal, if any, must exist. An editor passes the project it loaded as [expected],
     * and the save is refused if the stored project has changed since.
     */
    suspend fun saveProject(
        project: Project,
        expected: Project? = null,
    ): PlanningRefusal? =
        database.withTransaction {
            val goalId = project.goalId
            if (goalId != null && dao.goal(goalId) == null) return@withTransaction PlanningRefusal.GOAL_NOT_FOUND
            val now = clock()
            val existing = dao.project(project.id)
            if (expected != null && existing?.toDomain() != expected) return@withTransaction PlanningRefusal.PROJECT_CHANGED
            if (existing == null) {
                dao.insertProject(project.toEntity(createdAt = now, updatedAt = now))
            } else {
                dao.updateProject(project.toEntity(createdAt = existing.createdAtMillis, updatedAt = now))
            }
            null
        }

    /**
     * Creates or updates the action. Its project, if any, must exist, and an action may be added to or
     * moved into only a project that still accepts actions. An action already in a project that has
     * since closed can still be edited, for example to mark it done.
     */
    suspend fun saveAction(
        action: Action,
        expectedStatus: ActionStatus? = null,
    ): PlanningRefusal? =
        database.withTransaction {
            val existing = dao.action(action.id)
            // An editor opened before an outcome changed the status must not write its old status back.
            if (expectedStatus != null && existing != null && existing.status != expectedStatus.name) {
                return@withTransaction PlanningRefusal.ACTION_CHANGED
            }
            val projectId = action.projectId
            if (projectId != null) {
                val project = dao.project(projectId) ?: return@withTransaction PlanningRefusal.PROJECT_NOT_FOUND
                val joining = existing == null || existing.projectId != projectId
                if (joining && !ProjectState.valueOf(project.state).acceptsActions) return@withTransaction PlanningRefusal.PROJECT_CLOSED
            }
            val now = clock()
            if (existing == null) {
                dao.insertAction(action.toEntity(createdAt = now, updatedAt = now))
            } else {
                dao.updateAction(action.toEntity(createdAt = existing.createdAtMillis, updatedAt = now))
                // The manual-write rule: a hand-made status change clears the closing marker of every outcome of
                // this action, so a later correction can never reopen a closure the outcome did not make.
                val outcomes = dao.outcomesOfAction(action.id).map { it.toDomain() }
                outcomesAfterManualSave(ActionStatus.valueOf(existing.status), action.status, outcomes)
                    .filter { it !in outcomes }
                    .forEach { dao.saveOutcome(it.toEntity()) }
            }
            null
        }

    /**
     * Creates [action] only if it is new: an id that is already stored is [CreateActionResult.ALREADY_EXISTS] and is never
     * overwritten, so a creation that is applied twice, for example by a retry after an interruption, makes one action.
     * An open action with the same title in the same project is refused for the same reason. Links are checked as in [saveAction].
     */
    suspend fun createAction(action: Action): CreateActionResult =
        database.withTransaction {
            if (dao.action(action.id) != null) return@withTransaction CreateActionResult.ALREADY_EXISTS
            val projectId = action.projectId
            if (projectId != null) {
                val project = dao.project(projectId) ?: return@withTransaction CreateActionResult.PROJECT_NOT_FOUND
                if (!ProjectState.valueOf(project.state).acceptsActions) return@withTransaction CreateActionResult.PROJECT_CLOSED
            }
            val repeated =
                dao.actions().any {
                    it.status == ActionStatus.OPEN.name &&
                        it.projectId == projectId &&
                        it.title.trim().equals(action.title, ignoreCase = true)
                }
            if (repeated) return@withTransaction CreateActionResult.DUPLICATE_TITLE
            val now = clock()
            dao.insertAction(action.toEntity(createdAt = now, updatedAt = now))
            CreateActionResult.CREATED
        }

    /**
     * Replaces [before] with [after] only if [before] is exactly what is stored, so a change proposed from an earlier read
     * never overwrites a newer one. Only title, priority, estimate and due date can differ: status and project stay as stored.
     */
    suspend fun changeAction(
        before: Action,
        after: Action,
    ): ChangeActionResult =
        database.withTransaction {
            require(after.id == before.id && after.status == before.status && after.projectId == before.projectId) {
                "a change keeps the action's id, status and project"
            }
            val existing = dao.action(before.id) ?: return@withTransaction ChangeActionResult.STALE
            if (existing.toDomain() != before) return@withTransaction ChangeActionResult.STALE
            dao.updateAction(after.toEntity(createdAt = existing.createdAtMillis, updatedAt = clock()))
            ChangeActionResult.CHANGED
        }

    /** One stored action, or null. */
    suspend fun action(id: String): Action? = dao.action(id)?.toDomain()

    // ADR 0014: reminders. Nothing here reads or writes a plan, an outcome, an action or an Inbox item.

    suspend fun reminders(): List<Reminder> = dao.reminders().map { it.toDomain() }

    suspend fun reminder(id: String): Reminder? = dao.reminder(id)?.toDomain()

    /**
     * Keeps a new, SCHEDULED reminder. The target must exist when it is made (it may go later without touching the reminder),
     * and no more than [ReminderRules.MAX_PENDING] can be waiting. The time rules need the quiet period, which lives elsewhere,
     * so the caller checks those first.
     */
    suspend fun createReminder(reminder: Reminder): CreateReminderResult =
        database.withTransaction {
            require(reminder.status == ReminderStatus.SCHEDULED) { "a new reminder is scheduled" }
            dao.reminder(reminder.id)?.let {
                return@withTransaction if (it.toDomain() ==
                    reminder
                ) {
                    CreateReminderResult.ALREADY_CREATED
                } else {
                    CreateReminderResult.CONFLICT
                }
            }
            val targetFound =
                when (reminder.targetType) {
                    ReminderTargetType.NONE -> true
                    ReminderTargetType.INBOX_ITEM -> dao.inboxItem(reminder.targetId!!) != null
                    ReminderTargetType.ACTION -> dao.action(reminder.targetId!!) != null
                }
            if (!targetFound) return@withTransaction CreateReminderResult.TARGET_NOT_FOUND
            if (dao.scheduledReminderCount() >= ReminderRules.MAX_PENDING) return@withTransaction CreateReminderResult.TOO_MANY
            dao.insertReminder(reminder.toEntity())
            CreateReminderResult.CREATED
        }

    /**
     * Moves a reminder from [from] to [to], only if it is still in [from], and says whether it moved. A duplicate delivery, a
     * second tap or a stale screen finds the reminder already moved and changes nothing, so a resolved reminder is never revived.
     */
    suspend fun moveReminder(
        id: String,
        from: ReminderStatus,
        to: ReminderStatus,
        at: Long,
        reason: ReminderReason? = null,
    ): Boolean =
        database.withTransaction {
            val current = dao.reminder(id)?.toDomain() ?: return@withTransaction false
            if (current.status != from) return@withTransaction false
            val next = current.transitionTo(to, at, reason)
            dao.moveReminder(id, from.name, next.status.name, next.reason?.name, next.postedAtMillis, next.resolvedAtMillis) == 1
        }

    // ADR 0012: the Inbox.

    /** Every captured item in every state, oldest first. */
    suspend fun inbox(): List<Stamped<InboxItem>> = dao.inboxItems().map { Stamped(it.toDomain(), it.createdAtMillis, it.updatedAtMillis) }

    suspend fun inboxItem(id: String): InboxItem? = dao.inboxItem(id)?.toDomain()

    /**
     * Keeps a new capture. An id that is stored with the same text is [CaptureResult.ALREADY_CAPTURED], so a repeated save
     * makes one item; the same id with other text is a [CaptureResult.CONFLICT] and nothing is overwritten.
     */
    suspend fun captureItem(item: InboxItem): CaptureResult =
        database.withTransaction {
            require(item.state == InboxState.OPEN && item.body == null) { "a capture starts open, with no body" }
            val existing = dao.inboxItem(item.id)
            if (existing != null) {
                return@withTransaction if (existing.text == item.text) CaptureResult.ALREADY_CAPTURED else CaptureResult.CONFLICT
            }
            val now = clock()
            dao.insertInboxItem(item.toEntity(now, now))
            CaptureResult.CAPTURED
        }

    /**
     * Takes back a capture, only while it is still exactly as captured: open, never filed, never edited. Anything else, such as
     * an item since filed, edited or turned into an action, stays, so Undo never removes later work.
     */
    suspend fun undoCapture(
        id: String,
        text: String,
    ): Boolean =
        database.withTransaction {
            val existing = dao.inboxItem(id) ?: return@withTransaction false
            val untouched =
                existing.state == InboxState.OPEN.name &&
                    existing.body == null &&
                    existing.text == text &&
                    existing.updatedAtMillis == existing.createdAtMillis
            if (!untouched) return@withTransaction false
            dao.deleteInboxItem(id)
            true
        }

    /**
     * Files an open item as a note, an idea, an archive or a discard. Only the state changes, and the body of a note or an idea,
     * which starts as the original text unless [body] is given or the item held one before. The original text never changes.
     * Asking for the state the item is already in is [ResolveInboxResult.ALREADY_RESOLVED], unless a different [body] is asked.
     */
    suspend fun resolveItem(
        id: String,
        to: InboxState,
        body: String? = null,
    ): ResolveInboxResult =
        database.withTransaction {
            require(to.canReopen) { "an item is filed as a note, an idea, an archive or a discard" }
            require(body == null || to.hasBody) { "only a note or an idea has a body" }
            val existing = dao.inboxItem(id) ?: return@withTransaction ResolveInboxResult.NOT_FOUND
            if (existing.state == to.name) {
                return@withTransaction if (body == null || body == existing.body) {
                    ResolveInboxResult.ALREADY_RESOLVED
                } else {
                    ResolveInboxResult.NOT_OPEN
                }
            }
            if (existing.state != InboxState.OPEN.name) return@withTransaction ResolveInboxResult.NOT_OPEN
            val now = clock()
            val kept = if (to.hasBody) body ?: existing.body ?: existing.text else existing.body
            val updated = existing.toDomain().copy(state = to, body = kept, resolvedAtMillis = now)
            dao.updateInboxItem(updated.toEntity(existing.createdAtMillis, now))
            ResolveInboxResult.RESOLVED
        }

    /**
     * Puts a filed item back in the Inbox, only if it is still in [expected], so a reopening decided from an earlier read never
     * overwrites a newer change. The body is kept. An item that became an action is never reopened: its action stays behind.
     */
    suspend fun reopenItem(
        id: String,
        expected: InboxState,
    ): ReopenInboxResult =
        database.withTransaction {
            val existing = dao.inboxItem(id) ?: return@withTransaction ReopenInboxResult.NOT_FOUND
            if (existing.state != expected.name) return@withTransaction ReopenInboxResult.STALE
            if (!expected.canReopen) return@withTransaction ReopenInboxResult.NOT_REOPENABLE
            val updated = existing.toDomain().copy(state = InboxState.OPEN, resolvedAtMillis = null)
            dao.updateInboxItem(updated.toEntity(existing.createdAtMillis, clock()))
            ReopenInboxResult.REOPENED
        }

    /** Replaces the body of a note or an idea, only if it still holds [expected]. The original text is not touched. */
    suspend fun editBody(
        id: String,
        expected: String?,
        body: String,
    ): EditBodyResult =
        database.withTransaction {
            val existing = dao.inboxItem(id) ?: return@withTransaction EditBodyResult.NOT_FOUND
            if (!InboxState.valueOf(existing.state).hasBody) return@withTransaction EditBodyResult.NOT_EDITABLE
            if (existing.body != expected) return@withTransaction EditBodyResult.STALE
            val updated = existing.toDomain().copy(body = body)
            dao.updateInboxItem(updated.toEntity(existing.createdAtMillis, clock()))
            EditBodyResult.EDITED
        }

    /**
     * Turns an open item into [action] in one transaction: the action is created and the item is marked as having become it, or
     * neither happens. The same conversion repeated is [ConvertInboxResult.ALREADY_CONVERTED]; a different one for an item that
     * already became an action is a [ConvertInboxResult.CONFLICT], never a silent success. The action is not placed in any plan.
     */
    suspend fun convertToAction(
        itemId: String,
        action: Action,
    ): ConvertInboxResult =
        database.withTransaction {
            val item = dao.inboxItem(itemId) ?: return@withTransaction ConvertInboxResult.ITEM_NOT_FOUND
            if (item.state == InboxState.ACTION.name) {
                val stored = if (item.actionId == action.id) dao.action(action.id)?.toDomain() else null
                // Whether it was finished since does not make a retry a different conversion; its content does.
                val same = stored != null && action.copy(status = stored.status) == stored
                return@withTransaction if (same) ConvertInboxResult.ALREADY_CONVERTED else ConvertInboxResult.CONFLICT
            }
            if (item.state != InboxState.OPEN.name) return@withTransaction ConvertInboxResult.ITEM_NOT_OPEN
            when (createAction(action)) {
                CreateActionResult.CREATED -> Unit
                CreateActionResult.ALREADY_EXISTS -> return@withTransaction ConvertInboxResult.CONFLICT
                CreateActionResult.DUPLICATE_TITLE -> return@withTransaction ConvertInboxResult.DUPLICATE_TITLE
                CreateActionResult.PROJECT_NOT_FOUND -> return@withTransaction ConvertInboxResult.PROJECT_NOT_FOUND
                CreateActionResult.PROJECT_CLOSED -> return@withTransaction ConvertInboxResult.PROJECT_CLOSED
            }
            val now = clock()
            val updated = item.toDomain().copy(state = InboxState.ACTION, actionId = action.id, resolvedAtMillis = now)
            dao.updateInboxItem(updated.toEntity(item.createdAtMillis, now))
            ConvertInboxResult.CONVERTED
        }

    // ADR 0011: project context, commitments, working days and focus.

    suspend fun project(id: String): Project? = dao.project(id)?.toDomain()

    /** Sets one context field only if it still holds [expected], so a change proposed from an earlier read never overwrites a newer one. */
    suspend fun changeProjectContext(
        projectId: String,
        field: ContextField,
        expected: String?,
        text: String?,
    ): ContextChangeResult =
        database.withTransaction {
            val existing = dao.project(projectId) ?: return@withTransaction ContextChangeResult.PROJECT_NOT_FOUND
            val project = existing.toDomain()
            if (project.context(field) != expected) return@withTransaction ContextChangeResult.STALE
            dao.updateProject(project.withContext(field, text).toEntity(existing.createdAtMillis, clock()))
            ContextChangeResult.CHANGED
        }

    /** Every commitment, cancelled ones included, by start. */
    suspend fun commitments(): List<FixedCommitment> = dao.commitments().map { it.toDomain() }

    suspend fun commitment(id: String): FixedCommitment? = dao.commitment(id)?.toDomain()

    /**
     * Creates [commitment] only if it is new: a stored id is [CreateCommitmentResult.ALREADY_EXISTS] and never overwritten, and an
     * active one with the same title and instants is a duplicate. So a retried creation makes one commitment.
     */
    suspend fun createCommitment(commitment: FixedCommitment): CreateCommitmentResult =
        database.withTransaction {
            if (dao.commitment(commitment.id) != null) return@withTransaction CreateCommitmentResult.ALREADY_EXISTS
            val same =
                dao.commitments().any {
                    it.status == CommitmentStatus.ACTIVE.name &&
                        it.title.equals(commitment.title, ignoreCase = true) &&
                        it.startMillis == commitment.start.toEpochMilli() &&
                        it.endMillis == commitment.end.toEpochMilli()
                }
            if (same) return@withTransaction CreateCommitmentResult.DUPLICATE
            val now = clock()
            dao.insertCommitment(commitment.toEntity(now, now))
            CreateCommitmentResult.CREATED
        }

    /** Replaces a stored commitment by hand, cancelling included. False if it is not stored. */
    suspend fun updateCommitment(commitment: FixedCommitment): Boolean =
        database.withTransaction {
            val existing = dao.commitment(commitment.id) ?: return@withTransaction false
            dao.updateCommitment(commitment.toEntity(existing.createdAtMillis, clock()))
            true
        }

    /** Active commitments overlapping [day], from its rollover to the next, by start then id. */
    suspend fun commitmentsOf(
        day: ActiveDay,
        zone: ZoneId,
        rollover: LocalTime,
    ): List<FixedCommitment> {
        val from =
            day
                .localAt(rollover, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        val to =
            day
                .plusDays(1)
                .localAt(rollover, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        return dao.commitmentsOverlapping(from, to).map { it.toDomain() }
    }

    suspend fun workingDays(): List<WorkingDayRecord> = dao.workingDays().map { it.toDomain() }

    suspend fun workingDay(day: ActiveDay): WorkingDayRecord? = dao.workingDay(day.toString())?.toDomain()

    /** Opens at [now] the day [WorkingDays.labelForOpening] names, or reopens it. Refused while another day is open. */
    suspend fun openWorkingDay(
        now: Instant,
        zone: ZoneId,
    ): WorkingDayChange =
        database.withTransaction {
            val rows = workingDays()
            rows.singleOrNull { it.isOpen }?.let { return@withTransaction WorkingDayChange.AnotherOpen(it.day) }
            val label = WorkingDays.labelForOpening(now, zone, settings().rollover, rows)
            val existing = rows.singleOrNull { it.day == label }
            val opened =
                if (existing == null) {
                    WorkingDayRecord(label, openedAt = now)
                } else {
                    existing.copy(openedAt = existing.openedAt ?: now, closedAt = null)
                }
            dao.saveWorkingDay(opened.toEntity())
            WorkingDayChange.Opened(label, reopened = existing?.closedAt != null)
        }

    /** Closes [day] at [now], with an optional note. It needs no outcomes. */
    suspend fun closeWorkingDay(
        day: ActiveDay,
        now: Instant,
        note: String?,
    ): WorkingDayChange =
        database.withTransaction {
            val row = workingDay(day)
            if (row == null || !row.isOpen) return@withTransaction WorkingDayChange.NotApplicable
            dao.saveWorkingDay(row.copy(closedAt = now, closingNote = note ?: row.closingNote).toEntity())
            WorkingDayChange.Closed(day)
        }

    /** Start, from Choose: closes the earlier open [open] at [now], then opens. Its unrecorded outcomes stay unknown. */
    suspend fun startWorkingDay(
        open: ActiveDay,
        now: Instant,
        zone: ZoneId,
    ): WorkingDayChange =
        database.withTransaction {
            val row = workingDay(open)
            if (row == null || !row.isOpen) return@withTransaction WorkingDayChange.NotApplicable
            dao.saveWorkingDay(row.copy(closedAt = now).toEntity())
            openWorkingDay(now, zone)
        }

    /** Continue, from Choose: [open] stays current through [clockDay]. */
    suspend fun continueWorkingDay(
        open: ActiveDay,
        clockDay: ActiveDay,
    ): WorkingDayChange =
        database.withTransaction {
            val row = workingDay(open)
            if (row == null || !row.isOpen) return@withTransaction WorkingDayChange.NotApplicable
            dao.saveWorkingDay(row.copy(continuedOn = clockDay).toEntity())
            WorkingDayChange.Continued(open)
        }

    /** Sets or clears [day]'s focus capacity and returns what it was, for Undo. It changes no approved plan. */
    suspend fun setFocus(
        day: ActiveDay,
        minutes: Int?,
    ): Int? =
        database.withTransaction {
            val row = workingDay(day)
            dao.saveWorkingDay((row ?: WorkingDayRecord(day)).copy(focusMinutes = minutes).toEntity())
            row?.focusMinutes
        }

    /**
     * The user's explicit confirmation that this plan's opening call may be created: NOT_LINKED becomes REQUESTED.
     * Only a screen acting on the user's tick and press may call it. It is separate from approval, so a plan
     * approved with a time, and a plan restored from an export, stay NOT_LINKED until then. It writes nothing
     * to M2. [today] is the active day now: a past day or an earlier revision cannot be requested.
     */
    suspend fun authorizeOpeningCall(
        planId: String,
        today: ActiveDay,
    ): CallAuthorization =
        database.withTransaction {
            val plan = dao.plan(planId)?.toDomain()
            val call = dao.call(planId)
            when {
                plan == null || call == null -> CallAuthorization.NO_OPENING_TIME
                plan.activeDay < today || plan.revision != dao.latestRevision(plan.activeDay.toString()) ->
                    CallAuthorization.NOT_ELIGIBLE
                call.status != PlanCallEntity.NOT_LINKED -> CallAuthorization.NOT_UNLINKED
                else -> {
                    check(dao.requestCall(planId, clock()) == 1) { "the call changed during its own transaction" }
                    CallAuthorization.AUTHORIZED
                }
            }
        }

    /** Every recorded outcome, by item id. */
    suspend fun outcomes(): List<PlanOutcome> = dao.outcomes().map { it.toDomain() }

    /** Every plan's opening call row. A plan with none has no opening call. */
    suspend fun calls(): List<PlanCall> = dao.calls().map { it.toDomain() }

    /** One plan with its call row, read in one transaction, or null if the plan has no opening call. */
    internal suspend fun callTarget(planId: String): CallTarget? = database.withTransaction { target(dao.call(planId)) }

    /** The calls the launch retry may act on: those whose status is REQUESTED. Nothing else is ever returned. */
    internal suspend fun requestedCalls(): List<CallTarget> =
        database.withTransaction { dao.callsWithStatus(PlanCallEntity.REQUESTED).mapNotNull { target(it) } }

    /** REFUSED and NEEDS_ATTENTION calls: those whose displayed status may be reconciled from M2. Never REQUESTED or NOT_LINKED. */
    internal suspend fun unsettledCalls(): List<CallTarget> =
        database.withTransaction {
            (dao.callsWithStatus(CALL_REFUSED) + dao.callsWithStatus(CALL_NEEDS_ATTENTION)).mapNotNull { target(it) }
        }

    private suspend fun target(call: PlanCallEntity?): CallTarget? {
        val plan = call?.let { dao.plan(it.planId) }?.toDomain() ?: return null
        return CallTarget(plan, call.toDomain(), plan.revision == dao.latestRevision(plan.activeDay.toString()))
    }

    /**
     * Runs [block] inside one planning transaction, so no other planning write can commit between the state it reads
     * and what it does. The call link holds it across its read of the authorizing state, its write to M2 and the
     * recorded result. It is a fence on planning writers, not an atomic commit across `planning.db` and `checkins.db`:
     * a crash after M2 changes and before this commits leaves M2 changed and the call REQUESTED, which the launch retry
     * then finds, as ADR 0009 decision 6 describes.
     */
    internal suspend fun <T> fenced(block: suspend () -> T): T = database.withTransaction { block() }

    /**
     * Records what the call link did, but only if the call is still in one of the [from] statuses, so a
     * result computed from a stale read can never overwrite a newer one. Returns whether it was recorded.
     */
    internal suspend fun recordCall(
        planId: String,
        from: Set<String>,
        status: String,
        detail: String?,
    ): Boolean = dao.moveCall(planId, from.toList(), status, detail, clock()) == 1

    /**
     * Records [result] for [itemId], or corrects its outcome, together with its effect on the action's status,
     * in one transaction. A refusal writes nothing, and a failure anywhere rolls back both rows. [today] is the
     * active day now. Eligibility and the status rules are enforced here, not only by a screen. ADR 0009 slice 3.
     */
    suspend fun recordOutcome(
        itemId: String,
        result: OutcomeResult,
        today: ActiveDay,
    ): OutcomeRecording =
        database.withTransaction {
            val itemRow = dao.planItem(itemId) ?: return@withTransaction OutcomeRecording.Refused(OutcomeRefusal.ITEM_NOT_FOUND)
            val plan = checkNotNull(dao.plan(itemRow.planId)) { "a plan item without its plan" }.toDomain()
            val latest = dao.latestRevision(plan.activeDay.toString()) ?: 0
            if (!canRecordOutcome(itemRow.toDomain(), plan, latest, today)) {
                return@withTransaction OutcomeRecording.Refused(OutcomeRefusal.NOT_ELIGIBLE)
            }
            val action = checkNotNull(dao.action(itemRow.actionId)) { "a plan item without its action" }
            val others = dao.outcomesOfAction(action.id).filter { it.planItemId != itemId }.map { it.toDomain() }
            val now = clock()
            val effect = applyOutcome(itemId, result, now, ActionStatus.valueOf(action.status), dao.outcome(itemId)?.toDomain(), others)
            dao.saveOutcome(effect.outcome.toEntity())
            if (effect.status.name != action.status) dao.updateAction(action.copy(status = effect.status.name, updatedAtMillis = now))
            // The same check import runs. A violation here is a bug, and throwing rolls the whole write back.
            val violation =
                outcomeInvariantViolation(
                    dao.outcomesOfAction(action.id).map { it.toDomain() },
                    dao.itemsOfAction(action.id).map { it.toDomain() },
                    mapOf(action.id to effect.status),
                )
            check(violation == null) { "outcome invariant broken: $violation" }
            OutcomeRecording.Recorded(effect)
        }

    /**
     * The revision in force for [day], its highest, or null. Found by query: nothing marks an earlier
     * revision, which stays exactly as it was written.
     */
    suspend fun planFor(day: ActiveDay): DayPlanWithItems? =
        database.withTransaction { dao.latestPlan(day.toString())?.let { withItems(it) } }

    /** Each day's latest revision: the starting point for the Unfinished list. */
    suspend fun latestPlans(): List<DayPlanWithItems> = database.withTransaction { dao.latestPlans().map { withItems(it) } }

    /** Every revision of [day], oldest first. */
    suspend fun revisionsOf(day: ActiveDay): List<DayPlanWithItems> =
        database.withTransaction { dao.plansOfDay(day.toString()).map { withItems(it) } }

    /**
     * The proposal for [day], from the stored actions and projects behind [selected]. A plain read in one
     * transaction; nothing is kept, so leaving a proposal writes nothing. [windowOverride] is this day's
     * own window; without one the saved default is used and the proposal says so. Null when there is no
     * window at all, which means planning setup has not been done.
     */
    suspend fun propose(
        day: ActiveDay,
        zone: ZoneId,
        windowOverride: PlanningWindow?,
        selected: Set<String>,
        pins: Map<String, LocalTime>,
    ): PlanProposal? =
        database.withTransaction {
            val settings = settings()
            val window = windowOverride ?: settings.window ?: return@withTransaction null
            val data = readData(selected, day)
            Planner.propose(
                PlanInputs(
                    day,
                    zone,
                    settings.rollover,
                    window,
                    windowOverride == null,
                    selected,
                    pins,
                    data.latestRevision,
                    commitmentsOf(day, zone, settings.rollover),
                    workingDay(day)?.focusMinutes,
                ),
                data.actions,
                data.projects,
            )
        }

    /**
     * Approves [shown], or refuses and writes nothing. ADR 0009 decision 6 and the slice 2 clarifications.
     *
     * Approval does three different things, in this order, and the second is not the first:
     *
     * 1. **Independent validation.** [PlanValidator] checks the proposal against the rules on its own
     *    contents, without calling the planner: unique, open and eligible selected actions, pins inside the
     *    window and not overlapping, the capacity figures, the queue and every placement and exclusion, the
     *    project limit and the overload. A proposal that breaks them is [ApprovalRefusal.MALFORMED].
     * 2. **Staleness.** Inside one transaction it reads the **current** actions, projects, rollover, default
     *    window and latest revision, builds the proposal again from the choices in [shown], and requires it
     *    to equal [shown] exactly, snapshots included. Any difference, even a renamed action whose schedule is
     *    unchanged, is [ApprovalRefusal.CHANGED]. That is a comparison, not a validation.
     * 3. **Acknowledgements**, then the write: the next revision and its items, and nothing is updated.
     *    A failure anywhere rolls it all back.
     *
     * [currentZone] is read by the caller from M2 just before. M2's `checkins.db` and `planning.db` are two
     * databases, so that read and this transaction are **not atomic**: M2's zone could change between them.
     * The plan records the zone it was made in, and the call link of slice 3 re-reads M2 itself. Concurrent
     * approvals serialise on the transaction: the second sees the first's revision and is refused.
     */
    suspend fun approve(
        shown: PlanProposal,
        currentZone: ZoneId,
        acknowledgeUnresolved: Boolean = false,
        acknowledgeRestDay: Boolean = false,
        openingTime: LocalTime? = null,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): ApprovalResult =
        database.withTransaction {
            if (PlanValidator.validate(shown).isNotEmpty()) return@withTransaction ApprovalResult.Refused(ApprovalRefusal.MALFORMED)
            // U1: no new revision while the day's current revision has an effective recorded outcome.
            dao.latestPlan(shown.inputs.day.toString())?.let { current ->
                if (adjustmentLocked(dao.outcomesOfPlan(current.id).map { it.toDomain() })) {
                    return@withTransaction ApprovalResult.Refused(ApprovalRefusal.OUTCOMES_RECORDED)
                }
            }
            val inputs = shown.inputs
            val settings = settings()
            val window = if (inputs.windowIsDefault) settings.window else inputs.window
            if (window == null) return@withTransaction ApprovalResult.Refused(ApprovalRefusal.CHANGED)
            val data = readData(inputs.selected, inputs.day)
            val fresh =
                Planner.propose(
                    inputs.copy(
                        zone = currentZone,
                        rollover = settings.rollover,
                        window = window,
                        basedOnRevision = data.latestRevision,
                        commitments = commitmentsOf(inputs.day, currentZone, settings.rollover),
                        focusMinutes = workingDay(inputs.day)?.focusMinutes,
                    ),
                    data.actions,
                    data.projects,
                )
            when {
                fresh != shown -> ApprovalResult.Refused(ApprovalRefusal.CHANGED)
                !fresh.canApprove -> ApprovalResult.Refused(ApprovalRefusal.INVALID)
                fresh.needsUnresolvedAcknowledgement && !acknowledgeUnresolved ->
                    ApprovalResult.Refused(ApprovalRefusal.UNRESOLVED_NOT_ACKNOWLEDGED)
                fresh.isRestDay && !acknowledgeRestDay -> ApprovalResult.Refused(ApprovalRefusal.REST_DAY_NOT_ACKNOWLEDGED)
                else -> {
                    val rows = fresh.toApprovedRows(newId(), data.latestRevision + 1, clock(), newId)
                    dao.insertPlan(rows.plan.toEntity())
                    dao.insertPlanItems(rows.items.map { it.toEntity() })
                    // An opening time is only kept: NOT_LINKED, with no executable request. Approving a plan never
                    // asks for a call; [authorizeOpeningCall] does, separately. No time, no row.
                    if (openingTime != null) {
                        dao.insertCall(PlanCallEntity(rows.plan.id, openingTime.toString(), PlanCallEntity.NOT_LINKED, null, clock()))
                    }
                    ApprovalResult.Approved(rows)
                }
            }
        }

    /**
     * Undoes the approval of [expectedPlanId] by writing the next revision as an exact copy of the revision before it.
     * Only a direct tap calls this; no conversation path does. Nothing is deleted or updated: the undone revision, every
     * plan row and every outcome stay, and a recorded outcome refuses the Undo, like a new approval. Stale when
     * [expectedPlanId] is not the day's latest revision, which also refuses a repeated tap. A copied opening time is kept
     * as NOT_LINKED, like an approval: no call is requested, armed or moved.
     */
    suspend fun undoApproval(
        expectedPlanId: String,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): UndoResult =
        database.withTransaction {
            val expected = dao.plan(expectedPlanId) ?: return@withTransaction UndoResult.Refused(UndoRefusal.STALE)
            val latest = dao.latestPlan(expected.activeDay)
            if (latest == null || latest.id != expected.id) return@withTransaction UndoResult.Refused(UndoRefusal.STALE)
            val earlier =
                dao
                    .plansOfDay(expected.activeDay)
                    .lastOrNull { it.revision < expected.revision }
                    ?: return@withTransaction UndoResult.Refused(UndoRefusal.NO_PREVIOUS)
            if (adjustmentLocked(dao.outcomesOfPlan(expected.id).map { it.toDomain() })) {
                return@withTransaction UndoResult.Refused(UndoRefusal.OUTCOMES_RECORDED)
            }
            val rows = restoredRows(withItems(earlier), newId(), expected.revision + 1, clock(), newId)
            dao.insertPlan(rows.plan.toEntity())
            dao.insertPlanItems(rows.items.map { it.toEntity() })
            dao.call(earlier.id)?.let { call ->
                dao.insertCall(PlanCallEntity(rows.plan.id, call.openingTime, PlanCallEntity.NOT_LINKED, null, clock()))
            }
            UndoResult.Restored(rows)
        }

    private suspend fun readData(
        selected: Set<String>,
        day: ActiveDay,
    ): PlanningData {
        val actions = selected.sorted().mapNotNull { id -> dao.action(id)?.let { PlannedAction(it.toDomain(), it.createdAtMillis) } }
        val projects =
            actions
                .mapNotNull { it.action.projectId }
                .distinct()
                .sorted()
                .mapNotNull { id -> dao.project(id)?.let { ProjectRef(it.id, it.title, ProjectState.valueOf(it.state)) } }
        return PlanningData(actions, projects, dao.latestRevision(day.toString()) ?: 0)
    }

    private suspend fun withItems(plan: DayPlanEntity) = DayPlanWithItems(plan.toDomain(), dao.itemsOf(plan.id).map { it.toDomain() })

    // Privacy controls: permanent deletion the person asks for with a tap. No conversation path calls any of these.

    /** Removes the direction statement. Returns whether there was one. Nothing else is touched. */
    suspend fun clearDirection(): Boolean = dao.deleteDirection() > 0

    /**
     * Deletes a goal only if no project is linked to it. A linked goal is [GoalDeletion.Blocked] with the projects' titles and
     * nothing is changed: no project is unlinked, moved or deleted for the person.
     */
    suspend fun deleteGoal(id: String): GoalDeletion =
        database.withTransaction {
            if (dao.goal(id) == null) return@withTransaction GoalDeletion.NotFound
            val linked = dao.projects().filter { it.goalId == id }.map { it.title }
            if (linked.isNotEmpty()) return@withTransaction GoalDeletion.Blocked(linked)
            dao.deleteGoal(id)
            GoalDeletion.Deleted
        }

    /**
     * Deletes a filed note, idea, archive or discard. An open item (use Discard) and an item that became an action (the action
     * stays and is not touched, so the capture is kept as its origin) are refused. Reminders that point at the item are left
     * as they are: a reminder owns its text and already copes with a missing target.
     */
    suspend fun deleteFiledItem(id: String): InboxDeletion =
        database.withTransaction {
            val item = dao.inboxItem(id) ?: return@withTransaction InboxDeletion.NotFound
            when {
                item.state == InboxState.OPEN.name -> InboxDeletion.StillOpen
                item.state == InboxState.ACTION.name -> InboxDeletion.BecameAction
                else -> {
                    val pointing = dao.reminders().count { it.targetType == ReminderTargetType.INBOX_ITEM.name && it.targetId == id }
                    dao.deleteInboxItem(id)
                    InboxDeletion.Deleted(pointing)
                }
            }
        }

    /** Every closing note, newest day first. */
    suspend fun closingNotes(): List<Pair<ActiveDay, String>> =
        dao
            .workingDays()
            .mapNotNull { row -> row.closingNote?.let { ActiveDay(java.time.LocalDate.parse(row.day)) to it } }
            .sortedByDescending { it.first.date }

    /** Replaces [day]'s closing note, only if it still holds [expected]. The day, its times and its focus figure are kept. */
    suspend fun editClosingNote(
        day: ActiveDay,
        expected: String,
        note: String,
    ): NoteChange =
        database.withTransaction {
            val row = dao.workingDay(day.toString()) ?: return@withTransaction NoteChange.NOT_FOUND
            if (row.closingNote == null) return@withTransaction NoteChange.NOT_FOUND
            if (row.closingNote != expected) return@withTransaction NoteChange.STALE
            dao.saveWorkingDay(row.toDomain().copy(closingNote = note).toEntity())
            NoteChange.DONE
        }

    /** Deletes only the note: the working day row, its times, focus and continuation stay. Refused if the note changed. */
    suspend fun deleteClosingNote(
        day: ActiveDay,
        expected: String,
    ): NoteChange =
        database.withTransaction {
            val row = dao.workingDay(day.toString()) ?: return@withTransaction NoteChange.NOT_FOUND
            if (row.closingNote == null) return@withTransaction NoteChange.NOT_FOUND
            if (row.closingNote != expected) return@withTransaction NoteChange.STALE
            dao.saveWorkingDay(row.copy(closingNote = null))
            NoteChange.DONE
        }

    suspend fun settings(): PlanningSettings = dao.settings()?.toDomain() ?: PlanningSettings()

    suspend fun saveSettings(settings: PlanningSettings) = dao.saveSettings(settings.toEntity())

    /**
     * Every planning row, read in one transaction so an export is one consistent moment even while
     * the user saves. ADR 0009 decision 8.
     */
    suspend fun snapshot(): PlanningSnapshot =
        database.withTransaction {
            PlanningSnapshot(
                direction = dao.direction()?.let { DirectionRecord(Direction(it.text), it.updatedAtMillis) },
                settings = dao.settings()?.toDomain(),
                goals = dao.goals().map { Stamped(it.toDomain(), it.createdAtMillis, it.updatedAtMillis) },
                projects = dao.projects().map { Stamped(it.toDomain(), it.createdAtMillis, it.updatedAtMillis) },
                actions = dao.actions().map { Stamped(it.toDomain(), it.createdAtMillis, it.updatedAtMillis) },
                plans = dao.plans().map { it.toDomain() },
                planItems = dao.planItems().map { it.toDomain() },
                outcomes = dao.outcomes().map { it.toDomain() },
                openingTimes = dao.calls().associate { it.planId to LocalTime.parse(it.openingTime) },
                commitments = dao.commitments().map { Stamped(it.toDomain(), it.createdAtMillis, it.updatedAtMillis) },
                workingDays = dao.workingDays().map { it.toDomain() },
                inboxItems = dao.inboxItems().map { Stamped(it.toDomain(), it.createdAtMillis, it.updatedAtMillis) },
                reminders = dao.reminders().map { it.toDomain() },
            )
        }

    /**
     * Writes every row of [snapshot], with its id and times, in one transaction, but only if the
     * database holds no planning row at all, checked inside the same transaction. Returns whether it
     * wrote. Import never merges. The snapshot was validated when it was decoded.
     */
    suspend fun restoreIntoEmpty(snapshot: PlanningSnapshot): Boolean =
        database.withTransaction {
            if (dao.rowCount() != 0) return@withTransaction false
            val violation =
                outcomeInvariantViolation(
                    snapshot.outcomes,
                    snapshot.planItems,
                    snapshot.actions.associate { it.value.id to it.value.status },
                )
            check(violation == null) { "the snapshot breaks an outcome invariant: $violation" }
            snapshot.direction?.let { dao.saveDirection(DirectionEntity(text = it.direction.text, updatedAtMillis = it.updatedAtMillis)) }
            snapshot.settings?.let { dao.saveSettings(it.toEntity()) }
            dao.insertGoals(snapshot.goals.map { it.value.toEntity(it.createdAtMillis, it.updatedAtMillis) })
            dao.insertProjects(snapshot.projects.map { it.value.toEntity(it.createdAtMillis, it.updatedAtMillis) })
            dao.insertActions(snapshot.actions.map { it.value.toEntity(it.createdAtMillis, it.updatedAtMillis) })
            dao.insertPlans(snapshot.plans.map { it.toEntity() })
            dao.insertPlanItems(snapshot.planItems.map { it.toEntity() })
            dao.insertOutcomes(snapshot.outcomes.map { it.toEntity() })
            // Import never authorizes a call: a plan with an opening time is NOT_LINKED, never REQUESTED.
            val now = clock()
            dao.insertCalls(
                snapshot.openingTimes.map { (planId, time) ->
                    PlanCallEntity(planId, time.toString(), PlanCallEntity.NOT_LINKED, null, now)
                },
            )
            dao.insertCommitments(snapshot.commitments.map { it.value.toEntity(it.createdAtMillis, it.updatedAtMillis) })
            dao.insertWorkingDays(snapshot.workingDays.map { it.toEntity() })
            dao.insertInboxItems(snapshot.inboxItems.map { it.value.toEntity(it.createdAtMillis, it.updatedAtMillis) })
            dao.insertReminders(snapshot.reminders.map { it.toEntity() })
            true
        }
}

/** The stored actions and projects behind a selection, and the day's latest revision (0 if none). */
internal data class PlanningData(
    val actions: List<PlannedAction>,
    val projects: List<ProjectRef>,
    val latestRevision: Int,
)

private fun DayPlan.toEntity() =
    DayPlanEntity(
        id,
        activeDay.toString(),
        revision,
        zone.id,
        rollover.toString(),
        window.start.toString(),
        window.end.toString(),
        freeMinutes,
        targetMinutes,
        overloadMinutes,
        approvedAtMillis,
    )

private fun DayPlanEntity.toDomain() =
    DayPlan(
        id = id,
        activeDay = ActiveDay(LocalDate.parse(activeDay)),
        revision = revision,
        zone = ZoneId.of(zone),
        rollover = LocalTime.parse(rollover),
        window = PlanningWindow(LocalTime.parse(windowStart), LocalTime.parse(windowEnd)),
        freeMinutes = freeMinutes,
        targetMinutes = targetMinutes,
        overloadMinutes = overloadMinutes,
        approvedAtMillis = approvedAtMillis,
    )

private fun PlanCallEntity.toDomain() = PlanCall(planId, LocalTime.parse(openingTime), status, detail, updatedAtMillis)

private fun OutcomeEntity.toDomain() = PlanOutcome(planItemId, OutcomeResult.valueOf(result), recordedAtMillis, closedAction)

private fun PlanOutcome.toEntity() = OutcomeEntity(planItemId, result.name, recordedAtMillis, closedAction)

private fun PlanItem.toEntity() =
    PlanItemEntity(
        id,
        planId,
        actionId,
        kind.name,
        start?.toString(),
        minutes,
        position,
        placement?.name,
        reason?.name,
        title,
        priority.name,
        projectId,
        projectTitle,
        projectState?.name,
    )

private fun PlanItemEntity.toDomain() =
    PlanItem(
        id = id,
        planId = planId,
        actionId = actionId,
        kind = ItemKind.valueOf(kind),
        start = start?.let(LocalTime::parse),
        minutes = minutes,
        position = position,
        placement = placement?.let { Placement.valueOf(it) },
        reason = reason?.let { LeftOutReason.valueOf(it) },
        title = title,
        priority = Priority.valueOf(priority),
        projectId = projectId,
        projectTitle = projectTitle,
        projectState = projectState?.let { ProjectState.valueOf(it) },
    )

private fun PlanningSettings.toEntity() =
    SettingsEntity(
        rollover = rollover.toString(),
        windowStart = window?.start?.toString(),
        windowEnd = window?.end?.toString(),
    )

private fun GoalEntity.toDomain() =
    Goal(
        id = id,
        title = title,
        successCondition = successCondition,
        horizon = horizon?.let(LocalDate::parse),
        status = GoalStatus.valueOf(status),
    )

private fun Goal.toEntity(
    createdAt: Long,
    updatedAt: Long,
) = GoalEntity(id, title, successCondition, horizon?.toString(), status.name, createdAt, updatedAt)

private fun ProjectEntity.toDomain() =
    Project(
        id = id,
        title = title,
        goalId = goalId,
        state = ProjectState.valueOf(state),
        purpose = purpose,
        doneWhen = doneWhen,
        constraints = constraints,
        blocker = blocker,
    )

private fun Project.toEntity(
    createdAt: Long,
    updatedAt: Long,
) = ProjectEntity(id, title, goalId, state.name, createdAt, updatedAt, purpose, doneWhen, constraints, blocker)

private fun CommitmentEntity.toDomain() =
    FixedCommitment(
        id,
        title,
        Instant.ofEpochMilli(startMillis),
        Instant.ofEpochMilli(endMillis),
        ZoneId.of(zone),
        CommitmentStatus.valueOf(status),
    )

private fun FixedCommitment.toEntity(
    createdAt: Long,
    updatedAt: Long,
) = CommitmentEntity(id, title, start.toEpochMilli(), end.toEpochMilli(), zone.id, status.name, createdAt, updatedAt)

private fun WorkingDayEntity.toDomain() =
    WorkingDayRecord(
        day = ActiveDay(LocalDate.parse(day)),
        openedAt = openedAtMillis?.let(Instant::ofEpochMilli),
        closedAt = closedAtMillis?.let(Instant::ofEpochMilli),
        focusMinutes = focusMinutes,
        closingNote = closingNote,
        continuedOn = continuedOn?.let { ActiveDay(LocalDate.parse(it)) },
    )

private fun WorkingDayRecord.toEntity() =
    WorkingDayEntity(day.toString(), openedAt?.toEpochMilli(), closedAt?.toEpochMilli(), focusMinutes, closingNote, continuedOn?.toString())

private fun ActionEntity.toDomain() =
    Action(
        id = id,
        title = title,
        projectId = projectId,
        priority = Priority.valueOf(priority),
        estimateMinutes = estimateMinutes,
        dueDate = dueDate?.let(LocalDate::parse),
        status = ActionStatus.valueOf(status),
    )

private fun Action.toEntity(
    createdAt: Long,
    updatedAt: Long,
) = ActionEntity(id, title, projectId, priority.name, estimateMinutes, dueDate?.toString(), status.name, createdAt, updatedAt)

private fun SettingsEntity.toDomain(): PlanningSettings {
    val start = windowStart
    val end = windowEnd
    return PlanningSettings(
        rollover = LocalTime.parse(rollover),
        window = if (start != null && end != null) PlanningWindow(LocalTime.parse(start), LocalTime.parse(end)) else null,
    )
}

private fun InboxItemEntity.toDomain() = InboxItem(id, text, InboxState.valueOf(state), body, actionId, resolvedAtMillis)

private fun InboxItem.toEntity(
    createdAt: Long,
    updatedAt: Long,
) = InboxItemEntity(id, text, state.name, body, actionId, resolvedAtMillis, createdAt, updatedAt)

private fun ReminderEntity.toDomain() =
    Reminder(
        id = id,
        targetType = ReminderTargetType.valueOf(targetType),
        targetId = targetId,
        label = label,
        dueAtMillis = dueAtMillis,
        zone = zone,
        status = ReminderStatus.valueOf(status),
        reason = reason?.let { ReminderReason.valueOf(it) },
        postedAtMillis = postedAtMillis,
        resolvedAtMillis = resolvedAtMillis,
        createdAtMillis = createdAtMillis,
    )

private fun Reminder.toEntity() =
    ReminderEntity(
        id,
        targetType.name,
        targetId,
        label,
        dueAtMillis,
        zone,
        status.name,
        reason?.name,
        postedAtMillis,
        resolvedAtMillis,
        createdAtMillis,
    )
