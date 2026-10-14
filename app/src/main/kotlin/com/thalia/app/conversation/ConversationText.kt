package com.thalia.app.conversation

import com.thalia.app.planning.data.ApprovalRefusal
import com.thalia.conversation.DayTarget
import com.thalia.conversation.Proposal
import com.thalia.conversation.ProviderFailure
import com.thalia.planning.Action
import com.thalia.planning.InboxState
import com.thalia.planning.ItemKind
import com.thalia.planning.LeftOutReason
import com.thalia.planning.PlanErrorKind
import com.thalia.planning.PlanProposal
import com.thalia.planning.PlanWarningKind

/**
 * The words of the conversation, apart from the screen and the session so they are tested.
 *
 * What a failed request says. [budgetNotes] is true for the Gemini route, which keeps a monthly budget and holds an uncertain
 * request against it, so those texts say so. The ChatGPT-plan route keeps no such budget and its metering is not established, so it
 * passes false and these texts make no claim about one.
 */
internal fun providerFailureText(
    failure: ProviderFailure,
    budgetNotes: Boolean = true,
): String =
    when (failure) {
        ProviderFailure.UNAVAILABLE ->
            "AI is not set up on this build, so I cannot answer. Your plans, outcomes and manual controls work as usual."
        ProviderFailure.CONSENT_NEEDED ->
            "AI is off. Nothing was sent. Turn it on in AI settings, or carry on by hand: planning, outcomes and the Inbox work as usual."
        ProviderFailure.KEY_MISSING ->
            "AI has no key saved. Nothing was sent. Add one in AI settings, or carry on by hand."
        ProviderFailure.BUDGET_EXHAUSTED ->
            "This month's AI budget is used up (see AI settings). Nothing was sent. Planning by hand is unaffected."
        ProviderFailure.TOO_LARGE ->
            "Your planning snapshot is too large to send in one request, so nothing was sent. Planning by hand is unaffected."
        ProviderFailure.BUSY -> "Another AI request is still running. Nothing was sent."
        ProviderFailure.LEDGER_UNWRITABLE ->
            "The AI budget record could not be written, so nothing was sent. Planning by hand is unaffected."
        ProviderFailure.REJECTED -> "The AI service refused the request as malformed. Nothing was changed."
        ProviderFailure.NETWORK ->
            "I could not reach the AI service, or it did not answer in time. Nothing was changed." +
                (if (budgetNotes) " The request may still count against this month's budget." else "")
        ProviderFailure.AUTH -> "The AI service did not accept the key. Nothing was changed."
        ProviderFailure.RATE_LIMITED -> "The AI service is limiting requests right now. Nothing was changed. Try again later."
        ProviderFailure.SERVER -> "The AI service had a problem. Nothing was changed."
        ProviderFailure.NOT_FOUND ->
            "The AI service could not find the model or address Thalia asked for. Nothing was changed. This is a setup problem, not a busy service."
        ProviderFailure.REQUEST_TOO_LARGE ->
            "The AI service says the request was too large. It was sent" +
                (if (budgetNotes) " and may count against this month's budget" else "") +
                ". Nothing was changed."
        ProviderFailure.UNEXPECTED_STATUS ->
            "The AI service answered with an error Thalia does not recognise. Nothing was changed." +
                (if (budgetNotes) " The request may still count against this month's budget." else "")
        ProviderFailure.BLOCKED -> "The AI service did not answer that. Nothing was changed."
        ProviderFailure.UNREADABLE -> "The AI service's reply could not be read. Nothing was changed."
        ProviderFailure.SIGN_IN_NEEDED ->
            "Thalia is not signed in to your ChatGPT plan, or the sign-in is no longer accepted. Nothing was changed. " +
                "Planning by hand is unaffected."
        ProviderFailure.MODEL_NEEDED -> "No ChatGPT model is chosen yet, so nothing was sent. Nothing was changed."
        ProviderFailure.USAGE_LIMIT ->
            "ChatGPT reported that your plan's usage limit was reached. Nothing was changed and nothing else was tried. " +
                "Planning by hand is unaffected."
        ProviderFailure.USAGE_UNAVAILABLE ->
            "ChatGPT reported that plan usage is unavailable right now, and did not say why. Nothing was changed."
        ProviderFailure.BILLING_NEEDED ->
            "The AI account reports no billing or credit is available, so the request was not processed. Nothing was changed. " +
                "Check the account's billing page, then try again. Planning by hand is unaffected."
    }

