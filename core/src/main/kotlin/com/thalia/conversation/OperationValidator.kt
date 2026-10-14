package com.thalia.conversation

import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.CommitmentStatus
import com.thalia.planning.ContextField
import com.thalia.planning.FixedCommitment
import com.thalia.planning.InboxConversion
import com.thalia.planning.InboxItem
import com.thalia.planning.InboxState
import com.thalia.planning.InboxText
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanningText
import com.thalia.planning.Priority
import com.thalia.planning.ReminderProblem
import com.thalia.planning.ReminderRules
import com.thalia.planning.WorkingDayRecord
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** What is to be recorded, resolved to the stored plan item. [previous] is what was recorded for it, for undo. */
data class OutcomeTarget(
    val itemId: String,
    val actionId: String,
    val title: String,
    val result: OutcomeResult,
    val previous: OutcomeResult?,
)

/** A suggestion that waits for the person's approval. Nothing here has been written. */
sealed interface Proposal {
    val reason: String?

    /** [action] has its id fixed now, so applying the same proposal twice can never create two actions. */
    data class NewAction(
        val action: Action,
        val projectTitle: String?,
        override val reason: String?,
        /** The Inbox item this action is made from. Approval converts it and creates the action in one transaction. */
        val inboxItemId: String? = null,
        /** Which of "priority", "estimate", "project" and "due" the suggestion gave; the others are Thalia's defaults. */
        val suggested: Set<String> = emptySet(),
    ) : Proposal

    /** [item] as it was shown. [body] is the model's rewrite for a note or idea, or null to keep the original text. */
    data class InboxResolution(
        val item: InboxItem,
        val to: InboxState,
        val body: String?,
        override val reason: String?,
    ) : Proposal

    data class ActionChange(
        val before: Action,
        val after: Action,
        override val reason: String?,
    ) : Proposal

    /**
     * [pins] are the target day's existing pins for actions that stay selected. [day] is the active day this plans and [baseDay]
     * the day planning was on when it was proposed: if planning has moved on by the time of the tap, it is refused as stale.
     */
    data class DaySelection(
        val selected: Set<String>,
        val pins: Map<String, LocalTime>,
        val titles: Map<String, String>,
        override val reason: String?,
        val day: ActiveDay,
        val baseDay: ActiveDay,
        val target: DayTarget = DayTarget.TODAY,
    ) : Proposal

    /** One project context field from [before], what it held when proposed, to [after]. ADR 0011. */
    data class ProjectContext(
        val projectId: String,
        val projectTitle: String,
        val field: ContextField,
        val before: String?,
        val after: String,
        override val reason: String?,
    ) : Proposal

    /** One one-time reminder (Slice 5): [actionId] null is a checkpoint. [id] is fixed now so applying it twice makes one. */
    data class Reminder(
        val id: String,
        val actionId: String?,
        val actionTitle: String?,
        val dueAtMillis: Long,
        val zone: ZoneId,
        override val reason: String?,
    ) : Proposal

    /** [commitment] has its id fixed now, so applying it twice makes one. [note] flags a time a person could mean otherwise. */
    data class Commitment(
        val commitment: FixedCommitment,
        val note: String?,
        override val reason: String?,
    ) : Proposal

    /**
     * An outcome the person stated for a plan item. Stating it is not applying it: it waits for a tap like every other
     * model-derived change. [day] is the active day it was proposed on.
     */
    data class Outcome(
        val target: OutcomeTarget,
        val day: ActiveDay,
        override val reason: String? = null,
    ) : Proposal

    /** An inferred focus capacity for [day]. */
    data class Focus(
        val day: ActiveDay,
        val minutes: Int,
        override val reason: String?,
    ) : Proposal
}

enum class RefusalCode {
    UNSUPPORTED,
    UNKNOWN_REFERENCE,
    INVALID_VALUE,
    NOT_OPEN,
    NOT_ON_TODAY_PLAN,
    ALREADY_RECORDED,
    PROJECT_CLOSED,
    DUPLICATE_ACTION,
    NOTHING_TO_CHANGE,
    NO_WINDOW,
    EMPTY_SELECTION,
    PLAN_LOCKED,

