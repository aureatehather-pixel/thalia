package com.thalia.app.voicecheckins.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.thalia.app.R
import com.thalia.voicecheckins.domain.CheckInType

/**
 * The call notification, and the full-screen intent that turns it into a call screen.
 *
 * Android does the hard part. A [NotificationCompat.CallStyle] notification on a
 * high-importance channel, carrying a full-screen intent, is what makes the system ring
 * and raise a screen over the keyguard. When the platform withholds the full-screen
 * intent it degrades this to a persistent heads-up with the same two actions, which is
 * the honest fallback the PRD asks for and costs no extra code.
 */
object CallNotifications {
    const val CHANNEL_ID: String = "voice_checkins_calls"

    /** A separate, quiet channel for the armed test call, so it never borrows the call channel. */
    const val ARMED_CHANNEL_ID: String = "voice_checkins_test_armed"

    /** The incoming and ongoing call share an id; the armed test must be a separate entry. */
    const val NOTIFICATION_ID: Int = 1001

    const val ARMED_NOTIFICATION_ID: Int = 1002

    /**
     * IMPORTANCE_HIGH with no sound and no vibration of its own.
     *
     * High importance is what lets the notification pop up as a heads-up and carry a
     * full-screen intent. The ringing itself is [CallRingAlert], owned by the service, so
     * the channel makes no noise: nothing can ring twice, and updating the notification
     * can never restart a sound. The user's ringer mode and Do Not Disturb still decide
     * whether [CallRingAlert] is audible.
     */
    fun createChannel(context: Context) {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_checkin_calls),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_checkin_calls_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        val armedChannel =
            NotificationChannel(
                ARMED_CHANNEL_ID,
                context.getString(R.string.channel_test_armed),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_test_armed_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        context
            .getSystemService(NotificationManager::class.java)
            ?.createNotificationChannels(listOf(channel, armedChannel))
    }

    /**
     * The foreground notification while a development test call is armed.
     *
     * Quiet, on its own low-importance channel, with a live countdown to the ring and a
     * Cancel action. It is visible on the lock screen on purpose: it is how the tester
     * can tell the test is armed after the screen has gone off.
     */
    fun armed(
        context: Context,
        checkInType: CheckInType,
        fireAtMillis: Long,
    ): Notification =
        NotificationCompat
            .Builder(context, ARMED_CHANNEL_ID)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSmallIcon(R.drawable.ic_checkin_call)
            .setContentTitle(context.getString(R.string.test_armed_title))
            .setContentText(
                context.getString(R.string.test_armed_text, context.getString(checkInType.labelRes())),
            ).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Counts down to the moment the call rings. Posted once, so it is never reset.
            .setShowWhen(true)
            .setWhen(fireAtMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .addAction(
                R.drawable.ic_checkin_call,
                context.getString(R.string.test_armed_cancel),
                servicePendingIntent(context, CheckInCallService.ACTION_CANCEL_TEST_RING),
            ).build()

    fun ringing(
        context: Context,
        checkInType: CheckInType,
    ): Notification {
        val caller =
            Person
                .Builder()
                .setName(CallUiState.THALIA)
                .setImportant(true)
                .build()

        return baseBuilder(context)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    // Decline goes to the service: it needs no UI and must work from the
                    // shade without waking an activity.
                    servicePendingIntent(context, CheckInCallService.ACTION_DECLINE),
                    // Answer goes straight to the activity. Routing it through the
                    // service would mean the service starting an activity from the
                    // background, which Android restricts; a notification action that
                    // launches an activity is the sanctioned path.
                    callActivityPendingIntent(context, answerImmediately = true),
                ),
            ).setContentText(context.getString(checkInType.labelRes()))
            .setFullScreenIntent(callActivityPendingIntent(context), true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
    }

    fun ongoing(
        context: Context,
        checkInType: CheckInType,
    ): Notification {
        val caller =
            Person
                .Builder()
                .setName(CallUiState.THALIA)
                .setImportant(true)
                .build()

        return baseBuilder(context)
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    caller,
                    servicePendingIntent(context, CheckInCallService.ACTION_END_CALL),
                ),
            ).setContentText(context.getString(checkInType.labelRes()))
            .setContentIntent(callActivityPendingIntent(context))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            // Counts up from when this notification is posted, which is the moment the
            // call is answered. It is posted once, so the timer is never reset.
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setUsesChronometer(true)
            .build()
    }

    private fun baseBuilder(context: Context): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_checkin_call)
            .setOngoing(true)
            .setAutoCancel(false)
            // Belt and braces: the channel is silent, and an update must never alert.
            .setOnlyAlertOnce(true)
            // A call belongs on the lock screen in full. There is nothing private in a
            // caller name the user chose for themselves.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    private fun servicePendingIntent(
        context: Context,
        action: String,
    ): PendingIntent =
        PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, CheckInCallService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun callActivityPendingIntent(
        context: Context,
        answerImmediately: Boolean = false,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            if (answerImmediately) 1 else 0,
            CallActivity.intent(context, answerImmediately),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

internal fun CheckInType.labelRes(): Int =
    when (this) {
        CheckInType.OPENING -> R.string.checkin_opening
        CheckInType.CLOSING -> R.string.checkin_closing
    }
