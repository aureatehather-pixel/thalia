package com.thalia.app.reminders

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.PlanningActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0014 at the platform edge: one inexact alarm per reminder, and one quiet, generic notification per reminder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AndroidRemindersTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val a = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1e001"
    private val b = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1e002"

    @Test
    fun `each reminder has its own alarm, arming again replaces it, and cancelling removes only that one`() {
        val alarms = AndroidReminderAlarms(context)
        alarms.arm(a, 1_000_000L)
        alarms.arm(a, 2_000_000L)
        alarms.arm(b, 3_000_000L)
        val scheduled = shadowOf(alarmManager).scheduledAlarms
        assertEquals(setOf(2_000_000L, 3_000_000L), scheduled.map { it.triggerAtMs }.toSet())
        assertEquals(setOf(a, b), alarms.armedIds())

        alarms.cancel(a)
        assertEquals(listOf(3_000_000L), shadowOf(alarmManager).scheduledAlarms.map { it.triggerAtMs })
        assertEquals(setOf(b), alarms.armedIds())
        alarms.cancel(a) // already gone: nothing to find, nothing created, nothing thrown
        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `arming needs no exact alarm permission`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        AndroidReminderAlarms(context).arm(a, 1_000_000L)
        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `a reminder alarm intent identifies its reminder and nothing else`() {
        val one = ReminderAlarmReceiver.intent(context, a)
        assertTrue(one.filterEquals(ReminderAlarmReceiver.intent(context, a)))
        assertFalse(one.filterEquals(ReminderAlarmReceiver.intent(context, b)))
        assertEquals(a, one.data?.lastPathSegment)
        assertEquals(ReminderAlarmReceiver.ACTION_FIRE, one.action)
    }

    @Test
    fun `the channel is quiet and low importance`() {
        AndroidReminderNotifier.createChannel(context)
        val channel = notifications.getNotificationChannel(AndroidReminderNotifier.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
    }

    @Test
    fun `a notification is generic, tagged with its reminder, and posting twice leaves one`() {
        val notifier = AndroidReminderNotifier(context)
        assertTrue(notifier.canPost())
        notifier.post(a)
        notifier.post(a)
        notifier.post(b)
        assertEquals(setOf(a, b), notifier.postedIds())
        val posted = notifications.activeNotifications.single { it.tag == a }
        assertEquals(AndroidReminderNotifier.NOTIFICATION_ID, posted.id)
        val extras = posted.notification.extras
        assertEquals("Thalia reminder", extras.getCharSequence("android.title").toString())
        assertEquals("Open Thalia to see it.", extras.getCharSequence("android.text").toString())
        assertEquals(0, posted.notification.actions?.size ?: 0)
        assertNull(posted.notification.fullScreenIntent)

        val opens = shadowOf(posted.notification.contentIntent).savedIntent
        assertEquals(PlanningActivity::class.java.name, opens.component?.className)
        assertEquals(a, opens.getStringExtra(AndroidReminderNotifier.EXTRA_REMINDER_ID))

        notifier.withdraw(a)
        assertEquals(setOf(b), notifier.postedIds())
        notifier.withdraw(a) // already gone
        assertEquals(setOf(b), notifier.postedIds())
    }

    @Test
    fun `notifications turned off for the app mean a reminder cannot be posted`() {
        val notifier = AndroidReminderNotifier(context)
        shadowOf(notifications).setNotificationsEnabled(false)
        assertFalse(notifier.canPost())
        shadowOf(notifications).setNotificationsEnabled(true)
        assertTrue(notifier.canPost())
    }

    @Test
    fun `a channel the person switched off means a reminder cannot be posted`() {
        notifications.createNotificationChannel(
            NotificationChannel(AndroidReminderNotifier.CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_NONE),
        )
        assertFalse(AndroidReminderNotifier(context).canPost())
    }
}
