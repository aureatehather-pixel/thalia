package com.thalia.app.planning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.thalia.app.planning.data.OutcomeRecording
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.OutcomeResult
import com.thalia.planning.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private class TodayData(
    val zone: ZoneId?,
    val today: ActiveDay?,
    val view: TodayView?,
)

/** What an outcome tap just did, so Undo can put the previous result back through the same validated write. */
private class SavedNote(
    val itemId: String,
    val text: String,
    val previous: OutcomeResult?,
)

/** One row or card's target for the focus screen: everything it needs, nothing it has to look up again. */
private class FocusTarget(
    val itemId: String,
    val title: String,
    val line: String,
    val block: TodayBlock?,
    val result: OutcomeResult?,
    val actionStatus: ActionStatus?,
    val canRecord: Boolean,
)

/**
 * The Today tab, computed from the person's own approved plan, outcomes and commitments: the block in progress with time left,
 * what is up next, earlier blocks (collapsed), the flexible queue, and a focus screen for one item.
 *
 * An outcome is recorded only by a tap on a result, through [PlanningWrites.recordOutcome]; a block ending, a model reply and
 * a spoken or typed "yes" record nothing. Undo records the previous result the same way. Without an approved plan it says so
 * and offers to plan; it shows no placeholder data.
 */
@Composable
internal fun TodaySection(
    access: PlanningAccess,
    zoneSource: CheckInZoneSource,
    writes: PlanningWrites,
    changes: Int,
    onPlanTab: () -> Unit,
    focusId: String?,
    onFocus: (String?) -> Unit,
    onPrompt: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<TodayData?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(PlanningClock.now()) }
    var pastOpen by rememberSaveable { mutableStateOf(false) }
    var saved by remember { mutableStateOf<SavedNote?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Opening or leaving the focus screen starts clean: the last note and its Undo belong to the screen they were made on.
    val onFocusChange: (String?) -> Unit = {
        message = null
        saved = null
        onFocus(it)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = PlanningClock.now()
        }
    }
    LaunchedEffect(reload, changes, now.epochSecond / TICK_SECONDS) {
        try {
            val zone = (zoneSource.read() as? CheckInZone.Known)?.zone
            val at = PlanningClock.now()
            data =
                if (zone == null) {
                    TodayData(null, null, null)
                } else {
                    access.withStore { store ->
                        val settings = store.settings()
                        val days = store.workingDays()
                        val day = planningDay(at, zone, settings.rollover, days)
                        val today = outcomeToday(at, zone, settings.rollover, days)
                        val view =
                            buildTodayView(
                                at,
                                today,
                                store.planFor(day),
                                store.outcomes().associateBy { it.planItemId },
                                store.actions(),
                                store.commitmentsOf(day, zone, settings.rollover),
                            )
                        TodayData(zone, today, view)
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }

    fun record(
        itemId: String,
        result: OutcomeResult,
        previous: OutcomeResult?,
        today: ActiveDay,
        undo: Boolean,
    ) {
        busy = true
        scope.launch {
            try {
                when (val recorded = writes.recordOutcome(itemId, result, today)) {
                    is OutcomeRecording.Recorded -> {
                        message = recordedText(result, recorded.effect.staysDoneNote)
                        saved = if (undo) null else SavedNote(itemId, message.orEmpty(), previous)
                    }
                    is OutcomeRecording.Refused -> {
                        message = outcomeRefusalText(recorded.reason)
                        saved = null
                    }
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
    }

    if (failed) {
        Text("Today could not be read. Nothing was changed.", color = MaterialTheme.colorScheme.error)
        return
    }
    val loaded = data
    if (loaded == null) {
        Text("Loading...")
        return
    }
    val zone = loaded.zone
    val today = loaded.today
    val view = loaded.view
    if (zone == null || today == null || view == null) {
        Text("Today needs the check-in plan's time zone, which is not available yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val clock = remember(zone) { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    // One outcome prompt at a time: while this card asks, the working-day question below waits (see PromptRule).
    val asking = PromptRule.cardShown(view, focusId)
    LaunchedEffect(asking) { onPrompt(asking) }

    fun at(instant: Instant): String = clock.format(instant)

    val focus: FocusTarget? =
        focusId?.let { id ->
            val blocks = listOfNotNull(view.now) + view.upNext + view.earlier
            blocks.firstOrNull { it.itemId == id }?.let {
                FocusTarget(
                    id,
                    it.title,
                    "${at(it.start)} to ${at(it.end)}" + (it.priority?.let { p -> " · ${p.label()}" } ?: ""),
                    it,
                    it.result,
                    it.actionStatus,
                    it.canRecord,
                )
            } ?: view.queue.firstOrNull { it.itemId == id }?.let {
                FocusTarget(
                    id,
                    it.title,
                    "${it.priority.label()} · ${it.minutes} min, no time",
                    null,
                    it.result,
                    it.actionStatus,
                    it.canRecord,
                )
            }
        }
    if (focusId != null && focus == null) onFocusChange(null)
    BackHandler(enabled = focus != null) { onFocusChange(null) }

    message?.let { text ->
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(start = 12.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                val undoable = saved
                if (undoable != null) {
                    TextButton(
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { record(undoable.itemId, undoable.previous ?: OutcomeResult.UNKNOWN, null, today, undo = true) },
                    ) { Text("Undo") }
                }
            }
        }
    }

    if (focus != null) {
        FocusScreen(
            target = focus,
            now = now,
            after = focus.block?.let { view.after(it) },
            clockText = ::at,
            busy = busy,
            onBack = { onFocusChange(null) },
            onPick = { result -> record(focus.itemId, result, focus.result, today, undo = false) },
        )
        return
    }

    if (!view.hasPlan) {
        TodayCard {
            Text("No plan for today", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                "Plan the day with Thalia, or by hand. A day with nothing more is fine too.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onPlanTab, modifier = Modifier.heightIn(min = 48.dp)) { Text("Plan the day") }
        }
        TodayCard {
            Text("Fixed commitments", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            val fixed = view.upNext + view.earlier + listOfNotNull(view.now)
            if (fixed.isEmpty()) {
                Text("None recorded.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            fixed.sortedBy { it.start }.forEach {
                Text(
                    "${at(it.start)} to ${at(it.end)}  ${it.title}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }

    view.now?.let { block ->
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val left = minutesLeft(block, now)
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Now · ${at(block.start)} to ${at(block.end)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "$left min left",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        block.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        block.priority?.label() ?: "Fixed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                LinearProgressIndicator(
                    progress = { progressOf(block, now) },
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f),
                    drawStopIndicator = {},
                    modifier =
                        Modifier.fillMaxWidth().semantics {
                            contentDescription = "Progress"
                            stateDescription = "${(progressOf(block, now) * 100).toInt()} percent of the block has passed"
                        },
                )
                block.itemId?.let { id ->
                    Button(onClick = { onFocusChange(id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open focus") }
                }
            }
        }
    }
    if (view.now == null) {
        Text(
            if (view.upNext.isEmpty()) {
                "Nothing is in progress and nothing else is scheduled today."
            } else {
                "Nothing is in progress right now."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    view.toRecord?.takeIf { asking }?.let { block ->
        TodayCard {
            Text("${block.title}: how did it go?", style = MaterialTheme.typography.titleSmall)
            ResultChips(
                selected = block.result,
                enabled = !busy,
            ) { result -> record(block.itemId.orEmpty(), result, block.result, today, undo = false) }
            Text(
                "A block ending never marks work done. You record what happened.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    TodayCard {
        Text("Up next", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (view.upNext.isEmpty()) {
            Text(
                "Nothing else is scheduled today.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        view.upNext.forEach { block ->
            TimelineRow(
                time = at(block.start),
                title = block.title,
                detail =
                    "${at(block.start)} to ${at(block.end)} · " + (block.priority?.let { "${it.label()} · " } ?: "fixed · ") +
                        "${minutesOf(block)} min",
                kind = if (block.fixed) "Fixed" else "Focus",
                onOpen = block.itemId?.let { id -> { onFocusChange(id) } },
            )
        }
    }

    if (view.earlier.isNotEmpty()) {
        TodayCard {
            val missing = view.earlierToRecord
            val summary =
                "Earlier today · ${view.earlier.size} block${if (view.earlier.size == 1) "" else "s"}" +
                    if (missing > 0) " · $missing to record" else ""
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(summary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { pastOpen = !pastOpen },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (pastOpen) "Expanded" else "Collapsed" },
                ) { Text(if (pastOpen) "Hide" else "Show") }
            }
            if (pastOpen) {
                view.earlier.forEach { block ->
                    TimelineRow(
                        time = at(block.start),
                        title = block.title,
                        detail =
                            when {
                                block.fixed -> "fixed"
                                block.result != null -> resultWords(block.result)
                                else -> "No outcome"
                            } + (if (block.actionStatus == ActionStatus.DONE) " (action done)" else ""),
                        kind = null,
                        onOpen = block.itemId?.let { id -> { onFocusChange(id) } },
                    )
                }
            }
        }
    }

    TodayCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Flexible queue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("in order, no times", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (view.queue.isEmpty()) {
            Text(
                "Nothing is waiting in the queue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        view.queue.forEach { q ->
            TimelineRow(
                time = null,
                title = q.title,
                detail = "${q.priority.label()} · ${q.minutes} min" + (q.result?.let { " · ${resultWords(it)}" } ?: ""),
                kind = null,
                onOpen = { onFocusChange(q.itemId) },
            )
        }
    }
}

@Composable
private fun FocusScreen(
    target: FocusTarget,
    now: Instant,
    after: TodayBlock?,
    clockText: (Instant) -> String,
    busy: Boolean,
    onBack: () -> Unit,
    onPick: (OutcomeResult) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Focus", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to Today") }
    }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(target.line, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(target.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            target.block?.let { block ->
                val running = block.start <= now && now < block.end
                Text(
                    when {
                        running -> "${minutesLeft(block, now)} min left"
                        now < block.start -> "Starts at ${clockText(block.start)}"
                        else -> "Ended at ${clockText(block.end)}"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (running) {
                    LinearProgressIndicator(
                        progress = { progressOf(block, now) },
                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f),
                        drawStopIndicator = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
    TodayCard {
        Text("How did it go?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Text(
            "A block ending never marks work done. You record it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (target.canRecord) {
            ResultChips(selected = target.result, enabled = !busy, onPick = onPick)
        } else {
            Text(
                "An outcome can be recorded for a planned item of the latest plan, once its day has started.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (target.actionStatus == ActionStatus.DONE) {
            Text("The action is done.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    after?.let {
        TodayCard {
            Text("After this", style = MaterialTheme.typography.titleSmall)
            Text("${it.title}, ${clockText(it.start)}" + if (it.fixed) ", fixed" else "", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The five results, one tap each. The current one is shown as selected, and an unrecorded item shows Unknown. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultChips(
    selected: OutcomeResult?,
    enabled: Boolean,
    onPick: (OutcomeResult) -> Unit,
) {
    val current = selected ?: OutcomeResult.UNKNOWN
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutcomeResult.entries.forEach { result ->
            FilterChip(
                selected = result == current,
                enabled = enabled,
                onClick = { if (result != current) onPick(result) },
                label = { Text(result.name.lowercase().replaceFirstChar { it.uppercase() }) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun TimelineRow(
    time: String?,
    title: String,
    detail: String,
    kind: String?,
    onOpen: (() -> Unit)?,
) {
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (time != null) Text(time, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 2.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                    kind?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (onOpen == null) {
        body()
    } else {
        Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) { body() }
    }
}

@Composable
private fun TodayCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

private fun Priority.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private fun minutesOf(block: TodayBlock): Long =
    java.time.Duration
        .between(block.start, block.end)
        .toMinutes()

private const val TICK_MS = 30_000L
private const val TICK_SECONDS = 30L
