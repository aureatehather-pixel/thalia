package com.thalia.app.conversation

import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.ChangeActionResult
import com.thalia.app.planning.data.ContextChangeResult
import com.thalia.app.planning.data.ConvertInboxResult
import com.thalia.app.planning.data.CreateActionResult
import com.thalia.app.planning.data.CreateCommitmentResult
import com.thalia.app.planning.data.OutcomeRecording
import com.thalia.app.planning.data.ReopenInboxResult
import com.thalia.app.planning.data.ResolveInboxResult
import com.thalia.app.reminders.CreateOutcome
import com.thalia.app.reminders.ReminderText
import com.thalia.conversation.ContextLimits
import com.thalia.conversation.ConversationPrompt
import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.ConversationTools
import com.thalia.conversation.DayTarget
import com.thalia.conversation.Decision
import com.thalia.conversation.OperationValidator
import com.thalia.conversation.Proposal
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.Turn
import com.thalia.conversation.TurnRole
import com.thalia.planning.ActionStatus
import com.thalia.planning.InboxState
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanProposal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** How to take back what a stated fact saved. */
internal sealed interface Undo {
    /** Record [restore] for the item again. */
    data class Outcome(
        val itemId: String,
        val restore: OutcomeResult,
    ) : Undo

    /** Put the item back in the Inbox, only if it is still [expected]. Its original text and body are kept. */
    data class Inbox(
        val itemId: String,
        val expected: InboxState,
    ) : Undo

    /** Cancel the reminder [id] that this conversation just set for [whenText], only while it is still waiting. */
    data class Reminder(
        val id: String,
        val whenText: String,
    ) : Undo

    /** Put [day]'s focus capacity back to [restore], none when null. */
    data class Focus(
        val day: com.thalia.planning.ActiveDay,
        val restore: Int?,
    ) : Undo
}

/** One line of the conversation, labelled by what it is: said, saved, not applied, or a note. */
internal sealed interface Entry {
    val id: Int

    data class User(
        override val id: Int,
        val text: String,
    ) : Entry

    data class Assistant(
        override val id: Int,
        val text: String,
    ) : Entry

    /** Written to storage. [undo] is null once used or when there is nothing to take back. */
    data class Saved(
        override val id: Int,
        val text: String,
        val undo: Undo? = null,
        /** Labelled facts of what was saved, for a card (a reminder's target, date and time). The text says the same in words. */
        val facts: List<Pair<String, String>> = emptyList(),
    ) : Entry

    /** Refused or failed. Nothing was written. */
    data class NotApplied(
        override val id: Int,
        val text: String,
    ) : Entry

    /** Nothing was changed and the person is pointed to the day screen, which owns opening and closing the working day. */
    data class Redirect(
        override val id: Int,
        val text: String,
    ) : Entry

    /** Neither saved nor refused: a suggestion waiting, or the state of something uncertain. */
    data class Note(
        override val id: Int,
        val text: String,
    ) : Entry
}

/** The one suggestion waiting for approval. [preview] is set for a day selection. */
internal data class Pending(
    val proposal: Proposal,
    val summary: String,
    val approveLabel: String,
    val preview: PlanProposal? = null,
)

internal data class ConversationState(
    val entries: List<Entry> = emptyList(),
    val pending: Pending? = null,
    val busy: Boolean = false,
)

/**
 * One conversation: the person says something, the provider may answer with text and function calls, and Kotlin decides
 * what each call may do. A stated fact and a suggestion alike become the one
 * pending proposal and change nothing until the person taps Approve (an approved change keeps an Undo). Typed or spoken words
 * never approve: a bare "yes" is answered here and never sent to the provider, and while one is pending it only reminds the
 * person to tap Approve. Nothing is kept between
 * sessions except what the planning store holds, so leaving drops an unapproved proposal.
 *
 * Every step runs under one lock, so a second tap on Approve waits, finds nothing pending, and does nothing.
 */
