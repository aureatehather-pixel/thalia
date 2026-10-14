package com.thalia.app.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thalia.app.planning.data.WorkingDayChange
import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.FixedCommitment
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanOutcome
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningText
import com.thalia.planning.ReEntries
import com.thalia.planning.ReEntry
import com.thalia.planning.WorkingDayRecord
import com.thalia.planning.WorkingDayState
import com.thalia.planning.WorkingDays
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private class WorkingDayData(
    val settings: PlanningSettings,
    val rows: List<WorkingDayRecord>,
    val actions: List<Action>,
    val outcomes: Map<String, PlanOutcome>,
    val plan: DayPlanWithItems?,
    val commitments: List<FixedCommitment>,
    val state: WorkingDayState,
    val now: Instant,
)

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun ActiveDay.label() = date.format(dayFormat)

/**
 * The working day, ADR 0011: open, continue or start, close, focus capacity and re-entry. Plain and functional.
 *
 * Nothing here is automatic. Opening and closing are the person's taps, closing needs no outcomes and no note, and the
 * one re-entry question can be answered "don't know", after which nothing more is asked while the screen is open.
 */
@Composable
internal fun WorkingDaySection(
    access: PlanningAccess,
    zoneSource: CheckInZoneSource,
    writes: PlanningWrites,
    changes: Int,
    mayAsk: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var zone by remember { mutableStateOf<ZoneId?>(null) }
    var data by remember { mutableStateOf<WorkingDayData?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var declined by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(changes, reload) {
        try {
            val known = (zoneSource.read() as? CheckInZone.Known)?.zone
            zone = known
            if (known == null) return@LaunchedEffect
            val now = PlanningClock.now()
            data =
                access.withStore { store ->
                    val settings = store.settings()
                    val rows = store.workingDays()
                    val state = WorkingDays.resolve(now, known, settings.rollover, rows)
                    val day = (state as? WorkingDayState.Current)?.day
                    WorkingDayData(
                        settings,
                        rows,
                        store.actions(),
                        store.outcomes().associateBy { it.planItemId },
                        day?.let { store.planFor(it) },
                        day?.let { store.commitmentsOf(it, known, settings.rollover) }.orEmpty(),
                        state,
                        now,
                    )
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "The working day could not be read. Nothing was changed."
        }
    }

    fun act(
        done: (String) -> Unit = { message = it },
        write: suspend (com.thalia.app.planning.data.PlanningStore) -> String?,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val text = writes.run(write) { it != null }
                done(text ?: "Nothing was changed.")
            } catch (e: IllegalArgumentException) {
                message = e.message ?: "Not saved."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Not saved: planning data could not be written."
            } finally {
                busy = false
                reload++
            }
        }
    }

    Text("Working day", style = MaterialTheme.typography.titleMedium)
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    val known = zone ?: return Text("The working day needs the check-in time zone, which is not usable yet.")
    val loaded = data ?: return Text("Loading...")
    val lastNote = loaded.rows.filter { it.closedAt != null && it.closingNote != null }.maxByOrNull { it.day }
    lastNote?.let { Text("From your last closing (${it.day.label()}): ${it.closingNote}", style = MaterialTheme.typography.bodySmall) }

    fun describe(change: WorkingDayChange): String? =
        when (change) {
            is WorkingDayChange.Opened -> if (change.reopened) "Reopened ${change.day.label()}." else "Opened ${change.day.label()}."
            is WorkingDayChange.Closed -> "Closed ${change.day.label()}. Anything unrecorded stays unknown."
            is WorkingDayChange.Continued -> "Continuing ${change.day.label()}."
            is WorkingDayChange.AnotherOpen -> "Not opened: ${change.day.label()} is still open."
            WorkingDayChange.NotApplicable -> null
        }

    when (val state = loaded.state) {
        is WorkingDayState.NoneOpen -> {
            Text("No working day is open. Planning shows ${state.clockDay.label()}.")
            Button(enabled = !busy, onClick = { act { describe(it.openWorkingDay(PlanningClock.now(), known)) } }) {
                Text((if (state.reopens) "Reopen " else "Open ") + state.openable.label())
            }
        }
        is WorkingDayState.Choose -> {
            Text(
                "Your working day of ${state.open.label()} is still open (opened ${state.openedAt.atZone(known).format(dayFormat)} " +
                    "${state.openedAt.atZone(known).format(timeFormat)}). Nothing from it is recommended until you choose.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { act { describe(it.continueWorkingDay(state.open, state.clockDay)) } }) {
                    Text("Continue ${state.open.label()}")
                }
                Button(enabled = !busy, onClick = { act { describe(it.startWorkingDay(state.open, PlanningClock.now(), known)) } }) {
                    Text("Start ${state.startable.label()}")
                }
            }
        }
        is WorkingDayState.Current -> {
            val day = state.day
            val row = loaded.rows.single { it.day == day }
            Text("Working day ${day.label()}, opened ${row.openedAt?.atZone(known)?.format(timeFormat)}.")
            FocusRow(row.focusMinutes, busy) { minutes ->
                act { store ->
                    store.setFocus(day, minutes)
                    if (minutes == null) "Focus capacity cleared." else "Focus capacity $minutes min. An approved plan is not changed."
                }
            }
            val plan = loaded.plan
            if (plan == null) {
                Text("No plan approved for this day yet. Plan it below, or work without one.", style = MaterialTheme.typography.bodySmall)
            } else {
                val reEntry =
                    ReEntries.compute(
                        plan,
                        loaded.outcomes,
                        loaded.actions.associate { it.id to it.status },
                        loaded.commitments,
                        row.focusMinutes,
                        loaded.now,
                        declined,
                    )
                ReEntryCard(reEntry, busy, mayAsk, onAnswer = {
                    itemId,
                    result,
                    ->
                    act { store -> record(store, itemId, result, known) }
                }, onDecline = {
                    declined = true
                })
            }
            if (!closing) {
                OutlinedButton(enabled = !busy, onClick = { closing = true }) { Text("Close ${day.label()}") }
            } else {
                ClosingPanel(access, writes, changes, day, plan, loaded, known, busy, onRecord = { itemId, result ->
                    act { store -> record(store, itemId, result, known) }
                }, onEstimate = { action, minutes ->
                    act { store ->
                        if (store.saveAction(action.copy(estimateMinutes = minutes), action.status) ==
                            null
                        ) {
                            "Remaining estimate saved."
                        } else {
                            null
                        }
                    }
                }, onClose = { note ->
                    act({
                        message = it
                        closing = false
                    }) { describe(it.closeWorkingDay(day, PlanningClock.now(), note)) }
                }, onCancel = { closing = false })
            }
        }
    }
}

