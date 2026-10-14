package com.thalia.conversation

import com.thalia.planning.ContextField
import com.thalia.planning.InboxState
import com.thalia.planning.OutcomeResult
import com.thalia.planning.Priority
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/** One function call as a provider returned it: a name and its arguments as JSON text. Nothing in it is trusted yet. */
data class RawCall(
    val name: String,
    val argsJson: String,
)

/** Which active day a day selection is for: the one planning is on now, or the active day after it. Never a date from the model. */
enum class DayTarget { TODAY, NEXT }

/**
 * The only things a conversation can ask Thalia to do. Every one, a stated fact or a suggestion, becomes the one pending
 * proposal and changes nothing until the person taps Approve (owner decision, 2026-10-12). A typed or spoken "yes" never does.
 * Anything else a model returns is refused by [OperationParser], whatever it says it means.
 */
sealed interface RequestedOperation {
    /** An outcome the user stated plainly. Staged as a pending proposal; it is recorded only when Approve is tapped. */
    data class RecordOutcome(
        val actionRef: String,
        val result: OutcomeResult,
    ) : RequestedOperation

    /** A suggestion. Creating an action never places it in today's plan. */
    data class ProposeNewAction(
        val title: String,
        val projectRef: String?,
        val priority: Priority?,
        val estimateMinutes: Int?,
        val dueDate: LocalDate?,
        val reason: String?,
        /** The Inbox item this action is made from, if any. Approval converts the item and creates the action as one step. */
        val inboxRef: String? = null,
    ) : RequestedOperation

    /** A suggestion. Only the title, priority, estimate and due date of an open action can change. */
    data class ProposeActionChange(
        val actionRef: String,
        val title: String?,
        val priority: Priority?,
        val estimateMinutes: Int?,
        val dueDate: LocalDate?,
        val clearDueDate: Boolean,
        val reason: String?,
    ) : RequestedOperation

    /**
     * A suggestion: exactly these open actions as the selection of [target], run through the deterministic planner. The model
     * names only "today" or "next"; Kotlin resolves which date that is. No other date is accepted.
     */
    data class ProposeDaySelection(
        val actionRefs: List<String>,
        val reason: String?,
        val target: DayTarget = DayTarget.TODAY,
    ) : RequestedOperation

    data class AskClarification(
        val question: String,
    ) : RequestedOperation

    /** A suggestion: one project context field set to the person's meaning in the model's words. Always needs approval. */
    data class ProposeProjectContext(
        val projectRef: String,
        val field: ContextField,
        val text: String,
        val reason: String?,
    ) : RequestedOperation

    /** A suggestion: a fixed commitment at local date-times the person gave. Kotlin resolves them in the planning zone. */
    data class ProposeFixedCommitment(
        val title: String,
        val start: LocalDateTime,
        val end: LocalDateTime,
        val reason: String?,
    ) : RequestedOperation

    /** The person stated today's focused-work capacity. Staged as a pending proposal; saved only on Approve, then with Undo. */
    data class RecordFocusCapacity(
        val minutes: Int,
    ) : RequestedOperation

    /**
     * The person plainly told Thalia what to do with one Inbox item, such as "save this as a note" or "archive this".
     * The original text is kept as it is. Staged as a pending proposal; applied only on Approve, then with Undo. [to] is NOTE, IDEA,
     * ARCHIVED or DISCARDED.
     */
    data class ResolveInboxItem(
        val itemRef: String,
        val to: InboxState,
    ) : RequestedOperation

    /** A suggestion: filing an Inbox item the model chose to file, or with a [body] it wrote. Always needs approval. */
    data class ProposeInboxResolution(
        val itemRef: String,
        val to: InboxState,
        val body: String?,
        val reason: String?,
    ) : RequestedOperation

    /**
     * A suggestion: one one-shot reminder at a local date and time, for an open listed action or, with no [actionRef], a
     * checkpoint. Slice 5 only: no recurrence, no snooze, no calendar. Needs approval.
     */
    data class ProposeReminder(
        val at: java.time.LocalDateTime,
        val actionRef: String?,
        val reason: String?,
    ) : RequestedOperation

    /** A suggestion: a focus capacity the model inferred. Needs approval. */
    data class ProposeFocusCapacity(
        val minutes: Int,
        val reason: String?,
    ) : RequestedOperation
}

sealed interface ParseResult {
    data class Parsed(
        val operation: RequestedOperation,
    ) : ParseResult

    /** The call is not one Thalia supports, or its arguments are not exactly what the declaration allows. */
    data class Unsupported(
        val name: String,
        val why: String,
    ) : ParseResult
}

/** The function names a provider is declared, and the strict reading of what comes back. */
object OperationParser {
    const val RECORD_OUTCOME = "record_outcome"
    const val PROPOSE_NEW_ACTION = "propose_new_action"
    const val PROPOSE_ACTION_CHANGE = "propose_action_change"
    const val PROPOSE_DAY_SELECTION = "propose_day_selection"
    const val ASK_CLARIFICATION = "ask_clarification"
    const val PROPOSE_PROJECT_CONTEXT = "propose_project_context"
    const val PROPOSE_FIXED_COMMITMENT = "propose_fixed_commitment"
    const val RECORD_FOCUS_CAPACITY = "record_focus_capacity"
    const val PROPOSE_FOCUS_CAPACITY = "propose_focus_capacity"
    const val PROPOSE_REMINDER = "propose_reminder"
    const val RESOLVE_INBOX_ITEM = "resolve_inbox_item"
    const val PROPOSE_INBOX_RESOLUTION = "propose_inbox_resolution"

