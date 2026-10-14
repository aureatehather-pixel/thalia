package com.thalia.app.voicecheckins.schedule

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.thalia.app.ThaliaApplication
import com.thalia.app.voicecheckins.call.CheckInCallService
import com.thalia.voicecheckins.domain.CheckInType
import kotlinx.coroutines.launch

/**
 * Receives the exact alarm and starts the existing call.
 *
 * This is deliberately a few lines of Android glue. It reads the intent into an
 * [AlarmPayload], hands it to [CheckInFireHandler], which holds the decision and claims the
 * occurrence in the store, and logs what happened. The call itself is the M1 call, started
 * through [CheckInCallService.startRinging], and nothing here rings, vibrates or shows anything.
 * A suppressed alarm never reaches the service, so it starts nothing at all.
 *
 * The store is a database, so the work runs off the main thread. `goAsync` keeps the broadcast,
 * and the wake lock the alarm holds for it, open until the handler has finished.
 *
 * It is not exported, so only the system delivering Thalia's own `PendingIntent` can reach
 * it.
 *
 * The outcome is recorded in the store, and also logged under `ThaliaAlarm` for a device run
 * that has a cable or wireless `adb`.
 */
class CheckInAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_FIRE) return

        val scheduledAtMillis =
            intent
                .getLongExtra(EXTRA_SCHEDULED_AT_MILLIS, NO_TIME)
                .takeIf { it != NO_TIME }
        val payload =
            AlarmPayload.parse(
                occurrenceId = intent.data?.lastPathSegment,
                checkInTypeName = intent.getStringExtra(EXTRA_CHECK_IN_TYPE),
                scheduledAtMillis = scheduledAtMillis,
            )

        val deliveredAtMillis = System.currentTimeMillis()
        val app = context.applicationContext as ThaliaApplication
        val pending = goAsync()
        app.ioScope.launch {
            try {
                val outcome =
                    CheckInFireHandler(
                        clock = { deliveredAtMillis },
                        store = app.occurrenceStore,
                        callStarter = { type, id -> startCall(app, type, id) },
                        callHere = { app.callCoordinator.activeOccurrenceId },
                    ).onAlarm(payload)
                log(payload, deliveredAtMillis, outcome)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "alarm delivered but handling it failed, nothing rung", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun startCall(
        context: Context,
        checkInType: CheckInType,
        occurrenceId: String,
    ): Boolean =
        try {
            CheckInCallService.startRinging(context, checkInType, occurrenceId)
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.e(LOG_TAG, "Android refused to start the call service from the alarm", e)
            false
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "The call service could not be started from the alarm", e)
            false
        }

    private fun log(
        payload: AlarmPayload?,
        deliveredAtMillis: Long,
        outcome: FireOutcome,
    ) {
        val what =
            "occurrence=${payload?.occurrenceId} scheduledAtMillis=${payload?.scheduledAtMillis} " +
                "deliveredAtMillis=$deliveredAtMillis"
        when (outcome) {
            is FireOutcome.Ringing ->
                Log.i(LOG_TAG, "alarm delivered, starting call: $what latenessMillis=${outcome.latenessMillis}")

            is FireOutcome.TooLate ->
                Log.w(
                    LOG_TAG,
                    "alarm delivered too late, dropped and not rung: $what " +
                        "latenessMillis=${outcome.latenessMillis} recorded=${outcome.recorded}",
                )

            is FireOutcome.Suppressed ->
                Log.i(
                    LOG_TAG,
                    "alarm delivered and suppressed, nothing started: $what reason=${outcome.reason} " +
                        "latenessMillis=${outcome.latenessMillis}",
                )

            is FireOutcome.NotClaimed ->
                Log.w(LOG_TAG, "alarm delivered but the occurrence was not claimed, nothing rung: $what found=${outcome.found}")

            is FireOutcome.StartRefused ->
                Log.e(LOG_TAG, "alarm delivered but the call could not start: $what latenessMillis=${outcome.latenessMillis}")

            FireOutcome.Malformed ->
                Log.e(LOG_TAG, "alarm delivered without saying what it was for, nothing rung: $what")
        }
    }

    companion object {
        const val ACTION_FIRE: String = "com.thalia.app.action.CHECK_IN_ALARM"

        private const val LOG_TAG = "ThaliaAlarm"
        private const val EXTRA_CHECK_IN_TYPE = "check_in_type"
        private const val EXTRA_SCHEDULED_AT_MILLIS = "scheduled_at_millis"
        private const val NO_TIME = Long.MIN_VALUE

        /**
         * The intent that identifies one occurrence's alarm, and no more.
         *
         * Android matches a `PendingIntent` on its action, data and target, and ignores
         * extras. Putting the occurrence id in the data is what gives every occurrence its
         * own alarm, and lets [cancel][AlarmManagerBackend.cancel] find it without knowing
         * when it was armed.
         */
        internal fun identityIntent(
            context: Context,
            occurrenceId: String,
        ): Intent =
            Intent(context, CheckInAlarmReceiver::class.java)
                .setAction(ACTION_FIRE)
                .setData(
                    Uri
                        .Builder()
                        .scheme("thalia")
                        .authority("checkin")
                        .appendPath(occurrenceId)
                        .build(),
                )

        /** The identity intent plus what the receiver needs to know when it fires. */
        internal fun fireIntent(
            context: Context,
            request: AlarmRequest,
        ): Intent =
            identityIntent(context, request.occurrenceId)
                .putExtra(EXTRA_CHECK_IN_TYPE, request.checkInType.name)
                .putExtra(EXTRA_SCHEDULED_AT_MILLIS, request.triggerAtMillis)
    }
}
