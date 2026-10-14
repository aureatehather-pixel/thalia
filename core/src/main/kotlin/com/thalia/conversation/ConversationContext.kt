package com.thalia.conversation

import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.ContextField
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.Direction
import com.thalia.planning.FixedCommitment
import com.thalia.planning.Goal
import com.thalia.planning.GoalStatus
import com.thalia.planning.InboxItem
import com.thalia.planning.InboxState
import com.thalia.planning.ItemKind
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanOutcome
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import com.thalia.planning.Stamped
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How much of the planning data one request may carry. Everything past a limit is left out and counted, never guessed. */
object ContextLimits {
    const val ACTIONS = 40
    const val PROJECTS = 15
    const val GOALS = 8
    const val DIRECTION_CHARS = 600

    /** Oldest open Inbox items shown, so a long Inbox is worked through a few at a time. */
    const val INBOX = 10

    /** Characters of one capture shown to the model. The stored text is never cut; only what is shown is. */
    const val INBOX_CHARS = 400

    /** Captures one explicit triage may share, chosen by the person and never more. Slice 4A. */
    const val TRIAGE = 5

    /** Characters of one message the person types, past which it is refused locally rather than sent. */
    const val MESSAGE_CHARS = 2_000

    /** Everything one request may carry in UTF-8 bytes (instruction, snapshot, turns and tools). Over it, nothing is sent. */
    const val REQUEST_BYTES = 48_000

    /**
     * Output tokens one reply may use, thinking included (the provider counts both against this limit and bills both as
     * output). One request, one reply: the conversation never sends a function result back, so there is one round per message.
     */
    const val OUTPUT_TOKENS = 4_096
}

/** What today's plan says about an action: its item, and the outcome recorded for it, null if none is recorded. */
data class TodayEntry(
    val itemId: String,
    val kind: ItemKind,
    val outcome: OutcomeResult?,
)

/**
 * The day-loop facts a request carries, ADR 0011: the working day as a sentence, today's fixed commitments, the focus
 * capacity, the last closing note and the re-entry line. Empty when the screen has none of them.
 */
data class DayLoopFacts(
    val zone: ZoneId? = null,
    val rollover: LocalTime = LocalTime.of(4, 0),
    val workingDay: String? = null,
    val commitments: List<FixedCommitment> = emptyList(),
    val focusMinutes: Int? = null,
    val previousClosingNote: String? = null,
    val reEntry: String? = null,
    /** The instant of this request. With [zone] it gives the local date, time and weekday, and tomorrow's local date. */
    val now: java.time.Instant? = null,
)

/**
 * The active day after the one planning is on, as the closing conversation may plan it. [day] is worked out by Kotlin from the
 * working-day rules, never from the model. [selection] and [pins] are the current plan's, if one exists, so a proposal can
 * keep pins and be told it changes nothing.
 */
data class NextDay(
    val day: ActiveDay,
    val planRevision: Int?,
    val selection: Set<String>,
    val pins: Map<String, LocalTime>,
    val titles: Map<String, String>,
)

data class ContextProject(
    val ref: String,
    val project: Project,
)

data class ContextAction(
    val ref: String,
    val action: Action,
    val projectRef: String?,
    val today: TodayEntry?,
)

/** An open Inbox item as shown: [shown] is the capture with line breaks as spaces and cut at [ContextLimits.INBOX_CHARS]. */
data class ContextInboxItem(
    val ref: String,
    val item: InboxItem,
    val shown: String,
    val cut: Boolean,
)

/**
 * What the model is told about the user's planning data, and the key to what it may point at.
 *
 * The model never sees a stored id. It sees short references (`a1`, `p2`) that mean something only for this one
 * snapshot, so an answer can name only what was shown, and a reference that is not in the snapshot is refused.
 * Only what the minimal planning model stores can appear: a project has a title, a goal link and a state, and
 * nothing here describes its intent, blockers or constraints, because Thalia does not hold them. [render] says so.
 */

