package com.thalia.app.conversation

import com.thalia.conversation.Proposal
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the conversation reads, apart from the screen so it is tested. Kotlin's own lines (status, pending, saved, refused) are
 * the status; the model's words are shown after them, quieter and labelled, and never as a status. Labels follow the design
 * reference: the model's plain reply is shown as "Thalia" and its reasons as "Thalia's explanation".
 */
internal enum class RowStyle { YOU, REPLY, EXPLANATION, PROPOSED, SAVED, NOT_APPLIED, STATUS, NOTE, DAY_SCREEN }

internal enum class NextStep(
    val label: String?,
) {
    /** Open AI settings. */
    AI_SETTINGS("AI settings"),

    /** Open the planning screen, which holds Reminders, Inbox and the day. */
    PLANNING("Open planning"),

    /** Nothing to open: the proposal is still pending, so the person can tap Approve again. */
    APPROVE_AGAIN(null),

    /** Nothing to open: the person can say it differently. */
    REPHRASE(null),
}

/** One row of the transcript. [status] is Kotlin's line that belongs with a plain reply, shown above it. */
internal data class RowView(
    val style: RowStyle,
    val label: String,
    val lines: List<String>,
    /** The model's words beside a decision, for the secondary card. */
    val explanation: String? = null,
    val status: String? = null,
    val nextStep: NextStep? = null,
    val nextStepHint: String? = null,
    val undoEntryId: Int? = null,
    val undoDescription: String? = null,
    val entryIds: List<Int> = emptyList(),
    /** A card title and labelled facts (a reminder that was set). Then [lines] is empty and [spoken] says it all for a screen reader. */
    val title: String? = null,
    val facts: List<Pair<String, String>> = emptyList(),
    val spoken: String? = null,
)

internal object Presentation {
    const val LABEL_REPLY = "Thalia"
    const val LABEL_EXPLANATION = "Thalia's explanation"

    private const val PROPOSED = "Proposed, not applied yet. "
    private const val UPDATED = "Updated proposal, not applied yet. "
    private const val EXPLAIN_MARK = " ${ReplyText.AI_EXPLANATION}"

    /** The transcript as rows: a plain reply and its "No planning changes were applied." status become one row. */
    fun rows(entries: List<Entry>): List<RowView> {
        val out = mutableListOf<RowView>()
        var i = 0
        while (i < entries.size) {
            val entry = entries[i]
            val next = entries.getOrNull(i + 1)
            if (entry is Entry.Assistant &&
                entry.text.startsWith(ReplyText.AI_RESPONSE) &&
                next is Entry.Note &&
                next.text == ReplyText.NO_CHANGES
            ) {
                out +=
                    RowView(
                        RowStyle.REPLY,
                        LABEL_REPLY,
                        lines(entry.text.removePrefix(ReplyText.AI_RESPONSE)),
                        status = ReplyText.NO_CHANGES,
                        entryIds = listOf(entry.id, next.id),
                    )
                i += 2
            } else {
                out += row(entry)
                i += 1
            }
        }
        return out
    }

    fun row(entry: Entry): RowView =
        when (entry) {
            is Entry.User -> RowView(RowStyle.YOU, "You", listOf(entry.text), entryIds = listOf(entry.id))
            is Entry.Assistant ->
                when {
                    entry.text.startsWith(ReplyText.AI_RESPONSE) ->
                        RowView(
                            RowStyle.REPLY,
                            LABEL_REPLY,
                            lines(entry.text.removePrefix(ReplyText.AI_RESPONSE)),
                            entryIds = listOf(entry.id),
                        )
                    entry.text.startsWith(ReplyText.AI_EXPLANATION) ->
                        RowView(
                            RowStyle.EXPLANATION,
                            LABEL_EXPLANATION,
                            lines(entry.text.removePrefix(ReplyText.AI_EXPLANATION)),
                            entryIds = listOf(entry.id),
                        )
                    // A question Kotlin asked, or a reference-free question the model wrote: Thalia asks.
                    else -> RowView(RowStyle.REPLY, LABEL_REPLY, lines(entry.text), entryIds = listOf(entry.id))
                }
            is Entry.Saved ->
                RowView(
                    RowStyle.SAVED,
                    "Saved",
                    if (entry.facts.isEmpty()) lines(entry.text) else emptyList(),
                    title = if (entry.facts.isEmpty()) null else "Reminder set",
                    facts = entry.facts,
                    spoken = entry.text,
                    undoEntryId = entry.id.takeIf { entry.undo != null },
                    undoDescription = entry.undo?.let { "Undo: ${undoWhat(it)}" },
                    entryIds = listOf(entry.id),
                )
            is Entry.NotApplied -> {
                val step = nextStepFor(entry.text)
                RowView(
                    RowStyle.NOT_APPLIED,
                    "Not applied",
                    lines(entry.text),
                    nextStep = step,
                    nextStepHint = hintFor(step),
                    entryIds = listOf(entry.id),
                )
            }
            is Entry.Redirect -> RowView(RowStyle.DAY_SCREEN, "Day screen", lines(entry.text), entryIds = listOf(entry.id))
            is Entry.Note -> note(entry)
        }

