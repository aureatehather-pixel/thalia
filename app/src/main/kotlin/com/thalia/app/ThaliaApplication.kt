package com.thalia.app

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.thalia.app.ai.AiConsentWording
import com.thalia.app.ai.AiServices
import com.thalia.app.ai.AiTransportOverride
import com.thalia.app.planning.CheckInZoneSource
import com.thalia.app.planning.OpeningCallLink
import com.thalia.app.planning.PlanningAccess
import com.thalia.app.planning.PlanningTransfer
import com.thalia.app.planning.RetryReport
import com.thalia.app.planning.RoomCheckInZoneSource
import com.thalia.app.planning.data.PlanningFiles
import com.thalia.app.reminders.AndroidReminderAlarms
import com.thalia.app.reminders.AndroidReminderNotifier
import com.thalia.app.reminders.FireOutcome
import com.thalia.app.reminders.ReminderReconcileReport
import com.thalia.app.reminders.ReminderService
import com.thalia.app.voicecheckins.call.CallNotifications
import com.thalia.app.voicecheckins.call.CheckInCallCoordinator
import com.thalia.app.voicecheckins.call.LedgerCallOutcomeRecorder
import com.thalia.app.voicecheckins.data.CheckInDatabase
import com.thalia.app.voicecheckins.data.RoomOccurrenceStore
import com.thalia.app.voicecheckins.devtools.DevScheduledRing
import com.thalia.app.voicecheckins.devtools.DevTestRing
import com.thalia.app.voicecheckins.schedule.AlarmManagerBackend
import com.thalia.app.voicecheckins.schedule.AlarmScheduler
import com.thalia.app.voicecheckins.schedule.CheckInConfirmer
import com.thalia.app.voicecheckins.schedule.ExactAlarmScheduler
import com.thalia.app.voicecheckins.schedule.OccurrenceScheduler
import com.thalia.app.voicecheckins.schedule.OccurrenceStore
import com.thalia.app.voicecheckins.schedule.ReconcileReport
import com.thalia.app.voicecheckins.schedule.ReconcileTrigger
import com.thalia.app.voicecheckins.schedule.Reconciler
import com.thalia.conversation.ConversationProvider
import com.thalia.voicecheckins.voice.fake.FakeVoiceSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Thalia's application object.
 *
 * It owns the long-lived things the call needs: the call coordinator, shared by the
 * foreground service and the call activity, and the alarm scheduler. Holding them here
 * rather than in a global keeps their lifetime honest and leaves room for a
 * dependency-injection framework later without anything having to be untangled first.
 */
class ThaliaApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Where receivers and the call's recorder do their database work. Off the main thread, and
     * outliving any one broadcast, which keeps itself open with `goAsync` until the work is done.
     */
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database: CheckInDatabase by lazy {
        Room
            .databaseBuilder(this, CheckInDatabase::class.java, CheckInDatabase.FILE_NAME)
            .addMigrations(*CheckInDatabase.MIGRATIONS)
            .build()
    }

    /** The durable record of scheduled occurrences and what became of them, and the stored plan. */
    val occurrenceStore: OccurrenceStore by lazy { RoomOccurrenceStore(database) }

    /** M2's stored zone, read without creating a plan, for planning. */
    internal val checkInZone: CheckInZoneSource by lazy { RoomCheckInZoneSource(database) }

    /** Planning data, ADR 0009. Opened only when a planning screen asks, never by the call path. */
    internal val planningAccess: PlanningAccess by lazy {
        // An import or replacement changes which reminders exist, so alarms and notifications are brought back into line after it.
        PlanningAccess.production(this) { store -> reminderService.reconcile(store) }
    }

    /** One-shot reminders, ADR 0014. Inexact alarms and generic notifications, decided from the stored rows. */
    internal val reminderService: ReminderService by lazy {
        ReminderService(
            alarms = AndroidReminderAlarms(this),
            notifier = AndroidReminderNotifier(this),
            quietPeriod = { occurrenceStore.quietPeriod() },
        )
    }

    private fun planningExists() = PlanningFiles.of(this, BuildConfig.PLANNING_STORAGE_NAME).database.exists()

    /** A reminder's alarm was delivered. Null if there is no planning data to act on, in which case nothing is posted. */
    internal suspend fun fireReminder(id: String): FireOutcome? =
        withContext(Dispatchers.IO) {
            if (!planningExists()) return@withContext null
            planningAccess.withStore { reminderService.onAlarm(it, id) }
        }

    /**
     * Brings reminder alarms and notifications back into line with the stored reminders. Never creates planning storage, never
     * posts anything, and leaves everything as it is if planning data cannot be opened. Failures are logged by class.
     */
    internal suspend fun reconcileReminders(): ReminderReconcileReport? =
        withContext(Dispatchers.IO) {
            if (!planningExists()) return@withContext null
            try {
                planningAccess.withStore { reminderService.reconcile(it) }.also { Log.i(LOG_TAG, "reminders reconciled: $it") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "reminders could not be reconciled: ${e.javaClass.simpleName}")
                null
            }
        }

    /**
     * ADR 0013: consent, the sealed key and the monthly budget, behind one gated provider. No build wires a live transport in
     * Slice 4A (the transport is null unless an isolated check build placed a fake there, and the manifest requests no
     * INTERNET outside debug), so every request is answered as unavailable and nothing leaves the phone.
     */
    internal val aiServices: AiServices by lazy {
        AiServices.create(
            this,
            AiTransportOverride.transport,
            fake = AiTransportOverride.fake,
            consentVersion = AiTransportOverride.consentVersion ?: AiConsentWording.VERSION,
            consentText = AiTransportOverride.consentText ?: AiConsentWording.TEXT,
            estimateNote = AiTransportOverride.estimateNote,
        )
    }

    internal val conversationProvider: ConversationProvider get() = aiServices.provider

    /** The encrypted export and import of planning data, ADR 0009 slice 1B. */
    internal val planningTransfer: PlanningTransfer by lazy { PlanningTransfer(planningAccess, BuildConfig.VERSION_NAME) }

    val callCoordinator: CheckInCallCoordinator by lazy {
        CheckInCallCoordinator(
            scope = applicationScope,
            recorder =
                LedgerCallOutcomeRecorder(
                    scope = ioScope,
                    store = occurrenceStore,
                    clock = System::currentTimeMillis,
                    onError = { Log.e(LOG_TAG, "recording a call outcome failed", it) },
                ),
            // Paced so that a person can watch the transcript fill in, read the call
            // timer and press End Call. Five lines at six seconds is a half-minute call.
            // The unpaced fake finishes in milliseconds, which is right for tests and
            // useless for looking at the screen.
            voiceClient = FakeVoiceSessionClient(lineDelay = FAKE_LINE_DELAY),
            // Slice 4C: a scheduled call hands off to the conversation screen. A development button keeps the simulated session.
            handoffScheduled = true,
        )
    }

    /** Development only. The one-shot delay behind the "Ring in 10 seconds" button. */
    val devTestRing: DevTestRing by lazy {
        DevTestRing(scope = applicationScope, coordinator = callCoordinator)
    }

    /** Arms and disarms the exact alarm that starts a check-in call. No fallback to approximate. */
    val alarmScheduler: AlarmScheduler by lazy {
        ExactAlarmScheduler(AlarmManagerBackend(this), System::currentTimeMillis)
    }

    /** Records an occurrence, then arms its alarm. */
    val occurrenceScheduler: OccurrenceScheduler by lazy {
        OccurrenceScheduler(occurrenceStore, alarmScheduler, System::currentTimeMillis)
    }

    /** Confirms one opening or closing check-in on one day. Only a confirmed day rings. */
    val checkInConfirmer: CheckInConfirmer by lazy {
        CheckInConfirmer(occurrenceStore, occurrenceScheduler, System::currentTimeMillis)
    }

    /** The link from an approved plan's opening time to M2's slot. Built on M2's public types; M2 is unchanged. */
    internal val openingCallLink: OpeningCallLink by lazy {
        OpeningCallLink(checkInZone, occurrenceStore, occurrenceScheduler, checkInConfirmer, System::currentTimeMillis)
    }

    /**
     * The launch retry for opening calls the user has authorized, run after [reconcile] returns so M2's rows and
     * alarms are already in line. It acts on REQUESTED calls only. It never creates planning storage: if none exists
     * there is nothing to retry. Failures are logged by class and leave the calls REQUESTED for the next launch.
     */
    internal suspend fun retryOpeningCalls(): RetryReport? =
        withContext(Dispatchers.IO) {
            if (!PlanningFiles.of(this@ThaliaApplication, BuildConfig.PLANNING_STORAGE_NAME).database.exists()) return@withContext null
            try {
                planningAccess.withStore { openingCallLink.retry(it) }.also { Log.i(LOG_TAG, "opening-call retry: $it") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "opening-call retry could not run: ${e.javaClass.simpleName}")
                null
            }
        }

    private val reconciler: Reconciler by lazy {
        Reconciler(
            store = occurrenceStore,
            alarms = alarmScheduler,
            activeOccurrenceId = { callCoordinator.activeOccurrenceId },
            clock = System::currentTimeMillis,
        )
    }

    private val _lastReconcile = MutableStateFlow<ReconcileReport?>(null)

    /** What the most recent reconciliation in this process found. For the debug screen. */
    val lastReconcile: StateFlow<ReconcileReport?> = _lastReconcile.asStateFlow()

    /**
     * Bring the stored occurrences and Android's alarms back into agreement. Safe from any thread,
     * any number of times, and from any trigger. It never rings a call.
     */
    suspend fun reconcile(trigger: ReconcileTrigger): ReconcileReport =
        withContext(Dispatchers.IO) {
            val report = reconciler.reconcile(trigger)
            _lastReconcile.value = report
            Log.i(LOG_TAG, "reconciled: ${report.summary()}")
            report
        }

    /** Development only. The debug control that arms one real alarm two minutes ahead. */
    val devScheduledRing: DevScheduledRing by lazy {
        DevScheduledRing(occurrenceScheduler, occurrenceStore)
    }

    override fun onCreate() {
        super.onCreate()
        // Created up front so the diagnostics screen can report the channel's real
        // importance, including when the user has turned it down.
        CallNotifications.createChannel(this)
        AndroidReminderNotifier.createChannel(this)
    }

    private companion object {
        val FAKE_LINE_DELAY = 6.seconds

        /** Shared with the alarm receiver and the call service, so one logcat filter follows an alarm. */
        const val LOG_TAG = "ThaliaAlarm"
    }
}