internal fun approvalRefusalText(reason: ApprovalRefusal): String =
    when (reason) {
        ApprovalRefusal.CHANGED -> "The plan's data changed after this was proposed."
        ApprovalRefusal.INVALID -> "The planner found a problem with it."
        ApprovalRefusal.UNRESOLVED_NOT_ACKNOWLEDGED -> "It has unresolved minutes that were not acknowledged."
        ApprovalRefusal.REST_DAY_NOT_ACKNOWLEDGED -> "An empty day needs its own acknowledgement in Plan a day."
        ApprovalRefusal.MALFORMED -> "It did not pass the plan checks."
        ApprovalRefusal.OUTCOMES_RECORDED -> "Today's plan already has recorded outcomes, so it cannot be replaced."
    }

private fun Action.describe(): String =
    buildString {
        append("\"$title\", ${priority.name.lowercase()}, about $estimateMinutes min")
        dueDate?.let { append(", due $it") }
    }

internal fun proposalSummary(proposal: Proposal): String =
    when (proposal) {
        is Proposal.NewAction ->
            if (proposal.inboxItemId !=
                null
            ) {
                inboxActionSummary(proposal)
            } else {
                "Add the action ${proposal.action.describe()}${proposal.projectTitle?.let { " to \"$it\"" } ?: ""}. " +
                    "It will not be added to today's plan."
            }
        is Proposal.ActionChange -> {
            val a = proposal.after
            val b = proposal.before
            val changes =
                listOfNotNull(
                    a.title.takeIf { it != b.title }?.let { "title \"${b.title}\" to \"$it\"" },
                    a.priority.takeIf { it != b.priority }?.let { "priority ${b.priority.name.lowercase()} to ${it.name.lowercase()}" },
                    a.estimateMinutes.takeIf { it != b.estimateMinutes }?.let { "estimate ${b.estimateMinutes} to $it min" },
                    if (a.dueDate != b.dueDate) "due date ${b.dueDate ?: "none"} to ${a.dueDate ?: "none"}" else null,
                )
            "Change \"${b.title}\": ${changes.joinToString(", ")}."
        }
        is Proposal.DaySelection ->
            if (proposal.target == DayTarget.NEXT) "Plan the next active day, ${dayLabel(proposal.day)}." else "Replace today's selection."
        is Proposal.ProjectContext ->
            "Set \"${proposal.projectTitle}\" ${proposal.field.label} " +
                "${proposal.before?.let { "from \"$it\" " } ?: "(unknown until now) "}to \"${proposal.after}\"."
        is Proposal.Commitment -> "Add the fixed commitment \"${proposal.commitment.title}\"."
        is Proposal.Focus -> "Set focus capacity to ${proposal.minutes} min."
        is Proposal.Reminder -> reminderSummary(proposal)
        is Proposal.Outcome -> "Record \"${proposal.target.title}\" as ${proposal.target.result.name.lowercase()}."
        is Proposal.InboxResolution ->
            "${inboxVerb(proposal.to)} the Inbox item \"${shortText(proposal.item.text)}\"." +
                (proposal.body?.let { " The wording would be: \"$it\"." } ?: "") +
                " The original text stays as captured."
    }

/** The action an Inbox item would become, each value labelled as suggested or as a Thalia default, for review before approval. */
private fun inboxActionSummary(proposal: Proposal.NewAction): String {
    val a = proposal.action

    fun label(
        field: String,
        value: String,
    ) = "$value (${if (field in proposal.suggested) "suggested" else "default"})"
    return "Make an action from the Inbox item. Title: \"${a.title}\" (suggested). " +
        "Priority: ${label("priority", a.priority.name.lowercase())}. " +
        "Estimate: ${label("estimate", "${a.estimateMinutes} min")}. " +
        "Project: ${label("project", proposal.projectTitle ?: "none")}. " +
        "Due: ${label("due", a.dueDate?.toString() ?: "none")}. " +
        "It leaves the Inbox and is not added to today's plan."
}

