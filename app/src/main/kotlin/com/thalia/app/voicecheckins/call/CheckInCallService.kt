package com.thalia.app.voicecheckins.call

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.thalia.app.BuildConfig
import com.thalia.app.ThaliaApplication
import com.thalia.voicecheckins.domain.CheckInType
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps Thalia alive while, and only while, a check-in is ringing or in progress.
 *
 * This class is a thin shell around Android's service lifecycle. It forwards start
 * commands and coordinator state to a [CallServiceController], which decides everything
 * else, and it carries out the six side effects that controller asks for through
 * [CallServiceHost]. There is no call logic here to get wrong, and no second place that
 * posts a notification or starts a sound.
 *
 * It stops itself the moment the call ends. Nothing runs between check-ins. The one
 * exception is development only: while a ten second test call is armed it also runs, and
 * holds the CPU awake, so that the call can start with the phone locked and dark.
 */
class CheckInCallService : LifecycleService() {
    private val thalia: ThaliaApplication
        get() = application as ThaliaApplication

    private lateinit var ringAlert: CallRingAlert
    private lateinit var armedWakeLock: ArmedWakeLock
    private lateinit var controller: CallServiceController

    /** The id of the most recent start command, so a stop cannot cancel a newer start. */
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        ringAlert = CallRingAlert(this)
        armedWakeLock = ArmedWakeLock(this)
        controller = CallServiceController(thalia.callCoordinator, thalia.devTestRing, Host())

        // Observation begins now, but the controller ignores state until the first start
        // command has been handled, so the coordinator's idle or ended state at creation
        // time cannot be mistaken for a finished call.
        lifecycleScope.launch { controller.observe() }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        val command = intent.toCommand()
        // The other end of the alarm receiver's log line, so one logcat filter shows how long
        // the hand-over from receiver to service took on the device.
        if (command is CallServiceCommand.Ring) {
            Log.i(
                LOG_TAG,
                "ring command: occurrence=${command.occurrenceId} type=${command.checkInType} " +
                    "atMillis=${System.currentTimeMillis()}",
            )
        }
        controller.onCommand(command)

