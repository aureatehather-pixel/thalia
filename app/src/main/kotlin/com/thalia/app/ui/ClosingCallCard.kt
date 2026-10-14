package com.thalia.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.thalia.app.ThaliaApplication
import com.thalia.app.voicecheckins.call.CallCapabilities
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.occurrenceOn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private class ClosingLoaded(
    val view: ClosingCallView,
    val today: LocalDate,
    val capabilities: CallCapabilities,
)

/**
 * Settings: the closing call. It shows the confirmed time and lets the person confirm, change or cancel it by explicit taps, all
 * through [com.thalia.app.voicecheckins.schedule.CheckInConfirmer], so the quiet period, the exact-alarm permission and the
 * one-call-per-day rule are the existing ones. Nothing is scheduled by looking at this card, and answering the call never closes
 * the day or applies a change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClosingCallCard(app: ThaliaApplication) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var dayKey by rememberSaveable { mutableStateOf("") }
    var loaded by remember { mutableStateOf<ClosingLoaded?>(null) }
    var failed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // The exact-alarm and notification permissions are changed in system settings: read them again on return.
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose {}
    }
    // A call can ring or end while this screen is open, so the card reads the stored state again every few seconds.
    LaunchedEffect(Unit) {
        while (true) {
            delay(POLL_MS)
            refresh++
        }
    }
    LaunchedEffect(dayKey, refresh) {
        try {
            val store = app.occurrenceStore
            val plan = store.plan()
            val schedule = plan[CheckInType.CLOSING]
            val now = LocalDateTime.now(plan.zone)
            val today = now.toLocalDate()
            val date =
                when (dayKey) {
                    "today" -> today
                    "tomorrow" -> today.plusDays(1)
                    else -> defaultClosingDate(now, schedule.localTime)
                }
            val latest = store.slot(schedule.occurrenceOn(date).slot).lastOrNull()
            loaded =
                ClosingLoaded(
                    ClosingCallView(date, schedule.localTime, schedule.enabled, latest, store.quietPeriod()),
                    today,
                    CallCapabilities.read(context),
                )
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }

    SettingsCard("Closing call") {
        val data = loaded
        if (failed || data == null) {
            Text(
                if (failed) "The check-in plan could not be read. Nothing was changed." else "Loading...",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SettingsCard
        }
        val view = data.view
        val status = closingStatus(view)
        Text(closingStatusText(view), style = MaterialTheme.typography.bodyLarge)
        if (!data.capabilities.canScheduleExactAlarms) {
            Text(data.capabilities.exactAlarmBehaviour, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null)),
                    )
                },
            ) { Text("Open Alarms & reminders settings") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("today" to data.today, "tomorrow" to data.today.plusDays(1)).forEach { (key, date) ->
                FilterChip(
                    selected = view.date == date,
                    onClick = { dayKey = key },
                    label = { Text("${if (key == "today") "Today" else "Tomorrow"}, ${dayText(date)}") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        val canChange = status == ClosingCallStatus.NOT_CONFIRMED || status == ClosingCallStatus.CONFIRMED
        if (canChange) {
            val start =
                view.latest
                    ?.takeIf { status == ClosingCallStatus.CONFIRMED }
                    ?.localDateTime
                    ?.toLocalTime() ?: view.planTime
            var timeText by rememberSaveable(view.date, view.latest?.id, start) {
                mutableStateOf(DateTimeFormatter.ofPattern("HH:mm").format(start))
            }
            OutlinedTextField(
                timeText,
                { timeText = it },
                label = { Text("Time (HH:mm)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !busy && data.capabilities.canScheduleExactAlarms,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = {
                    val time = parseClosingTime(timeText)
                    if (time == null) {
                        message = "Times are HH:mm, for example 21:30. Nothing was changed."
                    } else {
                        busy = true
                        scope.launch {
                            try {
                                message = closingResultText(app.checkInConfirmer.reschedule(CheckInType.CLOSING, view.date, time))
                                refresh++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = "Not scheduled: the check-in plan could not be written. Nothing was changed."
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
            ) { Text(if (status == ClosingCallStatus.CONFIRMED) "Move to this time" else "Confirm closing call") }
        }
        if (status == ClosingCallStatus.CONFIRMED) {
            OutlinedButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            message = closingCancelText(app.checkInConfirmer.cancel(CheckInType.CLOSING, view.date))
                            refresh++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = "Not cancelled: the check-in plan could not be written."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Cancel closing call") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
        Text(quietText(view.quiet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!data.capabilities.postNotificationsGranted) {
            Text(data.capabilities.expectedBehaviour, style = MaterialTheme.typography.bodySmall)
        }
        Text(ANSWER_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val POLL_MS = 15_000L
