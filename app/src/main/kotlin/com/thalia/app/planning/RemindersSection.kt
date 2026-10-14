package com.thalia.app.planning

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.thalia.app.reminders.CreateOutcome
import com.thalia.app.reminders.ReminderRefusal
import com.thalia.app.reminders.ReminderService
import com.thalia.app.reminders.ReminderText
import com.thalia.planning.ActionStatus
import com.thalia.planning.InboxState
import com.thalia.planning.Reminder
import com.thalia.planning.ReminderStatus
import com.thalia.planning.ReminderTargetType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.DateTimeException
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

private data class Candidate(
    val id: String,
    val text: String,
)

private data class Loaded(
    val reminders: List<Reminder>,
    val goneIds: Set<String>,
    val inbox: List<Candidate>,
    val actions: List<Candidate>,
)

private const val CANDIDATES = 5
private const val RECENT_RESOLVED = 5

/**
 * One-shot reminders, ADR 0014. The person chooses what it is about (nothing, for a checkpoint), a day and a time; Thalia posts
 * one quiet notification around then and waits. Acknowledge, Dismiss and Cancel write the reminder and nothing else: no item,
 * action, plan, capacity or outcome changes.
 */
@Composable
internal fun RemindersSection(
    access: PlanningAccess,
    service: ReminderService,
    changes: Int,
    zone: () -> ZoneId = ZoneId::systemDefault,
    now: () -> Long = System::currentTimeMillis,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(ReminderTargetType.NONE) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var dayOffset by remember { mutableIntStateOf(0) }
    var timeText by remember { mutableStateOf("") }
    var askedForPermission by remember { mutableStateOf(false) }

    LaunchedEffect(changes, reload) {
        try {
            loaded =
                access.withStore { store ->
                    val reminders = store.reminders()
                    val inbox =
                        store
                            .inbox()
                            .filter { it.value.state == InboxState.OPEN }
                            .take(
                                CANDIDATES,
                            ).map { Candidate(it.value.id, it.value.text) }
                    val actions =
                        store
                            .actions()
                            .filter { it.status == ActionStatus.OPEN }
                            .take(
                                CANDIDATES,
                            ).map { Candidate(it.id, it.title) }
                    val gone =
                        reminders
                            .filter { it.targetId != null }
                            .filter {
                                when (it.targetType) {
                                    ReminderTargetType.INBOX_ITEM -> store.inboxItem(it.targetId!!) == null
                                    ReminderTargetType.ACTION -> store.action(it.targetId!!) == null
                                    ReminderTargetType.NONE -> false
                                }
                            }.map { it.id }
                            .toSet()
                    Loaded(reminders, gone, inbox, actions)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "Reminders could not be read."
        }
    }

    fun <T> guarded(work: suspend () -> T) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                work()
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

    val timeValue =
        try {
            LocalTime.parse(timeText.trim().let { if (it.length == 4 && it[1] == ':') "0$it" else it })
        } catch (e: DateTimeParseException) {
            null
        }

    fun create() {
        val time = timeValue ?: return
        val targetType = kind
        val targetId = if (targetType == ReminderTargetType.NONE) null else chosen
        val zoneId = zone()
        val due =
            try {
                ZonedDateTime
                    .of(
                        java.time.Instant
                            .ofEpochMilli(now())
                            .atZone(zoneId)
                            .toLocalDate()
                            .plusDays(dayOffset.toLong()),
                        time,
                        zoneId,
                    ).toInstant()
                    .toEpochMilli()
            } catch (e: DateTimeException) {
                message = "That time could not be placed on the calendar."
                return
            }
        val text =
            loaded
                ?.let { l ->
                    (
                        if (targetType ==
                            ReminderTargetType.INBOX_ITEM
                        ) {
                            l.inbox
                        } else {
                            l.actions
                        }
                    ).firstOrNull { it.id == targetId }?.text
                }.orEmpty()
        guarded {
            when (val outcome = access.withStore { service.create(it, targetType, targetId, text, due) }) {
                is CreateOutcome.Created -> {
                    message =
                        "Reminder set for ${ReminderText.around(
                            outcome.reminder,
                            now(),
                            zoneId,
                        )}. Thalia will post one quiet notification, then wait."
                    timeText = ""
                    chosen = null
                }
                is CreateOutcome.Refused -> message = ReminderText.refusal(outcome.why)
            }
        }
    }

    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            message = if (granted) null else ReminderText.refusal(ReminderRefusal.NOTIFICATIONS_BLOCKED)
            if (granted) create()
        }
    val needsPermission =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

    Text("Reminders", style = MaterialTheme.typography.titleMedium)
    Text(
        "One quiet notification around the time you choose, then Thalia waits. It never repeats and never asks a question. " +
            "Acknowledge, Dismiss and Cancel change only the reminder: not an item, an action or a plan.",
        style = MaterialTheme.typography.bodySmall,
    )
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            ReminderTargetType.NONE to "Checkpoint",
            ReminderTargetType.INBOX_ITEM to "Inbox item",
            ReminderTargetType.ACTION to "Action",
        ).forEach { (type, name) ->
            FilterChip(selected = kind == type, onClick = {
                kind = type
                chosen = null
            }, label = { Text(name) })
        }
    }
    val candidates =
        when (kind) {
            ReminderTargetType.INBOX_ITEM -> loaded?.inbox.orEmpty()
            ReminderTargetType.ACTION -> loaded?.actions.orEmpty()
            ReminderTargetType.NONE -> emptyList()
        }
    if (kind != ReminderTargetType.NONE && candidates.isEmpty()) {
        Text("Nothing open to remind you about here.", style = MaterialTheme.typography.bodySmall)
    }
    candidates.forEach { c ->
        TextButton(onClick = { chosen = c.id }, modifier = Modifier.fillMaxWidth()) {
            Text(
                (if (chosen == c.id) "Selected: " else "") +
                    com.thalia.planning.ReminderRules
                        .labelOf(c.text),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = dayOffset > 0, onClick = { dayOffset-- }) { Text("Earlier day") }
        Text(
            when (dayOffset) {
                0 -> "Today"
                1 -> "Tomorrow"
                else -> "In $dayOffset days"
            },
            modifier = Modifier.padding(top = 10.dp),
        )
        OutlinedButton(enabled = dayOffset < 7, onClick = { dayOffset++ }) { Text("Later day") }
    }
    OutlinedTextField(
        timeText,
        { timeText = it },
        label = { Text("Time, 24-hour, like 15:30") },
        isError = timeText.isNotBlank() && timeValue == null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = !busy && timeValue != null && (kind == ReminderTargetType.NONE || chosen != null),
        onClick = {
            if (needsPermission && !askedForPermission) {
                askedForPermission = true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                create()
            }
        },
    ) { Text("Set reminder") }

    val l = loaded
    if (l == null) {
        Text("Loading...")
        return
    }
    val zoneId = zone()
    val (open, resolved) = l.reminders.partition { it.status == ReminderStatus.SCHEDULED || it.status == ReminderStatus.POSTED }
    val ordered = open.sortedWith(compareBy({ if (it.status == ReminderStatus.POSTED) 0 else 1 }, { it.dueAtMillis }, { it.id }))
    val recent = resolved.sortedWith(compareByDescending<Reminder> { it.resolvedAtMillis ?: 0L }.thenBy { it.id }).take(RECENT_RESOLVED)
    if (ordered.isEmpty() && recent.isEmpty()) {
        Text("No reminders yet.", style = MaterialTheme.typography.bodySmall)
    }
    (ordered + recent).forEach { r ->
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ReminderText.about(r, r.id in l.goneIds), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${ReminderText.around(r, now(), zoneId)}. ${ReminderText.status(r)}.",
                style = MaterialTheme.typography.bodySmall,
            )
            when (r.status) {
                ReminderStatus.POSTED ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy,
                            onClick = { guarded { access.withStore { service.acknowledge(it, r.id) } } },
                        ) { Text("Acknowledge") }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { guarded { access.withStore { service.dismiss(it, r.id) } } },
                        ) { Text("Dismiss") }
                    }
                ReminderStatus.SCHEDULED ->
                    OutlinedButton(enabled = !busy, onClick = {
                        guarded {
                            val cancelled = access.withStore { service.cancel(it, r.id) }
                            message = if (cancelled) "Reminder cancelled." else "It was no longer waiting, so nothing was cancelled."
                        }
                    }) { Text("Cancel") }
                else -> Unit
            }
        }
    }
}