    val NAMES =
        setOf(
            RECORD_OUTCOME,
            PROPOSE_NEW_ACTION,
            PROPOSE_ACTION_CHANGE,
            PROPOSE_DAY_SELECTION,
            ASK_CLARIFICATION,
            PROPOSE_PROJECT_CONTEXT,
            PROPOSE_FIXED_COMMITMENT,
            RECORD_FOCUS_CAPACITY,
            PROPOSE_FOCUS_CAPACITY,
            PROPOSE_REMINDER,
            RESOLVE_INBOX_ITEM,
            PROPOSE_INBOX_RESOLUTION,
        )

    private val inboxOutcomes =
        mapOf(
            "note" to InboxState.NOTE,
            "idea" to InboxState.IDEA,
            "archive" to InboxState.ARCHIVED,
            "discard" to InboxState.DISCARDED,
        )

    private val fields =
        mapOf(
            "purpose" to ContextField.PURPOSE,
            "done_when" to ContextField.DONE_WHEN,
            "constraints" to ContextField.CONSTRAINTS,
            "blocker" to ContextField.BLOCKER,
        )

    private val results =
        mapOf(
            "done" to OutcomeResult.DONE,
            "partial" to OutcomeResult.PARTIAL,
            "skipped" to OutcomeResult.SKIPPED,
            "blocked" to OutcomeResult.BLOCKED,
        )

