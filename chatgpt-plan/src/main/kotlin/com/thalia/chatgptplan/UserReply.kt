package com.thalia.chatgptplan

import com.thalia.conversation.ConversationContext
import com.thalia.conversation.Decision
import com.thalia.conversation.Proposal
import com.thalia.conversation.RefusalCode
import com.thalia.planning.InboxState
import com.thalia.planning.OutcomeResult
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the person reads, and a candidate for what would be spoken. Both are composed by the app, never taken from the model. */
data class UserReply(
    val shown: String,
    val spoken: String,
)

/**
 * A candidate wording for each decision kind (see the proof document, "User-facing replies"). The model returned tool calls only
 * in all ten B2 cases, so the app writes the reply from what Kotlin decided. Rules:
 * - titles, never references (`a2`, `i1`); a reference the model left in a question is replaced by the title it points at;
 * - nothing is said as done unless it is applied; a proposal says it waits for a tap;
 * - one or two short sentences, with the result first;
 * - a capture's text is never read out in full (it is untrusted), only a short opening.
 *
 * Not a speech architecture: [spoken] is only the shown text with the screen words changed, to test whether app-composed text is
 * enough to hear. Whether a stated fact is applied at once with Undo ([factApplied] true, the product today) or waits for a tap
 * (false, the stated approval rule and B2) is an open decision, so both wordings exist.
 */
