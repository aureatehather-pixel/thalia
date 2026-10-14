package com.thalia.app.voicecheckins.devtools

import com.thalia.app.voicecheckins.schedule.FakeAlarmScheduler
import com.thalia.app.voicecheckins.schedule.InMemoryOccurrenceStore
import com.thalia.app.voicecheckins.schedule.OccurrenceScheduler
import com.thalia.app.voicecheckins.schedule.ScheduleResult
import com.thalia.app.voicecheckins.schedule.TestClock
import com.thalia.app.voicecheckins.schedule.occurrence
import com.thalia.voicecheckins.domain.CheckInPlan
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The debug scheduling control, tested without Android.
 *
 * It goes through the real scheduler and the real store here, with a fake for Android's alarms, so
 * this proves what the control records and arms. That the alarm fires is the device's to prove.
 */
class DevScheduledRingTest {
    private val clock = TestClock(1_789_000_000_000L)
    private val store = InMemoryOccurrenceStore()
    private val alarms = FakeAlarmScheduler(clock)
    private val auckland = ZoneId.of("Pacific/Auckland")
    private var zone = auckland

    private fun ring() = DevScheduledRing(OccurrenceScheduler(store, alarms, clock), store, clock = clock, zone = { zone })

    @Test
    fun `arms one morning check-in two minutes from now and records it`() =
        runTest {
            val result = ring().schedule()

            val stored = store.open().single()
            assertEquals(clock.millis + 2.minutes.inWholeMilliseconds, stored.scheduledAt.toEpochMilli())
            assertEquals(CheckInType.OPENING, stored.type)
            assertEquals(CheckInStatus.SCHEDULED, stored.status)
            assertEquals(ScheduleResult.Scheduled(stored.scheduledAt.toEpochMilli()), result)
            assertTrue(alarms.isArmed(stored.id))
        }

    @Test
    fun `the pair arms two check-ins for the same instant, replacing earlier debug ones`() =
        runTest {
            ring().schedule()
            val earlier = store.open().single().id
            clock.advanceMillis(5_000)

            val results = ring().schedulePair()

            val at = clock.millis + 2.minutes.inWholeMilliseconds
            assertEquals(listOf(ScheduleResult.Scheduled(at), ScheduleResult.Scheduled(at)), results)
            assertEquals(listOf("dev-$at", "dev-$at-b"), store.open().map { it.id })
            assertEquals(mapOf("dev-$at" to at, "dev-$at-b" to at), alarms.armed)
            assertEquals(CheckInStatus.CANCELLED, store.get(earlier)?.status)
        }

    @Test
    fun `the debug quiet period starts at the current minute and lasts fifteen minutes, wrapping past midnight`() {
        val afternoon = quietPeriodFrom(LocalTime.of(14, 7, 42))
        assertEquals(LocalTime.of(14, 7), afternoon.start)
        assertEquals(LocalTime.of(14, 22), afternoon.end)
        assertTrue(afternoon.enabled)
        assertTrue(afternoon.covers(LocalTime.of(14, 17)), "a ten-minute move from now falls inside it")

        val lateNight = quietPeriodFrom(LocalTime.of(23, 50))
        assertTrue(lateNight.spansMidnight)
        assertTrue(lateNight.covers(LocalTime.of(0, 4)))
        assertEquals("on, 23:50 to 00:05 (spans midnight)", lateNight.summaryLine())
        assertEquals("off (23:50 to 00:05 when on)", lateNight.copy(enabled = false).summaryLine())
    }

    @Test
    fun `the occurrence is stamped in the device's current zone`() =
        runTest {
            ring().schedule()

            assertEquals(auckland, store.open().single().zone)
        }

    @Test
    fun `the zone is read when arming, so a zone change between two arms is honoured`() =
        runTest {
            val ring = ring()
            zone = ZoneId.of("Asia/Kuala_Lumpur")
            ring.schedule()
            clock.advanceMillis(1_000)
            zone = ZoneId.of("Europe/London")
            ring.schedule()

            val zones = store.observeRecentBlocking().map { it.zone }.toSet()
            assertEquals(setOf(ZoneId.of("Asia/Kuala_Lumpur"), ZoneId.of("Europe/London")), zones)
        }

    @Test
    fun `every debug occurrence id starts with dev- and can never be a real occurrence id`() =
        runTest {
            ring().schedule()

            val id = store.open().single().id
            assertTrue(id.startsWith(DevScheduledRing.ID_PREFIX))
            assertFalse(Regex("""\d{4}-\d{2}-\d{2}-(opening|closing)""").matches(id))
        }

    @Test
    fun `a new one replaces an earlier one that has not rung`() =
        runTest {
            val ring = ring()
            ring.schedule()
            val first = store.open().single().id
            clock.advanceMillis(5_000)

            ring.schedule()

            assertEquals(1, store.open().size, "only the new one is still scheduled")
            assertFalse(alarms.isArmed(first), "the earlier alarm is disarmed")
            val old = store.get(first)!!
            assertEquals(CheckInStatus.CANCELLED, old.status)
            assertEquals(OccurrenceReason.USER_CANCELLED, old.reason)
        }

    @Test
    fun `an earlier one that has already rung is left alone by a new one`() =
        runTest {
            val ring = ring()
            ring.schedule()
            val first = store.open().single().id
            store.claimForRinging(first, java.time.Instant.ofEpochMilli(clock.millis))

            clock.advanceMillis(5_000)
            ring.schedule()

            assertEquals(CheckInStatus.RINGING, store.get(first)!!.status)
        }

    @Test
    fun `it never touches an occurrence that is not its own`() =
        runTest {
            store.insert(occurrence("2026-09-20-afternoon", CheckInType.CLOSING))
            alarms.armed["2026-09-20-afternoon"] = 1L

            ring().schedule()
            ring().cancel()

            assertEquals(CheckInStatus.SCHEDULED, store.get("2026-09-20-afternoon")!!.status)
            assertTrue(alarms.isArmed("2026-09-20-afternoon"))
        }

    @Test
    fun `without the permission nothing is armed and the record says why`() =
        runTest {
            alarms.permitted = false

            val result = ring().schedule()

            assertEquals(ScheduleResult.NotPermitted, result)
            assertTrue(alarms.armed.isEmpty())
            val record = store.observeRecentBlocking().single()
            assertEquals(CheckInStatus.CANCELLED, record.status)
            assertEquals(OccurrenceReason.NOT_PERMITTED, record.reason)
        }

    @Test
    fun `cancelling disarms every scheduled debug occurrence, and needs no memory of arming`() =
        runTest {
            ring().schedule()
            val id = store.open().single().id

            // A different instance, as after the app has been swiped away and reopened.
            ring().cancel()

            assertFalse(alarms.isArmed(id))
            assertEquals(CheckInStatus.CANCELLED, store.get(id)!!.status)
            assertTrue(store.open().isEmpty())
        }

    @Test
    fun `cancelling with nothing armed changes nothing`() =
        runTest {
            ring().cancel()

            assertTrue(store.open().isEmpty())
        }

    @Test
    fun `the plan summary names both slots, whether they are on, and the zone`() {
        val plan =
            CheckInPlan
                .default(
                    ZoneId.of("Asia/Kuala_Lumpur"),
                ).withEnabled(CheckInType.CLOSING, false)
                .withTime(CheckInType.OPENING, LocalTime.of(9, 30))

        assertEquals("Opening 09:30 on, Closing 15:00 off, zone Asia/Kuala_Lumpur", plan.summaryLine())
    }

    private suspend fun InMemoryOccurrenceStore.observeRecentBlocking() = observeRecent(20).first()
}