/** Records an outcome inside the write already running, with "today" read from the working day at that moment. */
private suspend fun record(
    store: com.thalia.app.planning.data.PlanningStore,
    itemId: String,
    result: OutcomeResult,
    zone: ZoneId,
): String {
    val today = outcomeToday(PlanningClock.now(), zone, store.settings().rollover, store.workingDays())
    return when (store.recordOutcome(itemId, result, today)) {
        is com.thalia.app.planning.data.OutcomeRecording.Recorded -> "Recorded: ${result.name.lowercase()}."
        is com.thalia.app.planning.data.OutcomeRecording.Refused -> "Not recorded: that item cannot take an outcome now."
    }
}

@Composable
private fun FocusRow(
    current: Int?,
    busy: Boolean,
    save: (Int?) -> Unit,
) {
    var text by remember(current) { mutableStateOf(current?.toString() ?: "") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, { text = it }, label = { Text("Focus available today (min), optional") }, modifier = Modifier.weight(1f))
        Button(enabled = !busy, onClick = {
            val minutes = text.trim().toIntOrNull()
            if (minutes != null && minutes in WorkingDayRecord.FOCUS_RANGE) save(minutes)
        }) { Text("Save focus") }
        if (current != null) TextButton(enabled = !busy, onClick = { save(null) }) { Text("Clear") }
    }
}

