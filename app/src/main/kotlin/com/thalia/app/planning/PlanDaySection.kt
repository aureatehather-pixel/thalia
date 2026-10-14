package com.thalia.app.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thalia.app.planning.data.ApprovalRefusal
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.OutcomeRecording
import com.thalia.app.planning.data.PlanCall
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.UndoRefusal
import com.thalia.app.planning.data.UndoResult
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.FixedCommitment
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanOutcome
import com.thalia.planning.PlanProposal
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.WorkingDayRecord
import com.thalia.planning.unfinishedEntries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private class DayData(
    val settings: PlanningSettings,
    val actions: List<Action>,
    val approved: List<DayPlanWithItems>,
    val outcomes: Map<String, PlanOutcome>,
    val calls: Map<String, PlanCall>,
    val workingDays: List<WorkingDayRecord>,
)

private const val PIN_TIMES_MESSAGE = "Pin times are HH:mm."

private fun clock(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim()) }.getOrNull()

/** The window editor's text for a fresh day: the default, or empty before setup has chosen one. */
internal fun defaultWindowTexts(default: PlanningWindow?): Pair<String, String> =
    if (default == null) "" to "" else default.start.toString() to default.end.toString()

/**
 * Plan a day: setup, selection, preview, Adjust, Approve and Keep original. ADR 0009 slice 2.
 *
 * Plain and functional. Nothing is written until Approve: the preview is made in memory by the
 * deterministic planner, leaving writes nothing, and a plan already approved for the day stays in force
 * until a new one is approved. Approving saves the plan only; it never schedules a call (slice 3).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanDaySection(
    access: PlanningAccess,
    zoneSource: CheckInZoneSource,
    link: OpeningCallLink,
    writes: PlanningWrites,
    changes: Int,
    /** False where the outcomes are shown elsewhere (the Today tab), so one day's outcomes are never offered twice. */
    showOutcomes: Boolean = true,
    /** Receives the fixed Approve bar's state (null when there is none), so the tab can draw it above the tab bar. */
    onBar: (PlanBarModel?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var zone by remember { mutableStateOf<CheckInZone?>(null) }
    var data by remember { mutableStateOf<DayData?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    var day by rememberSaveable(stateSaver = ActiveDaySaver) { mutableStateOf<ActiveDay?>(null) }
    var current by remember { mutableStateOf<DayPlanWithItems?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savedBar by remember { mutableStateOf<String?>(null) }
    // The plan whose approval can still be undone: set by a replacing Approve, cleared by an Undo, kept across rotation.
    var undoOffer by rememberSaveable { mutableStateOf<String?>(null) }
    var dayCommitments by remember { mutableStateOf(emptyList<FixedCommitment>()) }

    // The form: the choices that make a proposal.
    var selected by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(setOf<String>()) }
    var pinTexts by rememberSaveable(stateSaver = StringMapSaver) { mutableStateOf(mapOf<String, String>()) }
    var windowStart by rememberSaveable { mutableStateOf("") }
    var windowEnd by rememberSaveable { mutableStateOf("") }
    var previewing by rememberSaveable { mutableStateOf(false) }
    // Items left out of this proposal by the per-block controls. Only this screen's draft: the approved plan never changes.
    var leftOutIds by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(setOf<String>()) }
    var proposal by remember { mutableStateOf<PlanProposal?>(null) }
    var openingText by rememberSaveable { mutableStateOf("") }
    var callPreview by remember { mutableStateOf<CallPreview?>(null) }
    var setupStart by remember { mutableStateOf(PlanningSettings.SUGGESTED_WINDOW.start.toString()) }
    var setupEnd by remember { mutableStateOf(PlanningSettings.SUGGESTED_WINDOW.end.toString()) }

    fun clearForm() {
        selected = emptySet()
        leftOutIds = emptySet()
        pinTexts = emptyMap()
        previewing = false
        proposal = null
        openingText = ""
        callPreview = null
        // A day's own window never carries into the next editor: each starts from the default.
        defaultWindowTexts(data?.settings?.window).let { (start, end) ->
            windowStart = start
            windowEnd = end
        }
    }

    // Reloads on this section's own changes and on any successful write anywhere on the screen, so the actions and the
    // outcome rows never show a status that the editor or an outcome has since changed.
    LaunchedEffect(reload, changes) {
        try {
            zone = zoneSource.read()
            val loaded =
                access.withStore { store ->
                    DayData(
                        store.settings(),
                        store.actions(),
                        store.latestPlans(),
                        store.outcomes().associateBy {
                            it.planItemId
                        },
                        store.calls().associateBy { it.planId },
                        store.workingDays(),
                    )
                }
            data = loaded
            val known = (zone as? CheckInZone.Known)?.zone
            if (day == null && known != null) day = planningDay(PlanningClock.now(), known, loaded.settings.rollover, loaded.workingDays)
            val shown = day
            current = if (shown == null) null else access.withStore { it.planFor(shown) }
            dayCommitments =
                if (shown == null ||
                    known == null
                ) {
                    emptyList()
                } else {
                    access.withStore { it.commitmentsOf(shown, known, loaded.settings.rollover) }
                }
            val window = loaded.settings.window
            if (window != null && windowStart.isEmpty()) {
                windowStart = window.start.toString()
                windowEnd = window.end.toString()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadFailed = true
        }
    }

    val known = (zone as? CheckInZone.Known)?.zone
    val loaded = data
    val activeDay = day

    // Every change to the choices makes the proposal again, from the stored data, so Adjust is the same
    // path as the first preview and the screen never shows a proposal its choices no longer match.
    // loaded and known are keys too: a restored preview starts before the data has been read and must be made again once it is.
    LaunchedEffect(previewing, selected, pinTexts, windowStart, windowEnd, activeDay, reload, changes, loaded, known) {
        if (!previewing || known == null || activeDay == null || loaded == null) return@LaunchedEffect
        val typed = pinTexts.filterKeys { it in selected }.filterValues { it.isNotBlank() }
        if (typed.values.any { clock(it) == null }) {
            proposal = null
            message = PIN_TIMES_MESSAGE
            return@LaunchedEffect
        }
        val pins = typed.mapValues { clock(it.value)!! }
        val start = clock(windowStart)
        val end = clock(windowEnd)
        val default = loaded.settings.window
        val override =
            if (start != null && end != null && start != end) PlanningWindow(start, end).takeIf { it != default } else default
        try {
            proposal = access.withStore { it.propose(activeDay, known, if (override == default) null else override, selected, pins) }
            // A half-typed pin raised this; once the times are valid again the note must not outlive the problem.
            if (message == PIN_TIMES_MESSAGE) message = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            proposal = null
            message = "A preview could not be made."
        }
    }

    // The call effect of an optional opening time, read from M2 without changing it. Separate from approval.
    LaunchedEffect(previewing, openingText, activeDay, reload, known, loaded) {
        val time = clock(openingText)
        callPreview =
            if (!previewing || known == null || activeDay == null || loaded == null || time == null) {
                null
            } else {
                try {
                    link.preview(OpeningCall(activeDay, known, loaded.settings.rollover, time))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
    }

    Text("Plan a day", style = MaterialTheme.typography.titleMedium)
    if (loadFailed) {
        Text("Planning could not be read. Nothing was changed.", color = MaterialTheme.colorScheme.error)
        return
    }
    val zoneNow = zone
    if (zoneNow == null || loaded == null) {
        Text("Loading...")
        return
    }
    if (zoneNow is CheckInZone.Unusable) {
        Text(
            when (zoneNow.problem) {
                CheckInZoneProblem.NO_CHECK_IN_PLAN ->
                    "Planning needs the check-in plan's time zone, and no check-in plan is stored yet. Open the main screen once so check-ins create theirs, then come back."
                CheckInZoneProblem.INCONSISTENT ->
                    "The stored check-in plan is incomplete or names two different time zones, so planning will not guess one."
                CheckInZoneProblem.UNKNOWN_ZONE ->
                    "The stored check-in plan names a time zone this phone does not know, so planning will not guess one."
            },
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    val zoneId = (zoneNow as CheckInZone.Known).zone
    val systemZone = ZoneId.systemDefault()
    Text(
        "Time zone: ${zoneId.id}, from your check-in plan." +
            if (systemZone !=
                zoneId
            ) {
                " This phone is now in ${systemZone.id}; plans keep the check-in zone and nothing is rebased."
            } else {
                ""
            },
        style = MaterialTheme.typography.bodySmall,
    )

    // Setup: the first time, choose the window.
    if (loaded.settings.window == null) {
        Text("First, choose the hours you plan inside. This becomes your default; each day can use its own.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(setupStart, { setupStart = it }, label = { Text("Window start") }, modifier = Modifier.weight(1f))
            OutlinedTextField(setupEnd, { setupEnd = it }, label = { Text("Window end") }, modifier = Modifier.weight(1f))
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Button(enabled = !busy, onClick = {
            val start = clock(setupStart)
            val end = clock(setupEnd)
            if (start == null || end == null) {
                message = "Times are HH:mm."
            } else {
                busy = true
                scope.launch {
                    try {
                        val saved = PlanningSettings(loaded.settings.rollover, PlanningWindow(start, end))
                        access.withStore { it.saveSettings(saved) }
                        windowStart = ""
                        message = "Window saved as your default."
                        reload++
                    } catch (e: IllegalArgumentException) {
                        message = e.message ?: "Not saved."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = "Not saved: planning data could not be written."
                    } finally {
                        busy = false
                    }
                }
            }
        }) { Text("Save window as default") }
        return
    }

    if (activeDay == null) return
    Text("Active day $activeDay")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                day = activeDay.plusDays(-1)
                current = null
                clearForm()
                message = null
                reload++
            },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) { Text("Earlier day") }
        OutlinedButton(
            onClick = {
                day = activeDay.plusDays(1)
                current = null
                clearForm()
                message = null
                reload++
            },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) { Text("Later day") }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

    val titles = loaded.actions.associate { it.id to it.title }
    val approved = current
    val shown = proposal
    val approvalLocked = approved != null && adjustLocked(approved, loaded.outcomes)
    val replace = approved != null
    val large = isLargeText()
    val typedTime = clock(openingText)
    val dayText = DateTimeFormatter.ofPattern("EEE d MMM").format(activeDay.date)

    fun approve(
        shown: PlanProposal,
        acknowledge: Boolean,
        restDay: Boolean = false,
    ) {
        if (busy) return
        if (openingText.isNotBlank() && typedTime == null) {
            message = "The opening call time is HH:mm. Nothing was saved."
            return
        }
        busy = true
        scope.launch {
            try {
                // M2 is another database: its zone is read again just before the approval transaction.
                val zoneForApproval = (zoneSource.read() as? CheckInZone.Known)?.zone
                if (zoneForApproval == null) {
                    message = "The check-in plan's time zone is no longer usable. Nothing was saved."
                } else {
                    // Through the shared write path, so a saved plan tells the other tabs (Today) to read again.
                    val result =
                        writes.run({ it.approve(shown, zoneForApproval, acknowledge, restDay, typedTime) }) {
                            it is ApprovalResult.Approved
                        }
                    message =
                        when (result) {
                            is ApprovalResult.Approved ->
                                "Plan approved, revision ${result.plan.plan.revision}. No call was scheduled." +
                                    if (typedTime != null) " The opening time is kept; confirm the call on the plan below." else ""
                            is ApprovalResult.Refused ->
                                when (result.reason) {
                                    ApprovalRefusal.CHANGED ->
                                        "Something changed since this preview, so nothing was saved. " +
                                            "It was made again; check it and approve again."
                                    ApprovalRefusal.INVALID -> "This plan has problems to fix first. Nothing was saved."
                                    ApprovalRefusal.UNRESOLVED_NOT_ACKNOWLEDGED ->
                                        "Approve with the unresolved minutes, or fix the plan."
                                    ApprovalRefusal.REST_DAY_NOT_ACKNOWLEDGED ->
                                        "Approve the rest day explicitly, or select something."
                                    ApprovalRefusal.MALFORMED -> "This proposal breaks the planning rules, so nothing was saved."
                                    ApprovalRefusal.OUTCOMES_RECORDED ->
                                        "This day has recorded outcomes, so it cannot be adjusted. Nothing was saved."
                                }
                        }
                    if (result is ApprovalResult.Approved) {
                        undoOffer = if (replace) result.plan.plan.id else null
                        clearForm()
                        savedBar = if (replace) "Saved: plan replaced" else "Saved: first plan approved"
                    }
                }
                reload++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Not approved: planning data could not be written. Nothing was saved."
            } finally {
                busy = false
            }
        }
    }

    // The fixed bar above the tab bar. Its buttons call back into this section, which owns the proposal.
    val barActions = remember { PlanBarActions() }
    val barModel =
        if (previewing && shown != null) {
            val label =
                when {
                    shown.isRestDay -> "Approve rest day (nothing planned)"
                    shown.needsUnresolvedAcknowledgement -> "Approve with ${shown.overloadMinutes} minutes unresolved"
                    replace -> if (large) "Approve and replace" else "Approve and replace plan"
                    else -> "Approve first plan"
                }
            PlanBarModel(
                PlanBarKind.PENDING,
                label,
                shown.canApprove && !busy && !approvalLocked,
                if (replace) "Keep original" else "Discard",
                "",
                barActions,
            )
        } else if (savedBar != null) {
            PlanBarModel(PlanBarKind.SAVED, "", false, "", savedBar.orEmpty(), barActions)
        } else {
            null
        }
    SideEffect {
        barActions.approve = {
            if (shown != null) approve(shown, acknowledge = shown.needsUnresolvedAcknowledgement, restDay = shown.isRestDay)
        }
        barActions.secondary = {
            clearForm()
            message = if (replace) "Kept the original plan." else "Discarded. Nothing was saved."
        }
    }
    LaunchedEffect(barModel) { onBar(barModel) }
    DisposableEffect(Unit) { onDispose { onBar(null) } }
    LaunchedEffect(previewing, activeDay) { if (previewing) savedBar = null }
    LaunchedEffect(activeDay) { savedBar = null }

    if (previewing && shown != null) {
        // A pending proposal is only in memory. The approved plan stays in force until Approve is tapped.
        val text = previewText(shown) { titles[it] ?: "an action" }
        val review = reviewOfProposal(shown, approved)
        if (approvalLocked) Text(ADJUST_LOCKED_NOTE, color = MaterialTheme.colorScheme.error)
        PlanBanner(
            replace,
            dayText,
            approved?.let { "Revision ${it.plan.revision} (approved ${approvedAtText(it, zoneId)})" }.orEmpty(),
            review.counts,
        )
        CapacityCard(review, text.summary.take(1) + text.summary.drop(2))
        ReviewCard(
            header = {
                FlowRow(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Waiting for your approval",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text("Not saved yet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
                Text(
                    "Proposed plan, revision ${(approved?.plan?.revision ?: 0) + 1}",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            review = review,
            leftOutLines = text.leftOut,
            removedLine =
                if (review.removed.isEmpty()) {
                    null
                } else {
                    "Removed from the plan: " + review.removed.joinToString { "${it.title} (${it.minutes} min)" } +
                        ". They stay open actions."
                },
            onLeaveOut = { id ->
                Adjustment(selected, leftOutIds).leaveOut(id).let {
                    selected = it.selected
                    leftOutIds = it.leftOut
                }
            },
            leftOut = leftOutIds.filter { it !in selected }.map { it to (titles[it] ?: "an action") },
            onInclude = { id ->
                Adjustment(selected, leftOutIds).include(id).let {
                    selected = it.selected
                    leftOutIds = it.leftOut
                }
            },
        )
        ExplanationCard(
            explainProposal(
                shown,
                review,
                approved,
                leftOutIds.count { it !in selected },
            ) { titles[it] ?: "an action" },
        )
        text.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        text.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        Text(
            "Nothing is saved until you approve. Leave blocks out with their controls, or change the choices below. " +
                "Approving saves the plan only. No call is scheduled or changed.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            openingText,
            { openingText = it },
            label = { Text("Opening call time HH:mm (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val suggestion = (zone as? CheckInZone.Known)?.defaultOpening
        if (openingText.isBlank() && suggestion != null) {
            TextButton(onClick = { openingText = suggestion.toString() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Use my default opening time, $suggestion")
            }
        }
        if (openingText.isNotBlank() && typedTime == null) {
            Text("The opening call time is HH:mm.", color = MaterialTheme.colorScheme.error)
        } else if (typedTime != null) {
            Text(
                "Approving only keeps this time. A call is scheduled only after you approve, then tick and confirm it on the approved plan.",
                style = MaterialTheme.typography.bodySmall,
            )
            callPreview?.let { Text(callPreviewText(activeDay, typedTime, it), style = MaterialTheme.typography.bodySmall) }
        }

        if (large) {
            OutlinedButton(onClick = { barActions.secondary() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (replace) "Keep original" else "Discard")
            }
        }
    } else if (approved != null) {
        val text = approvedText(approved)
        val review = reviewOfApproved(approved, dayCommitments)
        CapacityCard(review, text.summary.drop(1))
        ReviewCard(
            header = {
                Text(
                    text.summary.first(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
            },
            review = review,
            leftOutLines = text.leftOut,
            removedLine = null,
        )
        text.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        Text(
            "Approving saves the plan only. No call is scheduled or changed.",
            style = MaterialTheme.typography.bodySmall,
        )
        val locked = adjustLocked(approved, loaded.outcomes)
        if (locked) Text(ADJUST_LOCKED_NOTE, style = MaterialTheme.typography.bodySmall)
        loaded.calls[approved.plan.id]?.let { call ->
            OpeningCallSection(approved, call, access, link, reload) { text ->
                message = text
                reload++
            }
        }
        if (undoOffer == approved.plan.id && approved.plan.revision > 1 && locked) {
            Text(UNDO_LOCKED_NOTE, style = MaterialTheme.typography.bodySmall)
        }
        if (undoOffer == approved.plan.id && approved.plan.revision > 1 && !locked) {
            val restoring = approved.plan.revision - 1
            Text(
                "Undo restores revision $restoring as revision ${approved.plan.revision + 1}. Nothing is deleted.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val result = writes.undoApproval(approved.plan.id)
                            message = undoText(result)
                            if (result !is UndoResult.Refused || result.reason != UndoRefusal.OUTCOMES_RECORDED) undoOffer = null
                            if (result is UndoResult.Restored) savedBar = "Saved: approval undone"
                            reload++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = "Not undone: planning data could not be written. Nothing was changed."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Undo this approval") }
        }
        TextButton(enabled = !locked, onClick = {
            val prefill = adjustPrefill(approved, loaded.actions)
            selected = prefill.selected
            pinTexts = prefill.pins.mapValues { it.value.toString() }
            windowStart =
                approved.plan.window.start
                    .toString()
            windowEnd =
                approved.plan.window.end
                    .toString()
            leftOutIds = emptySet()
            previewing = true
            message = adjustMessage(approved.plan.revision, prefill)
        }) { Text("Adjust this plan") }

        if (showOutcomes) {
            // Outcomes: plain, one tap per result, only for items that can take one today. Nothing prompts.
            val today = outcomeToday(PlanningClock.now(), zoneId, loaded.settings.rollover, loaded.workingDays)
            val rows = outcomeRows(approved, loaded.outcomes, loaded.actions, today)
            if (approved.plan.activeDay > today) {
                Text("Outcomes can be recorded once this day has started.", style = MaterialTheme.typography.bodySmall)
            } else if (rows.isNotEmpty()) {
                Text("Outcomes", style = MaterialTheme.typography.labelLarge)
                rows.forEach { row ->
                    Text(outcomeRowText(row))
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        OutcomeResult.entries.forEach { result ->
                            TextButton(
                                enabled = !busy && result != (row.result ?: OutcomeResult.UNKNOWN),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            message =
                                                when (val recorded = writes.recordOutcome(row.itemId, result, today)) {
                                                    is OutcomeRecording.Recorded ->
                                                        recordedText(result, recorded.effect.staysDoneNote)
                                                    is OutcomeRecording.Refused -> outcomeRefusalText(recorded.reason)
                                                }
                                            reload++
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            message = "Not saved: planning data could not be written."
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                            ) {
                                Text(
                                    result.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        Text("No plan approved for this day yet.", style = MaterialTheme.typography.bodySmall)
    }

    // Selection: only the actions chosen are planned. Unfinished ones are offered, unselected.
    val open = loaded.actions.filter { it.status == ActionStatus.OPEN }
    val unfinished = unfinishedEntries(activeDay, loaded.approved, loaded.actions, loaded.outcomes).associateBy { it.action.id }
    Text("Choose what to plan", style = MaterialTheme.typography.labelLarge)
    if (open.isEmpty()) Text("There are no open actions. Add some above.", style = MaterialTheme.typography.bodySmall)
    open.sortedWith(compareBy({ it.id !in unfinished }, { it.title })).forEach { action ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = action.id in selected, onCheckedChange = { on ->
                selected = if (on) selected + action.id else selected - action.id
            })
            Text(
                (unfinished[action.id]?.let { unfinishedLabel(it) } ?: "") +
                    "${action.title} (${action.priority.name.lowercase()}, ${action.estimateMinutes} min)",
                modifier = Modifier.weight(1f),
            )
            if (action.id in selected) {
                OutlinedTextField(
                    pinTexts[action.id] ?: "",
                    { pinTexts = pinTexts + (action.id to it) },
                    label = { Text("Pin HH:mm") },
                    singleLine = true,
                    modifier = Modifier.weight(0.7f),
                )
            }
        }
    }
    Text("This day's window", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(windowStart, { windowStart = it }, label = { Text("Start") }, modifier = Modifier.weight(1f))
        OutlinedTextField(windowEnd, { windowEnd = it }, label = { Text("End") }, modifier = Modifier.weight(1f))
    }
    val default = loaded.settings.window
    if (default != null) {
        TextButton(onClick = {
            windowStart = default.start.toString()
            windowEnd = default.end.toString()
        }) { Text("Use the default ${default.start} to ${default.end}") }
    }
    if (!previewing) {
        Button(onClick = {
            leftOutIds = emptySet()
            previewing = true
        }) { Text(if (selected.isEmpty()) "Preview a rest day (nothing planned)" else "Preview plan") }
    }
}

/**
 * The opening call of an approved plan: the stored status, M2's live state beside it, and the one explicit action
 * its status allows. Every action needs an unchecked box ticked, and the link re-reads the stored call, the plan and
 * the clock before it does anything, so what this screen loaded earlier is never the authority. ADR 0009 slice 3.
 */
@Composable
private fun OpeningCallSection(
    plan: DayPlanWithItems,
    call: PlanCall,
    access: PlanningAccess,
    link: OpeningCallLink,
    reload: Int,
    onDone: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<CallPreview?>(null) }
    var attempts by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    val opening = OpeningCall(plan.plan.activeDay, plan.plan.zone, plan.plan.rollover, call.openingTime)
    LaunchedEffect(plan.plan.id, call, reload) {
        preview =
            try {
                link.preview(opening)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
    }
    val live = plan.plan.activeDay >= ActiveDay.containing(Instant.now(), plan.plan.zone, plan.plan.rollover)
    // A stale REFUSED or NEEDS_ATTENTION status is brought up to date when M2 already holds the call at this time. This
    // writes only the displayed status, never M2, and grants no authorization.
    LaunchedEffect(plan.plan.id, call, preview) {
        val unsettled = call.status == CallStatus.REFUSED.name || call.status == CallStatus.NEEDS_ATTENTION.name
        if (unsettled && preview?.slot == SlotState.ScheduledAtTime) {
            try {
                if (access.withStore { link.reconcile(it, plan.plan.id) }) onDone("The call is already confirmed at ${call.openingTime}.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The status stays as it was; the live line above is still accurate.
            }
        }
    }
    val recovery = preview?.slot?.let { recoveryOf(it) }
    val id = plan.plan.id
    val action: Pair<String, suspend (PlanningStore, Boolean) -> CallAttempt>? =
        when {
            !live -> null
            call.status == CallStatus.NOT_LINKED.name ->
                "Confirm the opening call at ${call.openingTime} on ${plan.plan.activeDay}" to { s, t -> link.confirm(s, id, t) }
            call.status == CallStatus.REFUSED.name -> "Try again at ${call.openingTime}" to { s, t -> link.tryAgain(s, id, t) }
            call.status == CallStatus.NEEDS_ATTENTION.name && recovery != null ->
                recoveryOffer(recovery, preview?.slot, call.openingTime) to { s, t -> link.recover(s, id, recovery, t) }
            else -> null
        }

    // The tick is keyed to everything it refers to, and to how many actions have run, so it starts unticked whenever the
    // day, plan, time, preview or offered action changes, and after every action.
    var ticked by remember(TickTarget(id, call, preview, action?.first, attempts)) { mutableStateOf(false) }

    Text(callStatusText(call, dayPassed = !live), style = MaterialTheme.typography.bodySmall)
    preview?.let { Text(callPreviewText(plan.plan.activeDay, call.openingTime, it), style = MaterialTheme.typography.bodySmall) }
    if (action != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = ticked, onCheckedChange = { ticked = it })
            Text(action.first, modifier = Modifier.weight(1f))
        }
        Button(enabled = ticked && !working, onClick = {
            working = true
            scope.launch {
                try {
                    onDone(attemptText(access.withStore { action.second(it, ticked) }))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onDone("The call could not be changed: planning data could not be read or written.")
                } finally {
                    attempts++
                    working = false
                }
            }
        }) { Text("Confirm") }
    }
}

/** When a plan was approved, as the person's own weekday and clock time in the plan's zone. */
private fun approvedAtText(
    plan: DayPlanWithItems,
    zone: ZoneId,
): String = DateTimeFormatter.ofPattern("EEE HH:mm").withZone(zone).format(Instant.ofEpochMilli(plan.plan.approvedAtMillis))
