package com.thalia.app.conversation

import com.thalia.app.planning.CheckInZone
import com.thalia.app.planning.CheckInZoneSource
import com.thalia.app.planning.PlanningAccess
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.ChangeActionResult
import com.thalia.app.planning.data.ContextChangeResult
import com.thalia.app.planning.data.ConvertInboxResult
import com.thalia.app.planning.data.CreateActionResult
import com.thalia.app.planning.data.CreateCommitmentResult
import com.thalia.app.planning.data.OutcomeRecording
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.ReopenInboxResult
import com.thalia.app.planning.data.ResolveInboxResult
import com.thalia.app.reminders.CreateOutcome
import com.thalia.app.reminders.ReminderService
import com.thalia.conversation.ContextLimits
import com.thalia.conversation.ConversationContext
import com.thalia.conversation.DayLoopFacts
import com.thalia.conversation.Proposal
import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.ContextField
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.FixedCommitment
import com.thalia.planning.InboxItem
import com.thalia.planning.InboxState
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanProposal
import com.thalia.planning.ReEntries
import com.thalia.planning.Reminder
import com.thalia.planning.ReminderTargetType
import com.thalia.planning.WorkingDayState
import com.thalia.planning.WorkingDays
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Runs a block against the open planning store, serialised with every other planning access. */
internal interface StoreRunner {
    suspend fun <T> run(block: suspend (PlanningStore) -> T): T
}

internal class AccessStoreRunner(
    private val access: PlanningAccess,
) : StoreRunner {
    override suspend fun <T> run(block: suspend (PlanningStore) -> T): T = access.withStore(block)
}

/** The data a request is built from, and the zone the day was resolved in. */
internal data class ConversationSnapshot(
    val context: ConversationContext,
    val zone: ZoneId,
)

/**
 * Everything the conversation reads or writes, and nothing else. Each write is one of the planning store's existing
 * transactional operations, which enforce their own rules again at the moment of writing.
 */
internal interface ConversationPort {
    /**
     * The snapshot for today, or null when planning cannot be used (no usable check-in zone, or no data). Inbox captures are
     * private: [inboxIds] null leaves them out entirely, and a set shares only those of its items that are still open (at most
     * [com.thalia.conversation.ContextLimits.TRIAGE], oldest first). Slice 4A.
     */
    suspend fun snapshot(inboxIds: Set<String>? = null): ConversationSnapshot?

    suspend fun recordOutcome(
        itemId: String,
        result: OutcomeResult,
        today: ActiveDay,
    ): OutcomeRecording

    suspend fun currentOutcome(itemId: String): OutcomeResult?

    suspend fun createAction(action: Action): CreateActionResult

    suspend fun changeAction(
        before: Action,
        after: Action,
    ): ChangeActionResult

    suspend fun action(id: String): Action?

    /**
     * The deterministic proposal for [day] with exactly [selected] and [pins], in the day's current window and the check-in
     * zone read now. Null without a window or a usable zone.
     */
    suspend fun previewDay(
        day: ActiveDay,
        selected: Set<String>,
        pins: Map<String, LocalTime>,
    ): PlanProposal?

    /** Approves [shown] through the store's own validation and staleness check, with the zone read now. Null without a usable zone. */
    suspend fun approveDay(
        shown: PlanProposal,
        acknowledgeUnresolved: Boolean,
    ): ApprovalResult?

    suspend fun planFor(day: ActiveDay): DayPlanWithItems?

    suspend fun changeProjectContext(
        projectId: String,
        field: ContextField,
        expected: String?,
        text: String?,
    ): ContextChangeResult

    suspend fun createCommitment(commitment: FixedCommitment): CreateCommitmentResult

    suspend fun commitment(id: String): FixedCommitment?

    /** Sets [day]'s focus capacity and returns what it was. */
    suspend fun setFocus(
        day: ActiveDay,
        minutes: Int?,
    ): Int?