/** The next approved action, notices that change what is feasible, and at most one question. ADR 0011, "Re-entry". */
@Composable
private fun ReEntryCard(
    reEntry: ReEntry,
    busy: Boolean,
    mayAsk: Boolean,
    onAnswer: (String, OutcomeResult) -> Unit,
    onDecline: () -> Unit,
) {
    Text(reEntry.next?.let { "Next in your approved order: ${it.title}" } ?: "Nothing left in the approved plan.")
    if (reEntry.windowOver) Text("The planning window has ended.", style = MaterialTheme.typography.bodySmall)
    if (reEntry.overrunMinutes > 0 && !reEntry.windowOver) {
        Text(
            "About ${reEntry.overrunMinutes} min more is planned than the ${reEntry.remainingFreeMinutes} free min left in the window.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    reEntry.freeNowMinutes?.let {
        Text("Free time in the window is now $it min, after commitments changed.", style = MaterialTheme.typography.bodySmall)
    }
    reEntry.focusBelowPlanMinutes?.let {
        Text("Your focus capacity, $it min, is less than the plan's queued work.", style = MaterialTheme.typography.bodySmall)
    }
    val question = reEntry.question?.takeIf { mayAsk } ?: return
    Text("Did \"${question.title}\" happen? It decides whether the rest fits.", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        listOf(OutcomeResult.DONE, OutcomeResult.PARTIAL, OutcomeResult.SKIPPED, OutcomeResult.BLOCKED).forEach { result ->
            TextButton(enabled = !busy, contentPadding = PaddingValues(horizontal = 4.dp), onClick = { onAnswer(question.id, result) }) {
                Text(result.name.lowercase().replaceFirstChar { it.uppercase() })
            }
        }
        TextButton(enabled = !busy, contentPadding = PaddingValues(horizontal = 4.dp), onClick = onDecline) { Text("Don't know") }
    }
}

/** Closing: optional outcomes, an optional remaining estimate after Partial, an optional note. Nothing is required. */
@Composable
private fun ClosingPanel(
    access: PlanningAccess,
    writes: PlanningWrites,
    changes: Int,
    day: ActiveDay,
    plan: DayPlanWithItems?,
    loaded: WorkingDayData,
    zone: ZoneId,
    busy: Boolean,
    onRecord: (String, OutcomeResult) -> Unit,
    onEstimate: (Action, Int) -> Unit,
    onClose: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    Text("Closing ${day.label()}", style = MaterialTheme.typography.labelLarge)
    Text(
        "Record what you know. Anything left is kept as unknown, open actions stay available, and nothing is put into tomorrow.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (plan != null) {
        val today = outcomeToday(PlanningClock.now(), zone, loaded.settings.rollover, loaded.rows)
        outcomeRows(plan, loaded.outcomes, loaded.actions, today).forEach { row ->
            Text(outcomeRowText(row))
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                listOf(OutcomeResult.DONE, OutcomeResult.PARTIAL, OutcomeResult.SKIPPED, OutcomeResult.BLOCKED).forEach { result ->
                    TextButton(
                        enabled = !busy && result != row.result,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        onClick = { onRecord(row.itemId, result) },
                    ) { Text(result.name.lowercase().replaceFirstChar { it.uppercase() }) }
                }
            }
            val action = loaded.actions.firstOrNull { it.id == plan.items.single { item -> item.id == row.itemId }.actionId }
            if (row.result == OutcomeResult.PARTIAL && action != null) RemainingEstimate(action, busy, onEstimate)
        }
    }
    // ADR 0012: an optional small batch from the Inbox. Closing never needs it, and nothing here is required.
    var inboxOpen by remember { mutableStateOf(false) }
    TextButton(onClick = {
        inboxOpen = !inboxOpen
    }) { Text(if (inboxOpen) "Hide the Inbox" else "Look at the Inbox first (up to $INBOX_PAGE)") }
    if (inboxOpen) InboxOpenList(access, writes, changes, limit = INBOX_PAGE)
    var note by remember { mutableStateOf("") }
    OutlinedTextField(note, { note = it }, label = { Text("Note for the next opening, optional") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = { onClose(PlanningText.clean(note).ifEmpty { null }) }) { Text("Close ${day.label()}") }
        TextButton(onClick = onCancel) { Text("Not now") }
    }
}

@Composable
private fun RemainingEstimate(
    action: Action,
    busy: Boolean,
    save: (Action, Int) -> Unit,
) {
    var text by remember(action) { mutableStateOf(action.estimateMinutes.toString()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, { text = it }, label = { Text("Remaining estimate (min), optional") }, modifier = Modifier.weight(1f))
        TextButton(
            enabled = !busy,
            onClick = {
                text
                    .trim()
                    .toIntOrNull()
                    ?.takeIf { it in Action.ESTIMATE_RANGE }
                    ?.let { save(action, it) }
            },
        ) {
            Text("Save")
        }
    }
}
