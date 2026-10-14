package com.thalia.app.voicecheckins.devtools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.thalia.app.BuildConfig
import com.thalia.app.ui.ThaliaTheme
import com.thalia.app.voicecheckins.call.CallCapabilities
import com.thalia.voicecheckins.domain.CheckInType
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter

/**
 * The development screen: provoke a check-in, and see what Android will allow.
 *
 * Every line here is diagnostic. None of it belongs in the shipped product, and the
 * heading says so, so that a later reader does not mistake it for a design.
 */
@Composable
fun DevHomeScreen(
    capabilities: CallCapabilities,
    testRing: DevTestRingState,
    occurrences: List<OccurrenceRow>,
    planSummary: String?,
    lastReconcile: String?,
    onRing: (CheckInType) -> Unit,
    onArmTestRing: () -> Unit,
    onCancelTestRing: () -> Unit,
    onScheduleRing: () -> Unit,
    onSchedulePair: () -> Unit,
    onCancelScheduledRing: () -> Unit,
    confirmMessage: String?,
    onConfirm: (ConfirmAction, CheckInType, ConfirmDay) -> Unit,
    quietPeriodSummary: String?,
    onQuietPeriodFromNow: () -> Unit,
    onQuietPeriodOff: () -> Unit,
    onReconcileNow: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenFullScreenIntentSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenPlanning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Thalia", style = MaterialTheme.typography.headlineMedium)
            Text(
                text =
                    "Voice Check-ins, milestone 2. Development build: the call is " +
                        "simulated, no AI provider is contacted, and a check-in is scheduled " +
                        "only when you use the debug controls below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(onClick = onOpenPlanning) { Text("Planning: direction, goals, projects, actions") }

            OutcomeCard(capabilities)
            SchedulingCard(capabilities)

            val armed = testRing as? DevTestRingState.Armed

            Text("Trigger a check-in now", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onRing(CheckInType.OPENING) }, enabled = armed == null) {
                    Text("Ring opening")
                }
                Button(onClick = { onRing(CheckInType.CLOSING) }, enabled = armed == null) {
                    Text("Ring closing")
                }
            }
            if (BuildConfig.DEBUG) {
                Text(
                    text =
                        "These ring immediately, with the screen on. They cannot test a call " +
                            "that starts while the phone is locked. Use the button below for that.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Locked-screen test (development only)", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = onArmTestRing,
                    enabled = armed == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ring in 10 seconds") }
                Text(
                    text =
                        "Tap it, press the power button to lock the phone, and put it down. " +
                            "The call starts ten seconds later, while the phone is already " +
                            "locked and the screen is off. One test at a time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (armed != null) ArmedCard(armed, onCancelTestRing)

                Spacer(Modifier.height(8.dp))
                Text("Real alarm test (development only)", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = onScheduleRing,
                    enabled = capabilities.canScheduleExactAlarms,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Schedule in 2 minutes") }
                Text(
                    text =
                        "Records a check-in, then arms a real exact alarm for it. Lock the phone " +
                            "or swipe Thalia away, and the call should still arrive at the time " +
                            "shown. The alarm is restored after a reboot, an update, or a change " +
                            "of time or time zone, and an overdue one is marked missed and never " +
                            "rung late. A new one replaces an earlier one that has not rung.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onSchedulePair,
                    enabled = capabilities.canScheduleExactAlarms,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Schedule two at once in 2 minutes") }
                Text(
                    text =
                        "Arms two check-ins for the same instant, so they race for the phone. " +
                            "Exactly one should ring, and the other should be stored as suppressed, " +
                            "call in progress, with nothing shown for it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onCancelScheduledRing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel scheduled alarm") }

                Spacer(Modifier.height(8.dp))
                Text("Confirm a day's check-in (development only)", style = MaterialTheme.typography.titleMedium)
                Text(
                    text =
                        "A check-in rings only for a day you confirm here, at the time in the " +
                            "stored plan. Nothing is scheduled from the plan by itself, and " +
                            "repeating an action changes nothing. +10 min moves it to ten minutes " +
                            "from now on the clock, and Cancel withdraws it so it can be confirmed again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (day in ConfirmDay.entries) {
                    for (type in CheckInType.entries) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${day.label} ${type.name.lowercase()}", modifier = Modifier.weight(1f))
                            for (action in ConfirmAction.entries) {
                                OutlinedButton(
                                    onClick = { onConfirm(action, type, day) },
                                    enabled = capabilities.canScheduleExactAlarms || action == ConfirmAction.CANCEL,
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                ) { Text(action.label) }
                            }
                        }
                    }
                }
                if (confirmMessage != null) {
                    Text(confirmMessage, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(8.dp))
                Text("Quiet period (development only)", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Quiet period: ${quietPeriodSummary ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text =
                        "No check-in rings inside it. Confirming or moving one into it is refused, " +
                            "and one already confirmed that falls inside it is suppressed when its " +
                            "alarm arrives, with nothing shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onQuietPeriodFromNow, modifier = Modifier.weight(1f)) {
                        Text("Quiet next $DEV_QUIET_MINUTES min")
                    }
                    OutlinedButton(onClick = onQuietPeriodOff, modifier = Modifier.weight(1f)) {
                        Text("Quiet off")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Stored occurrences (development only)", style = MaterialTheme.typography.titleMedium)
                if (planSummary != null) {
                    Text(
                        text = "Plan: $planSummary",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OccurrenceList(occurrences)
                OutlinedButton(
                    onClick = onReconcileNow,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reconcile now") }
                Text(
                    text = lastReconcile?.let { "Last reconcile in this process: $it" } ?: "No reconcile yet in this process.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            Check("Android API level", capabilities.sdkInt.toString(), neutral = true)
            Check("Notification permission", capabilities.postNotificationsGranted)
            Check("Notifications enabled", capabilities.notificationsEnabled)
            Check("Channel importance is High", capabilities.channelImportance >= 4)
            Check(
                label =
                    if (capabilities.fullScreenIntentIsGovernedByPermission) {
                        "Full-screen intent allowed"
                    } else {
                        "Full-screen intent (unrestricted below Android 14)"
                    },
                ok = capabilities.canUseFullScreenIntent,
            )
            Check("Exact alarms allowed", capabilities.canScheduleExactAlarms)

            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!capabilities.postNotificationsGranted) {
                    Button(
                        onClick = onRequestNotificationPermission,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Grant notification permission") }
                }
                OutlinedButton(
                    onClick = onOpenNotificationSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open notification settings") }

                if (capabilities.fullScreenIntentIsGovernedByPermission) {
                    OutlinedButton(
                        onClick = onOpenFullScreenIntentSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open full-screen intent settings") }
                }

                OutlinedButton(
                    onClick = onOpenExactAlarmSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open alarms & reminders settings") }
            }
        }
    }
}

@Composable
private fun ArmedCard(
    armed: DevTestRingState.Armed,
    onCancel: () -> Unit,
) {
    var secondsLeft by remember(armed.fireAtMillis) { mutableLongStateOf(secondsUntil(armed.fireAtMillis)) }
    LaunchedEffect(armed.fireAtMillis) {
        while (true) {
            secondsLeft = secondsUntil(armed.fireAtMillis)
            delay(250)
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Test call armed: ringing in $secondsLeft s",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Lock the phone now. Nothing more to do.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel test call")
            }
        }
    }
}

/** Whole seconds until [fireAtMillis], rounded up so the card never shows 0 while still armed. */
private fun secondsUntil(fireAtMillis: Long): Long = ((fireAtMillis - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)

@Composable
private fun SchedulingCard(capabilities: CallCapabilities) {
    val ok = capabilities.canScheduleExactAlarms
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (ok) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
            ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (ok) "Scheduled calls possible" else "Scheduled calls off",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = capabilities.exactAlarmBehaviour, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * The stored occurrences, latest first: when each was due, what it became and why, and the two
 * moments the store keeps, fired and resolved. Read from the store, not from this process's memory,
 * so it is right after a swipe-away or a reboot. "armed" is whether Android still holds the alarm.
 */
@Composable
private fun OccurrenceList(rows: List<OccurrenceRow>) {
    if (rows.isEmpty()) {
        Text(
            text = "No occurrences stored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            val o = row.occurrence

            fun clock(instant: java.time.Instant?) = instant?.let { CLOCK_FORMAT.format(it.atZone(o.zone)) } ?: "-"
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${clock(o.scheduledAt)}  ${o.status}" + (o.reason?.let { " - $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text =
                            "fired ${clock(o.firedAt)}  resolved ${clock(o.resolvedAt)}" +
                                (row.armed?.let { "  armed ${if (it) "yes" else "no"}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "${o.id}  ${o.zone.id}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OutcomeCard(capabilities: CallCapabilities) {
    val ok = capabilities.fullScreenCallExpected
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (ok) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
            ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (ok) "Full-screen call expected" else "Degraded",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = capabilities.expectedBehaviour, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Check(
    label: String,
    ok: Boolean,
) = CheckRow(label, if (ok) "yes" else "no", ok)

@Composable
private fun Check(
    label: String,
    value: String,
    neutral: Boolean,
) = CheckRow(label, value, ok = true, neutral = neutral)

@Composable
private fun CheckRow(
    label: String,
    value: String,
    ok: Boolean,
    neutral: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color =
                when {
                    neutral -> MaterialTheme.colorScheme.onSurface
                    ok -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DevHomePreview() {
    ThaliaTheme {
        DevHomeScreen(
            capabilities =
                CallCapabilities(
                    sdkInt = 36,
                    postNotificationsGranted = true,
                    notificationsEnabled = true,
                    channelImportance = 4,
                    canUseFullScreenIntent = true,
                    canScheduleExactAlarms = true,
                ),
            testRing = DevTestRingState.Off,
            occurrences = emptyList(),
            planSummary = null,
            lastReconcile = null,
            onRing = {},
            onArmTestRing = {},
            onCancelTestRing = {},
            onScheduleRing = {},
            onSchedulePair = {},
            onCancelScheduledRing = {},
            confirmMessage = null,
            onConfirm = { _, _, _ -> },
            quietPeriodSummary = "off (22:00 to 07:00 when on)",
            onQuietPeriodFromNow = {},
            onQuietPeriodOff = {},
            onReconcileNow = {},
            onRequestNotificationPermission = {},
            onOpenNotificationSettings = {},
            onOpenFullScreenIntentSettings = {},
            onOpenExactAlarmSettings = {},
            onOpenPlanning = {},
        )
    }
}

/** The two days the debug screen can confirm a check-in for. */
enum class ConfirmDay(
    val label: String,
    val offset: Long,
) {
    TODAY("Today", 0),
    TOMORROW("Tomorrow", 1),
}

/** What a debug button does to a day's check-in. */
enum class ConfirmAction(
    val label: String,
) {
    CONFIRM("Confirm"),
    MOVE("+10 min"),
    CANCEL("Cancel"),
}
