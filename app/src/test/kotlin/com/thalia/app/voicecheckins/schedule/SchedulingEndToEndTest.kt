package com.thalia.app.voicecheckins.schedule

import android.app.AlarmManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.ThaliaApplication
import com.thalia.app.voicecheckins.call.CheckInCallService
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real receivers, the real Room database and the real scheduler, run on the computer.
 *
 * Android's alarm clock is Robolectric's stand-in, and a "reboot" is cancelling every alarm the way
 * the platform does. Everything Thalia owns is the real thing: a broadcast reaches the manifest's
 * receiver, which works through the store and either starts the call service or does not.
 *
 * This is the closest the computer can get to the device for the parts that are Thalia's. It says
 * nothing about whether Android delivers the alarm, starts the service, or plays the ringtone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = ThaliaApplication::class)
class SchedulingEndToEndTest {
    private val app: ThaliaApplication get() = ApplicationProvider.getApplicationContext()
    private val alarmManager: AlarmManager get() = app.getSystemService(AlarmManager::class.java)
    private val store get() = app.occurrenceStore

    @Before
    fun exactAlarmsAllowed() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun status(id: String): CheckInStatus? = runBlocking { store.get(id)?.status }

    private fun stored(id: String) = runBlocking { store.get(id) }

    private fun startedServices(): List<Intent> = generateSequence { shadowOf(app).nextStartedService }.toList()

    private fun fire(
        id: String,
        type: CheckInType,
        scheduledAtMillis: Long,
    ) = app.sendBroadcast(CheckInAlarmReceiver.fireIntent(app, AlarmRequest(id, type, scheduledAtMillis)))

    /** Every alarm Android holds, as the fake clock has it. */
    private fun scheduledAlarms() = shadowOf(alarmManager).scheduledAlarms

    /**
     * What a restart of the phone does: every alarm, and the intent behind it, is gone.
     *
     * It works on Robolectric's own record of the alarms, not through Thalia's cancel, so the
     * wipe does not depend on the code under test. The deprecated field is the only handle
     * Robolectric gives on an alarm's intent.
     */
    @Suppress("DEPRECATION")
    private fun rebootPhone() {
        scheduledAlarms().toList().forEach { alarm ->
            alarm.operation?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
    }

    private fun insertScheduled(
        id: String,
        atMillis: Long,
        type: CheckInType = CheckInType.OPENING,
    ) = runBlocking { store.insert(occurrence(id, type, Instant.ofEpochMilli(atMillis))) }

    // An alarm is delivered

    @Test
    fun `an alarm delivered on time claims the occurrence and starts the call service for it`() {
        val now = System.currentTimeMillis()
        insertScheduled("2026-09-20-morning", now)

        fire("2026-09-20-morning", CheckInType.OPENING, now)
        await("the occurrence to be claimed") { status("2026-09-20-morning") == CheckInStatus.RINGING }

        val service = assertNotNull(shadowOf(app).nextStartedService)
        assertEquals(CheckInCallService.ACTION_RING, service.action)
        assertEquals("2026-09-20-morning", service.getStringExtra("occurrence_id"))
        assertEquals("OPENING", service.getStringExtra("check_in_type"))
        assertNotNull(stored("2026-09-20-morning")?.firedAt)
    }

    @Test
    fun `the same alarm delivered several times starts the call service exactly once`() {
        val now = System.currentTimeMillis()
        insertScheduled("2026-09-20-morning", now)

        repeat(6) { fire("2026-09-20-morning", CheckInType.OPENING, now) }
        await("the occurrence to be claimed") { status("2026-09-20-morning") == CheckInStatus.RINGING }
        Thread.sleep(500) // give any wrongly started second call time to appear
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, startedServices().size, "one call, however many deliveries")
    }

    @Test
    fun `an alarm delivered over two minutes late starts nothing and is recorded missed`() {
        val late = System.currentTimeMillis() - 3 * 60_000
        insertScheduled("2026-09-20-morning", late)

        fire("2026-09-20-morning", CheckInType.OPENING, late)
        await("the late alarm to be recorded") { status("2026-09-20-morning") == CheckInStatus.MISSED }

        assertEquals(OccurrenceReason.TOO_LATE, stored("2026-09-20-morning")?.reason)
        assertNull(shadowOf(app).nextStartedService, "a check-in that is too late must never ring")
    }