    /** The next active day cannot be named yet (an earlier working day awaits a choice). */
    NO_NEXT_DAY,
    DUPLICATE_IN_REPLY,
    ONE_PROPOSAL_AT_A_TIME,

    /** While captures are shared for review, only Inbox work is allowed. */
    INBOX_REVIEW_ONLY,
}

sealed interface Decision {
    /** A stated outcome, valid against the snapshot. The session stages it as a pending proposal; nothing is written before Approve. */
    data class Fact(
        val target: OutcomeTarget,
    ) : Decision

    data class Propose(
        val proposal: Proposal,
    ) : Decision

    data class Ask(
        val question: String,
    ) : Decision

    /** The person plainly said what to do with an Inbox item. Staged as a pending proposal, applied only on Approve. [item] is what was shown. */
    data class InboxFact(
        val item: InboxItem,
        val to: InboxState,
    ) : Decision

    /** The person stated today's focus capacity. Staged as a pending proposal, saved only on Approve. */
    data class FocusFact(
        val day: ActiveDay,
        val minutes: Int,
    ) : Decision

    data class Refuse(
        val code: RefusalCode,
        val message: String,
    ) : Decision
}

/**
 * Judges what a provider returned against one [ConversationContext], with no store and no clock. A call that is not
 * exactly a supported operation, that names something the snapshot did not show, or that would change something it
 * must not (a completed action, an outcome already recorded, a plan with outcomes) is refused with its reason.
 * Applying a decision is the caller's job and is checked again against the stored data at that moment.
 */
object OperationValidator {
    private const val WHICH_LIMIT = 5

    /** Every call in order. At most one suggestion is kept per reply; a second fact about one action is refused. */
    fun validateAll(
        context: ConversationContext,
        calls: List<RawCall>,
        newId: () -> String,
    ): List<Decision> {
        var proposalKept = false
        val recorded = mutableSetOf<String>()
        return calls.map { call ->
            val decision =
                when (val parsed = OperationParser.parse(call)) {
                    is ParseResult.Unsupported -> unusable(parsed)
                    is ParseResult.Parsed -> validate(context, parsed.operation, newId)
                }
            when {
                decision is Decision.Fact && !recorded.add(decision.target.actionId) ->
                    Decision.Refuse(
                        RefusalCode.DUPLICATE_IN_REPLY,
                        "Not applied: \"${decision.target.title}\" was named twice in one reply.",
                    )
                decision is Decision.InboxFact && !recorded.add("inbox:${decision.item.id}") ->
                    Decision.Refuse(RefusalCode.DUPLICATE_IN_REPLY, "Not applied: an Inbox item was named twice in one reply.")
                decision is Decision.FocusFact && !recorded.add("focus") ->
                    Decision.Refuse(RefusalCode.DUPLICATE_IN_REPLY, "Not applied: the focus capacity was stated twice in one reply.")
                // Stated facts wait for a tap like suggestions, and only one thing can wait at a time.
                decision.needsApproval() && proposalKept ->
                    Decision.Refuse(
                        RefusalCode.ONE_PROPOSAL_AT_A_TIME,
                        "Not proposed: one suggestion at a time, so ${waitingLabel(decision)} was not kept. Ask again for the next one.",
                    )
                decision.needsApproval() -> decision.also { proposalKept = true }
                else -> decision
            }
        }
    }

    /** What was dropped, in words, so a refused second change is visible and not silently lost. */
    private fun waitingLabel(decision: Decision): String =
        when (decision) {
            is Decision.Fact -> "\"${decision.target.title}\" as ${decision.target.result.name.lowercase()}"
            is Decision.InboxFact -> "filing an Inbox item"
            is Decision.FocusFact -> "the focus capacity of ${decision.minutes} min"
            is Decision.Propose -> "another suggestion"
            else -> "that"
        }

    private fun Decision.needsApproval() =
        this is Decision.Propose || this is Decision.Fact || this is Decision.InboxFact || this is Decision.FocusFact