internal class ConversationSession(
    private val port: ConversationPort,
    private val provider: ConversationProvider,
    /**
     * The Inbox captures the person chose to go through (Triage with Thalia), or null for an ordinary conversation, which
     * never sees the Inbox. Only these, and only while still open, are shared with the provider (ADR 0013).
     */
    private val triageIds: Set<String>? = null,
    /** False on the ChatGPT-plan route, whose failure texts then make no claim about a monthly budget. */
    private val budgetNotes: Boolean = true,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = mutable.asStateFlow()
    private var nextEntry = 1
    private val turns = mutableListOf<Turn>()

    suspend fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        lock.withLock {
            busy(true)
            try {
                add { Entry.User(it, text) }
                if (text.lowercase().trim('.', '!', ' ') in YES) {
                    // Words never approve. A bare yes is answered here, never sent to the provider, and changes nothing.
                    if (mutable.value.pending != null) {
                        add { Entry.Note(it, "Nothing was applied. Tap Approve to apply it, or Dismiss to drop it.") }
                    } else {
                        add { Entry.Note(it, "Nothing is waiting for approval.") }
                    }
                } else if (DayRequests.classify(text) != null) {
                    // Opening and closing the working day belong to the day screen. Answered here, without a provider.
                    add { Entry.Redirect(it, DayRequests.redirectText(checkNotNull(DayRequests.classify(text)))) }
                } else {
                    turnLocked(text)
                }
            } finally {
                busy(false)
            }
        }
    }

    suspend fun approve() =
        lock.withLock {
            busy(true)
            try {
                approveLocked()
            } finally {
                busy(false)
            }
        }

    /** Drops the pending proposal. Nothing was written, so there is nothing to undo. */
    suspend fun dismiss() =
        lock.withLock {
            if (mutable.value.pending != null) {
                mutable.update { it.copy(pending = null) }
                add { Entry.Note(it, "Dismissed. Nothing was applied.") }
            }
        }

    /**
     * Cancels exactly the reminder that was set, through the reminder service. "Undone" is said only after it reports the
     * reminder was cancelled; one that was no longer waiting is said not to have been cancelled; a failure is read back first.
     */
    private suspend fun undoReminder(
        entryId: Int,
        undo: Undo.Reminder,
    ) {
        val cancelled: Boolean =
            try {
                port.cancelReminder(undo.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The write may or may not have committed: read the reminder back before saying either.
                when (runCatching { port.reminder(undo.id) }.getOrNull()?.status) {
                    com.thalia.planning.ReminderStatus.CANCELLED -> true
                    com.thalia.planning.ReminderStatus.SCHEDULED -> {
                        add {
                            Entry.NotApplied(
                                it,
                                "Not cancelled: storage failed and the reminder is still waiting. You can press Undo again.",
                            )
                        }
                        return
                    }
                    else -> {
                        add { Entry.Note(it, "Could not confirm whether the reminder was cancelled. Check Reminders.") }
                        return
                    }
                }
            }
        replace(entryId) { (it as Entry.Saved).copy(undo = null) }
        if (cancelled) {
            add { Entry.Saved(it, "Undone: the reminder for ${undo.whenText} was cancelled. Nothing will be posted.") }
        } else {
            add {
                Entry.NotApplied(it, "Could not undo: that reminder was no longer waiting, so nothing was cancelled. Check Reminders.")
            }
        }
    }

    suspend fun undo(entryId: Int) =
        lock.withLock {
            val entry = mutable.value.entries.firstOrNull { it.id == entryId } as? Entry.Saved
            val undo = entry?.undo ?: return@withLock
            if (undo is Undo.Focus) {
                try {
                    port.setFocus(undo.day, undo.restore)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    add { Entry.Note(it, "Could not confirm whether the undo was saved. Check the day screen.") }
                    return@withLock
                }
                replace(entryId) { (it as Entry.Saved).copy(undo = null) }
                add { Entry.Saved(it, "Undone: focus capacity is ${undo.restore?.let { m -> "back to $m min" } ?: "not given again"}.") }
                return@withLock
            }
            if (undo is Undo.Reminder) {
                undoReminder(entryId, undo)
                return@withLock
            }
            if (undo is Undo.Inbox) {
                val result =
                    try {
                        port.reopenInbox(undo.itemId, undo.expected)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        add { Entry.Note(it, "Could not confirm whether the undo was saved. Check the Inbox.") }
                        return@withLock
                    }
                replace(entryId) { (it as Entry.Saved).copy(undo = null) }
                when (result) {
                    ReopenInboxResult.REOPENED -> add { Entry.Saved(it, "Undone: that item is back in the Inbox.") }
                    else -> add { Entry.NotApplied(it, "Could not undo: that item was changed since. Check the Inbox.") }
                }
                return@withLock
            }
            undo as Undo.Outcome
            val snapshot = port.snapshot()
            if (snapshot == null) {
                add { Entry.NotApplied(it, "Could not undo: planning is not ready.") }
                return@withLock
            }
            val result =
                try {
                    port.recordOutcome(undo.itemId, undo.restore, snapshot.context.day)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    add { Entry.Note(it, "Could not confirm whether the undo was saved. Check the day screen.") }
                    return@withLock
                }
            replace(entryId) { (it as Entry.Saved).copy(undo = null) }
            when (result) {
                is OutcomeRecording.Recorded -> add { Entry.Saved(it, "Undone: that outcome is back to ${undo.restore.name.lowercase()}.") }
                is OutcomeRecording.Refused -> add { Entry.NotApplied(it, "Could not undo: that item can no longer take an outcome.") }
            }
        }

    /**
     * What the Inbox part of a request would carry right now, one line per capture, so the person sees what a Send shares and
     * which captures are cut before pressing it. Reads the store only; no provider is reached.
     */
    suspend fun sharingPreview(): List<String> {
        if (triageIds == null) return emptyList()
        val context = port.snapshot(triageIds)?.context ?: return emptyList()
        return context.inbox.map {
            "${it.ref}: \"${it.shown}\"${if (it.cut) " [cut: only the first ${ContextLimits.INBOX_CHARS} characters are shared]" else ""}"
        }
    }

    // One request

    private suspend fun turnLocked(text: String) {
        if (text.length > ContextLimits.MESSAGE_CHARS) {
            add {
                Entry.NotApplied(
                    it,
                    "That message is ${text.length} characters; the limit is ${ContextLimits.MESSAGE_CHARS}. Nothing was sent.",
                )
            }
            return
        }
        turns += Turn(TurnRole.USER, text)
        val snapshot = port.snapshot(triageIds)
        if (snapshot == null) {
            add {
                Entry.NotApplied(
                    it,
                    "Planning is not ready: it needs a usable check-in time zone and open planning data. Nothing was changed.",
                )
            }
            return
        }
        val context = snapshot.context
        val waiting = mutable.value.pending?.let { "\nWaiting for the person's approval, so do not propose it again: ${it.summary}" } ?: ""
        val request =
            ProviderRequest(
                instruction = ConversationPrompt.INSTRUCTION,
                contextText = context.render() + waiting,
                turns = turns.takeLast(MAX_TURNS),
                toolsJson = ConversationTools.DECLARATIONS_JSON,
            )
        val response =
            try {
                provider.respond(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProviderResponse.Failed(com.thalia.conversation.ProviderFailure.UNREADABLE)
            }
        when (response) {
            is ProviderResponse.Failed -> add { Entry.NotApplied(it, providerFailureText(response.failure, budgetNotes)) }
            is ProviderResponse.Reply -> {
                // A made-up call to open or close the day is not a dead-end refusal: it points to the day screen, once. The
                // model's own words are dropped with it, since they may say it was done.
                val (dayCalls, supported) = response.calls.partition { DayRequests.isDayStateCall(it.name) }
                val decisions = OperationValidator.validateAll(context, supported, newId)
                val shownBefore = mutable.value.entries.size
                val modelText = if (dayCalls.isEmpty()) response.text?.trim()?.takeIf { it.isNotEmpty() } else null
                modelText?.let { turns += Turn(TurnRole.ASSISTANT, it) }
                // Plain conversation: the model's words are labelled as the model's, and a separate line states, without reading
                // those words, that nothing was applied. When Kotlin handles the reply the words follow its result as an explanation.
                if (modelText != null && decisions.isEmpty()) {
                    add { Entry.Assistant(it, ReplyText.response(modelText, context)) }
                    add { Entry.Note(it, ReplyText.NO_CHANGES) }
                }
                if (dayCalls.isNotEmpty()) {
                    val opening = dayCalls.any { call -> listOf("open", "start", "begin").any { it in call.name.lowercase() } }
                    val kind = if (opening) DayRequests.Kind.OPEN else DayRequests.Kind.CLOSE
                    add { Entry.Redirect(it, DayRequests.redirectText(kind)) }
                }
                for (decision in decisions) {
                    when (decision) {
                        is Decision.Fact -> stage(Proposal.Outcome(decision.target, context.day), context)
                        is Decision.FocusFact -> stage(Proposal.Focus(decision.day, decision.minutes, null), context)
                        is Decision.InboxFact -> stage(Proposal.InboxResolution(decision.item, decision.to, null, null), context)
                        is Decision.Propose -> stage(decision.proposal, context)
                        is Decision.Ask -> add { Entry.Assistant(it, ReplyText.titled(decision.question, context)) }
                        is Decision.Refuse -> add { Entry.NotApplied(it, ReplyText.titled(decision.message, context)) }
                    }
                }
                if (modelText != null && decisions.isNotEmpty()) {
                    add { Entry.Assistant(it, ReplyText.explanation(modelText, context)) }
                }
                // A reply of tool calls only, or of nothing, must still leave the person something to read.
                if (mutable.value.entries.size == shownBefore) add { Entry.Note(it, ReplyText.NO_ANSWER) }
            }
        }
    }

    private suspend fun stage(
        proposal: Proposal,
        context: com.thalia.conversation.ConversationContext,
    ) {
        val pending =
            when (proposal) {
                is Proposal.NewAction -> Pending(proposal, proposalSummary(proposal), "Add action")
                is Proposal.ActionChange -> Pending(proposal, proposalSummary(proposal), "Apply change")
                is Proposal.ProjectContext -> Pending(proposal, proposalSummary(proposal), "Save to project")
                is Proposal.Commitment -> Pending(proposal, commitmentSummary(proposal, snapshotZone(context)), "Add commitment")
                is Proposal.InboxResolution -> Pending(proposal, proposalSummary(proposal), inboxApproveLabel(proposal.to))
                is Proposal.Focus -> Pending(proposal, "Set focus capacity for ${proposal.day} to ${proposal.minutes} min.", "Set focus")
                is Proposal.Outcome -> Pending(proposal, proposalSummary(proposal), "Record outcome")
                is Proposal.Reminder -> {
                    if (!port.canSetReminders) {
                        add { Entry.NotApplied(it, "Reminders are not available in this build, so nothing was scheduled.") }
                        return
                    }
                    Pending(proposal, proposalSummary(proposal), "Set reminder")
                }
                is Proposal.DaySelection -> {
                    val preview = port.previewDay(proposal.day, proposal.selected, proposal.pins)
                    when {
                        preview == null -> {
                            add { Entry.NotApplied(it, "Not proposed: planning has no window or time zone to plan in.") }
                            return
                        }
                        !preview.canApprove -> {
                            add {
                                Entry.NotApplied(
                                    it,
                                    "Not proposed: the planner cannot place that (${planErrorText(
                                        preview.errors.map { e ->
                                            e.kind
                                        },
                                    )}).",
                                )
                            }
                            return
                        }
                        else ->
                            Pending(
                                proposal,
                                daySummary(proposal, preview, selectionFor(proposal, context)),
                                approveLabel(preview, proposal),
                                preview,
                            )
                    }
                }
            }
        if (mutable.value.pending != null) add { Entry.Note(it, "The earlier suggestion was replaced and was not applied.") }
        mutable.update { it.copy(pending = pending.copy(summary = ReplyText.display(pending.summary))) }
        val reason = proposal.reason?.let { " ${ReplyText.AI_EXPLANATION}${ReplyText.titled(it, context)}" } ?: ""
        add { Entry.Note(it, "Proposed, not applied yet. ${pending.summary}$reason") }
    }

    // Approval

    private suspend fun approveLocked() {
        val pending = mutable.value.pending
        if (pending == null) {
            add { Entry.Note(it, "Nothing is waiting for approval.") }
            return
        }
        when (val proposal = pending.proposal) {
            is Proposal.NewAction -> approveNew(proposal)
            is Proposal.ActionChange -> approveChange(proposal)
            is Proposal.DaySelection -> approveDay(proposal, pending)
            is Proposal.ProjectContext -> approveContext(proposal)
            is Proposal.Commitment -> approveCommitment(proposal)
            is Proposal.Focus -> approveFocus(proposal)
            is Proposal.Outcome -> approveOutcome(proposal)
            is Proposal.Reminder -> approveReminder(proposal)
            is Proposal.InboxResolution -> approveInbox(proposal)
        }
    }

    /** A one-time reminder, set only now that they tapped. It is described as set only after the reminder service has stored it. */
    private suspend fun approveReminder(proposal: Proposal.Reminder) {
        val done = "Reminder set for ${reminderWhen(
            proposal,
        )}. Thalia will post one quiet notification, then wait. Cancel it under Reminders."
        val outcome =
            try {
                port.createReminder(proposal)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The write may or may not have committed: look before claiming either.
                val stored = runCatching { port.reminder(proposal.id) }
                when {
                    stored.isFailure -> {
                        clearPending()
                        add { Entry.Note(it, "Could not tell whether the reminder was set. Check Reminders before trying again.") }
                    }
                    stored.getOrNull() != null ->
                        saved(proposal, done, Undo.Reminder(proposal.id, reminderWhen(proposal)), reminderFacts(proposal))
                    else -> add { Entry.NotApplied(it, "Not set: storage failed and nothing was scheduled. You can approve again.") }
                }
                return
            }
        when (outcome) {
            is CreateOutcome.Created -> saved(proposal, done, Undo.Reminder(proposal.id, reminderWhen(proposal)), reminderFacts(proposal))
            is CreateOutcome.Refused -> refused(ReminderText.refusal(outcome.why))
        }
    }

    /** An outcome the person stated, applied only now that they tapped. The Undo restores what was recorded before. */
    private suspend fun approveOutcome(proposal: Proposal.Outcome) {
        val target = proposal.target
        val label = target.result.name.lowercase()
        val recorded = { closed: Boolean -> "Recorded: \"${target.title}\" as $label.${if (closed) " The action is now closed." else ""}" }
        val undo = Undo.Outcome(target.itemId, target.previous ?: OutcomeResult.UNKNOWN)
        val now = port.snapshot()?.context
        if (now == null || now.day != proposal.day) {
            refused("Not recorded: the day changed since this was proposed. Ask again.")
            return
        }
        val current = runCatching { port.currentOutcome(target.itemId) }
        if (current.isSuccess && current.getOrNull() != target.previous) {
            refused("Not recorded: \"${target.title}\" changed since this was proposed. Ask again.")
            return
        }
        val result =
            try {
                port.recordOutcome(target.itemId, target.result, proposal.day)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The write may or may not have committed: look before claiming either.
                val seen = runCatching { port.currentOutcome(target.itemId) }
                when {
                    seen.isFailure -> {
                        clearPending()
                        add { Entry.Note(it, "Could not tell whether \"${target.title}\" was recorded as $label. Check the day screen.") }
                    }
                    seen.getOrNull() == target.result -> saved(proposal, recorded(false), undo)
                    else ->
                        add {
                            Entry.NotApplied(
                                it,
                                "Not recorded: storage failed and \"${target.title}\" is unchanged. You can approve again.",
                            )
                        }
                }
                return
            }
        when (result) {
            is OutcomeRecording.Recorded -> saved(proposal, recorded(result.effect.status == ActionStatus.DONE), undo)
            is OutcomeRecording.Refused -> refused("Not recorded: \"${target.title}\" can no longer take an outcome.")
        }
    }

    private suspend fun approveContext(proposal: Proposal.ProjectContext) {
        val result =
            try {
                port.changeProjectContext(proposal.projectId, proposal.field, proposal.before, proposal.after)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clearPending()
                add { Entry.Note(it, "Could not tell whether \"${proposal.projectTitle}\" was updated. Check the project.") }
                return
            }
        when (result) {
            ContextChangeResult.CHANGED -> saved(proposal, "Saved to \"${proposal.projectTitle}\": ${proposal.field.label}.")
            ContextChangeResult.STALE ->
                refused(
                    "Not saved: that field of \"${proposal.projectTitle}\" changed since this was proposed. Ask again.",
                )
            ContextChangeResult.PROJECT_NOT_FOUND -> refused("Not saved: that project no longer exists.")
        }
    }

    private suspend fun approveCommitment(proposal: Proposal.Commitment) {
        val commitment = proposal.commitment
        val result =
            try {
                port.createCommitment(commitment)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val stored = runCatching { port.commitment(commitment.id) }
                when {
                    stored.isFailure -> {
                        clearPending()
                        add {
                            Entry.Note(
                                it,
                                "Could not tell whether \"${commitment.title}\" was added. Check Fixed commitments before trying again.",
                            )
                        }
                    }
                    stored.getOrNull() != null -> saved(proposal, "Added the fixed commitment \"${commitment.title}\".")
                    else -> add { Entry.NotApplied(it, "Not added: storage failed and nothing changed. You can approve again.") }
                }
                return
            }
        when (result) {
            CreateCommitmentResult.CREATED ->
                saved(
                    proposal,
                    "Added the fixed commitment \"${commitment.title}\". Today's approved plan is unchanged.",
                )
            CreateCommitmentResult.ALREADY_EXISTS ->
                saved(
                    proposal,
                    "\"${commitment.title}\" was already added by this suggestion. Nothing more was done.",
                )
            CreateCommitmentResult.DUPLICATE -> refused("Not added: the same commitment is already recorded.")
        }
    }

    private suspend fun approveFocus(proposal: Proposal.Focus) {
        val previous =
            try {
                port.setFocus(proposal.day, proposal.minutes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                clearPending()
                add { Entry.Note(it, "Could not tell whether the focus capacity was saved. Check the day screen.") }
                return
            }
        saved(
            proposal,
            "Focus capacity for ${proposal.day} is ${proposal.minutes} min. Today's approved plan is unchanged.",
            Undo.Focus(proposal.day, previous),
        )
    }

    private fun snapshotZone(context: com.thalia.conversation.ConversationContext) = context.dayLoop.zone ?: java.time.ZoneOffset.UTC

    private suspend fun approveInbox(proposal: Proposal.InboxResolution) {
        val item = proposal.item
        val filed = "Filed \"${shortText(item.text)}\" as ${inboxWord(proposal.to)}. The original text is unchanged."
        val result =
            try {
                port.resolveInbox(item.id, proposal.to, proposal.body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val seen = runCatching { port.inboxItem(item.id) }
                when {
                    seen.isFailure -> {
                        clearPending()
                        add { Entry.Note(it, "Could not tell whether that item was filed. Check the Inbox before trying again.") }
                    }
                    seen.getOrNull()?.state == proposal.to -> saved(proposal, filed, Undo.Inbox(item.id, proposal.to))
                    seen.getOrNull()?.state == InboxState.OPEN ->
                        add { Entry.NotApplied(it, "Not filed: storage failed and nothing changed. You can approve again.") }
                    else -> refused("Not filed: that item is no longer in the Inbox.")
                }
                return
            }
        when (result) {
            ResolveInboxResult.RESOLVED -> saved(proposal, filed, Undo.Inbox(item.id, proposal.to))
            ResolveInboxResult.ALREADY_RESOLVED -> saved(proposal, "That item was already filed as ${inboxWord(proposal.to)}.")
            ResolveInboxResult.NOT_OPEN, ResolveInboxResult.NOT_FOUND -> refused("Not filed: that item is no longer in the Inbox.")
        }
    }

    /** An action made from an Inbox item: the action and the item's resolution are one transaction. It is never planned. */
    private suspend fun approveConversion(proposal: Proposal.NewAction) {
        val action = proposal.action
        val itemId = checkNotNull(proposal.inboxItemId)
        val added = "Added \"${action.title}\" from the Inbox. It is not in today's plan."
        val result =
            try {
                port.convertInbox(itemId, action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val seen = runCatching { port.inboxItem(itemId) }
                when {
                    seen.isFailure -> {
                        clearPending()
                        add { Entry.Note(it, "Could not tell whether the action was added. Check the Inbox and the Actions list.") }
                    }
                    seen.getOrNull()?.let { it.state == InboxState.ACTION && it.actionId == action.id } == true -> saved(proposal, added)
                    seen.getOrNull()?.state == InboxState.OPEN ->
                        add { Entry.NotApplied(it, "Not added: storage failed and nothing changed. You can approve again.") }
                    else -> refused("Not added: that item is no longer in the Inbox.")
                }
                return
            }
        when (result) {
            ConvertInboxResult.CONVERTED -> saved(proposal, added)
            ConvertInboxResult.ALREADY_CONVERTED ->
                saved(
                    proposal,
                    "\"${action.title}\" was already added from the Inbox. Nothing more was done.",
                )
            ConvertInboxResult.ITEM_NOT_FOUND, ConvertInboxResult.ITEM_NOT_OPEN ->
                refused("Not added: that item is no longer in the Inbox.")
            ConvertInboxResult.CONFLICT -> refused("Not added: that item already became a different action.")
            ConvertInboxResult.DUPLICATE_TITLE -> refused("Not added: an open action called \"${action.title}\" already exists.")
            ConvertInboxResult.PROJECT_NOT_FOUND -> refused("Not added: its project no longer exists.")
            ConvertInboxResult.PROJECT_CLOSED -> refused("Not added: its project is closed.")
        }
    }

    private suspend fun approveNew(proposal: Proposal.NewAction) {
        if (proposal.inboxItemId != null) return approveConversion(proposal)
        val action = proposal.action
        val result =
            try {
                port.createAction(action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val stored = runCatching { port.action(action.id) }
                when {
                    stored.isFailure -> {
                        clearPending()
                        add {
                            Entry.Note(
                                it,
                                "Could not tell whether \"${action.title}\" was added. Check the Actions list before trying again.",
                            )
                        }
                    }
                    stored.getOrNull() != null -> saved(proposal, "Added \"${action.title}\". It is not in today's plan.")
                    else -> add { Entry.NotApplied(it, "Not added: storage failed and nothing changed. You can approve again.") }
                }
                return
            }
        when (result) {
            CreateActionResult.CREATED -> saved(proposal, "Added \"${action.title}\". It is not in today's plan.")
            CreateActionResult.ALREADY_EXISTS ->
                saved(
                    proposal,
                    "\"${action.title}\" was already added by this suggestion. Nothing more was done.",
                )
            CreateActionResult.DUPLICATE_TITLE -> refused("Not added: an open action called \"${action.title}\" already exists.")
            CreateActionResult.PROJECT_NOT_FOUND -> refused("Not added: its project no longer exists.")
            CreateActionResult.PROJECT_CLOSED -> refused("Not added: its project is closed.")
        }
    }

    private suspend fun approveChange(proposal: Proposal.ActionChange) {
        val result =
            try {
                port.changeAction(proposal.before, proposal.after)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val stored = runCatching { port.action(proposal.after.id) }
                when {
                    stored.isFailure -> {
                        clearPending()
                        add { Entry.Note(it, "Could not tell whether \"${proposal.before.title}\" was changed. Check the Actions list.") }
                    }
                    stored.getOrNull() == proposal.after -> saved(proposal, "Changed \"${proposal.before.title}\".")
                    stored.getOrNull() == proposal.before ->
                        add {
                            Entry.NotApplied(it, "Not changed: storage failed and nothing changed. You can approve again.")
                        }
                    else -> refused("Not changed: \"${proposal.before.title}\" is different from when this was proposed. Ask again.")
                }
                return
            }
        when (result) {
            ChangeActionResult.CHANGED -> saved(proposal, "Changed \"${proposal.before.title}\".")
            ChangeActionResult.STALE ->
                refused(
                    "Not changed: \"${proposal.before.title}\" is different from when this was proposed. Ask again.",
                )
        }
    }

    private suspend fun approveDay(
        proposal: Proposal.DaySelection,
        pending: Pending,
    ) {
        val shown = checkNotNull(pending.preview) { "a day selection is pending without its preview" }
        val day = shown.inputs.day
        val now = port.snapshot()?.context
        // Stale if planning has moved on (the day rolled over, or the next day can no longer be named the same way).
        if (now == null || now.day != proposal.baseDay || (proposal.target == DayTarget.NEXT && now.next?.day != proposal.day)) {
            refused("Not applied: the day changed since this was proposed. Ask again.")
            return
        }
        val result =
            try {
                port.approveDay(shown, acknowledgeUnresolved = shown.needsUnresolvedAcknowledgement)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val latest = runCatching { port.planFor(day) }
                val based = shown.inputs.basedOnRevision
                when {
                    latest.isFailure -> {
                        clearPending()
                        add { Entry.Note(it, "Could not tell whether the plan was saved. Check Plan a day before trying again.") }
                    }
                    (latest.getOrNull()?.plan?.revision ?: 0) == based ->
                        add {
                            Entry.NotApplied(it, "Not saved: storage failed and today's plan is unchanged. You can approve again.")
                        }
                    latest.getOrNull()?.selected == proposal.selected ->
                        saved(proposal, savedPlanText(proposal, latest.getOrNull()?.plan?.revision, calls = false))
                    else -> refused("Not applied: today's plan changed in the meantime. Ask again.")
                }
                return
            }
        when (result) {
            null -> refused("Not applied: planning has no usable time zone.")
            is ApprovalResult.Approved -> saved(proposal, savedPlanText(proposal, result.plan.plan.revision, calls = true))
            is ApprovalResult.Refused ->
                if (result.reason == com.thalia.app.planning.data.ApprovalRefusal.CHANGED) {
                    repreview(proposal, now)
                } else {
                    refused("Not applied: ${approvalRefusalText(result.reason)}")
                }
        }
    }

    /** The data moved under an approval: the same choices, planned again. The person approves the new preview, or not. */
    private suspend fun repreview(
        proposal: Proposal.DaySelection,
        context: com.thalia.conversation.ConversationContext,
    ) {
        val fresh = port.previewDay(proposal.day, proposal.selected, proposal.pins)
        if (fresh == null || !fresh.canApprove) {
            refused("Not applied: things changed since this was proposed and it can no longer be planned. Ask again.")
            return
        }
        val pending =
            Pending(
                proposal,
                daySummary(proposal, fresh, selectionFor(proposal, context)),
                approveLabel(fresh, proposal),
                fresh,
            )
        mutable.update { it.copy(pending = pending.copy(summary = ReplyText.display(pending.summary))) }
        add { Entry.NotApplied(it, "Not applied: things changed since this was proposed. Nothing was saved.") }
        add { Entry.Note(it, "Updated proposal, not applied yet. ${pending.summary}") }
    }

    // State

    private fun saved(
        proposal: Proposal,
        text: String,
        undo: Undo? = null,
        facts: List<Pair<String, String>> = emptyList(),
    ) {
        check(mutable.value.pending?.proposal == proposal) { "approving something that is not pending" }
        clearPending()
        add { Entry.Saved(it, text, undo, facts) }
    }

    private fun refused(text: String) {
        clearPending()
        add { Entry.NotApplied(it, text) }
    }

    private fun clearPending() = mutable.update { it.copy(pending = null) }

    private fun busy(value: Boolean) = mutable.update { it.copy(busy = value) }

    private fun add(make: (Int) -> Entry) {
        val entry = displayed(make(nextEntry++))
        mutable.update { it.copy(entries = it.entries + entry) }
    }

    /** Everything Thalia says is shown without the proof-data prefix; what the person typed is shown as typed. */
    private fun displayed(entry: Entry): Entry =
        when (entry) {
            is Entry.User -> entry
            is Entry.Assistant -> entry.copy(text = ReplyText.display(entry.text))
            is Entry.Saved -> entry.copy(text = ReplyText.display(entry.text))
            is Entry.NotApplied -> entry.copy(text = ReplyText.display(entry.text))
            is Entry.Redirect -> entry.copy(text = ReplyText.display(entry.text))
            is Entry.Note -> entry.copy(text = ReplyText.display(entry.text))
        }

    private fun replace(
        id: Int,
        change: (Entry) -> Entry,
    ) = mutable.update { state -> state.copy(entries = state.entries.map { if (it.id == id) change(it) else it }) }

    /** The label names the target date for the next active day, so the person sees which day the tap saves. */
    private fun approveLabel(
        preview: PlanProposal,
        proposal: Proposal.DaySelection,
    ): String {
        val base = if (preview.needsUnresolvedAcknowledgement) "Approve with ${preview.overloadMinutes} min unresolved" else "Approve plan"
        return if (proposal.target == DayTarget.NEXT) "$base for ${dayLabel(proposal.day)}" else base
    }

    /** The selection the proposal would change: the target day's current plan, today's or the next active day's. */
    private fun selectionFor(
        proposal: Proposal.DaySelection,
        context: com.thalia.conversation.ConversationContext,
    ): Set<String> = if (proposal.target == DayTarget.NEXT) context.next?.selection.orEmpty() else context.todaySelection

    private fun savedPlanText(
        proposal: Proposal.DaySelection,
        revision: Int?,
        calls: Boolean,
    ): String =
        if (proposal.target == DayTarget.NEXT) {
            "The plan for ${dayLabel(proposal.day)} was saved as revision $revision. Today's plan is not changed." +
                if (calls) " Calls are not changed." else ""
        } else {
            "Today's plan was saved as revision $revision." + if (calls) " Calls are not changed." else ""
        }

    private companion object {
        const val MAX_TURNS = 10
        val YES = setOf("yes", "y", "approve", "approved", "ok", "okay", "yes please", "go ahead")
    }
}