object UserReplyComposer {
    private const val TAP = "Tap to approve."
    private const val TAP_SPOKEN = "It is on your screen to approve."
    private val refToken = Regex("""\b[aip]\d+\b""")
    private val quotedAfterRef = Regex("""\b([aip]\d+)\b,?\s*[“"]([^”"]+)[”"]""")
    private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val timeFormat = DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH)

    fun compose(
        decisions: List<Decision>,
        context: ConversationContext,
        modelText: String? = null,
        factApplied: Boolean = false,
    ): UserReply {
        val parts = decisions.map { sentence(it, context, factApplied) }
        val shown =
            when {
                parts.isNotEmpty() -> parts.joinToString(" ")
                !modelText.isNullOrBlank() -> titled(modelText.trim(), context)
                else -> "I did not get an answer I can use, so nothing changed."
            }
        return UserReply(shown, shown.replace("\"", "").replace(TAP, TAP_SPOKEN))
    }

    private fun sentence(
        decision: Decision,
        context: ConversationContext,
        factApplied: Boolean,
    ): String =
        when (decision) {
            is Decision.Fact -> {
                val what = "\"${decision.target.title}\" ${outcomeWords(decision.target.result)}"
                if (factApplied) "Recorded: $what. Undo is on your screen." else "You said $what. Record it? $TAP"
            }
            is Decision.InboxFact -> {
                val item = "\"${snippet(decision.item.text)}\""
                if (factApplied) {
                    "${inboxDone(decision.to)} $item. Undo is on your screen."
                } else {
                    "${inboxAsk(decision.to)} $item? $TAP"
                }
            }
            is Decision.FocusFact ->
                if (factApplied) {
                    "Focus time for today is ${decision.minutes} minutes. Undo is on your screen."
                } else {
                    "Set today's focus time to ${decision.minutes} minutes? $TAP"
                }
            is Decision.Ask -> titled(decision.question, context)
            is Decision.Refuse -> refusal(decision.code)
            is Decision.Propose -> proposal(decision.proposal, context)
        }

    private fun proposal(
        proposal: Proposal,
        context: ConversationContext,
    ): String =
        when (proposal) {
            is Proposal.NewAction ->
                with(proposal.action) {
                    "Add \"$title\", ${priority.name.lowercase()}, $estimateMinutes minutes" +
                        (dueDate?.let { ", due ${it.format(dayFormat)}" } ?: "") +
                        (proposal.projectTitle?.let { ", in \"$it\"" } ?: "") + "? It will not go in today's plan. $TAP"
                }
            is Proposal.ActionChange -> {
                val b = proposal.before
                val a = proposal.after
                val changes =
                    listOfNotNull(
                        if (a.title != b.title) "the title to \"${a.title}\"" else null,
                        if (a.priority != b.priority) "the priority to ${a.priority.name.lowercase()}" else null,
                        if (a.estimateMinutes != b.estimateMinutes) "the estimate to ${a.estimateMinutes} minutes" else null,
                        if (a.dueDate != b.dueDate) a.dueDate?.let { "the due date to ${it.format(dayFormat)}" } ?: "no due date" else null,
                    )
                "Change ${changes.joinToString(" and ")} on \"${b.title}\"? $TAP"
            }
            is Proposal.DaySelection -> {
                val titles = proposal.titles.values.toList()
                "Plan today with ${titles.size} ${if (titles.size == 1) "action" else "actions"}" +
                    (if (titles.isEmpty()) "" else ": " + titles.joinToString { "\"$it\"" }) + ". The planner checks it first. $TAP"
            }
            is Proposal.Commitment -> {
                val zone = proposal.commitment.zone
                val start = proposal.commitment.start.atZone(zone)
                val end = proposal.commitment.end.atZone(zone)
                "Add \"${proposal.commitment.title}\" on ${start.format(
                    dayFormat,
                )}, ${start.format(timeFormat)} to ${end.format(timeFormat)}? $TAP"
            }
            is Proposal.ProjectContext -> "Note the ${proposal.field.label} of \"${proposal.projectTitle}\" as \"${proposal.after}\"? $TAP"
            is Proposal.Focus -> "Set today's focus time to ${proposal.minutes} minutes? $TAP"
            is Proposal.Reminder -> "Set a one-time reminder? $TAP"
            is Proposal.Outcome -> "Record \"${proposal.target.title}\" ${outcomeWords(proposal.target.result)}? $TAP"
            is Proposal.InboxResolution ->
                "${inboxAsk(proposal.to)} \"${snippet(proposal.item.text)}\"? $TAP"
        }

    /** A refusal says what was not done and why in the person's terms. The validator's own messages can carry references. */
    private fun refusal(code: RefusalCode): String =
        when (code) {
            RefusalCode.UNSUPPORTED -> "I cannot do that from here, so nothing changed."
            RefusalCode.UNKNOWN_REFERENCE -> "I could not tell which item you meant, so nothing changed."
            RefusalCode.INVALID_VALUE -> "One of those values does not work, so nothing changed."
            RefusalCode.NOT_OPEN -> "That one is already closed, so it stays as it is."
            RefusalCode.NOT_ON_TODAY_PLAN -> "That is not in today's plan, so I did not record it."
            RefusalCode.ALREADY_RECORDED -> "That already has a result recorded, so nothing changed."
            RefusalCode.PROJECT_CLOSED -> "That project is closed, so nothing changed."
            RefusalCode.DUPLICATE_ACTION -> "You already have an open action like that, so I did not add another."
            RefusalCode.NOTHING_TO_CHANGE -> "That would not change anything."
            RefusalCode.NO_WINDOW -> "Set your planning window first, then I can plan the day."
            RefusalCode.EMPTY_SELECTION -> "That would leave today empty, so nothing changed."
            RefusalCode.PLAN_LOCKED -> "Today's plan already has results recorded, so it cannot be replaced."
            RefusalCode.NO_NEXT_DAY -> "The next day cannot be named yet. Settle the earlier working day first, so nothing changed."
            RefusalCode.DUPLICATE_IN_REPLY -> "I proposed the same thing twice, so I kept one."
            RefusalCode.ONE_PROPOSAL_AT_A_TIME -> "One suggestion at a time. Ask again for the next one."
            RefusalCode.INBOX_REVIEW_ONLY -> "While we go through the Inbox I only file captures or turn them into actions."
        }

    /** Replaces a reference the model wrote in a question or text with the title it points at; an unknown one becomes "that item". */
    internal fun titled(
        text: String,
        context: ConversationContext,
    ): String {
        fun titleOf(ref: String): String? =
            context.action(ref)?.action?.title
                ?: context.inboxItem(ref)?.let { snippet(it.item.text) }
                ?: context.project(ref)?.project?.title
        // The model often writes the reference and then the title: `a2, “Title,”`. Say the title once.
        val once =
            quotedAfterRef.replace(text) { m ->
                val title = titleOf(m.groupValues[1])
                val quoted = m.groupValues[2].trimEnd(',', ' ')
                if (title != null && quoted == title) "\"$title\"" else m.value
            }
        return refToken.replace(once) { m -> titleOf(m.value)?.let { "\"$it\"" } ?: "that item" }
    }

    private fun snippet(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length > 40) flat.take(40).trimEnd() + "..." else flat
    }

    private fun outcomeWords(result: OutcomeResult) =
        when (result) {
            OutcomeResult.DONE -> "is done"
            OutcomeResult.PARTIAL -> "is partly done"
            OutcomeResult.SKIPPED -> "was skipped"
            OutcomeResult.BLOCKED -> "is blocked"
            OutcomeResult.UNKNOWN -> "has an unknown result"
        }

    private fun inboxAsk(to: InboxState) =
        when (to) {
            InboxState.NOTE -> "Save as a note"
            InboxState.IDEA -> "Keep as an idea"
            InboxState.ARCHIVED -> "Archive"
            InboxState.DISCARDED -> "Discard"
            else -> "File"
        }

    private fun inboxDone(to: InboxState) =
        when (to) {
            InboxState.NOTE -> "Saved as a note:"
            InboxState.IDEA -> "Kept as an idea:"
            InboxState.ARCHIVED -> "Archived:"
            InboxState.DISCARDED -> "Discarded:"
            else -> "Filed:"
        }
}
