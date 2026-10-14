package com.thalia.app.checkin

import com.thalia.app.planning.OutcomeRow
import com.thalia.app.planning.PlanningClock
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.WorkingDayChange
import com.thalia.app.planning.outcomeRows
import com.thalia.app.planning.outcomeToday
import com.thalia.app.planning.planningDay
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.FixedCommitment
import com.thalia.planning.PlanProposal
import com.thalia.planning.WorkingDayRecord
import com.thalia.planning.WorkingDayState
import com.thalia.planning.WorkingDays
import com.thalia.planning.unfinishedEntries
import java.time.Instant
import java.time.ZoneId

/**
 * The structured check-in that needs no AI and no network: the same planning rules and validated writes as the Plan, Today and
 * Life tabs, laid out as a short guided flow. Reading here writes nothing. Every change is a separate explicit call made by a
 * tap: an outcome, a remaining estimate, closing the day with a note, and approving a next-day plan.
 */
internal data class GuidedState(
    val zone: ZoneId,
    /** The day planning is on now. */
    val day: ActiveDay,
    /** The day outcomes may be recorded up to. */
    val outcomeDay: ActiveDay,
    val plan: DayPlanWithItems?,
    val commitments: List<FixedCommitment>,
    val rows: List<OutcomeRow>,
    /** Actions with a Partial result, whose remaining estimate may be set. */
    val partial: List<Action>,
    val workingDay: WorkingDayRecord?,
    /** The working day is open and can be closed from here. */
    val canClose: Boolean,
    val lastClosingNote: String?,
    /** The active day after [day] by the working-day rules, or null while an earlier working day awaits a choice. */
    val nextDay: ActiveDay?,
    val nextPlan: DayPlanWithItems?,
    /** The open actions the next-day proposal would carry, in a stable order. */
    val carry: List<Action>,
)

/** Reads everything the flow shows at [at]. Writes nothing. */
internal suspend fun loadGuided(
    store: PlanningStore,
    zone: ZoneId,
    at: Instant = PlanningClock.now(),
): GuidedState {
    val settings = store.settings()
    val rows = store.workingDays()
    val state = WorkingDays.resolve(at, zone, settings.rollover, rows)
    val day = planningDay(at, zone, settings.rollover, rows)
    val outcomeDay = outcomeToday(at, zone, settings.rollover, rows)
    val plan = store.planFor(day)
    val outcomes = store.outcomes().associateBy { it.planItemId }
    val actions = store.actions()
    val outcomeRows = if (plan == null) emptyList() else outcomeRows(plan, outcomes, actions, outcomeDay)
    val byItem = plan?.items.orEmpty().associateBy { it.id }
    val partial =
        outcomeRows
            .filter { it.result == com.thalia.planning.OutcomeResult.PARTIAL }
            .mapNotNull { r -> actions.firstOrNull { it.id == byItem[r.itemId]?.actionId } }
            .filter { it.status == ActionStatus.OPEN }
    val workingDay = rows.firstOrNull { it.day == day }
    val nextDay = WorkingDays.nextActiveDay(state, day)
    val nextPlan = nextDay?.let { store.planFor(it) }
    val carry =
        nextDay
            ?.let { next ->
                val unfinished = unfinishedEntries(next, store.latestPlans(), actions, outcomes).map { it.action }
                val already = nextPlan?.selected.orEmpty()
                val kept = actions.filter { it.id in already && it.status == ActionStatus.OPEN }
                (kept + unfinished).distinctBy { it.id }.sortedBy { it.id }
            }.orEmpty()
    return GuidedState(
        zone,
        day,
        outcomeDay,
        plan,
        store.commitmentsOf(day, zone, settings.rollover),
        outcomeRows,
        partial,
        workingDay,
        canClose = workingDay?.isOpen == true && state is WorkingDayState.Current,
        lastClosingNote =
            rows
                .filter { it.closedAt != null && it.day <= day && it.closingNote != null }
                .maxByOrNull { it.day }
                ?.closingNote,
        nextDay = nextDay,
        nextPlan = nextPlan,
        carry = carry,
    )
}

/**
 * The deterministic next-day proposal: the open actions the earlier plans placed and did not finish, plus whatever the next
 * day's current plan already holds, with that plan's own pins. Null when there is nothing to carry or no window. Nothing is
 * saved: only the store's approval writes a plan.
 */
internal suspend fun carryProposal(
    store: PlanningStore,
    state: GuidedState,
): PlanProposal? {
    val next = state.nextDay ?: return null
    if (state.carry.isEmpty()) return null
    val selected = state.carry.map { it.id }.toSet()
    val pins =
        state.nextPlan
            ?.pins
            .orEmpty()
            .filterKeys { it in selected }
    val own = state.nextPlan?.plan?.window
    return store.propose(next, state.zone, own?.takeIf { it != store.settings().window }, selected, pins)
}

/** Closes the working day with an optional note. The only write here that is not an outcome, an estimate or an approval. */
internal suspend fun closeDay(
    store: PlanningStore,
    state: GuidedState,
    note: String?,
    at: Instant = PlanningClock.now(),
): String =
    when (store.closeWorkingDay(state.day, at, note)) {
        is WorkingDayChange.Closed -> "Closed ${state.day}. Anything you did not record stays unknown."
        else -> "No open working day to close. Nothing was changed."
    }

/**
 * The planned items still without an outcome: what the guided closing's separate "not recorded" question is about. Only plan
 * items are ever listed (pinned and queued), so a fixed commitment, which takes no outcome, can never appear here.
 */
internal fun unrecorded(rows: List<OutcomeRow>): List<OutcomeRow> = rows.filter { it.result == null }

/** The words of the separate unknown-outcome question. Asking and answering it records nothing. */
internal object UnknownQuestion {
    const val TITLE = "Not recorded"
    const val LEAVE = "Leave as unknown"

    fun question(titles: List<String>): String {
        val n = titles.size
        return "${if (n == 1) "1 planned item has" else "$n planned items have"} no outcome yet: ${titles.joinToString()}. " +
            "Unknown is fine, and you are asked this once. To record one, use its buttons in step 1."
    }

    fun left(titles: List<String>): String =
        "Left as unknown: ${titles.joinToString()}. Nothing was recorded or assumed, and you can still record any of them above."
}