    @Test
    fun `an alarm for an occurrence that was never recorded starts nothing`() {
        val now = System.currentTimeMillis()

        fire("2026-09-20-morning", CheckInType.OPENING, now)
        Thread.sleep(500)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(app).nextStartedService)
        assertNull(stored("2026-09-20-morning"))
    }

    @Test
    fun `an alarm that says nothing about what it is for starts nothing`() {
        app.sendBroadcast(Intent(app, CheckInAlarmReceiver::class.java).setAction(CheckInAlarmReceiver.ACTION_FIRE))
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(app).nextStartedService)
    }

    // Scheduling arms a real alarm and records it

    @Test
    fun `scheduling records the occurrence and arms an exact alarm for it`() {
        val due = System.currentTimeMillis() + 3_600_000

        val result = runBlocking { app.occurrenceScheduler.schedule(occurrence("2026-09-20-morning", at = Instant.ofEpochMilli(due))) }

        assertEquals(ScheduleResult.Scheduled(due), result)
        assertEquals(CheckInStatus.SCHEDULED, status("2026-09-20-morning"))
        assertEquals(1, scheduledAlarms().size)
        assertTrue(app.alarmScheduler.isArmed("2026-09-20-morning"))
    }

    @Test
    fun `cancelling disarms the alarm and cancels the record`() {
        val due = System.currentTimeMillis() + 3_600_000
        runBlocking { app.occurrenceScheduler.schedule(occurrence("2026-09-20-morning", at = Instant.ofEpochMilli(due))) }

        runBlocking { app.occurrenceScheduler.cancel("2026-09-20-morning") }

        assertEquals(CheckInStatus.CANCELLED, status("2026-09-20-morning"))
        assertEquals(0, scheduledAlarms().size)
        assertEquals(false, app.alarmScheduler.isArmed("2026-09-20-morning"))
    }

    // Restoration after the system says something happened

    @Test
    fun `after a reboot the boot broadcast restores a future alarm and marks an overdue one missed`() {
        val now = System.currentTimeMillis()
        runBlocking {
            app.occurrenceScheduler.schedule(occurrence("future", at = Instant.ofEpochMilli(now + 3_600_000)))
        }
        insertScheduled("overdue", now - 3_600_000, CheckInType.CLOSING)
        assertEquals(1, scheduledAlarms().size)

        rebootPhone()
        assertEquals(0, scheduledAlarms().size, "the reboot took every alarm")
        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED))

        await("the future alarm to come back and the overdue one to be marked") {
            scheduledAlarms().size == 1 && status("overdue") == CheckInStatus.MISSED
        }
        assertTrue(app.alarmScheduler.isArmed("future"))
        assertEquals(OccurrenceReason.ALARM_MISSING, stored("overdue")?.reason)
        assertNull(shadowOf(app).nextStartedService, "restoring after a reboot must never ring a call")
    }

    @Test
    fun `every system event that can upset the alarms restores a lost one`() {
        val now = System.currentTimeMillis()
        val events =
            mapOf(
                ReconcileTrigger.BOOT to Intent.ACTION_BOOT_COMPLETED,
                ReconcileTrigger.PACKAGE_REPLACED to Intent.ACTION_MY_PACKAGE_REPLACED,
                ReconcileTrigger.TIME_CHANGED to Intent.ACTION_TIME_CHANGED,
                ReconcileTrigger.TIMEZONE_CHANGED to Intent.ACTION_TIMEZONE_CHANGED,
                ReconcileTrigger.EXACT_ALARM_PERMISSION_GRANTED to AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            )
        assertEquals(ReconcileTrigger.entries.toSet() - setOf(ReconcileTrigger.APP_LAUNCH, ReconcileTrigger.MANUAL), events.keys)

        events.forEach { (trigger, action) ->
            val id = "future-${trigger.name.lowercase()}"
            runBlocking { app.occurrenceScheduler.schedule(occurrence(id, at = Instant.ofEpochMilli(now + 3_600_000))) }
            rebootPhone()
            assertEquals(false, app.alarmScheduler.isArmed(id), "$trigger: the alarm must be gone before the event")

            app.sendBroadcast(Intent(action))

            await("$trigger to restore the alarm") { app.alarmScheduler.isArmed(id) }
            // Leave nothing armed for the next event to trip over.
            runBlocking { app.occurrenceScheduler.cancel(id) }
        }
    }

    @Test
    fun `granting the exact alarm permission arms an occurrence that could not be armed before`() {
        val due = System.currentTimeMillis() + 3_600_000
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        insertScheduled("waiting", due)
        runBlocking { app.reconcile(ReconcileTrigger.APP_LAUNCH) }
        assertEquals(false, app.alarmScheduler.isArmed("waiting"))
        assertEquals(CheckInStatus.SCHEDULED, status("waiting"), "not armed, and not lost")

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        app.sendBroadcast(Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))

        await("the alarm to be armed") { app.alarmScheduler.isArmed("waiting") }
    }

    @Test
    fun `an unrelated broadcast does not reconcile anything`() {
        val due = System.currentTimeMillis() + 3_600_000
        insertScheduled("unarmed", due)

        app.sendBroadcast(Intent(app, SystemEventReceiver::class.java).setAction("android.intent.action.LOCKED_BOOT_COMPLETED"))
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(false, app.alarmScheduler.isArmed("unarmed"))
    }

    @Test
    fun `reconciling from the app launch does the same work`() {
        val now = System.currentTimeMillis()
        insertScheduled("future", now + 3_600_000)
        insertScheduled("overdue", now - 3_600_000, CheckInType.CLOSING)

        val report = runBlocking { app.reconcile(ReconcileTrigger.APP_LAUNCH) }

        assertEquals(1, report.restored)
        assertEquals(listOf("overdue"), report.changes.map { it.occurrenceId })
        assertEquals(CheckInStatus.MISSED, status("overdue"))
        assertEquals(report, app.lastReconcile.value)
    }
}
