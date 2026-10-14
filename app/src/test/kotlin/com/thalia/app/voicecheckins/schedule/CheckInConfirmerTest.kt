package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInPlan
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.domain.QuietPeriod
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckInConfirmerTest {
    private val today = LocalDate.of(2026, 9, 20)
    private val tomorrow = today.plusDays(1)
    private val clock = TestClock() // a minute before 10:00 on the 20th, Kuala Lumpur
    private val alarms = FakeAlarmScheduler(clock)
    private val store = InMemoryOccurrenceStore()
    private val confirmer = CheckInConfirmer(store, OccurrenceScheduler(store, alarms, clock), clock)

    @Test
    fun `confirming a day records one scheduled occurrence and arms one alarm at the plan's time`() =
        runTest {
            val result = confirmer.confirm(CheckInType.OPENING, today)

            val confirmed = assertIs<ConfirmResult.Confirmed>(result).occurrence
            assertEquals("2026-09-20-opening", confirmed.id)
            assertEquals(DUE, confirmed.scheduledAt)
            assertEquals(CheckInStatus.SCHEDULED, store.get(confirmed.id)?.status)
            assertEquals(mapOf(confirmed.id to DUE.toEpochMilli()), alarms.armed)
        }

    @Test
    fun `opening and closing on the same day are two separate occurrences`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            confirmer.confirm(CheckInType.CLOSING, today)

            assertEquals(setOf("2026-09-20-opening", "2026-09-20-closing"), alarms.armed.keys)
            assertEquals(DUE.plusSeconds(5 * 3600).toEpochMilli(), alarms.armed["2026-09-20-closing"])
        }

    @Test
    fun `confirming the same day twice changes nothing and never arms a second alarm`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            val again = confirmer.confirm(CheckInType.OPENING, today)

            assertEquals(ConfirmResult.AlreadyConfirmed(CheckInStatus.SCHEDULED), again)
            assertEquals(listOf("2026-09-20-opening"), alarms.armCalls)
            assertEquals(1, store.open().size)
        }

    @Test
    fun `confirming at a plan time inside the quiet period records nothing and arms nothing`() =
        runTest {
            store.saveQuietPeriod(QuietPeriod(LocalTime.of(9, 0), LocalTime.of(11, 0), enabled = true))

            val result = confirmer.confirm(CheckInType.OPENING, today)

            assertEquals(DUE, assertIs<ConfirmResult.InQuietPeriod>(result).occurrence.scheduledAt)
            assertTrue(store.slot("2026-09-20-opening").isEmpty())
            assertTrue(alarms.armed.isEmpty())
        }

    @Test
    fun `moving a confirmed check-in into the quiet period is refused and it stays where it was`() =
        runTest {
            confirmer.confirm(CheckInType.CLOSING, today)
            store.saveQuietPeriod(QuietPeriod(LocalTime.of(22, 0), LocalTime.of(7, 0), enabled = true))

            val result = confirmer.reschedule(CheckInType.CLOSING, today, LocalTime.of(23, 30))

            assertIs<ConfirmResult.InQuietPeriod>(result)
            val slot = store.slot("2026-09-20-closing")
            assertEquals(listOf(CheckInStatus.SCHEDULED), slot.map { it.status }, "no new revision, the old one untouched")
            assertEquals(LocalTime.of(15, 0), slot.single().localDateTime.toLocalTime())
            assertEquals(listOf("2026-09-20-closing"), alarms.armed.keys.toList())
        }

    @Test
    fun `a quiet period spanning midnight refuses early morning and allows the day`() =
        runTest {
            store.saveQuietPeriod(QuietPeriod(LocalTime.of(22, 0), LocalTime.of(7, 0), enabled = true))

            assertIs<ConfirmResult.InQuietPeriod>(confirmer.reschedule(CheckInType.OPENING, tomorrow, LocalTime.of(6, 30)))
            assertIs<ConfirmResult.Confirmed>(confirmer.reschedule(CheckInType.OPENING, tomorrow, LocalTime.of(7, 0)))
        }

    @Test
    fun `a day suppressed by the quiet period can be confirmed again as a new revision`() =
        runTest {
            confirmer.confirm(CheckInType.CLOSING, today)
            store.transition("2026-09-20-closing", CheckInStatus.SCHEDULED, CheckInStatus.SUPPRESSED, DUE, OccurrenceReason.QUIET_PERIOD)

            val result = confirmer.reschedule(CheckInType.CLOSING, today, LocalTime.of(16, 0))

            assertEquals("2026-09-20-closing-r2", assertIs<ConfirmResult.Confirmed>(result).occurrence.id)
        }

    @Test
    fun `a day whose call is ringing cannot be confirmed again`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            store.claimForRinging("2026-09-20-opening", DUE)

            assertEquals(ConfirmResult.InProgress(CheckInStatus.RINGING), confirmer.confirm(CheckInType.OPENING, today))
            assertEquals(1, alarms.armCalls.size)
        }

    @Test
    fun `an unconfirmed day has no occurrence and no alarm, whatever the plan says`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            val plan = store.plan() // reading the plan is not confirming anything

            assertTrue(plan.opening.enabled && plan.closing.enabled)
            assertNull(store.get("2026-09-21-opening"))
            assertNull(store.get("2026-09-21-closing"))
            assertNull(store.get("2026-09-20-closing"))
            assertEquals(setOf("2026-09-20-opening"), alarms.armed.keys)
        }

    @Test
    fun `a reconcile creates no occurrence for an unconfirmed day`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            alarms.loseEverything()

            Reconciler(store, alarms, { null }, clock).reconcile(ReconcileTrigger.BOOT)

            assertEquals(setOf("2026-09-20-opening"), alarms.armed.keys, "the confirmed one is restored, nothing else appears")
            assertEquals(listOf("2026-09-20-opening"), store.open().map { it.id })
        }

    @Test
    fun `a time that has passed records nothing and leaves the day free`() =
        runTest {
            clock.set(DUE.plusSeconds(60))

            val result = confirmer.confirm(CheckInType.OPENING, today)

            assertIs<ConfirmResult.TimePassed>(result)
            assertNull(store.get("2026-09-20-opening"))
            assertTrue(alarms.armCalls.isEmpty())

            // The next day is unaffected.
            assertIs<ConfirmResult.Confirmed>(confirmer.confirm(CheckInType.OPENING, tomorrow))
        }

    @Test
    fun `the exact due moment counts as passed, so nothing rings late`() =
        runTest {
            clock.set(DUE)

            assertIs<ConfirmResult.TimePassed>(confirmer.confirm(CheckInType.OPENING, today))
            assertNull(store.get("2026-09-20-opening"))
        }

    @Test
    fun `a slot that is switched off cannot be confirmed`() =
        runTest {
            store.savePlan(CheckInPlan.default(KUALA_LUMPUR).withEnabled(CheckInType.CLOSING, enabled = false))

            assertEquals(ConfirmResult.SlotOff, confirmer.confirm(CheckInType.CLOSING, today))
            assertNull(store.get("2026-09-20-closing"))
            assertTrue(alarms.armCalls.isEmpty())
        }

    @Test
    fun `without exact alarms the day is recorded as cancelled for that reason and nothing is armed`() =
        runTest {
            alarms.permitted = false

            assertEquals(ConfirmResult.NotPermitted, confirmer.confirm(CheckInType.OPENING, today))

            val stored = store.get("2026-09-20-opening")
            assertEquals(CheckInStatus.CANCELLED, stored?.status)
            assertEquals(OccurrenceReason.NOT_PERMITTED, stored?.reason)
            assertTrue(alarms.armed.isEmpty())
        }

    @Test
    fun `the occurrence is stamped in the plan's zone at the plan's wall-clock time`() =
        runTest {
            val result = assertIs<ConfirmResult.Confirmed>(confirmer.confirm(CheckInType.CLOSING, tomorrow))

            assertEquals(KUALA_LUMPUR, result.occurrence.zone)
            assertEquals(LocalDate.of(2026, 9, 21).atTime(15, 0), result.occurrence.localDateTime)
        }
}
