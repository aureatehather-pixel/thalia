package com.thalia.app.voicecheckins.schedule

import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the built app declares to Android, read from the merged manifest.
 *
 * Reboot restoration depends on a receiver being declared for the right broadcasts and a
 * permission being requested, and neither shows up in any other test. This is where a typo in the
 * manifest, or a broadcast action that is not the platform's, is caught before the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlatformWiringTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val pm: PackageManager get() = context.packageManager

    @Test
    fun `the broadcast actions Thalia listens for are the platform's own constants`() {
        assertEquals(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.BOOT_COMPLETED")
        assertEquals(Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.MY_PACKAGE_REPLACED")
        assertEquals(Intent.ACTION_TIME_CHANGED, "android.intent.action.TIME_SET")
        assertEquals(Intent.ACTION_TIMEZONE_CHANGED, "android.intent.action.TIMEZONE_CHANGED")
        assertEquals(
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
        assertEquals(
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            ),
            ReconcileTrigger.SYSTEM_ACTIONS.keys,
        )
    }

    @Test
    fun `the manifest declares the system event receiver for every one of those broadcasts`() {
        ReconcileTrigger.SYSTEM_ACTIONS.keys.forEach { action ->
            val receivers = pm.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            assertTrue(
                receivers.any { it.activityInfo.name == SystemEventReceiver::class.java.name },
                "no receiver is declared for $action",
            )
        }
    }

    @Test
    fun `the system event receiver is not exported, and nor is the alarm receiver`() {
        val system = pm.getReceiverInfo(ComponentName(context, SystemEventReceiver::class.java), 0)
        val alarm = pm.getReceiverInfo(ComponentName(context, CheckInAlarmReceiver::class.java), 0)

        assertFalse(system.exported, "only the system sends these broadcasts")
        assertFalse(alarm.exported, "only Android delivering Thalia's own PendingIntent may reach the alarm receiver")
    }

    @Test
    fun `the system event receiver is not declared for the locked boot broadcast`() {
        // The database is not readable before the first unlock, so that earlier broadcast is not used.
        val receivers = pm.queryBroadcastReceivers(Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED).setPackage(context.packageName), 0)

        assertTrue(receivers.none { it.activityInfo.name == SystemEventReceiver::class.java.name })
    }

    @Test
    fun `the permissions requested are exactly the ones the feature uses`() {
        val requested = assertNotNull(pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions).toSet()

        assertTrue("android.permission.SCHEDULE_EXACT_ALARM" in requested)
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in requested)
        // Deliberately absent, and to stay absent.
        assertFalse("android.permission.USE_EXACT_ALARM" in requested, "it cannot be revoked, so the degraded state could never be seen")
        assertFalse("android.permission.READ_PHONE_STATE" in requested)
        // INTERNET differs by build type since the M3A spike: the debug build requests it and the release build must not.
        // RECORD_AUDIO is in every build since Slice 4B, for on-device speech only. src/testDebug and src/testRelease hold each side.
    }
}