    // ADR 0012: the Inbox.

    suspend fun inboxItem(id: String): InboxItem?

    suspend fun resolveInbox(
        id: String,
        to: InboxState,
        body: String?,
    ): ResolveInboxResult

    suspend fun reopenInbox(
        id: String,
        expected: InboxState,
    ): ReopenInboxResult

    /** Creates [action] and marks the item as having become it, in one transaction. */
    suspend fun convertInbox(
        itemId: String,
        action: Action,
    ): ConvertInboxResult

    // Slice 5: one-time reminders, through the existing reminder service and its own rules.

    /** False where this build has no reminder service, so a reminder is never proposed that could not be set. */
    val canSetReminders: Boolean

    /** Sets the reminder through [ReminderService.create], which applies the quiet period, the lead and horizon, the limit and the notification check again. */
    suspend fun createReminder(proposal: Proposal.Reminder): CreateOutcome

    suspend fun reminder(id: String): Reminder?

    /** Cancels the reminder [id] through [ReminderService.cancel]: its alarm first, then the row, only while it is still waiting. False if it was not. */
    suspend fun cancelReminder(id: String): Boolean
}

internal class StoreConversationPort(
    private val runner: StoreRunner,
    private val zoneSource: CheckInZoneSource,
    private val now: () -> Instant = Instant::now,
    private val reminders: ReminderService? = null,
) : ConversationPort {
    override val canSetReminders: Boolean get() = reminders != null

    override suspend fun createReminder(proposal: Proposal.Reminder): CreateOutcome {
        val service = checkNotNull(reminders) { "no reminder service in this build" }
        val type = if (proposal.actionId == null) ReminderTargetType.NONE else ReminderTargetType.ACTION
        return runner.run { service.create(it, type, proposal.actionId, proposal.actionTitle.orEmpty(), proposal.dueAtMillis, proposal.id) }
    }

    override suspend fun reminder(id: String) = runner.run { it.reminder(id) }

    override suspend fun cancelReminder(id: String): Boolean {
        val service = checkNotNull(reminders) { "no reminder service in this build" }
        return runner.run { service.cancel(it, id) }
    }

    override suspend fun snapshot(inboxIds: Set<String>?): ConversationSnapshot? {
        val zone = (zoneSource.read() as? CheckInZone.Known)?.zone ?: return null
        return runner.run { store ->
            val settings = store.settings()
            val at = now()
            val rows = store.workingDays()
            // ADR 0011: the current working day, or the clock's day when none is open or an earlier one awaits a choice.
            val state = WorkingDays.resolve(at, zone, settings.rollover, rows)
            val day = (state as? WorkingDayState.Current)?.day ?: ActiveDay.containing(at, zone, settings.rollover)
            val plan = store.planFor(day)
            // The active day after this one, by the working-day rules (never the calendar date plus one), and its current plan.
            val nextDay = WorkingDays.nextActiveDay(state, day)
            val nextPlan = nextDay?.let { store.planFor(it) }
            val outcomes = store.outcomes().associateBy { it.planItemId }
            val commitments = store.commitmentsOf(day, zone, settings.rollover)
            val focus = rows.firstOrNull { it.day == day }?.focusMinutes
            val reEntry =
                if (state is WorkingDayState.Current && plan != null) {
                    val status = store.actions().associate { it.id to it.status }
                    ReEntries.compute(plan, outcomes, status, commitments, focus, at, declined = false).let { r ->
                        listOfNotNull(
                            r.next?.let { "Next in the approved order: \"${it.title}\"." },
                            r.question?.let { "Worth asking once whether \"${it.title}\" happened: it decides whether the rest fits." },
                            r.overrunMinutes.takeIf { it > 0 }?.let { "About $it min more is planned than the time left." },
                        ).joinToString(" ").ifEmpty { null }
                    }
                } else {
                    null
                }
            val workingDay =
                when (state) {
                    is WorkingDayState.Current -> "Working day: $day, open."
                    is WorkingDayState.NoneOpen -> "Working day: none open; planning is on $day."
                    is WorkingDayState.Choose ->
                        "An earlier working day, ${state.open}, is still open. Do not plan from it; the person chooses in the day screen."
                }
            val lastNote =
                rows
                    .filter { it.closedAt != null && it.day <= day && it.closingNote != null }
                    .maxByOrNull { it.day }
                    ?.closingNote
            val facts = DayLoopFacts(zone, settings.rollover, workingDay, commitments, focus, lastNote, reEntry, at)
            ConversationSnapshot(
                ConversationContext.build(
                    day,
                    store.direction(),
                    store.goals(),
                    store.projects(),
                    store.actions(),
                    plan,
                    outcomes,
                    settings,
                    facts,
                    inboxIds?.let { chosen ->
                        store
                            .inbox()
                            .filter { it.value.id in chosen && it.value.state == InboxState.OPEN }
                            .sortedWith(compareBy({ it.createdAtMillis }, { it.value.id }))
                            .take(ContextLimits.TRIAGE)
                    },
                    nextDay = nextDay,
                    nextPlan = nextPlan,
                ),
                zone,
            )
        }
    }

    override suspend fun recordOutcome(
        itemId: String,
        result: OutcomeResult,
        today: ActiveDay,
    ) = runner.run { it.recordOutcome(itemId, result, today) }

    override suspend fun currentOutcome(itemId: String): OutcomeResult? =
        runner.run { store -> store.outcomes().firstOrNull { it.planItemId == itemId }?.result }

    override suspend fun createAction(action: Action) = runner.run { it.createAction(action) }

    override suspend fun changeAction(
        before: Action,
        after: Action,
    ) = runner.run { it.changeAction(before, after) }

    override suspend fun action(id: String) = runner.run { it.action(id) }

    override suspend fun previewDay(
        day: ActiveDay,
        selected: Set<String>,
        pins: Map<String, LocalTime>,
    ): PlanProposal? {
        val zone = (zoneSource.read() as? CheckInZone.Known)?.zone ?: return null
        return runner.run { store ->
            // A day that already has a plan keeps the window it was made with; otherwise the saved default applies.
            val own = store.planFor(day)?.plan?.window
            store.propose(day, zone, own?.takeIf { it != store.settings().window }, selected, pins)
        }
    }

    override suspend fun approveDay(
        shown: PlanProposal,
        acknowledgeUnresolved: Boolean,
    ): ApprovalResult? {
        val zone = (zoneSource.read() as? CheckInZone.Known)?.zone ?: return null
        return runner.run { it.approve(shown, zone, acknowledgeUnresolved = acknowledgeUnresolved) }
    }

    override suspend fun planFor(day: ActiveDay) = runner.run { it.planFor(day) }

    override suspend fun changeProjectContext(
        projectId: String,
        field: ContextField,
        expected: String?,
        text: String?,
    ) = runner.run { it.changeProjectContext(projectId, field, expected, text) }

    override suspend fun createCommitment(commitment: FixedCommitment) = runner.run { it.createCommitment(commitment) }

    override suspend fun commitment(id: String) = runner.run { it.commitment(id) }

    override suspend fun setFocus(
        day: ActiveDay,
        minutes: Int?,
    ) = runner.run { it.setFocus(day, minutes) }

    override suspend fun inboxItem(id: String) = runner.run { it.inboxItem(id) }

    override suspend fun resolveInbox(
        id: String,
        to: InboxState,
        body: String?,
    ) = runner.run { it.resolveItem(id, to, body) }

    override suspend fun reopenInbox(
        id: String,
        expected: InboxState,
    ) = runner.run { it.reopenItem(id, expected) }

    override suspend fun convertInbox(
        itemId: String,
        action: Action,
    ) = runner.run { it.convertToAction(itemId, action) }
}