data class ConversationContext(
    val day: ActiveDay,
    val direction: String?,
    val goals: List<Goal>,
    val projects: List<ContextProject>,
    val actions: List<ContextAction>,
    val actionsNotShown: Int,
    val planRevision: Int?,
    val window: PlanningWindow?,
    /** Today's pins by action id, kept when the conversation proposes another selection for the day. */
    val todayPins: Map<String, LocalTime>,
    /** True when today's current revision has an effective recorded outcome, which refuses a new revision. */
    val planLocked: Boolean,
    val dayLoop: DayLoopFacts = DayLoopFacts(),
    /** Open Inbox items, oldest first, ADR 0012. Captures are not actions and are never in a plan. */
    val inbox: List<ContextInboxItem> = emptyList(),
    val inboxNotShown: Int = 0,
    /** False unless the person started an Inbox triage: captures are private and are otherwise left out entirely. */
    val inboxShared: Boolean = inbox.isNotEmpty(),
    /** The next active day, or null while it cannot be named (an earlier working day awaits a choice). */
    val next: NextDay? = null,
) {
    private val actionsByRef = actions.associateBy { it.ref }
    private val inboxByRef = inbox.associateBy { it.ref }
    private val projectsByRef = projects.associateBy { it.ref }

    fun action(ref: String): ContextAction? = actionsByRef[ref]

    fun project(ref: String): ContextProject? = projectsByRef[ref]

    fun inboxItem(ref: String): ContextInboxItem? = inboxByRef[ref]

    /** The action ids of today's plan, pinned and queued. */
    val todaySelection: Set<String> get() = actions.filter { it.today != null }.map { it.action.id }.toSet()

    /**
     * The local clock as the person lives it, from the request's instant and zone: date, weekday, time, zone, and tomorrow's
     * local calendar date. It is the calendar date, which can differ from the active day between midnight and the rollover.
     */
    private fun clockLine(): String? {
        val zone = dayLoop.zone ?: return null
        val at = dayLoop.now?.atZone(zone) ?: return null
        val tomorrow = at.toLocalDate().plusDays(1)
        val day = DateTimeFormatter.ofPattern("EEEE", java.util.Locale.ENGLISH)
        return "Now: ${at.toLocalDate()} ${at.format(day)} ${at.format(DateTimeFormatter.ofPattern("HH:mm"))} in ${zone.id}. " +
            "Tomorrow is $tomorrow ${tomorrow.format(day)}. Resolve \"today\", \"tomorrow\" and times of day from this, not from guesses."
    }

    fun render(): String =
        buildString {
            clockLine()?.let { appendLine(it) }
            appendLine("Active day: $day. Planning window: ${window?.let { "${it.start} to ${it.end}" } ?: "not set"}.")
            appendLine("Direction: ${direction?.let { "\"$it\"" } ?: "not recorded"}.")
            if (goals.isEmpty()) appendLine("Goals: none recorded.") else appendLine("Active goals:")
            goals.forEach {
                appendLine(
                    "- \"${it.title}\", success: \"${it.successCondition}\"${it.horizon?.let { h ->
                        ", by $h"
                    } ?: ""}",
                )
            }
            if (projects.isEmpty()) appendLine("Projects: none recorded.") else appendLine("Projects:")
            projects.forEach { appendLine("- ${it.ref} \"${it.project.title}\" state=${it.project.state}" + contextOf(it.project)) }
            appendLine("Open actions (${actions.size} shown${if (actionsNotShown > 0) ", $actionsNotShown more not shown" else ""}):")
            actions.forEach { appendLine("- " + line(it)) }
            if (!inboxShared) {
                appendLine("Inbox: captures are private and are not shared in this conversation. Do not mention or guess at them.")
            } else if (inbox.isEmpty()) {
                appendLine("Inbox: the person chose captures to go through, but none of them is still open.")
            } else {
                appendLine(
                    "Inbox: the person chose these ${inbox.size} open captures to go through, oldest first" +
                        "${if (inboxNotShown > 0) ", and $inboxNotShown more were not included" else ""}. " +
                        "They are captures, not actions or commitments, and none is in any plan:",
                )
                inbox.forEach {
                    appendLine(
                        "- ${it.ref} \"${it.shown}\"${if (it.cut) " (CUT: you see only the first ${ContextLimits.INBOX_CHARS} characters; the rest is stored)" else ""}",
                    )
                }
                val cuts = inbox.count { it.cut }
                if (cuts > 0) appendLine("$cuts of these captures are cut. Do not guess what the missing part says; ask the person.")
            }
            appendLine(
                when {
                    planRevision == null -> "Today's plan: none approved yet."
                    else -> {
                        val locked = if (planLocked) ", with outcomes recorded, so it cannot be replaced" else ""
                        "Today's plan: revision $planRevision approved$locked."
                    }
                },
            )
            next?.let { n ->
                val label = DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH).format(n.day.date)
                val plan =
                    if (n.planRevision == null) {
                        "none approved yet"
                    } else {
                        "revision ${n.planRevision} approved" +
                            if (n.titles.isEmpty()) "" else ": " + n.titles.values.joinToString { "\"$it\"" }
                    }
                appendLine(
                    "Next active day: ${n.day} ($label), the active day after $day. Its plan: $plan. " +
                        "To plan it call propose_day_selection with day next; never name a date.",
                )
            }
            dayLoop.workingDay?.let { appendLine(it) }
            val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            val zone = dayLoop.zone
            if (zone != null) {
                if (dayLoop.commitments.isEmpty()) {
                    appendLine("Fixed commitments today: none recorded.")
                } else {
                    appendLine("Fixed commitments today:")
                    dayLoop.commitments.forEach {
                        appendLine("- \"${it.title}\" ${it.start.atZone(zone).format(time)} to ${it.end.atZone(zone).format(time)}")
                    }
                }
            }
            appendLine("Focus capacity today: ${dayLoop.focusMinutes?.let { "$it min, as the person said" } ?: "not given"}.")
            dayLoop.previousClosingNote?.let { appendLine("Note from the last closing: \"$it\".") }
            dayLoop.reEntry?.let { appendLine(it) }
            append(
                "Project context fields shown as unknown are unknown to you, as are dependencies, how long things really take, " +
                    "and what was tried before. Do not assume them. Ask when one would change your answer.",
            )
        }

    private fun contextOf(project: Project): String =
        ContextField.entries.joinToString("") { field ->
            val key = field.name.lowercase()
            " $key=" + (project.context(field)?.let { "\"$it\"" } ?: "unknown")
        }

    private fun line(a: ContextAction): String =
        buildString {
            append("${a.ref} \"${a.action.title}\" priority=${a.action.priority} estimate=${a.action.estimateMinutes}m")
            a.projectRef?.let { append(" project=$it") }
            a.action.dueDate?.let { append(" due=$it") }
            a.today?.let {
                append(" | today: ${it.kind.name.lowercase()}, ")
                append(
                    it.outcome?.takeIf { r -> r != OutcomeResult.UNKNOWN }?.let { r -> "outcome ${r.name.lowercase()}" }
                        ?: "no outcome recorded",
                )
            }
        }

    companion object {
        /**
         * The snapshot for [day] from the stored planning data. Today's own actions come first, then the rest by
         * priority, due date and title, so a limit removes the least relevant. [todayPlan] is the latest revision for
         * [day], and [outcomes] is keyed by plan item id.
         */
        fun build(
            day: ActiveDay,
            direction: Direction?,
            goals: List<Goal>,
            projects: List<Project>,
            actions: List<Action>,
            todayPlan: DayPlanWithItems?,
            outcomes: Map<String, PlanOutcome>,
            settings: PlanningSettings,
            dayLoop: DayLoopFacts = DayLoopFacts(),
            /** Null leaves the Inbox out of the snapshot altogether. A list is the captures the person chose to share. */
            inboxItems: List<Stamped<InboxItem>>? = null,
            /** The active day after [day] and its current plan, or a null day when the next day cannot be named. */
            nextDay: ActiveDay? = null,
            nextPlan: DayPlanWithItems? = null,
        ): ConversationContext {
            val items =
                todayPlan
                    ?.items
                    .orEmpty()
                    .filter { it.kind != ItemKind.LEFT_OUT }
                    .associateBy { it.actionId }
            val open = actions.filter { it.status == ActionStatus.OPEN }
            val ordered =
                open.sortedWith(
                    compareBy<Action>({
                        if (it.id in
                            items
                        ) {
                            0
                        } else {
                            1
                        }
                    }, { it.priority.ordinal }, { it.dueDate == null }, { it.dueDate }, { it.title }, { it.id }),
                )
            val openInbox =
                inboxItems.orEmpty().filter { it.value.state == InboxState.OPEN }.sortedWith(
                    compareBy({ it.createdAtMillis }, { it.value.id }),
                )
            val shown = ordered.take(ContextLimits.ACTIONS)
            val listedProjects =
                projects
                    .sortedWith(compareBy<Project>({ it.state != ProjectState.ACTIVE }, { it.title }, { it.id }))
                    .take(ContextLimits.PROJECTS)
                    .mapIndexed { index, project -> ContextProject("p${index + 1}", project) }
            val projectRefById = listedProjects.associate { it.project.id to it.ref }
            val contextActions =
                shown.mapIndexed { index, action ->
                    ContextAction(
                        "a${index + 1}",
                        action,
                        action.projectId?.let { projectRefById[it] },
                        items[action.id]?.let { TodayEntry(it.id, it.kind, outcomes[it.id]?.result) },
                    )
                }
            val directionText =
                direction?.text?.let {
                    if (it.length >
                        ContextLimits.DIRECTION_CHARS
                    ) {
                        it.take(ContextLimits.DIRECTION_CHARS) + "..."
                    } else {
                        it
                    }
                }
            return ConversationContext(
                day = day,
                direction = directionText,
                goals = goals.filter { it.status == GoalStatus.ACTIVE }.sortedBy { it.id }.take(ContextLimits.GOALS),
                projects = listedProjects,
                actions = contextActions,
                actionsNotShown = open.size - shown.size,
                planRevision = todayPlan?.plan?.revision,
                window = todayPlan?.plan?.window ?: settings.window,
                todayPins = todayPlan?.pins.orEmpty(),
                planLocked = items.values.any { outcomes[it.id]?.isRecorded == true },
                dayLoop = dayLoop,
                inbox = openInbox.take(ContextLimits.INBOX).mapIndexed { index, stamped -> inboxLine("i${index + 1}", stamped.value) },
                inboxNotShown = (openInbox.size - ContextLimits.INBOX).coerceAtLeast(0),
                inboxShared = inboxItems != null,
                next =
                    nextDay?.let { d ->
                        val planned = nextPlan?.items.orEmpty().filter { it.kind != ItemKind.LEFT_OUT }
                        NextDay(
                            d,
                            nextPlan?.plan?.revision,
                            planned.map { it.actionId }.toSet(),
                            nextPlan?.pins.orEmpty(),
                            planned.associate { it.actionId to it.title },
                        )
                    },
            )
        }

        private fun inboxLine(
            ref: String,
            item: InboxItem,
        ): ContextInboxItem {
            val flat =
                item.text
                    .replace(Regex("\\s*\\R\\s*"), " ")
                    .trim()
                    .replace("\"", "'")
            val cut = flat.length > ContextLimits.INBOX_CHARS
            return ContextInboxItem(ref, item, if (cut) flat.take(ContextLimits.INBOX_CHARS) + "..." else flat, cut)
        }
    }
}
