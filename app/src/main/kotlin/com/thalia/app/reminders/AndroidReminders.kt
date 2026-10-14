package com.thalia.app.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.thalia.app.R
import com.thalia.app.planning.PlanningActivity

/**
 * `AlarmManager.setAndAllowWhileIdle`: inexact, so no exact-alarm permission is used or consulted. Android may deliver later than
 * asked and never earlier. One `PendingIntent` per reminder, told apart by the intent data, so arming again replaces the alarm.
 * The registry of armed ids is a set of random ids and nothing else.
 */
internal class AndroidReminderAlarms(
    private val context: Context,
) : ReminderAlarms {
    private val alarmManager: AlarmManager = requireNotNull(context.getSystemService(AlarmManager::class.java))
    private val registry = context.getSharedPreferences(REGISTRY, Context.MODE_PRIVATE)

    override fun arm(
        id: String,
        atMillis: Long,
    ) {
        val pending =
            PendingIntent.getBroadcast(
                context,
                0,
                ReminderAlarmReceiver.intent(context, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        registry.edit { putStringSet(KEY, armedIds() + id) }
    }

    override fun cancel(id: String) {
        // NO_CREATE: only find an alarm that exists. Creating one just to cancel it would leave a live PendingIntent behind.
        PendingIntent
            .getBroadcast(
                context,
                0,
                ReminderAlarmReceiver.intent(context, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            )?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        registry.edit { putStringSet(KEY, armedIds() - id) }
    }

    override fun armedIds(): Set<String> = registry.getStringSet(KEY, emptySet()).orEmpty().toSet()

    private companion object {
        const val REGISTRY = "thalia_reminder_alarms"
        const val KEY = "ids"
    }
}

/** The reminder channel and notification. Generic text only: what the person wrote is never shown outside the app. */
internal class AndroidReminderNotifier(
    private val context: Context,
) : ReminderNotifier {
    override fun canPost(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    override fun post(id: String) {
        createChannel(context)
        val open =
            PendingIntent.getActivity(
                context,
                0,
                openIntent(context, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_checkin_call)
                .setContentTitle(context.getString(R.string.reminder_title))
                .setContentText(context.getString(R.string.reminder_text))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        // The tag is the reminder id, so posting the same reminder twice replaces one notification and never adds a second.
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        manager.notify(id, NOTIFICATION_ID, notification)
    }

    override fun withdraw(id: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id, NOTIFICATION_ID)
    }

    override fun postedIds(): Set<String> =
        context
            .getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            .orEmpty()
            .filter { it.id == NOTIFICATION_ID && it.tag != null }
            .map { it.tag }
            .toSet()

    companion object {
        const val CHANNEL_ID = "thalia_reminders"
        const val NOTIFICATION_ID = 2001
        const val EXTRA_REMINDER_ID = "reminder_id"

        /** Low importance: no sound, no vibration, no heads-up, no full-screen intent. */
        fun createChannel(context: Context) {
            val channel =
                NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.channel_reminders_description)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        /** Opens the planning screen, which shows the current day's plan as it always does. It opens and closes no day. */
        fun openIntent(
            context: Context,
            id: String,
        ): Intent =
            Intent(context, PlanningActivity::class.java)
                .setData(
                    Uri
                        .Builder()
                        .scheme("thalia")
                        .authority("reminder-open")
                        .appendPath(id)
                        .build(),
                ).putExtra(EXTRA_REMINDER_ID, id)
    }
}
