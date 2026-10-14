package com.thalia.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.thalia.app.planning.PlanningActivity
import com.thalia.app.ui.ThaliaTheme
import com.thalia.app.voicecheckins.call.CallCapabilities
import com.thalia.app.voicecheckins.call.CheckInCallService
import com.thalia.app.voicecheckins.devtools.ConfirmAction
import com.thalia.app.voicecheckins.devtools.DevHomeScreen
import com.thalia.app.voicecheckins.devtools.DevTestRing
import com.thalia.app.voicecheckins.devtools.OccurrenceRow
import com.thalia.app.voicecheckins.devtools.describe
import com.thalia.app.voicecheckins.devtools.quietPeriodFrom
import com.thalia.app.voicecheckins.devtools.summaryLine
import com.thalia.app.voicecheckins.schedule.ReconcileTrigger
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Check-in tools: development triggers and a diagnostics panel. Reached from Settings.
 *
 * This is not Thalia's home screen (the tabs are, see [PlanningActivity]). It exists so the incoming
 * call can be provoked on demand, a real alarm can be armed, and the platform's answers
 * about full-screen intents and exact alarms are visible on the device rather than
 * guessed at from a laptop. It was the app's launcher screen before the four-tab shell.
 */
class ToolsActivity : ComponentActivity() {
    private val devTestRing
        get() = (application as ThaliaApplication).devTestRing

    private val thalia
        get() = application as ThaliaApplication

    private val devScheduledRing
        get() = thalia.devScheduledRing

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Starting the app is one of the moments the stored occurrences are brought back into
        // line with Android's alarms. It never rings a call.
        // The opening-call retry follows in the same coroutine, so it reads M2 only after the reconcile has returned.
        thalia.ioScope.launch {
            thalia.reconcile(ReconcileTrigger.APP_LAUNCH)
            thalia.retryOpeningCalls()
            thalia.reconcileReminders()
        }