        // Not sticky: a check-in that was interrupted by a process death is a missed
        // check-in, not something to silently resurrect and ring at the wrong moment.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.onDestroy()
        removeArmedNotification()
        armedWakeLock.release()
        super.onDestroy()
    }

    private inner class Host : CallServiceHost {
        override fun showArmed(
            checkInType: CheckInType,
            fireAtMillis: Long,
        ) {
            // Hold the CPU for the rest of the armed window plus a margin. The timeout is
            // a backstop only: the hold is released the moment the window ends.
            val remaining = (fireAtMillis - System.currentTimeMillis()).coerceAtLeast(0).milliseconds
            armedWakeLock.hold(remaining + ARMED_HOLD_MARGIN)
            enterForeground(
                CallNotifications.armed(this@CheckInCallService, checkInType, fireAtMillis),
                CallNotifications.ARMED_NOTIFICATION_ID,
            )
        }

        override fun showRinging(checkInType: CheckInType) {
            enterForeground(CallNotifications.ringing(this@CheckInCallService, checkInType))
            removeArmedNotification()
            armedWakeLock.release()
        }

        override fun showOngoing(checkInType: CheckInType) {
            enterForeground(CallNotifications.ongoing(this@CheckInCallService, checkInType))
            removeArmedNotification()
            armedWakeLock.release()
        }

        override fun startRingAlert() = ringAlert.start()

        override fun stopRingAlert() = ringAlert.stop()

        override fun shutDown() {
            armedWakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            removeArmedNotification()
            // Stopping by start id means a start command that arrived after the one this
            // stop belongs to keeps the service alive, instead of being silently dropped.
            stopSelf(lastStartId)
        }
    }

    private fun removeArmedNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(CallNotifications.ARMED_NOTIFICATION_ID)
    }

    /**
     * Enter the foreground, or update the notification once already there.
     *
     * Calling startForeground again is the supported way to change a foreground
     * notification, and it keeps the service type attached.
     */
    private fun enterForeground(
        notification: Notification,
        notificationId: Int = CallNotifications.NOTIFICATION_ID,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Service types are declared per call from Android 14, and specialUse is the
            // type this service carries in the manifest. See ADR 0004.
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    companion object {
        const val ACTION_RING: String = "com.thalia.app.action.RING"
        const val ACTION_DECLINE: String = "com.thalia.app.action.DECLINE"
        const val ACTION_END_CALL: String = "com.thalia.app.action.END_CALL"
        const val ACTION_ARM_TEST_RING: String = "com.thalia.app.action.ARM_TEST_RING"
        const val ACTION_CANCEL_TEST_RING: String = "com.thalia.app.action.CANCEL_TEST_RING"

        /** How long past the armed window the wake lock may last if it is somehow never released. */
        private val ARMED_HOLD_MARGIN = 5.seconds

        /** Shared with the alarm receiver, so one logcat filter follows an alarm into a call. */
        private const val LOG_TAG = "ThaliaAlarm"

        private const val EXTRA_CHECK_IN_TYPE = "check_in_type"
        private const val EXTRA_OCCURRENCE_ID = "occurrence_id"

        /**
         * Start ringing for [occurrenceId]. The one door into a call.
         *
         * A button in the foreground app reaches it with the default id. The alarm receiver
         * reaches it from the background, which is the case Android restricts: whether a
         * foreground service may start from there, and whether the ringtone is then audible,
         * is what the device validation for the scheduled call has to settle. This throws
         * what Android throws when the start is refused, and the caller decides what to do.
         */
        fun startRinging(
            context: Context,
            checkInType: CheckInType,
            occurrenceId: String = CheckInCallCoordinator.MANUAL_TRIGGER_OCCURRENCE_ID,
        ) {
            val intent =
                Intent(context, CheckInCallService::class.java)
                    .setAction(ACTION_RING)
                    .putExtra(EXTRA_CHECK_IN_TYPE, checkInType.name)
                    .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
            context.startForegroundService(intent)
        }

        /**
         * Development only: arm one test call that will ring after a short delay.
         *
         * Starts the service, which holds the process and the CPU through the wait. Reached
         * only from a button in the foreground app, so the foreground-service start
         * restriction does not apply.
         */
        fun armTestRing(
            context: Context,
            checkInType: CheckInType,
        ) {
            if (!BuildConfig.DEBUG) return
            val intent =
                Intent(context, CheckInCallService::class.java)
                    .setAction(ACTION_ARM_TEST_RING)
                    .putExtra(EXTRA_CHECK_IN_TYPE, checkInType.name)
            context.startForegroundService(intent)
        }

        private fun Intent?.toCommand(): CallServiceCommand =
            when (this?.action) {
                ACTION_RING -> CallServiceCommand.Ring(checkInType(), occurrenceId())
                ACTION_DECLINE -> CallServiceCommand.Decline
                ACTION_END_CALL -> CallServiceCommand.EndCall
                ACTION_ARM_TEST_RING ->
                    if (BuildConfig.DEBUG) {
                        CallServiceCommand.ArmTestRing(checkInType())
                    } else {
                        CallServiceCommand.Unrecognised
                    }
                ACTION_CANCEL_TEST_RING -> CallServiceCommand.CancelTestRing
                else -> CallServiceCommand.Unrecognised
            }

        private fun Intent?.checkInType(): CheckInType =
            this
                ?.getStringExtra(EXTRA_CHECK_IN_TYPE)
                ?.let { CheckInType.fromStoredName(it) }
                ?: CheckInType.OPENING

        private fun Intent?.occurrenceId(): String =
            this
                ?.getStringExtra(EXTRA_OCCURRENCE_ID)
                ?.takeIf { it.isNotBlank() }
                ?: CheckInCallCoordinator.MANUAL_TRIGGER_OCCURRENCE_ID
    }
}
