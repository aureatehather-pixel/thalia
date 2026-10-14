package com.thalia.app.voicecheckins.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context

/**
 * The Android half of scheduling: `AlarmManager` and `PendingIntent`, and nothing else.
 *
 * One `PendingIntent` per occurrence, identified by [CheckInAlarmReceiver.identityIntent].
 * Arming an occurrence again updates that same `PendingIntent` and replaces its alarm, so an
 * occurrence can never have two.
 *
 * `setExactAndAllowWhileIdle` rather than `setAlarmClock`: this is a call, not an alarm
 * clock, and `setAlarmClock` would register it as the user's next alarm. It can fire in Doze
 * at most once per nine minutes per app, which two check-ins a day never approach.
 *
 * Whether the alarm really fires on time from deep sleep is not something this class can
 * prove. Only the device can.
 */
internal class AlarmManagerBackend(
    private val context: Context,
) : AlarmBackend {
    private val alarmManager: AlarmManager = requireNotNull(context.getSystemService(AlarmManager::class.java))

    override fun canScheduleExactAlarms(): Boolean = alarmManager.canScheduleExactAlarms()

    override fun setExact(request: AlarmRequest) {
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                CheckInAlarmReceiver.fireIntent(context, request),
                // UPDATE_CURRENT: re-arming the same occurrence replaces its extras, and
                // with them the instant the receiver will judge lateness against.
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, request.triggerAtMillis, pendingIntent)
    }

    override fun cancel(occurrenceId: String) {
        // NO_CREATE: only find an alarm that exists. Creating one just to cancel it would
        // leave a live PendingIntent behind.
        val existing =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                CheckInAlarmReceiver.identityIntent(context, occurrenceId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            ) ?: return
        alarmManager.cancel(existing)
        existing.cancel()
    }

    override fun isArmed(occurrenceId: String): Boolean =
        // NO_CREATE: only look. A PendingIntent that is not there means no alarm can be, because
        // Android drops it when the phone restarts and when the app is force-stopped.
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            CheckInAlarmReceiver.identityIntent(context, occurrenceId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null

    private companion object {
        /** Occurrences are told apart by the intent's data, so one request code serves them all. */
        const val REQUEST_CODE = 0
    }
}