    private fun note(entry: Entry.Note): RowView {
        val text = entry.text
        val prefix = listOf(PROPOSED, UPDATED).firstOrNull { text.startsWith(it) }
        return when {
            prefix != null -> {
                val body = text.removePrefix(prefix)
                val kotlin = body.substringBefore(EXPLAIN_MARK)
                val explanation = body.substringAfter(EXPLAIN_MARK, "").takeIf { EXPLAIN_MARK in body }
                RowView(
                    RowStyle.PROPOSED,
                    if (prefix == UPDATED) "Proposal updated, not applied" else "Proposed, not applied",
                    listOf(headline(kotlin)),
                    explanation = explanation,
                    entryIds = listOf(entry.id),
                )
            }
            text == ReplyText.NO_CHANGES -> RowView(RowStyle.STATUS, "Status", listOf(text), entryIds = listOf(entry.id))
            text.startsWith("Dismissed") -> RowView(RowStyle.NOTE, "Dismissed", lines(text), entryIds = listOf(entry.id))
            else -> RowView(RowStyle.NOTE, "Note", lines(text), entryIds = listOf(entry.id))
        }
    }

    /** The first sentence of a proposal summary, so the transcript records what was proposed without repeating the whole card. */
    fun headline(summary: String): String = summary.trim().substringBefore(". ").let { if (it.endsWith(".")) it else "$it." }

    /** A text broken into readable lines at sentence ends, so a long note is not one run. */
    fun lines(text: String): List<String> =
        text
            .trim()
            .split(Regex("(?<=[.!?])\\s+(?=[A-Z\"])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** What a refusal or failure leaves the person to do, decided from fixed phrases Kotlin itself wrote. */
    fun nextStepFor(text: String): NextStep =
        when {
            "AI settings" in text ||
                text.startsWith(
                    "AI is off",
                ) ||
                text.startsWith("AI has no key") ||
                text.startsWith("AI is not set up") ->
                NextStep.AI_SETTINGS
            "approve again" in text -> NextStep.APPROVE_AGAIN
            listOf("Reminders", "planning screen", "Check the Inbox", "Check the day screen").any { it in text } -> NextStep.PLANNING
            else -> NextStep.REPHRASE
        }

    private fun hintFor(step: NextStep): String =
        when (step) {
            NextStep.AI_SETTINGS -> "Nothing was sent or changed. Turn AI on in settings, or carry on by hand."
            NextStep.PLANNING -> "Nothing was changed here. You can check it on the planning screen."
            NextStep.APPROVE_AGAIN -> "The suggestion is still waiting. Tap Approve to try again, or Dismiss."
            NextStep.REPHRASE -> "Nothing was applied. Ask again in different words, or do it by hand on the planning screen."
        }

    private fun undoWhat(undo: Undo): String =
        when (undo) {
            is Undo.Reminder -> "cancel this reminder"
            is Undo.Outcome -> "put the outcome back"
            is Undo.Inbox -> "put the item back in the Inbox"
            is Undo.Focus -> "put the focus capacity back"
        }

    // The waiting proposal, as a card.

    internal data class Field(
        val label: String,
        val value: String,
        val detail: String? = null,
    )

    internal data class PendingCard(
        val headline: String,
        val about: Field?,
        /** Date and time, side by side. */
        val when_: List<Field>,
        val lines: List<String>,
        val approveLabel: String,
        /** One text for a screen reader: everything on the card, in order. */
        val spoken: String,
    )

    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    fun pendingCard(pending: Pending): PendingCard {
        val proposal = pending.proposal
        return if (proposal is Proposal.Reminder) {
            // The scheduling zone is kept in the proposal and used to place the reminder; it is not shown on the everyday card.
            val at = Instant.ofEpochMilli(proposal.dueAtMillis).atZone(proposal.zone)
            val about =
                Field(
                    "About",
                    proposal.actionTitle?.let { ReplyText.display(it) } ?: "Generic check-in",
                    if (proposal.actionTitle == null) "It carries no description of what it is for." else null,
                )
            val whenFields = listOf(Field("Date", at.format(day)), Field("Time", at.format(clock)))
            PendingCard(
                "One-time reminder",
                about,
                whenFields,
                emptyList(),
                pending.approveLabel,
                "Waiting for your approval. Not set yet. One-time reminder. About ${about.value}. ${about.detail.orEmpty()} " +
                    "Date ${whenFields[0].value}. Time ${whenFields[1].value}.",
            )
        } else {
            val all = lines(ReplyText.display(pending.summary))
            PendingCard(
                all.firstOrNull() ?: pending.summary,
                null,
                emptyList(),
                all.drop(1),
                pending.approveLabel,
                "Waiting for your approval. ${all.joinToString(" ")}",
            )
        }
    }
}
