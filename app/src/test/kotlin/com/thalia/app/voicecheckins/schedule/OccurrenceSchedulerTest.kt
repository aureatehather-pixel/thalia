package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.OccurrenceReason
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recording an occurrence and arming its alarm, in that order, and keeping the record true. */
class OccurrenceSchedulerTest {
    private val clock = TestClock()
    private val store = InMemoryOccurrenceStore()
    private val alarms = FakeAlarmScheduler(clock)
    private val scheduler = OccurrenceScheduler(store, alarms, clock)
    private val id = "2026-09-20-morning"

    @Test
    fun `scheduling records the occurrence and arms its alarm`() =
        runTest {
            val result = scheduler.schedule(occurrence())

            assertEquals(ScheduleResult.Scheduled(DUE.toEpochMilli()), result)
            assertEquals(CheckInStatus.SCHEDULED, store.get(id)!!.status)
            assertTrue(alarms.isArmed(id))
        }

    @Test
    fun `the record is written before the alarm is armed, so no alarm can exist without one`() =
        runTest {
            var recordedWhenArmed: Boolean? = null
            alarms.beforeArm = { recordedWhenArmed = runBlocking { store.get(it) } != null }

            scheduler.schedule(occurrence())

            assertEquals(true, recordedWhenArmed)
        }

    @Test
    fun `without the permission nothing is armed and the record is cancelled with the reason`() =
        runTest {
            alarms.permitted = false

            val result = scheduler.schedule(occurrence())

            assertEquals(ScheduleResult.NotPermitted, result)
            assertFalse(alarms.isArmed(id))
            val record = store.get(id)!!
            assertEquals(CheckInStatus.CANCELLED, record.status)
            assertEquals(OccurrenceReason.NOT_PERMITTED, record.reason)
        }

    @Test
    fun `a moment that is not in the future is cancelled with its own reason`() =
        runTest {
            clock.set(DUE.plusSeconds(1))

            val result = scheduler.schedule(occurrence())

            assertEquals(ScheduleResult.NotInTheFuture, result)
            assertFalse(alarms.isArmed(id))
            assertEquals(OccurrenceReason.NOT_IN_THE_FUTURE, store.get(id)!!.reason)
        }

    @Test
    fun `an occurrence that is already recorded is not scheduled again`() =
        runTest {
            scheduler.schedule(occurrence())
            store.claimForRinging(id, DUE)

            val result = scheduler.schedule(occurrence())

            assertEquals(ScheduleResult.AlreadyRecorded, result)
            assertEquals(CheckInStatus.RINGING, store.get(id)!!.status, "the existing record is untouched")
            assertEquals(1, alarms.armCalls.size, "no second alarm")
        }

    @Test
    fun `only a scheduled occurrence can be scheduled`() =
        runTest {
            val ringing = occurrence().transitionTo(CheckInStatus.RINGING, DUE)

            assertFailsWith<IllegalArgumentException> { scheduler.schedule(ringing) }
            assertNull(store.get(id))
        }

    @Test
    fun `cancelling disarms the alarm and cancels the record`() =
        runTest {
            scheduler.schedule(occurrence())

            assertTrue(scheduler.cancel(id))

            assertFalse(alarms.isArmed(id))
            val record = store.get(id)!!
            assertEquals(CheckInStatus.CANCELLED, record.status)
            assertEquals(OccurrenceReason.USER_CANCELLED, record.reason)
        }

    @Test
    fun `cancelling can give its own reason`() =
        runTest {
            scheduler.schedule(occurrence())

            scheduler.cancel(id, OccurrenceReason.PLAN_DISABLED)

            assertEquals(OccurrenceReason.PLAN_DISABLED, store.get(id)!!.reason)
        }

    @Test
    fun `cancelling an occurrence that already rang disarms the alarm and leaves the record alone`() =
        runTest {
            scheduler.schedule(occurrence())
            store.claimForRinging(id, DUE)

            assertFalse(scheduler.cancel(id))

            assertEquals(CheckInStatus.RINGING, store.get(id)!!.status)
            assertFalse(alarms.isArmed(id))
        }

    @Test
    fun `cancelling something never scheduled changes nothing`() =
        runTest {
            assertFalse(scheduler.cancel("nope"))
        }
}