    /**
     * A call Kotlin cannot use, said in the person's terms. A tool that does not exist is "cannot do that"; a time that is
     * missing or malformed is a question, because no time is ever guessed; an outcome that is not one of the four recordable
     * ones records nothing, so an outcome the person is unsure of stays unknown. The tool's name and the parser's wording are not shown.
     */
    private fun unusable(parsed: ParseResult.Unsupported): Decision =
        when {
            parsed.name !in OperationParser.NAMES ->
                Decision.Refuse(RefusalCode.UNSUPPORTED, unsupportedWords(parsed.name))
            parsed.name == OperationParser.PROPOSE_FIXED_COMMITMENT ->
                Decision.Ask("What date, start time and end time should I use for it? I do not guess times, so nothing was added.")
            parsed.name == OperationParser.PROPOSE_REMINDER ->
                Decision.Ask(
                    "What day and time should the reminder be for? It can be from a minute to 7 days ahead. Nothing was set.",
                )
            parsed.name == OperationParser.RECORD_OUTCOME ->
                Decision.Refuse(
                    RefusalCode.INVALID_VALUE,
                    "I recorded nothing: I can only record done, partial, skipped or blocked, and an outcome you are not sure of stays unknown.",
                )
            else -> Decision.Refuse(RefusalCode.UNSUPPORTED, "I could not use that request as written, so nothing changed.")
        }

    /**
     * What a tool that does not exist most likely was for, judged from its name alone (the person's words are not read), so a
     * refused reminder feature is named precisely. Only Slice 5's one-time reminders exist.
     */
    private fun unsupportedWords(name: String): String {
        val n = name.lowercase()
        return when {
            listOf("recur", "repeat", "daily", "weekly", "monthly").any { it in n } ->
                "I can only set one-time reminders, not repeating ones, so nothing changed."
            "snooze" in n -> "I cannot snooze a reminder. Dismiss or acknowledge it under Reminders, then set a new one."
            "calendar" in n -> "I cannot use a calendar. Thalia keeps its own one-time reminders and fixed commitments, so nothing changed."
            listOf("cancel", "delete", "remove", "edit", "update", "list").any { it in n } && "remind" in n ->
                "I cannot change or cancel a reminder from here. Do that under Reminders, so nothing changed."
            else -> "I cannot do that from here, so nothing changed."
        }
    }

    private fun reminder(
        context: ConversationContext,
        op: RequestedOperation.ProposeReminder,
        newId: () -> String,
    ): Decision {
        val zone = context.dayLoop.zone ?: return refuse(RefusalCode.INVALID_VALUE, "Not proposed: planning has no time zone.")
        val now =
            context.dayLoop.now ?: return refuse(RefusalCode.INVALID_VALUE, "Not proposed: planning has no clock to place a reminder by.")
        val at = exactInstant(op.at, zone) ?: return ambiguous(op.at, zone)
        val problem = ReminderRules.problem(at.toEpochMilli(), now.toEpochMilli(), pending = 0, inQuietPeriod = false)
        if (problem == ReminderProblem.TOO_SOON || problem == ReminderProblem.TOO_FAR) {
            return Decision.Ask(
                "A reminder can be from a minute to 7 days ahead, and that time is " +
                    (if (problem == ReminderProblem.TOO_SOON) "too soon or already past" else "too far away") +
                    ", so nothing was set. What day and time instead?",
            )
        }
        val action =
            op.actionRef?.let {
                (context.action(it) ?: return whichAction(context, todayOnly = false)).action
            }
        return Decision.Propose(Proposal.Reminder(newId(), action?.id, action?.title, at.toEpochMilli(), zone, op.reason))
    }

    /** While captures are shared for review the conversation only files them or turns them into actions. */
    private fun inboxReviewOnly(
        context: ConversationContext,
        operation: RequestedOperation,
    ): Decision? {
        if (!context.inboxShared) return null
        val allowed =
            operation is RequestedOperation.AskClarification ||
                operation is RequestedOperation.ResolveInboxItem ||
                operation is RequestedOperation.ProposeInboxResolution ||
                (operation is RequestedOperation.ProposeNewAction && operation.inboxRef != null)
        return if (allowed) {
            null
        } else {
            refuse(
                RefusalCode.INBOX_REVIEW_ONLY,
                "While we go through the Inbox I only file captures or turn them into actions, so nothing else was changed. " +
                    "Ask for that outside the Inbox review.",
            )
        }
    }

