package com.thalia.planning

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * One working day as stored. ADR 0011. [day] is its label, read from the clock when it was opened. A row may exist with
 * [openedAt] null only to hold a [focusMinutes] figure set before opening.
 */
data class WorkingDayRecord(
    val day: ActiveDay,
    val openedAt: Instant? = null,
    val closedAt: Instant? = null,
    val focusMinutes: Int? = null,
    val closingNote: String? = null,
    /** The clock day on which the person chose to continue this day past its own label. */
    val continuedOn: ActiveDay? = null,
) {
    init {
        require(closedAt == null || openedAt != null) { "a working day is closed only after it was opened" }
        require(focusMinutes == null || focusMinutes in FOCUS_RANGE) { "focus minutes must be in $FOCUS_RANGE, got $focusMinutes" }
        closingNote?.let { PlanningText.require("A closing note", it, PlanningText.CONTEXT_MAX) }
    }

    val isOpen: Boolean get() = openedAt != null && closedAt == null

    companion object {
        val FOCUS_RANGE = 0..720
    }
}

/** What the resolver found. ADR 0011, "The resolver". */
sealed interface WorkingDayState {
    /** Planning works on [clockDay]; opening would open or reopen [openable]. */
    data class NoneOpen(
        val clockDay: ActiveDay,
        val openable: ActiveDay,
        val reopens: Boolean,
    ) : WorkingDayState

    data class Current(
        val day: ActiveDay,
    ) : WorkingDayState

    /** An earlier day is still open. Nothing is recommended from it until the person continues it or starts [startable]. */
    data class Choose(
        val open: ActiveDay,
        val openedAt: Instant,
        val clockDay: ActiveDay,
        val startable: ActiveDay,
    ) : WorkingDayState
}

/** The pure rules of ADR 0011. They read no clock: the caller passes now. */
object WorkingDays {
    fun resolve(
        now: Instant,
        zone: ZoneId,
        rollover: LocalTime,
        rows: Collection<WorkingDayRecord>,
    ): WorkingDayState {
        val clockDay = ActiveDay.containing(now, zone, rollover)
        val open = rows.singleOrNull { it.isOpen }
        check(rows.count { it.isOpen } <= 1) { "more than one working day is open" }
        if (open == null) {
            val label = labelForOpening(now, zone, rollover, rows)
            return WorkingDayState.NoneOpen(clockDay, label, rows.any { it.day == label })
        }
        if (open.day >= clockDay || open.continuedOn == clockDay) return WorkingDayState.Current(open.day)
        return WorkingDayState.Choose(open.day, checkNotNull(open.openedAt), clockDay, labelForOpening(now, zone, rollover, rows - open))
    }

    /**
     * The label opening at [now] uses: the clock's active day, or now's calendar date when that active day was already
     * closed and the calendar date is later. Both are readings of the clock; nothing is derived from another day.
     */
    fun labelForOpening(
        now: Instant,
        zone: ZoneId,
        rollover: LocalTime,
        rows: Collection<WorkingDayRecord>,
    ): ActiveDay {
        val clockDay = ActiveDay.containing(now, zone, rollover)
        val calendar = ActiveDay(now.atZone(zone).toLocalDate())
        val closed = rows.any { it.day == clockDay && it.closedAt != null }
        return if (closed && calendar > clockDay) calendar else clockDay
    }

    /** The day outcomes may be recorded up to: the later of the clock's active day and the current working day. */
    fun outcomeDay(
        state: WorkingDayState,
        clockDay: ActiveDay,
    ): ActiveDay = if (state is WorkingDayState.Current && state.day > clockDay) state.day else clockDay

    /**
     * The next active day to plan, or null when none can be named yet. Active days are consecutive: it is the day after
     * [planningDay], which the working-day rules chose (the current working day, or the clock's active day). It is never the
     * calendar date plus one: between midnight and the rollover the calendar date is already a day past the active day, so
     * calendar-plus-one would skip a day. While an earlier working day awaits a choice, no next day is named.
     */
    fun nextActiveDay(
        state: WorkingDayState,
        planningDay: ActiveDay,
    ): ActiveDay? = if (state is WorkingDayState.Choose) null else planningDay.plusDays(1)
}

enum class CommitmentStatus { ACTIVE, CANCELLED }

/** A fixed commitment, entered by hand, at real instants. It is not an action and takes no outcome. ADR 0011. */
data class FixedCommitment(
    val id: String,
    val title: String,
    val start: Instant,
    val end: Instant,
    /** The zone it was entered in, to show it as entered. */
    val zone: ZoneId,
    val status: CommitmentStatus = CommitmentStatus.ACTIVE,
) {
    init {
        require(id.isNotBlank()) { "a commitment needs an id" }
        PlanningText.require("A commitment's title", title, PlanningText.TITLE_MAX)
        require(end.isAfter(start)) { "a commitment ends after it starts" }
        require(
            java.time.Duration
                .between(start, end)
                .toHours() <= MAX_HOURS,
        ) { "a commitment is at most $MAX_HOURS hours" }
    }

    companion object {
        const val MAX_HOURS = 24L
    }
}

/** The project fields that hold its context, each unknown until the person says. ADR 0011. */
enum class ContextField(
    val label: String,
) {
    PURPOSE("purpose"),
    DONE_WHEN("done-when"),
    CONSTRAINTS("constraints"),
    BLOCKER("blocker"),
}