/** One line of a capture for a sentence, never more than a short run of it. */
internal fun shortText(text: String): String {
    val flat = text.replace(Regex("\\s*\\R\\s*"), " ").trim()
    return if (flat.length > 80) flat.take(80) + "..." else flat
}

internal fun inboxVerb(to: InboxState): String =
    when (to) {
        InboxState.NOTE -> "Save as a note"
        InboxState.IDEA -> "Keep as an idea"
        InboxState.ARCHIVED -> "Archive"
        InboxState.DISCARDED -> "Discard"
        else -> "File"
    }

internal fun inboxWord(to: InboxState): String =
    when (to) {
        InboxState.NOTE -> "a note"
        InboxState.IDEA -> "an idea"
        InboxState.ARCHIVED -> "archived"
        InboxState.DISCARDED -> "discarded"
        else -> "filed"
    }

internal fun inboxApproveLabel(to: InboxState): String =
    when (to) {
        InboxState.NOTE -> "Save as note"
        InboxState.IDEA -> "Keep as idea"
        InboxState.ARCHIVED -> "Archive"
        InboxState.DISCARDED -> "Discard"
        else -> "File"
    }

/** A commitment's resolved times in [zone], with the note about a time a person could mean otherwise. ADR 0011. */
internal fun commitmentSummary(
    proposal: Proposal.Commitment,
    zone: java.time.ZoneId,
): String =
    "Add the fixed commitment \"${proposal.commitment.title}\", " +
        "${com.thalia.app.planning.CommitmentText.span(proposal.commitment, zone)} (${zone.id}). " +
        "Today's approved plan is not changed; new previews count it." +
        (proposal.note?.let { " $it" } ?: "")

/** What approving a day selection would do, from the deterministic proposal. */
internal fun daySummary(
    proposal: Proposal.DaySelection,
    preview: PlanProposal,
    currentSelection: Set<String>,
): String {
    val titles = preview.actions.associate { it.action.id to it.action.title }
    val next = proposal.target == DayTarget.NEXT

    fun name(id: String) = titles[id] ?: proposal.titles[id] ?: "an action"
    val lines =
        preview.items.filter { it.kind != ItemKind.LEFT_OUT }.sortedWith(compareBy({ it.kind.ordinal }, { it.position })).map {
            "${name(it.actionId)}, ${it.minutes} min${it.start?.let { s -> " at $s" } ?: ""}"
        }
    val leftOut =
        preview.items.filter { it.kind == ItemKind.LEFT_OUT }.map {
            "${name(it.actionId)} (${
                when (it.reason) {
                    LeftOutReason.PROJECT_LIMIT -> "project limit"
                    LeftOutReason.OVER_TARGET -> if (next) "over that day's target" else "over today's target"
                    LeftOutReason.PROJECT_NOT_ACTIVE -> "its project is not active"
                    null -> "left out"
                }
            })"
        }
    val removed = (currentSelection - proposal.selected).map { name(it) }
    val added = (proposal.selected - currentSelection).map { name(it) }
    return buildString {
        if (next) {
            append("The plan for ${dayLabel(proposal.day)}, the next active day, would be: ${lines.joinToString("; ")}. ")
            append("Today's plan is not changed. ")
        } else {
            append("Today's plan would be: ${lines.joinToString("; ")}. ")
        }
        append("${preview.targetMinutes} of ${preview.freeMinutes} free minutes are targeted.")
        if (currentSelection.isNotEmpty()) {
            append(
                " Added: ${added.ifEmpty {
                    listOf(
                        "nothing",
                    )
                }.joinToString()}. Removed: ${removed.ifEmpty { listOf("nothing") }.joinToString()}.",
            )
        }
        if (leftOut.isNotEmpty()) append(" The planner leaves out: ${leftOut.joinToString()}.")
        if (preview.overloadMinutes > 0) append(" ${preview.overloadMinutes} minutes of Must work do not fit.")
        preview.warnings
            .filter {
                it.kind == PlanWarningKind.MUST_USES_BUFFER
            }.forEach { append(" Must work uses ${it.minutes} min of the buffer.") }
    }
}

internal fun planErrorText(kinds: List<PlanErrorKind>): String = kinds.joinToString { it.name.lowercase().replace('_', ' ') }