    /** The question for a missing or unknown action: which one, with today's plan to choose from when there is one. */
    private fun whichAction(
        context: ConversationContext,
        todayOnly: Boolean,
    ): Decision.Ask {
        val titles =
            context.actions
                .filter { !todayOnly || it.today != null }
                .take(WHICH_LIMIT)
                .map { "\"${it.action.title}\"" }
        val choices =
            if (titles.isEmpty()) {
                ""
            } else {
                " ${if (todayOnly) "Today's plan has" else "Open actions include"} ${titles.joinToString()}."
            }
        return Decision.Ask("I could not tell which action you mean, so nothing changed. Which one is it?$choices")
    }

    fun validate(
        context: ConversationContext,
        operation: RequestedOperation,
        newId: () -> String,
    ): Decision =
        inboxReviewOnly(context, operation) ?: when (operation) {
            is RequestedOperation.AskClarification -> Decision.Ask(operation.question)
            is RequestedOperation.RecordOutcome -> recordOutcome(context, operation)
            is RequestedOperation.ProposeNewAction -> newAction(context, operation, newId)
            is RequestedOperation.ProposeActionChange -> actionChange(context, operation)
            is RequestedOperation.ProposeDaySelection -> daySelection(context, operation)
            is RequestedOperation.ProposeProjectContext -> projectContext(context, operation)
            is RequestedOperation.ProposeFixedCommitment -> commitment(context, operation, newId)
            is RequestedOperation.ProposeReminder -> reminder(context, operation, newId)
            is RequestedOperation.RecordFocusCapacity ->
                focusMinutes(operation.minutes)?.let { refuse(RefusalCode.INVALID_VALUE, "Not recorded: $it") }
                    ?: Decision.FocusFact(context.day, operation.minutes)
            is RequestedOperation.ResolveInboxItem -> resolveInbox(context, operation)
            is RequestedOperation.ProposeInboxResolution -> proposeInbox(context, operation)
            is RequestedOperation.ProposeFocusCapacity ->
                focusMinutes(operation.minutes)?.let { refuse(RefusalCode.INVALID_VALUE, "Not proposed: $it") }
                    ?: Decision.Propose(Proposal.Focus(context.day, operation.minutes, operation.reason))
        }

    private fun resolveInbox(
        context: ConversationContext,
        op: RequestedOperation.ResolveInboxItem,
    ): Decision {
        val item =
            context.inboxItem(op.itemRef)?.item
                ?: return refuse(RefusalCode.UNKNOWN_REFERENCE, "Not applied: ${op.itemRef} is not an Inbox item I was shown.")
        return Decision.InboxFact(item, op.to)
    }

    private fun proposeInbox(
        context: ConversationContext,
        op: RequestedOperation.ProposeInboxResolution,
    ): Decision {
        val item =
            context.inboxItem(op.itemRef)?.item
                ?: return refuse(RefusalCode.UNKNOWN_REFERENCE, "Not proposed: ${op.itemRef} is not an Inbox item I was shown.")
        if (op.body != null && !op.to.hasBody) {
            return refuse(RefusalCode.INVALID_VALUE, "Not proposed: only a note or an idea has a body.")
        }
        op.body?.let { body -> InboxText.problem(body)?.let { return refuse(RefusalCode.INVALID_VALUE, "Not proposed: $it") } }
        return Decision.Propose(Proposal.InboxResolution(item, op.to, op.body, op.reason))
    }

    private fun focusMinutes(minutes: Int): String? =
        if (minutes in WorkingDayRecord.FOCUS_RANGE) null else "focus capacity is from 0 to ${WorkingDayRecord.FOCUS_RANGE.last} minutes."