    fun parse(call: RawCall): ParseResult {
        if (call.name !in NAMES) return ParseResult.Unsupported(call.name, "that operation is not supported")
        val args =
            try {
                Json.parseToJsonElement(call.argsJson.ifBlank { "{}" }) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return ParseResult.Unsupported(call.name, "its arguments are not a JSON object")
        val reader = Reader(args)
        val operation =
            when (call.name) {
                RECORD_OUTCOME ->
                    reader.allow("action_ref", "result").let {
                        val ref = reader.text("action_ref")
                        val result = results[reader.text("result")?.lowercase()]
                        if (ref == null || result == null) null else RequestedOperation.RecordOutcome(ref, result)
                    }
                PROPOSE_NEW_ACTION ->
                    reader.allow("title", "project_ref", "priority", "estimate_minutes", "due_date", "reason", "inbox_ref").let {
                        RequestedOperation.ProposeNewAction(
                            title = reader.text("title") ?: return@let null,
                            projectRef = reader.text("project_ref"),
                            priority = reader.priority(),
                            estimateMinutes = reader.int("estimate_minutes"),
                            dueDate = reader.date("due_date"),
                            reason = reader.text("reason"),
                            inboxRef = reader.text("inbox_ref"),
                        )
                    }
                PROPOSE_ACTION_CHANGE ->
                    reader.allow("action_ref", "title", "priority", "estimate_minutes", "due_date", "clear_due_date", "reason").let {
                        RequestedOperation.ProposeActionChange(
                            actionRef = reader.text("action_ref") ?: return@let null,
                            title = reader.text("title"),
                            priority = reader.priority(),
                            estimateMinutes = reader.int("estimate_minutes"),
                            dueDate = reader.date("due_date"),
                            clearDueDate = reader.boolean("clear_due_date") ?: false,
                            reason = reader.text("reason"),
                        )
                    }
                PROPOSE_DAY_SELECTION ->
                    reader.allow("action_refs", "reason", "day").let {
                        val refs = reader.strings("action_refs")
                        val rawDay = reader.text("day")
                        val target =
                            when (rawDay?.lowercase()) {
                                null ->
                                    if (reader.has(
                                            "day",
                                        )
                                    ) {
                                        reader
                                            .reject(
                                                "day must be today or next; no other date is supported",
                                            ).let { null }
                                    } else {
                                        DayTarget.TODAY
                                    }
                                "today" -> DayTarget.TODAY
                                "next" -> DayTarget.NEXT
                                else -> {
                                    reader.reject("day must be today or next; no other date is supported")
                                    null
                                }
                            }
                        if (refs == null ||
                            target == null
                        ) {
                            null
                        } else {
                            RequestedOperation.ProposeDaySelection(refs, reader.text("reason"), target)
                        }
                    }
                PROPOSE_PROJECT_CONTEXT ->
                    reader.allow("project_ref", "field", "text", "reason").let {
                        val ref = reader.text("project_ref")
                        val field = reader.text("field")?.let { f -> fields[f.lowercase()] }
                        val text = reader.text("text")
                        if (ref == null ||
                            field == null ||
                            text == null
                        ) {
                            null
                        } else {
                            RequestedOperation.ProposeProjectContext(ref, field, text, reader.text("reason"))
                        }
                    }
                PROPOSE_FIXED_COMMITMENT ->
                    reader.allow("title", "start", "end", "reason").let {
                        val title = reader.text("title")
                        val start = reader.dateTime("start")
                        val end = reader.dateTime("end")
                        if (title == null ||
                            start == null ||
                            end == null
                        ) {
                            null
                        } else {
                            RequestedOperation.ProposeFixedCommitment(title, start, end, reader.text("reason"))
                        }
                    }
                RECORD_FOCUS_CAPACITY ->
                    reader.allow("minutes").let { reader.int("minutes")?.let { m -> RequestedOperation.RecordFocusCapacity(m) } }
                RESOLVE_INBOX_ITEM ->
                    reader.allow("item_ref", "outcome").let {
                        val ref = reader.text("item_ref")
                        val to = reader.text("outcome")?.let { o -> inboxOutcomes[o.lowercase()] }
                        if (ref == null || to == null) null else RequestedOperation.ResolveInboxItem(ref, to)
                    }
                PROPOSE_INBOX_RESOLUTION ->
                    reader.allow("item_ref", "outcome", "body", "reason").let {
                        val ref = reader.text("item_ref")
                        val to = reader.text("outcome")?.let { o -> inboxOutcomes[o.lowercase()] }
                        if (ref == null ||
                            to == null
                        ) {
                            null
                        } else {
                            RequestedOperation.ProposeInboxResolution(ref, to, reader.text("body"), reader.text("reason"))
                        }
                    }
                PROPOSE_REMINDER ->
                    reader.allow("at", "action_ref", "reason").let {
                        reader.dateTime("at")?.let { at ->
                            RequestedOperation.ProposeReminder(at, reader.text("action_ref"), reader.text("reason"))
                        }
                    }
                PROPOSE_FOCUS_CAPACITY ->
                    reader.allow("minutes", "reason").let {
                        reader.int("minutes")?.let { m -> RequestedOperation.ProposeFocusCapacity(m, reader.text("reason")) }
                    }
                else ->
                    reader.allow("question").let {
                        reader.text("question")?.let { q -> RequestedOperation.AskClarification(q) }
                    }
            }
        return if (operation == null || reader.problem != null) {
            ParseResult.Unsupported(call.name, reader.problem ?: "a required argument is missing or not valid")
        } else {
            ParseResult.Parsed(operation)
        }
    }

    /** Reads typed values and remembers the first thing that was wrong, so a bad call is refused whole. */
    private class Reader(
        private val args: JsonObject,
    ) {
        var problem: String? = null
            private set

        /** True when [key] is given with a value, even a blank or mistyped one. */
        fun has(key: String): Boolean = element(key) != null

        fun reject(message: String) {
            problem = problem ?: message
        }

        fun allow(vararg keys: String) {
            val extra = args.keys - keys.toSet()
            if (extra.isNotEmpty()) problem = "it has an argument that is not allowed (${extra.first()})"
        }

        private fun element(key: String): JsonElement? = args[key]?.takeIf { it !is JsonNull }

        fun text(key: String): String? {
            val e = element(key) ?: return null
            val p = e as? JsonPrimitive
            if (p == null || !p.isString) {
                problem = problem ?: "$key must be text"
                return null
            }
            return p.content.trim().ifEmpty { null }
        }

        fun int(key: String): Int? {
            val e = element(key) ?: return null
            val p = e as? JsonPrimitive
            val value = if (p == null || p.isString) null else p.intOrNull
            if (value == null) problem = problem ?: "$key must be a whole number"
            return value
        }

        fun boolean(key: String): Boolean? {
            val e = element(key) ?: return null
            val value = (e as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            if (value == null) problem = problem ?: "$key must be true or false"
            return value
        }

        fun date(key: String): LocalDate? {
            val raw = text(key) ?: return null
            return try {
                LocalDate.parse(raw)
            } catch (e: DateTimeParseException) {
                problem = problem ?: "$key must be a date as YYYY-MM-DD"
                null
            }
        }

        /** A local date-time to the minute, YYYY-MM-DDTHH:MM, with no zone or offset: the zone is Thalia's to apply. */
        fun dateTime(key: String): LocalDateTime? {
            val raw = text(key) ?: return null
            return try {
                LocalDateTime.parse(raw).takeIf { it.second == 0 && it.nano == 0 && raw.length == 16 }
                    ?: throw DateTimeParseException("not to the minute", raw, 0)
            } catch (e: DateTimeParseException) {
                problem = problem ?: "$key must be a local date and time as YYYY-MM-DDTHH:MM"
                null
            }
        }

        fun priority(): Priority? {
            val raw = text("priority") ?: return null
            return Priority.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: run {
                    problem = problem ?: "priority must be must, important, normal or optional"
                    null
                }
        }

        fun strings(key: String): List<String>? {
            val array = element(key) as? JsonArray
            if (array == null) {
                problem = problem ?: "$key must be a list"
                return null
            }
            val out = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
            if (out.any { it.isNullOrEmpty() }) {
                problem = problem ?: "$key must hold only references"
                return null
            }
            return out.filterNotNull()
        }
    }
}