/**
 * Explicit requests to open or close the working day. Thalia cannot do either from a conversation: the day screen is where the
 * working day is opened and closed, with its own confirmations. These are recognised here, before any provider is reached, so
 * the answer is the same with AI off, costs nothing and cannot be turned into a claim of completion.
 */
internal object DayRequests {
    enum class Kind(
        val word: String,
    ) {
        OPEN("Opening"),
        CLOSE("Closing"),
    }

    private val open = Regex("""(open( up)?|(start|begin) (my|the|today's|this) working)""")
    private val close = Regex("""(close( out)?|end|finish|wrap up)""")
    private val tail = Regex("""( my| the| today's| this)?( working)? day( now| please)?""")
    private val lead = Regex("""(please |can you |could you |let's |lets |i want to |i'd like to |i need to |go ahead and )?""")
    private val noun = Regex("""( my| the| today's| this)? (working )?day( now| please)?""")

    /** Null for anything that is not plainly and only such a request, such as "Help me start my day", which goes to the model. */
    fun classify(message: String): Kind? {
        val text =
            message
                .lowercase()
                .replace(Regex("""[.!?,]"""), "")
                .trim()
                .replace(Regex("""\s+"""), " ")
        if (text.isEmpty() || text.split(' ').size > 8) return null

        fun matches(verb: Regex) = Regex("${lead.pattern}${verb.pattern}${noun.pattern}").matches(text)
        return when {
            matches(close) -> Kind.CLOSE
            matches(open) -> Kind.OPEN
            else -> null
        }
    }

    /** Whether a function name a model made up is an attempt to open or close the working day. Supported names never are. */
    fun isDayStateCall(name: String): Boolean {
        if (name in com.thalia.conversation.OperationParser.NAMES) return false
        val n = name.lowercase()
        return "day" in n && listOf("open", "close", "end", "start", "begin", "finish", "complete").any { it in n }
    }

    fun redirectText(kind: Kind): String =
        "${kind.word} the working day is done on the day screen, where you confirm it yourself. " +
            "Thalia cannot do it from a conversation, so nothing was changed and the working day is as it was."
}

private val reminderWhen =
    java.time.format.DateTimeFormatter
        .ofPattern("EEE d MMM, HH:mm", java.util.Locale.ENGLISH)

/**
 * The day and time a reminder is for, always with the date so it is never read against the wrong day. The zone it is placed in
 * stays in the proposal and is used for scheduling; it is not shown in everyday wording.
 */
internal fun reminderWhen(proposal: Proposal.Reminder): String =
    java.time.Instant
        .ofEpochMilli(proposal.dueAtMillis)
        .atZone(proposal.zone)
        .format(reminderWhen)

/** What approving a reminder would do. Slice 5 only: one notification around that time, then it waits for the person. */
internal fun reminderSummary(proposal: Proposal.Reminder): String =
    "Set a one-time reminder around ${reminderWhen(proposal)}" +
        (
            proposal.actionTitle?.let { " about \"$it\"" }
                ?: " as a generic checkpoint, which carries no description of what it is for"
        ) +
        ". Thalia will post one quiet notification, then wait for you. You can cancel it under Reminders."

/** The labelled facts of a reminder that was set, for the saved card. */
internal fun reminderFacts(proposal: Proposal.Reminder): List<Pair<String, String>> {
    val at =
        java.time.Instant
            .ofEpochMilli(proposal.dueAtMillis)
            .atZone(proposal.zone)
    return listOf(
        "About" to (proposal.actionTitle ?: "Generic check-in"),
        "Date" to
            at.format(
                java.time.format.DateTimeFormatter
                    .ofPattern("EEE d MMM", java.util.Locale.ENGLISH),
            ),
        "Time" to
            at.format(
                java.time.format.DateTimeFormatter
                    .ofPattern("HH:mm", java.util.Locale.ENGLISH),
            ),
    )
}

/** A day as the person reads it, for example "Tue 13 Oct". Every next-day proposal and result names its date this way. */
internal fun dayLabel(day: com.thalia.planning.ActiveDay): String =
    java.time.format.DateTimeFormatter
        .ofPattern("EEE d MMM", java.util.Locale.ENGLISH)
        .format(day.date)
