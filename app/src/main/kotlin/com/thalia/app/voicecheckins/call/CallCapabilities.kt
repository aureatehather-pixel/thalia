package com.thalia.app.voicecheckins.call

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * What Android will actually let this app do right now.
 *
 * M1 exists to answer one question: does a locked Pixel 10 raise a full-screen call
 * screen? A build that silently degrades would answer it wrongly, so every input to
 * that outcome is read from the platform at runtime and shown on screen.
 */
data class CallCapabilities(
    val sdkInt: Int,
    val postNotificationsGranted: Boolean,
    val notificationsEnabled: Boolean,
    val channelImportance: Int,
    val canUseFullScreenIntent: Boolean,
    val canScheduleExactAlarms: Boolean,
) {
    /** True when the incoming-call surface should appear over the lock screen. */
    val fullScreenCallExpected: Boolean
        get() =
            notificationsEnabled &&
                postNotificationsGranted &&
                canUseFullScreenIntent &&
                channelImportance >= NotificationManager.IMPORTANCE_HIGH

    /**
     * What the user will actually get, in plain language.
     *
     * The honest fallback is not something this app has to build: when the full-screen
     * intent is unavailable, Android itself shows the call notification as a persistent
     * heads-up with Answer and Decline. That is a worse experience, and the app says so
     * rather than pretending the call screen appeared.
     */
    val expectedBehaviour: String
        get() =
            when {
                !postNotificationsGranted ->
                    "Nothing will happen. Notification permission is not granted, so Thalia cannot ring at all."

                !notificationsEnabled ->
                    "Nothing will happen. Notifications are switched off for Thalia in system settings."

                channelImportance < NotificationManager.IMPORTANCE_HIGH ->
                    "A silent entry in the notification shade. The check-in channel has been " +
                        "turned down below High importance, so it will not ring or pop up."

                !canUseFullScreenIntent ->
                    "A notification with Answer and Decline remains available, not a full-screen call. " +
                        "Android is withholding the full-screen intent permission. This is the " +
                        "honest fallback, not a failure."

                else ->
                    "A full-screen incoming-call screen, over the lock screen, with Answer and Decline."
            }

    /**
     * What Android's exact-alarm permission means for scheduled check-ins, in plain language.
     *
     * Independent of [expectedBehaviour], which is about how a call is presented. This is
     * about whether one can be started at its time at all. There is no approximate fallback:
     * without the permission Thalia places no scheduled call, and says so here.
     */
    val exactAlarmBehaviour: String
        get() =
            if (canScheduleExactAlarms) {
                "Scheduled check-ins can start at their exact time."
            } else {
                "Scheduled check-ins are off. Android is not allowing Thalia to set exact alarms, " +
                    "and Thalia does not fall back to approximate times. Allow Alarms & reminders " +
                    "for Thalia to turn them on."
            }

    /** Whether the full-screen intent permission is a thing on this OS version at all. */
    val fullScreenIntentIsGovernedByPermission: Boolean
        get() = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    companion object {
        fun read(context: Context): CallCapabilities {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager?.getNotificationChannel(CallNotifications.CHANNEL_ID)

            return CallCapabilities(
                sdkInt = Build.VERSION.SDK_INT,
                postNotificationsGranted = isPostNotificationsGranted(context),
                notificationsEnabled =
                    NotificationManagerCompat
                        .from(context)
                        .areNotificationsEnabled(),
                channelImportance = channel?.importance ?: NotificationManager.IMPORTANCE_NONE,
                canUseFullScreenIntent = canUseFullScreenIntent(manager),
                canScheduleExactAlarms = canScheduleExactAlarms(context),
            )
        }

        // minSdk is 31, which is where the exact-alarm permission and this query begin.
        // Read fresh each time: the user can change it in Settings at any moment.
        private fun canScheduleExactAlarms(context: Context): Boolean =
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false

        private fun isPostNotificationsGranted(context: Context): Boolean =
            // Before Android 13 there is no such runtime permission; posting is allowed.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                true
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            }

        private fun canUseFullScreenIntent(manager: NotificationManager?): Boolean =
            // The permission, and the API to query it, both arrive in Android 14.
            // Earlier versions allow full-screen intents unconditionally.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                true
            } else {
                manager?.canUseFullScreenIntent() ?: false
            }
    }
}
