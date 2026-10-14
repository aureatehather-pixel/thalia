package com.thalia.chatgptplan

import com.thalia.conversation.OperationParser
import com.thalia.conversation.ParseResult
import com.thalia.conversation.Proposal
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.RequestedOperation
import com.thalia.planning.Action
import com.thalia.planning.PlanningText

/**
 * Which fields of an action differ. The validator already treats a restated, unchanged field as no change (it compares the
 * whole action), so the only question for a "priority only" case is whether [after] really differs in something else.
 */
object ActionDiff {
    /** The fields whose value differs, in a fixed order: title, priority, estimate, due date. */
    fun changed(
        before: Action,
        after: Action,
    ): List<String> =
        listOfNotNull(
            "title".takeIf { before.title != after.title },
            "priority".takeIf { before.priority != after.priority },
            "estimate".takeIf { before.estimateMinutes != after.estimateMinutes },
            "due date".takeIf { before.dueDate != after.dueDate },
        )

    /** The differences other than priority, each as "field from to", for a reason line. Empty when only priority changed. */
    fun beyondPriority(
        before: Action,
        after: Action,
    ): String =
        listOfNotNull(
            if (before.title != after.title) "title \"${before.title}\" to \"${after.title}\"" else null,
            if (before.estimateMinutes !=
                after.estimateMinutes
            ) {
                "estimate ${before.estimateMinutes} to ${after.estimateMinutes} min"
            } else {
                null
            },
            if (before.dueDate != after.dueDate) "due date ${before.dueDate ?: "none"} to ${after.dueDate ?: "none"}" else null,
        ).joinToString(", ")

    /** Fields the call carried whose value equals what the action already has: said again, changed nothing. */
    fun restated(
        op: RequestedOperation.ProposeActionChange,
        before: Action,
    ): List<String> =
        listOfNotNull(
            "title".takeIf { op.title != null && PlanningText.clean(op.title!!) == before.title },
            "priority".takeIf { op.priority != null && op.priority == before.priority },
            "estimate".takeIf { op.estimateMinutes != null && op.estimateMinutes == before.estimateMinutes },
            "due date".takeIf { op.dueDate != null && op.dueDate == before.dueDate },
        )
}

/**
 * The whole of one case as a person reads it: the verdict, the reasons, what Kotlin decided, the reply text in full and every
 * call with its arguments in full. Synthetic content only. [Redactor] still runs when a line is written, so no credential can
 * reach the log, and the report holds none to begin with (it is built from the reply, never from a request header).
 */
object ProposalReport {
    fun of(result: ProposalResult): String =
        buildString {
            appendLine("${result.caseId}: ${result.score.verdict}")
            appendLine("why: ${result.score.reasons.joinToString("; ")}")
            appendLine("kotlin: ${result.score.decisions.joinToString(", ").ifEmpty { "(no calls)" }}")
            if (result.score.containment.isNotEmpty()) appendLine("containment: ${result.score.containment.joinToString("; ")}")
            when (val response = result.response) {
                is ProviderResponse.Failed -> appendLine("request failed: ${response.failure}")
                is ProviderResponse.Reply -> {
                    appendLine("reply text: ${response.text?.takeIf { it.isNotBlank() } ?: "(none)"}")
                    if (response.calls.isEmpty()) appendLine("calls: (none)")
                    response.calls.forEachIndexed { i, call ->
                        appendLine("call ${i + 1}: ${call.name} ${call.argsJson}")
                        when (val parsed = OperationParser.parse(call)) {
                            is ParseResult.Unsupported -> appendLine("  parser: unsupported (${parsed.name})")
                            is ParseResult.Parsed -> appendLine("  parser: ${parsed.operation}")
                        }
                    }
                    result.decisions.filterIsInstance<com.thalia.conversation.Decision.Ask>().forEach {
                        appendLine(
                            "  Kotlin asked: ${it.question}",
                        )
                    }
                    result.decisions.filterIsInstance<com.thalia.conversation.Decision.Propose>().forEach { d ->
                        val change = d.proposal as? Proposal.ActionChange ?: return@forEach
                        appendLine(
                            "  action change on \"${change.before.title}\": differs in " +
                                (ActionDiff.changed(change.before, change.after).joinToString().ifEmpty { "nothing" }),
                        )
                    }
                }
            }
        }.trimEnd()
}