        setContent {
            ThaliaTheme {
                var capabilities by remember { mutableStateOf(CallCapabilities.read(this)) }

                // Bumped to reload what is stored and whether each alarm is still armed.
                var refresh by remember { mutableIntStateOf(0) }
                var occurrences by remember { mutableStateOf(emptyList<OccurrenceRow>()) }
                var planSummary by remember { mutableStateOf<String?>(null) }
                var quietPeriodSummary by remember { mutableStateOf<String?>(null) }
                var confirmMessage by remember { mutableStateOf<String?>(null) }
                val lastReconcile by thalia.lastReconcile.collectAsStateWithLifecycle()

                // Re-read on every resume, because the user may have just come back from
                // a settings screen having changed exactly what this reports.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    capabilities = CallCapabilities.read(this@ToolsActivity)
                    refresh++
                }

                // The stored occurrences are read from the database, not from memory, so they are
                // right after a swipe-away or a reboot. The list follows the database as it changes,
                // and each scheduled one is asked whether Android still holds its alarm.
                LaunchedEffect(refresh) {
                    planSummary = withContext(Dispatchers.IO) { thalia.occurrenceStore.plan().summaryLine() }
                    quietPeriodSummary = withContext(Dispatchers.IO) { thalia.occurrenceStore.quietPeriod().summaryLine() }
                    thalia.occurrenceStore.observeRecent(RECENT_OCCURRENCES).collect { stored ->
                        occurrences =
                            withContext(Dispatchers.IO) {
                                stored.map { o ->
                                    val armed = if (o.status == CheckInStatus.SCHEDULED) thalia.alarmScheduler.isArmed(o.id) else null
                                    OccurrenceRow(o, armed)
                                }
                            }
                    }
                }

                val requestNotifications =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { capabilities = CallCapabilities.read(this) }

                val testRing by devTestRing.state.collectAsStateWithLifecycle()

                DevHomeScreen(
                    capabilities = capabilities,
                    testRing = testRing,
                    occurrences = occurrences,
                    planSummary = planSummary,
                    lastReconcile = lastReconcile?.summary(),
                    onRing = ::ring,
                    onArmTestRing = ::armTestRing,
                    onCancelTestRing = devTestRing::cancel,
                    onScheduleRing = {
                        scheduleRing()
                        // A refusal means the permission just changed under us, so show it now.
                        capabilities = CallCapabilities.read(this)
                    },
                    onSchedulePair = {
                        if (BuildConfig.DEBUG) {
                            lifecycleScope.launch { devScheduledRing.schedulePair() }
                            capabilities = CallCapabilities.read(this)
                        }
                    },
                    onCancelScheduledRing = {
                        lifecycleScope.launch {
                            devScheduledRing.cancel()
                            refresh++
                        }
                    },
                    quietPeriodSummary = quietPeriodSummary,
                    onQuietPeriodFromNow = {
                        lifecycleScope.launch {
                            val store = thalia.occurrenceStore
                            store.saveQuietPeriod(quietPeriodFrom(LocalTime.now(store.plan().zone)))
                            refresh++
                        }
                    },
                    onQuietPeriodOff = {
                        lifecycleScope.launch {
                            val store = thalia.occurrenceStore
                            store.saveQuietPeriod(store.quietPeriod().copy(enabled = false))
                            refresh++
                        }
                    },
                    confirmMessage = confirmMessage,
                    onConfirm = { action, type, day ->
                        lifecycleScope.launch {
                            val zone = thalia.occurrenceStore.plan().zone
                            val date = LocalDate.now(zone).plusDays(day.offset)
                            val confirmer = thalia.checkInConfirmer
                            confirmMessage =
                                when (action) {
                                    ConfirmAction.CONFIRM -> confirmer.confirm(type, date).describe()
                                    // The clock time ten minutes from now, on the chosen day.
                                    ConfirmAction.MOVE -> {
                                        val soon = LocalTime.now(zone).plusMinutes(MOVE_MINUTES).truncatedTo(ChronoUnit.MINUTES)
                                        confirmer.reschedule(type, date, soon).describe()
                                    }
                                    ConfirmAction.CANCEL -> confirmer.cancel(type, date).describe()
                                }
                            capabilities = CallCapabilities.read(this@ToolsActivity)
                            refresh++
                        }
                    },
                    onReconcileNow = {
                        lifecycleScope.launch {
                            thalia.reconcile(ReconcileTrigger.MANUAL)
                            refresh++
                        }
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onOpenNotificationSettings = ::openNotificationSettings,
                    onOpenFullScreenIntentSettings = ::openFullScreenIntentSettings,
                    onOpenExactAlarmSettings = ::openExactAlarmSettings,
                    onOpenPlanning = { startActivity(Intent(this, PlanningActivity::class.java)) },
                )
            }
        }
    }

    private fun ring(checkInType: CheckInType) {
        CheckInCallService.startRinging(this, checkInType)
    }

    /**
     * Development only. The service holds the process and the CPU through the ten second
     * wait, so the call can start after the phone has been locked. See [DevTestRing].
     */
    private fun armTestRing() {
        CheckInCallService.armTestRing(this, CheckInType.OPENING)
    }

    /**
     * Development only. Arms one real exact alarm two minutes ahead, through the same
     * scheduler and receiver the real schedule will use. See [DevScheduledRing].
     */
    private fun scheduleRing() {
        if (!BuildConfig.DEBUG) return
        lifecycleScope.launch { devScheduledRing.schedule() }
    }

    /**
     * Opens Settings > Special app access > Alarms & reminders for this app.
     *
     * Exact alarms are off by default on a fresh install that targets Android 13 or later,
     * so this is where the user turns them on, and where they can be turned off again.
     */
    private fun openExactAlarmSettings() {
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private companion object {
        /** How many of the latest stored occurrences the debug screen lists. */
        const val RECENT_OCCURRENCES = 8

        /** How far ahead the debug "+10 min" button moves a check-in. */
        const val MOVE_MINUTES = 10L
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    /**
     * Opens Settings > Special app access > Manage full screen intents.
     *
     * This screen only exists from Android 14, which is also the only place the
     * permission can be withheld, so the button that reaches it is hidden below that.
     */
    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }
}
