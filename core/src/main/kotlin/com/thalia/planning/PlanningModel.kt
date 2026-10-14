package com.thalia.planning

import java.time.LocalDate
import java.time.LocalTime

/**
 * The information the minimal planning loop keeps: one direction, goals, user-defined projects and
 * hand-entered actions. ADR 0009 decision 2.
 *
 * Every value checks itself when it is made, as M2's domain types do, so nothing invalid can reach
 * the database. Text is stored as the user typed it apart from surrounding spaces, which callers
 * trim with [PlanningText.clean] before building a value.
 */
object PlanningText {
    const val DIRECTION_MAX = 2_000
    const val TITLE_MAX = 200
    const val SUCCESS_CONDITION_MAX = 1_000

    /** A project context field or a closing note. ADR 0011. */
    const val CONTEXT_MAX = 1_000

    /** The user's input with surrounding whitespace removed. */
    fun clean(raw: String): String = raw.trim()

    internal fun require(
        what: String,
        text: String,
        max: Int,
    ) {
        require(text.isNotBlank()) { "$what must not be blank" }
        require(text == text.trim()) { "$what must not start or end with spaces" }
        require(text.length <= max) { "$what must be at most $max characters, got ${text.length}" }
    }
}

/** The durable life vector. It has no progress and no deadline, and there is only ever one. */
data class Direction(
    val text: String,
) {
    init {
        PlanningText.require("The direction", text, PlanningText.DIRECTION_MAX)
    }
}

enum class GoalStatus { ACTIVE, ACHIEVED, DROPPED }

/** A desired outcome, with what success looks like and, optionally, when. */
data class Goal(
    val id: String,
    val title: String,
    val successCondition: String,
    val horizon: LocalDate? = null,
    val status: GoalStatus = GoalStatus.ACTIVE,
) {
    init {
        require(id.isNotBlank()) { "a goal needs an id" }
        PlanningText.require("A goal's title", title, PlanningText.TITLE_MAX)
        PlanningText.require("A goal's success condition", successCondition, PlanningText.SUCCESS_CONDITION_MAX)
    }
}

/** The specification's project states. Only an active project competes for planned time. */
enum class ProjectState {
    SOMEDAY,
    PLANNED,
    ACTIVE,
    PAUSED,
    COMPLETED,
    ABANDONED,
    ARCHIVED,
    ;

    /** Whether an action may be added to, or moved into, a project in this state. */
    val acceptsActions: Boolean get() = this != COMPLETED && this != ABANDONED && this != ARCHIVED
}

/** A user-defined body of work, linked to the goal it serves or to none. Thalia never decomposes it. */
data class Project(
    val id: String,
    val title: String,
    val goalId: String? = null,
    val state: ProjectState = ProjectState.PLANNED,
    /** Why it matters and the outcome wanted. This and the three below are null until the person says. ADR 0011. */
    val purpose: String? = null,
    val doneWhen: String? = null,
    val constraints: String? = null,
    val blocker: String? = null,
) {
    init {
        require(id.isNotBlank()) { "a project needs an id" }
        require(goalId == null || goalId.isNotBlank()) { "a project's goal link must be absent or an id" }
        PlanningText.require("A project's title", title, PlanningText.TITLE_MAX)
        ContextField.entries.forEach { field ->
            context(field)?.let { PlanningText.require("A project's ${field.label}", it, PlanningText.CONTEXT_MAX) }
        }
    }

    fun context(field: ContextField): String? =
        when (field) {
            ContextField.PURPOSE -> purpose
            ContextField.DONE_WHEN -> doneWhen
            ContextField.CONSTRAINTS -> constraints
            ContextField.BLOCKER -> blocker
        }

    /** This project with [field] set to [text], or cleared when [text] is null. */
    fun withContext(
        field: ContextField,
        text: String?,
    ): Project =
        when (field) {
            ContextField.PURPOSE -> copy(purpose = text)
            ContextField.DONE_WHEN -> copy(doneWhen = text)
            ContextField.CONSTRAINTS -> copy(constraints = text)
            ContextField.BLOCKER -> copy(blocker = text)
        }
}

enum class Priority { MUST, IMPORTANT, NORMAL, OPTIONAL }

enum class ActionStatus { OPEN, DONE, DROPPED }

/** A finite action, entered by hand, under a project or standalone. */
data class Action(
    val id: String,
    val title: String,
    val projectId: String? = null,
    val priority: Priority = Priority.NORMAL,
    val estimateMinutes: Int = DEFAULT_ESTIMATE_MINUTES,
    val dueDate: LocalDate? = null,
    val status: ActionStatus = ActionStatus.OPEN,
) {
    init {
        require(id.isNotBlank()) { "an action needs an id" }
        require(projectId == null || projectId.isNotBlank()) { "an action's project link must be absent or an id" }
        PlanningText.require("An action's title", title, PlanningText.TITLE_MAX)
        require(estimateMinutes in ESTIMATE_RANGE) {
            "An action's estimate must be from ${ESTIMATE_RANGE.first} to ${ESTIMATE_RANGE.last} minutes, got $estimateMinutes"
        }
    }

    companion object {
        const val DEFAULT_ESTIMATE_MINUTES = 30
        val ESTIMATE_RANGE = 5..720
    }
}

/**
 * When an active day turns over, and the window the user plans inside. ADR 0009 decision 3.
 *
 * Active day D runs from D at [rollover] up to, but not including, D + 1 at [rollover]. The
 * window has no value until the user chooses one during planning setup, and when it has one it
 * must lie inside the active day: it may cross midnight, but not the rollover.
 */
data class PlanningSettings(
    val rollover: LocalTime = DEFAULT_ROLLOVER,
    val window: PlanningWindow? = null,
) {
    init {
        requireWholeMinute("The rollover", rollover)
        window?.let { w ->
            val start = minutesAfter(rollover, w.start)
            val end = minutesAfter(rollover, w.end).let { if (it == 0) MINUTES_PER_DAY else it }
            require(start < end) {
                "The window ${w.start} to ${w.end} must lie inside the active day, which turns over at $rollover"
            }
        }
    }

    companion object {
        val DEFAULT_ROLLOVER: LocalTime = LocalTime.of(4, 0)

        /** What planning setup suggests. The user chooses; nothing uses it until they do. */
        val SUGGESTED_WINDOW = PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))

        private const val MINUTES_PER_DAY = 24 * 60

        private fun minutesAfter(
            rollover: LocalTime,
            time: LocalTime,
        ): Int = Math.floorMod(minuteOfDay(time) - minuteOfDay(rollover), MINUTES_PER_DAY)

        private fun minuteOfDay(time: LocalTime): Int = time.hour * 60 + time.minute
    }
}

/** The part of the active day the user plans inside, as local wall-clock times. */
data class PlanningWindow(
    val start: LocalTime,
    val end: LocalTime,
) {
    init {
        requireWholeMinute("The window's start", start)
        requireWholeMinute("The window's end", end)
        require(start != end) { "The window must not be empty" }
    }
}

private fun requireWholeMinute(
    what: String,
    time: LocalTime,
) = require(time.second == 0 && time.nano == 0) { "$what must be a whole minute, got $time" }
