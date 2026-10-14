package com.thalia.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.thalia.app.ThaliaApplication
import kotlinx.coroutines.launch

/**
 * Receives a reminder's alarm and hands it to [ReminderService], which decides from the stored row. Not exported: only the system
 * delivering Thalia's own `PendingIntent` can reach it. The work is a database read and write, so it runs off the main thread
 * with `goAsync` holding the broadcast open.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.data?.lastPathSegment ?: return
        val app = context.applicationContext as ThaliaApplication
        val pending = goAsync()
        app.ioScope.launch {
            try {
                Log.i(LOG_TAG, "reminder alarm delivered: ${app.fireReminder(id)}")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "reminder alarm delivered but handling it failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    internal companion object {
        const val ACTION_FIRE = "com.thalia.app.action.REMINDER_ALARM"
        private const val LOG_TAG = "ThaliaReminder"

        fun intent(
            context: Context,
            id: String,
        ): Intent =
            Intent(context, ReminderAlarmReceiver::class.java)
                .setAction(ACTION_FIRE)
                .setData(
                    Uri
                        .Builder()
                        .scheme("thalia")
                        .authority("reminder")
                        .appendPath(id)
                        .build(),
                )
    }
}
