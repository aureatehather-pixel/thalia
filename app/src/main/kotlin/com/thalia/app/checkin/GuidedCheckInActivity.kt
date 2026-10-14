package com.thalia.app.checkin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thalia.app.ThaliaApplication
import com.thalia.app.planning.CapacityCard
import com.thalia.app.planning.CheckInZone
import com.thalia.app.planning.PlanBanner
import com.thalia.app.planning.PlanningAccess
import com.thalia.app.planning.PlanningWrites
import com.thalia.app.planning.ReviewCard
import com.thalia.app.planning.data.ApprovalRefusal
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.OutcomeRecording
import com.thalia.app.planning.outcomeRefusalText
import com.thalia.app.planning.outcomeRowText
import com.thalia.app.planning.previewText
import com.thalia.app.planning.recordedText
import com.thalia.app.planning.reviewOfApproved
import com.thalia.app.planning.reviewOfProposal
import com.thalia.app.ui.ThaliaTab
import com.thalia.app.ui.ThaliaTheme
import com.thalia.planning.Action
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanProposal
import com.thalia.planning.PlanningText
import com.thalia.voicecheckins.domain.CheckInType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/**
 * The structured check-in, used when AI is off or has failed, or whenever the person chooses it. It makes no model or network
 * request. Opening it, from a call or a button, only reads: nothing is saved, approved or closed until an explicit tap.
 */
class GuidedCheckInActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ThaliaApplication
        val start = intent.getStringExtra(EXTRA_TYPE)?.let { n -> CheckInType.entries.firstOrNull { it.name == n } } ?: CheckInType.OPENING
        setContent { ThaliaTheme { GuidedScreen(app, start, onClose = ::finish) } }
    }

    companion object {
        const val EXTRA_TYPE = "com.thalia.app.checkin.TYPE"

        fun intent(
            context: Context,
            type: CheckInType,
        ): Intent = Intent(context, GuidedCheckInActivity::class.java).putExtra(EXTRA_TYPE, type.name)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuidedScreen(
    app: ThaliaApplication,
    start: CheckInType,
    onClose: () -> Unit,
) {
    val access: PlanningAccess = app.planningAccess
    val scope = rememberCoroutineScope()
    var changes by remember { mutableIntStateOf(0) }
    val writes = remember(access) { PlanningWrites(access) { changes++ } }
    var typeName by rememberSaveable { mutableStateOf(start.name) }
    val type = CheckInType.valueOf(typeName)
    var state by remember { mutableStateOf<GuidedState?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var proposing by rememberSaveable { mutableStateOf(false) }
    var proposal by remember { mutableStateOf<PlanProposal?>(null) }

    LaunchedEffect(changes) {
        try {
            val zone = (app.checkInZone.read() as? CheckInZone.Known)?.zone
            if (zone == null) {
                problem = "Planning needs the check-in plan's time zone, which is not usable yet."
            } else {
                state = access.withStore { loadGuided(it, zone) }
                problem = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "Planning could not be read. Nothing was changed."
        }
    }
    // A restored proposal is made again from the stored data, never kept: only Approve saves anything.
    LaunchedEffect(proposing, state) {
        val s = state
        proposal =
            if (proposing && s != null) {
                try {
                    access.withStore { carryProposal(it, s) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Guided check-in",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            }
            Text(
                "No AI and no internet are used here. Nothing is saved, approved or closed until you tap it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CheckInType.OPENING to "Opening", CheckInType.CLOSING to "Closing").forEach { (t, label) ->
                    FilterChip(
                        selected = type == t,
                        onClick = {
                            typeName = t.name
                            proposing = false
                            message = null
                        },
                        label = { Text(label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            val s = state
            if (s == null) {
                if (problem == null) Text("Loading...")
            } else if (type == CheckInType.OPENING) {
                OpeningPart(s, onPlanByHand = { ctxGoToPlan(app) })
            } else {
                ClosingPart(
                    s,
                    busy,
                    proposing,
                    proposal,
                    onRecord = { itemId, result ->
                        busy = true
                        scope.launch {
                            try {
                                message =
                                    when (val r = writes.recordOutcome(itemId, result, s.outcomeDay)) {
                                        is OutcomeRecording.Recorded -> recordedText(result, r.effect.staysDoneNote)
                                        is OutcomeRecording.Refused -> outcomeRefusalText(r.reason)
                                    }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = "Not saved: planning data could not be written."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    onEstimate = { action, minutes ->
                        busy = true
                        scope.launch {
                            try {
                                message =
                                    if (writes.saveAction(action.copy(estimateMinutes = minutes), action.status) == null) {
                                        "Remaining estimate saved: ${action.title}, $minutes min."
                                    } else {
                                        "Not saved: that action changed. Nothing was changed."
                                    }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = "Not saved: ${e.message ?: "planning data could not be written."}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    onCloseDay = { note ->
                        busy = true
                        scope.launch {
                            try {
                                message = writes.run({ closeDay(it, s, note) }) { it.startsWith("Closed") }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = "Not closed: planning data could not be written."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    onBuildProposal = {
                        message = null
                        proposing = true
                    },
                    onDiscard = {
                        proposing = false
                        message = "Discarded. Nothing was saved."
                    },
                    onApprove = { shown ->
                        busy = true
                        scope.launch {
                            try {
                                val zone = (app.checkInZone.read() as? CheckInZone.Known)?.zone
                                if (zone == null) {
                                    message = "The check-in plan's time zone is no longer usable. Nothing was saved."
                                } else {
                                    val result =
                                        writes.run(
                                            { it.approve(shown, zone, acknowledgeUnresolved = shown.needsUnresolvedAcknowledgement) },
                                        ) {
                                            it is ApprovalResult.Approved
                                        }
                                    message =
                                        when (result) {
                                            is ApprovalResult.Approved -> {
                                                proposing = false
                                                "The plan for ${shown.inputs.day} was saved as revision ${result.plan.plan.revision}. No call was changed."
                                            }
                                            is ApprovalResult.Refused ->
                                                if (result.reason == ApprovalRefusal.CHANGED) {
                                                    "Something changed since this preview, so nothing was saved. It was made again; check it and approve again."
                                                } else {
                                                    "Not saved: ${result.reason.name.lowercase().replace('_', ' ')}."
                                                }
                                        }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = "Not saved: planning data could not be written."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    onPlanByHand = { ctxGoToPlan(app) },
                )
            }
            OutlinedButton(
                onClick = { ctxGoToToday(app) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Open Today") }
        }
    }
}

private fun ctxGoToPlan(app: ThaliaApplication) =
    app.startActivity(
        com.thalia.app.ui
            .tabIntent(app, ThaliaTab.PLAN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

private fun ctxGoToToday(app: ThaliaApplication) =
    app.startActivity(
        com.thalia.app.ui
            .tabIntent(app, ThaliaTab.TODAY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

@Composable
private fun Card(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun OpeningPart(
    s: GuidedState,
    onPlanByHand: () -> Unit,
) {
    val day = DateTimeFormatter.ofPattern("EEE d MMM").format(s.day.date)
    s.lastClosingNote?.let {
        Card("From your last closing") { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    val plan = s.plan
    if (plan == null) {
        Card("No plan for $day") {
            Text(
                "Nothing is approved for today. You can plan it by hand; nothing is planned for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onPlanByHand, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Plan the day by hand") }
        }
    } else {
        val review = reviewOfApproved(plan, s.commitments)
        CapacityCard(review, emptyList())
        ReviewCard(
            header = { Text("Today's plan, revision ${plan.plan.revision}", style = MaterialTheme.typography.titleSmall) },
            review = review,
            leftOutLines = emptyList(),
            removedLine = null,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClosingPart(
    s: GuidedState,
    busy: Boolean,
    proposing: Boolean,
    proposal: PlanProposal?,
    onRecord: (String, OutcomeResult) -> Unit,
    onEstimate: (Action, Int) -> Unit,
    onCloseDay: (String?) -> Unit,
    onBuildProposal: () -> Unit,
    onDiscard: () -> Unit,
    onApprove: (PlanProposal) -> Unit,
    onPlanByHand: () -> Unit,
) {
    var step = 0

    fun numbered(title: String) = "${++step}. $title"
    Card(numbered("What happened")) {
        if (s.rows.isEmpty()) {
            Text(
                "Nothing in today's plan can take an outcome. Anything you do not record stays unknown.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        s.rows.forEach { row ->
            Text(outcomeRowText(row), style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(OutcomeResult.DONE, OutcomeResult.PARTIAL, OutcomeResult.SKIPPED, OutcomeResult.BLOCKED).forEach { result ->
                    FilterChip(
                        selected = row.result == result,
                        enabled = !busy,
                        onClick = { if (row.result != result) onRecord(row.itemId, result) },
                        label = { Text(result.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
    val unknown = unrecorded(s.rows)
    if (unknown.isNotEmpty()) {
        val titles = unknown.map { it.title }
        // The one question about what is still unknown. Leaving it unknown is the person's answer and writes nothing.
        var left by rememberSaveable { mutableStateOf("") }
        val ids = unknown.joinToString(",") { it.itemId }
        Card(numbered(UnknownQuestion.TITLE)) {
            if (left == ids) {
                Text(UnknownQuestion.left(titles), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(UnknownQuestion.question(titles), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { left = ids }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(UnknownQuestion.LEAVE)
                }
            }
        }
    }
    if (s.partial.isNotEmpty()) {
        Card(numbered("Remaining estimates")) {
            s.partial.forEach { action ->
                var text by remember(action) { mutableStateOf(action.estimateMinutes.toString()) }
                Text(action.title, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        text,
                        { text = it },
                        label = { Text("Minutes left") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 56.dp),
                        onClick = {
                            text
                                .trim()
                                .toIntOrNull()
                                ?.takeIf { it in Action.ESTIMATE_RANGE }
                                ?.let { onEstimate(action, it) }
                        },
                    ) { Text("Save") }
                }
                Text(
                    "From ${Action.ESTIMATE_RANGE.first} to ${Action.ESTIMATE_RANGE.last} minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Card(numbered("Note and close the day")) {
        var note by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            note,
            { note = it },
            label = { Text("Note for the next opening, optional") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!s.canClose) {
            Text(
                "No working day is open here, so there is nothing to close. Open it from Today first if you want to keep a note.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            enabled = !busy && s.canClose,
            onClick = { onCloseDay(PlanningText.clean(note).ifEmpty { null }) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Close ${DateTimeFormatter.ofPattern("EEE d MMM").format(s.day.date)} with this note") }
    }
    val next = s.nextDay
    Card(numbered("The next active day")) {
        if (next == null) {
            Text(
                "The next active day cannot be named yet: an earlier working day is still open. Settle it on Today first.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Card
        }
        val label = DateTimeFormatter.ofPattern("EEE d MMM").format(next.date)
        if (s.carry.isEmpty()) {
            Text(
                "Nothing unfinished to carry into $label. You can plan it by hand.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onPlanByHand, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Plan $label by hand") }
        } else if (!proposing) {
            Text(
                "Unfinished and open: ${s.carry.joinToString {
                    it.title
                }}. A proposal for $label is made from these by the planner, and nothing is saved until you approve it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onBuildProposal,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Review a proposal for $label") }
        } else if (proposal == null) {
            Text(
                "The planner cannot make a proposal (no planning window, or nothing could be placed). Plan $label by hand.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onDiscard, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close the review") }
        } else {
            val titles = proposal.actions.associate { it.action.id to it.action.title }
            val review = reviewOfProposal(proposal, s.nextPlan)
            val text = previewText(proposal) { titles[it] ?: "an action" }
            PlanBanner(s.nextPlan != null, label, s.nextPlan?.let { "Revision ${it.plan.revision}" }.orEmpty(), review.counts)
            CapacityCard(review, text.summary)
            ReviewCard(
                header = { Text("Waiting for your approval. Not saved yet.", style = MaterialTheme.typography.titleSmall) },
                review = review,
                leftOutLines = text.leftOut,
                removedLine = null,
            )
            text.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            text.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
            Button(
                enabled = proposal.canApprove && !busy,
                onClick = { onApprove(proposal) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text(
                    if (proposal.needsUnresolvedAcknowledgement) {
                        "Approve with ${proposal.overloadMinutes} minutes unresolved for $label"
                    } else if (s.nextPlan != null) {
                        "Approve and replace the plan for $label"
                    } else {
                        "Approve the plan for $label"
                    },
                )
            }
            OutlinedButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Discard") }
        }
    }
}