    private fun projectContext(
        context: ConversationContext,
        op: RequestedOperation.ProposeProjectContext,
    ): Decision {
        val project =
            context.project(op.projectRef)?.project
                ?: return refuse(RefusalCode.UNKNOWN_REFERENCE, "Not proposed: ${op.projectRef} is not a project I was shown.")
        val text = PlanningText.clean(op.text)
        if (text.length > PlanningText.CONTEXT_MAX) {
            return refuse(RefusalCode.INVALID_VALUE, "Not proposed: that is longer than ${PlanningText.CONTEXT_MAX} characters.")
        }
        val before = project.context(op.field)
        if (before == text) return refuse(RefusalCode.NOTHING_TO_CHANGE, "Not proposed: \"${project.title}\" already says that.")
        return Decision.Propose(Proposal.ProjectContext(project.id, project.title, op.field, before, text, op.reason))
    }

    /**
     * A commitment at the local date-times given, resolved in the planning zone. A time the clocks skip is refused, and one
     * that happens twice is refused for clarification: nothing is guessed. An end before the start is refused, never moved
     * to the next day.
     */
    private fun commitment(
        context: ConversationContext,
        op: RequestedOperation.ProposeFixedCommitment,
        newId: () -> String,
    ): Decision {
        val zone = context.dayLoop.zone ?: return refuse(RefusalCode.INVALID_VALUE, "Not proposed: planning has no time zone.")
        val start = exactInstant(op.start, zone) ?: return ambiguous(op.start, zone)
        val end = exactInstant(op.end, zone) ?: return ambiguous(op.end, zone)
        if (!end.isAfter(
                start,
            )
        ) {
            return Decision.Ask("That ends at or before it starts, so nothing was added. What end time did you mean?")
        }
        val commitment =
            try {
                FixedCommitment(newId(), PlanningText.clean(op.title), start, end, zone, CommitmentStatus.ACTIVE)
            } catch (e: IllegalArgumentException) {
                return refuse(RefusalCode.INVALID_VALUE, "Not proposed: ${e.message}.")
            }
        val rollover = context.dayLoop.rollover
        val early = op.start.toLocalTime() < rollover
        val note =
            if (early) {
                "It starts before $rollover, when the day turns over, so it falls in the working day of ${op.start.toLocalDate().minusDays(
                    1,
                )}."
            } else {
                null
            }
        return Decision.Propose(Proposal.Commitment(commitment, note, op.reason))
    }

    /** The one instant [local] names in [zone], or null when the clocks skip it or it happens twice. */
    private fun exactInstant(
        local: LocalDateTime,
        zone: ZoneId,
    ) = zone.rules
        .getValidOffsets(local)
        .singleOrNull()
        ?.let { local.toInstant(it) }

    private fun ambiguous(
        local: LocalDateTime,
        zone: ZoneId,
    ): Decision =
        if (zone.rules.getValidOffsets(local).isEmpty()) {
            refuse(RefusalCode.INVALID_VALUE, "Not proposed: $local does not exist in ${zone.id}, because the clocks skip it.")
        } else {
            refuse(RefusalCode.INVALID_VALUE, "Not proposed: $local happens twice in ${zone.id}. Which one did you mean?")
        }

    private fun refuse(
        code: RefusalCode,
        message: String,
    ) = Decision.Refuse(code, message)

    private fun recordOutcome(
        context: ConversationContext,
        op: RequestedOperation.RecordOutcome,
    ): Decision {
        val entry =
            context.action(op.actionRef) ?: return whichAction(context, todayOnly = true)
        val title = entry.action.title
        if (entry.action.status !=
            ActionStatus.OPEN
        ) {
            return refuse(RefusalCode.NOT_OPEN, "Not recorded: \"$title\" is already closed, and a conversation does not reopen work.")
        }
        val today = entry.today ?: return refuse(RefusalCode.NOT_ON_TODAY_PLAN, "Not recorded: \"$title\" is not in today's approved plan.")
        if (today.outcome != null && today.outcome != OutcomeResult.UNKNOWN) {
            return refuse(RefusalCode.ALREADY_RECORDED, "Not recorded: \"$title\" already has an outcome. Correct it in the day screen.")
        }
        return Decision.Fact(OutcomeTarget(today.itemId, entry.action.id, title, op.result, today.outcome))
    }

    private fun newAction(
        context: ConversationContext,
        op: RequestedOperation.ProposeNewAction,
        newId: () -> String,
    ): Decision {
        val project =
            op.projectRef?.let {
                context.project(it)
                    ?: return refuse(RefusalCode.UNKNOWN_REFERENCE, "Not proposed: ${op.projectRef} is not a project I was shown.")
            }
        if (project != null && !project.project.state.acceptsActions) {
            return refuse(RefusalCode.PROJECT_CLOSED, "Not proposed: \"${project.project.title}\" is closed and takes no new actions.")
        }
        op.dueDate?.let {
            if (it <
                context.day.date
            ) {
                return refuse(RefusalCode.INVALID_VALUE, "Not proposed: the due date $it has passed.")
            }
        }
        val inbox =
            op.inboxRef?.let {
                context.inboxItem(it)
                    ?: return refuse(RefusalCode.UNKNOWN_REFERENCE, "Not proposed: $it is not an Inbox item I was shown.")
            }
        val action =
            try {
                Action(
                    id = inbox?.let { InboxConversion.actionIdFor(it.item.id) } ?: newId(),
                    title = PlanningText.clean(op.title),
                    projectId = project?.project?.id,
                    priority = op.priority ?: Priority.NORMAL,
                    estimateMinutes = op.estimateMinutes ?: Action.DEFAULT_ESTIMATE_MINUTES,
                    dueDate = op.dueDate,
                )
            } catch (e: IllegalArgumentException) {
                return refuse(RefusalCode.INVALID_VALUE, "Not proposed: ${e.message}.")
            }
        val same = context.actions.any { it.action.projectId == action.projectId && sameTitle(it.action.title, action.title) }
        if (same) return refuse(RefusalCode.DUPLICATE_ACTION, "Not proposed: an open action called \"${action.title}\" already exists.")
        return Decision.Propose(Proposal.NewAction(action, project?.project?.title, op.reason, inbox?.item?.id, suggestedFields(op)))
    }

    private fun suggestedFields(op: RequestedOperation.ProposeNewAction): Set<String> =
        buildSet {
            if (op.priority != null) add("priority")
            if (op.estimateMinutes != null) add("estimate")
            if (op.projectRef != null) add("project")
            if (op.dueDate != null) add("due")
        }

    private fun actionChange(
        context: ConversationContext,
        op: RequestedOperation.ProposeActionChange,
    ): Decision {
        val entry =
            context.action(op.actionRef) ?: return whichAction(context, todayOnly = false)
        val before = entry.action
        if (before.status !=
            ActionStatus.OPEN
        ) {
            return refuse(RefusalCode.NOT_OPEN, "Not proposed: \"${before.title}\" is closed and stays as it is.")
        }
        if (op.clearDueDate &&
            op.dueDate != null
        ) {
            return refuse(RefusalCode.INVALID_VALUE, "Not proposed: a due date was both set and cleared.")
        }
        op.dueDate?.let {
            if (it <
                context.day.date
            ) {
                return refuse(RefusalCode.INVALID_VALUE, "Not proposed: the due date $it has passed.")
            }
        }
        val after =
            try {
                before.copy(
                    title = op.title?.let { PlanningText.clean(it) } ?: before.title,
                    priority = op.priority ?: before.priority,
                    estimateMinutes = op.estimateMinutes ?: before.estimateMinutes,
                    dueDate = if (op.clearDueDate) null else op.dueDate ?: before.dueDate,
                )
            } catch (e: IllegalArgumentException) {
                return refuse(RefusalCode.INVALID_VALUE, "Not proposed: ${e.message}.")
            }
        if (after ==
            before
        ) {
            return refuse(RefusalCode.NOTHING_TO_CHANGE, "Not proposed: that would change nothing about \"${before.title}\".")
        }
        // A priority change that also carries another change is not offered. The call alone cannot show that the person asked for
        // more than the priority, so nothing waits for a tap; the person is asked, and can make each change as its own request.
        val alsoChanges = otherChanges(before, after)
        if (after.priority != before.priority && alsoChanges.isNotEmpty()) {
            return Decision.Ask(
                "I can set the priority of \"${before.title}\" to ${after.priority.name.lowercase()}, but this would also change " +
                    "${alsoChanges.joinToString(" and ")}. A priority change has to be proposed separately from other changes, so I have " +
                    "not offered this one. Tell me the priority change on its own, and the other change as a separate request.",
            )
        }
        return Decision.Propose(Proposal.ActionChange(before, after, op.reason))
    }

    /** The differences other than priority, in the person's terms. Restated, unchanged values are not differences. */
    private fun otherChanges(
        before: Action,
        after: Action,
    ): List<String> =
        listOfNotNull(
            if (after.title != before.title) "the title to \"${after.title}\"" else null,
            if (after.estimateMinutes != before.estimateMinutes) {
                "the estimate from ${before.estimateMinutes} to ${after.estimateMinutes} minutes"
            } else {
                null
            },
            when {
                after.dueDate == before.dueDate -> null
                after.dueDate == null -> "the due date (removing ${before.dueDate})"
                before.dueDate == null -> "the due date (adding ${after.dueDate})"
                else -> "the due date from ${before.dueDate} to ${after.dueDate}"
            },
        )

    private fun daySelection(
        context: ConversationContext,
        op: RequestedOperation.ProposeDaySelection,
    ): Decision {
        val next = context.next
        if (op.target == DayTarget.NEXT && next == null) {
            return refuse(
                RefusalCode.NO_NEXT_DAY,
                "Not proposed: the next active day cannot be named yet. Settle the earlier working day on the day screen first.",
            )
        }
        if (context.window ==
            null
        ) {
            return refuse(RefusalCode.NO_WINDOW, "Not proposed: the planning window is not set. Set it in Plan a day first.")
        }
        if (op.actionRefs.isEmpty()) {
            return refuse(
                RefusalCode.EMPTY_SELECTION,
                "Not proposed: an empty day is a rest day, which needs its own acknowledgement in Plan a day.",
            )
        }
        if (op.target == DayTarget.TODAY && context.planLocked) {
            return refuse(
                RefusalCode.PLAN_LOCKED,
                "Not proposed: today's plan has recorded outcomes, so it cannot be replaced.",
            )
        }
        val entries =
            op.actionRefs.map {
                context.action(it)
                    ?: return refuse(RefusalCode.UNKNOWN_REFERENCE, "Not proposed: $it is not an action I was shown.")
            }
        if (entries.map { it.ref }.toSet().size !=
            entries.size
        ) {
            return refuse(RefusalCode.INVALID_VALUE, "Not proposed: an action was listed twice.")
        }
        val selected = entries.map { it.action.id }.toSet()
        return if (op.target == DayTarget.NEXT) {
            val target = checkNotNull(next)
            if (selected == target.selection) {
                return refuse(RefusalCode.NOTHING_TO_CHANGE, "Not proposed: that is already the next active day's plan.")
            }
            val pins = target.pins.filterKeys { it in selected }
            val titles = (entries.map { it.action.id to it.action.title } + target.titles.toList()).toMap()
            Decision.Propose(Proposal.DaySelection(selected, pins, titles, op.reason, target.day, context.day, DayTarget.NEXT))
        } else {
            if (selected ==
                context.todaySelection
            ) {
                return refuse(RefusalCode.NOTHING_TO_CHANGE, "Not proposed: that is already today's plan.")
            }
            val pins = context.todayPins.filterKeys { it in selected }
            val titles =
                (context.actions.filter { it.action.id in selected || it.today != null }).associate {
                    it.action.id to
                        it.action.title
                }
            Decision.Propose(Proposal.DaySelection(selected, pins, titles, op.reason, context.day, context.day, DayTarget.TODAY))
        }
    }

    private fun sameTitle(
        a: String,
        b: String,
    ) = a.trim().equals(b.trim(), ignoreCase = true)
}
