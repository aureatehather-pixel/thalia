package com.thalia.planning

import java.time.LocalTime
import java.time.ZoneId

/**
 * An approved plan for one active day. ADR 0009 decision 2, slice 2.
 *
 * It is never edited, not even to mark it superseded: adjusting writes the next [revision], and the
 * latest revision of a day is the one in force, found by query. It keeps the zone, rollover and window it was made with, and its
 * [PlanItem]s keep a snapshot of the action and project as they were, so editing either later cannot
 * change what was approved. The call fields arrive with slice 3.
 */
data class DayPlan(
    val id: String,
    val activeDay: ActiveDay,
    val revision: Int,
    val zone: ZoneId,
    val rollover: LocalTime,
    val window: PlanningWindow,
    val freeMinutes: Int,
    val targetMinutes: Int,
    val overloadMinutes: Int,
    val approvedAtMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "a plan needs an id" }
        require(revision >= 1) { "a revision starts at 1, got $revision" }
        require(freeMinutes in MINUTES_RANGE && targetMinutes in MINUTES_RANGE && overloadMinutes in MINUTES_RANGE) {
            "plan minutes must be from ${MINUTES_RANGE.first} to ${MINUTES_RANGE.last}"
        }
        require(targetMinutes <= freeMinutes) { "the target cannot exceed free time" }
    }

    companion object {
        val MINUTES_RANGE = 0..2_000
    }
}

/** One selected action in an approved plan, with the action and project as they were at approval. */
data class PlanItem(
    val id: String,
    val planId: String,
    val actionId: String,
    val kind: ItemKind,
    val start: LocalTime?,
    val minutes: Int,
    val position: Int,
    val placement: Placement?,
    val reason: LeftOutReason?,
    val title: String,
    val priority: Priority,
    val projectId: String?,
    val projectTitle: String?,
    val projectState: ProjectState?,
) {
    init {
        require(id.isNotBlank() && planId.isNotBlank() && actionId.isNotBlank()) { "a plan item needs ids" }
        PlanningText.require("A plan item's title", title, PlanningText.TITLE_MAX)
        require(minutes in Action.ESTIMATE_RANGE) {
            "a plan item's minutes must be from ${Action.ESTIMATE_RANGE.first} to ${Action.ESTIMATE_RANGE.last}"
        }
        require(position >= 0) { "a position starts at 0" }
        require((start != null) == (kind == ItemKind.PINNED)) { "only a pinned item has a start" }
        require((placement != null) == (kind == ItemKind.QUEUED)) { "only a queued item has a placement" }
        require((reason != null) == (kind == ItemKind.LEFT_OUT)) { "only a left-out item has a reason" }
        require((projectId == null) == (projectTitle == null) && (projectId == null) == (projectState == null)) {
            "a project snapshot is all or nothing"
        }
        projectTitle?.let { PlanningText.require("A plan item's project title", it, PlanningText.TITLE_MAX) }
    }
}

/** An approved plan revision with its items, ordered pinned, queued, then left out. */
data class DayPlanWithItems(
    val plan: DayPlan,
    val items: List<PlanItem>,
) {
    /** The selection and pins this plan was made from, the starting point for an Adjust. */
    val selected: Set<String> get() = items.map { it.actionId }.toSet()
    val pins: Map<String, LocalTime> get() = items.filter { it.kind == ItemKind.PINNED }.associate { it.actionId to it.start!! }
}

/** The plan's rows for a proposal that was just approved, as revision [revision]. Pure, so it is tested without a database. */
fun PlanProposal.toApprovedRows(
    planId: String,
    revision: Int,
    approvedAtMillis: Long,
    newItemId: () -> String,
): DayPlanWithItems {
    check(canApprove) { "a proposal with errors cannot be approved" }
    val actionById = actions.associateBy { it.action.id }
    val projectById = projects.associateBy { it.id }
    val plan =
        DayPlan(
            id = planId,
            activeDay = inputs.day,
            revision = revision,
            zone = inputs.zone,
            rollover = inputs.rollover,
            window = inputs.window,
            freeMinutes = freeMinutes,
            targetMinutes = targetMinutes,
            overloadMinutes = overloadMinutes,
            approvedAtMillis = approvedAtMillis,
        )
    val rows =
        items.map { item ->
            val action = actionById.getValue(item.actionId).action
            val project = action.projectId?.let { projectById[it] }
            PlanItem(
                id = newItemId(),
                planId = planId,
                actionId = action.id,
                kind = item.kind,
                start = item.start,
                minutes = item.minutes,
                position = item.position,
                placement = item.placement,
                reason = item.reason,
                title = action.title,
                priority = action.priority,
                projectId = project?.id,
                projectTitle = project?.title,
                projectState = project?.state,
            )
        }
    return DayPlanWithItems(plan, rows)
}

/**
 * The rows of a new revision that restores [previous] exactly as it was approved: the same day, zone, window, capacity
 * figures and items with their snapshots, under new ids. Only the revision number and the approval time are new. Pure,
 * so it is tested without a database. Undo of a plan is this, never a deletion: the revision being undone stays.
 */
fun restoredRows(
    previous: DayPlanWithItems,
    planId: String,
    revision: Int,
    approvedAtMillis: Long,
    newItemId: () -> String,
): DayPlanWithItems =
    DayPlanWithItems(
        previous.plan.copy(id = planId, revision = revision, approvedAtMillis = approvedAtMillis),
        previous.items.map { it.copy(id = newItemId(), planId = planId) },
    )

/**
 * Open actions to offer, unselected, when planning [day]. ADR 0009 decision 4, as amended for slice 3.
 *
 * Membership is by status only: the action is open and has a pinned or queued item in the latest
 * revision of some earlier active day. Outcomes never filter the list; a reopened action whose last
 * outcome was done is still offered. Nothing is counted, badged or totalled.
 */
fun unfinishedFor(
    day: ActiveDay,
    latestPlans: List<DayPlanWithItems>,
    actions: List<Action>,
): List<Action> = unfinishedEntries(day, latestPlans, actions, emptyMap()).map { it.action }

/**
 * [unfinishedFor] with one entry per action, annotated from its item in the latest earlier day that
 * placed it. [outcomes] is keyed by plan item id. An absent or UNKNOWN outcome gives a null result.
 */
fun unfinishedEntries(
    day: ActiveDay,
    latestPlans: List<DayPlanWithItems>,
    actions: List<Action>,
    outcomes: Map<String, PlanOutcome>,
): List<UnfinishedEntry> {
    val open = actions.filter { it.status == ActionStatus.OPEN }.associateBy { it.id }
    val lastItem = mutableMapOf<String, Pair<ActiveDay, PlanItem>>()
    for (plan in latestPlans.filter { it.plan.activeDay < day }) {
        for (item in plan.items.filter { it.kind != ItemKind.LEFT_OUT }) {
            val seen = lastItem[item.actionId]
            if (seen == null || seen.first < plan.plan.activeDay) lastItem[item.actionId] = plan.plan.activeDay to item
        }
    }
    return lastItem
        .mapNotNull { (actionId, placed) ->
            open[actionId]?.let { UnfinishedEntry(it, outcomes[placed.second.id]?.result?.takeIf { r -> r != OutcomeResult.UNKNOWN }) }
        }.sortedBy { it.action.id }
}
