package com.thalia.app.voicecheckins.schedule

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.voicecheckins.data.CheckInDatabase
import com.thalia.app.voicecheckins.data.RoomOccurrenceStore
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A whole reboot, on a real database file: the process dies, the phone is off for a while, and the
 * app comes back to find only what was written down.
 *
 * The database is closed and reopened as a new instance, the fake alarms are wiped as a restart
 * wipes them, and the reconciler is built fresh, so nothing survives except what is on disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RebootRestorationRoomTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val file = "reboot-test.db"
    private val clock = TestClock(DUE.toEpochMilli() - 3_600_000)
    private val alarms = FakeAlarmScheduler(clock)
    private val opened = mutableListOf<CheckInDatabase>()

    private fun open(): RoomOccurrenceStore {
        val db = Room.databaseBuilder(context, CheckInDatabase::class.java, file).build()
        opened += db
        return RoomOccurrenceStore(db) { KUALA_LUMPUR }
    }

    private fun closeAll() {
        opened.forEach { it.close() }
        opened.clear()
    }

    @After
    fun cleanUp() {
        closeAll()
        context.deleteDatabase(file)
    }

    @Test
    fun `future alarms are restored and overdue ones are marked missed after the app comes back from a reboot`() =
        runTest {
            // Before the reboot: three occurrences scheduled and armed.
            val before = open()
            val scheduler = OccurrenceScheduler(before, alarms, clock)
            scheduler.schedule(occurrence("soon", at = DUE))
            scheduler.schedule(occurrence("later", CheckInType.CLOSING, DUE.plusSeconds(18_000)))
            scheduler.schedule(occurrence("tomorrow", at = DUE.plusSeconds(86_400)))
            assertEquals(3, alarms.armed.size)

            // The process dies, the phone shuts down and every alarm goes with it.
            closeAll()
            alarms.loseEverything()
            clock.set(DUE.plusSeconds(1800)) // and it is half an hour after "soon" was due when the phone is back

            // After the reboot: a new database instance, a new reconciler, nothing in memory.
            val after = open()
            val report = Reconciler(after, alarms, { null }, clock).reconcile(ReconcileTrigger.BOOT)

            assertEquals(CheckInStatus.MISSED, after.get("soon")!!.status)
            assertEquals(OccurrenceReason.ALARM_MISSING, after.get("soon")!!.reason)
            assertEquals(setOf("later", "tomorrow"), alarms.armed.keys, "the two still to come are armed again")
            assertEquals(DUE.plusSeconds(18_000).toEpochMilli(), alarms.armed["later"])
            assertEquals(2, report.restored)
            assertEquals(listOf("soon"), report.changes.map { it.occurrenceId })
        }

    @Test
    fun `what a reboot restored is still there when the app is restarted again`() =
        runTest {
            val first = open()
            OccurrenceScheduler(first, alarms, clock).schedule(occurrence("kept", at = DUE.plusSeconds(3600)))
            closeAll()
            alarms.loseEverything()
            Reconciler(open(), alarms, { null }, clock).reconcile(ReconcileTrigger.BOOT)
            closeAll()

            val third = open()

            assertEquals(CheckInStatus.SCHEDULED, third.get("kept")!!.status)
            assertTrue(alarms.isArmed("kept"))
        }

    @Test
    fun `a call that was ringing when the process died is cleared by the reconciliation that follows`() =
        runTest {
            val before = open()
            // Two calls held at once can no longer come from the claim, but a file written before the
            // one-call rule can hold them, so they are written as rows here.
            before.insert(occurrence("was-ringing").transitionTo(CheckInStatus.RINGING, DUE))
            before.insert(
                occurrence("was-talking", CheckInType.CLOSING, DUE.plusSeconds(1))
                    .transitionTo(CheckInStatus.RINGING, DUE)
                    .transitionTo(CheckInStatus.ANSWERED, DUE.plusSeconds(5)),
            )
            closeAll()
            clock.set(DUE.plusSeconds(300))

            val after = open()
            Reconciler(after, alarms, { null }, clock).reconcile(ReconcileTrigger.APP_LAUNCH)

            assertEquals(CheckInStatus.MISSED, after.get("was-ringing")!!.status)
            assertEquals(CheckInStatus.FAILED, after.get("was-talking")!!.status)
            assertEquals(OccurrenceReason.INTERRUPTED, after.get("was-ringing")!!.reason)
            assertEquals(OccurrenceReason.INTERRUPTED, after.get("was-talking")!!.reason)
        }

    @Test
    fun `reconciling twice on the real database changes nothing the second time`() =
        runTest {
            val store = open()
            OccurrenceScheduler(store, alarms, clock).schedule(occurrence("a", at = DUE))
            alarms.loseEverything()
            clock.set(DUE.plusSeconds(600))
            val reconciler = Reconciler(store, alarms, { null }, clock)

            val first = reconciler.reconcile(ReconcileTrigger.BOOT)
            val second = reconciler.reconcile(ReconcileTrigger.BOOT)

            assertEquals(1, first.changes.size)
            assertTrue(second.changes.isEmpty())
            assertEquals(OccurrenceReason.ALARM_MISSING, store.get("a")!!.reason)
        }

    @Test
    fun `the alarm that fires after a restoration is claimed once on the real database`() =
        runTest {
            val store = open()
            OccurrenceScheduler(store, alarms, clock).schedule(occurrence("restored", at = DUE.plusSeconds(3600)))
            alarms.loseEverything()
            Reconciler(store, alarms, { null }, clock).reconcile(ReconcileTrigger.BOOT)
            assertTrue(alarms.isArmed("restored"))

            var calls = 0
            val starter = CallStarter { _, _ -> (++calls) > 0 }
            val payload = AlarmPayload("restored", CheckInType.OPENING, DUE.plusSeconds(3600).toEpochMilli())
            val handler = CheckInFireHandler({ DUE.plusSeconds(3600).toEpochMilli() }, store, starter)

            handler.onAlarm(payload)
            handler.onAlarm(payload)

            assertEquals(1, calls)
            assertEquals(CheckInStatus.RINGING, store.get("restored")!!.status)
        }
}
