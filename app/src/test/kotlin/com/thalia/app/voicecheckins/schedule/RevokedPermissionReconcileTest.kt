package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.OccurrenceReason
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the app believes about its alarm when exact alarms are revoked, against a backend that behaves
 * as the Pixel 10 did on 2026-09-30: revoking removes the alarm Android holds and stops the app, but
 * the alarm's `PendingIntent` record stays.
 *
 * The "armed" label and the choice between `OVERDUE` and `ALARM_MISSING` both come from
 * [AlarmScheduler.isArmed], which can only see that record. These tests pin that it never claims an
 * alarm while Android would not allow one.
 */
class RevokedPermissionReconcileTest {
    /** A platform in which revoking clears the registered alarms and leaves the intent records. */
    private class Platform : AlarmBackend {
        var allowed = true
        val intents = mutableSetOf<String>()
        val registered = mutableMapOf<String, Long>()

        override fun canScheduleExactAlarms() = allowed

        override fun setExact(request: AlarmRequest) {
            if (!allowed) throw SecurityException("no exact alarm permission")
            intents += request.occurrenceId
            registered[request.occurrenceId] = request.triggerAtMillis
        }

        override fun cancel(occurrenceId: String) {
            intents -= occurrenceId
            registered -= occurrenceId
        }

        override fun isArmed(occurrenceId: String) = occurrenceId in intents

        fun revoke() {
            allowed = false
            registered.clear()
        }
    }

    private val clock = TestClock(DUE.toEpochMilli() - 3_600_000)
    private val platform = Platform()
    private val alarms = ExactAlarmScheduler(platform, clock)
    private val store = InMemoryOccurrenceStore()
    private val reconciler = Reconciler(store, alarms, activeOccurrenceId = { null }, clock = clock)
    private val id = "2026-09-20-opening"

    private suspend fun confirmedAndArmed() {
        OccurrenceScheduler(store, alarms, clock).schedule(occurrence(id))
        assertEquals(mapOf(id to DUE.toEpochMilli()), platform.registered)
    }

    private suspend fun record() = store.get(id)!!

    private fun pastDue() = clock.set(DUE.plusSeconds(600))

    @Test
    fun `an alarm is not called armed while exact alarms are not allowed, whatever intent record is left`() =
        runTest {
            confirmedAndArmed()
            assertTrue(alarms.isArmed(id))

            platform.revoke()

            assertTrue(platform.intents.contains(id), "the platform really does keep the intent record")
            assertFalse(alarms.isArmed(id), "but no alarm can exist without the permission")
        }

    @Test
    fun `launching while revoked reports the alarm unarmed and drops the useless intent record`() =
        runTest {
            confirmedAndArmed()
            platform.revoke()

            val report = reconciler.reconcile(ReconcileTrigger.APP_LAUNCH)

            assertEquals(0, report.armed)
            assertEquals(1, report.unarmed)
            assertEquals(CheckInStatus.SCHEDULED, record().status, "not yet due, so nothing is marked missed")
            assertTrue(platform.intents.isEmpty())
        }

    @Test
    fun `granting again after a launch while revoked restores the alarm and counts it as restored`() =
        runTest {
            confirmedAndArmed()
            platform.revoke()
            reconciler.reconcile(ReconcileTrigger.APP_LAUNCH)

            platform.allowed = true
            val report = reconciler.reconcile(ReconcileTrigger.EXACT_ALARM_PERMISSION_GRANTED)

            assertEquals(1, report.armed)
            assertEquals(1, report.restored)
            assertEquals(mapOf(id to DUE.toEpochMilli()), platform.registered)
        }

    @Test
    fun `an occurrence that comes due while exact alarms are still off is recorded as alarm missing, not overdue`() =
        runTest {
            confirmedAndArmed()
            platform.revoke()
            pastDue()

            reconciler.reconcile(ReconcileTrigger.APP_LAUNCH)

            assertEquals(CheckInStatus.MISSED, record().status)
            assertEquals(OccurrenceReason.ALARM_MISSING, record().reason)
        }

    @Test
    fun `the same holds when the app never ran between the revoke and the due time`() =
        runTest {
            confirmedAndArmed()
            platform.revoke() // Android stops the app; nothing reconciles before the due time
            pastDue()

            reconciler.reconcile(ReconcileTrigger.APP_LAUNCH)

            assertEquals(OccurrenceReason.ALARM_MISSING, record().reason)
        }

    @Test
    fun `an occurrence that came due while revoked and was seen before permission returned is alarm missing`() =
        runTest {
            confirmedAndArmed()
            platform.revoke()
            reconciler.reconcile(ReconcileTrigger.APP_LAUNCH) // drops the intent record while it is known useless
            pastDue()
            platform.allowed = true

            reconciler.reconcile(ReconcileTrigger.EXACT_ALARM_PERMISSION_GRANTED)

            assertEquals(OccurrenceReason.ALARM_MISSING, record().reason)
        }

    @Test
    fun `a known limit - revoked and granted again with no reconcile in between and past due reads as overdue`() =
        runTest {
            confirmedAndArmed()
            platform.revoke()
            pastDue()
            platform.allowed = true // no launch happened, so the useless intent record is still there and looks live

            reconciler.reconcile(ReconcileTrigger.EXACT_ALARM_PERMISSION_GRANTED)

            // The record says only what was seen: an alarm appeared to be registered and never rang.
            assertEquals(OccurrenceReason.OVERDUE, record().reason)
        }

    @Test
    fun `permission on and the alarm present is armed, and the revoke does not disturb a fresh arming`() =
        runTest {
            confirmedAndArmed()
            platform.revoke()
            reconciler.reconcile(ReconcileTrigger.APP_LAUNCH)
            platform.allowed = true
            reconciler.reconcile(ReconcileTrigger.MANUAL)

            assertTrue(alarms.isArmed(id))
            assertEquals(Instant.ofEpochMilli(DUE.toEpochMilli()), Instant.ofEpochMilli(platform.registered.getValue(id)))
        }
}
