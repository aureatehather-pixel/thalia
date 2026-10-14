package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInPlan
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import com.thalia.voicecheckins.domain.nextRevisionAt
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Moving, cancelling and re-confirming a day: the rules in [CheckInConfirmer]'s documentation, one
 * test each, plus the races and the recoveries that make them worth having.
 *
 * The invariant every test ends on: a slot has at most one eligible occurrence, its history is
 * never shortened, and an alarm can only ring the slot's live occurrence.
 */
class CheckInRevisionTest {
    private val today = LocalDate.of(2026, 9, 20)
    private val clock = TestClock() // a minute before 10:00 on the 20th, Kuala Lumpur
    private val alarms = FakeAlarmScheduler(clock)
    private val store = InMemoryOccurrenceStore()
    private val scheduler = OccurrenceScheduler(store, alarms, clock)
    private val confirmer = CheckInConfirmer(store, scheduler, clock)
    private val slot = "2026-09-20-opening"
    private val later = LocalTime.of(11, 30)

    private suspend fun history() = store.slot(slot)

    private suspend fun statuses() = history().map { it.status }

    private suspend fun live() = history().filter { it.isEligible }

    private suspend fun assertAtMostOneLive() = assertTrue(live().size <= 1, "more than one live occurrence: ${live().map { it.id }}")

    private class RecordingStarter : CallStarter {
        val started = mutableListOf<String>()

        override fun startCall(
            checkInType: CheckInType,
            occurrenceId: String,
        ): Boolean {
            started += occurrenceId
            return true
        }
    }

    private fun payload(o: ScheduledOccurrence) = AlarmPayload(o.id, o.type, o.scheduledAt.toEpochMilli())

    // ---- Moving

    @Test
    fun `moving a scheduled check-in retires it as superseded and arms only the new revision`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)

            val moved = assertIs<ConfirmResult.Rescheduled>(confirmer.reschedule(CheckInType.OPENING, today, later)).occurrence

            assertEquals("2026-09-20-opening-r2", moved.id)
            assertEquals(2, moved.revision)
            assertEquals(listOf(CheckInStatus.CANCELLED, CheckInStatus.SCHEDULED), statuses())
            assertEquals(OccurrenceReason.SUPERSEDED, history()[0].reason)
            assertEquals(mapOf(moved.id to moved.scheduledAt.toEpochMilli()), alarms.armed, "the old alarm is disarmed")
            assertEquals(today.atTime(later), moved.localDateTime)
            assertAtMostOneLive()
        }

    @Test
    fun `moving to the time it is already at changes nothing`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            confirmer.reschedule(CheckInType.OPENING, today, later)

            val again = confirmer.reschedule(CheckInType.OPENING, today, later)

            assertIs<ConfirmResult.Unchanged>(again)
            assertEquals(2, history().size)
            assertEquals(listOf("2026-09-20-opening", "2026-09-20-opening-r2"), alarms.armCalls)
        }

    @Test
    fun `moving repeatedly keeps every revision and exactly one live alarm`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            confirmer.reschedule(CheckInType.OPENING, today, LocalTime.of(11, 0))
            confirmer.reschedule(CheckInType.OPENING, today, LocalTime.of(12, 0))
            confirmer.reschedule(CheckInType.OPENING, today, LocalTime.of(13, 0))

            assertEquals(listOf(1, 2, 3, 4), history().map { it.revision })
            assertEquals(
                listOf(CheckInStatus.CANCELLED, CheckInStatus.CANCELLED, CheckInStatus.CANCELLED, CheckInStatus.SCHEDULED),
                statuses(),
            )
            assertEquals(setOf("2026-09-20-opening-r4"), alarms.armed.keys)
            assertAtMostOneLive()
        }

    @Test
    fun `moving to a time that is not in the future changes nothing`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)

            val result = confirmer.reschedule(CheckInType.OPENING, today, LocalTime.of(9, 0))

            assertIs<ConfirmResult.TimePassed>(result)
            assertEquals(listOf(CheckInStatus.SCHEDULED), statuses())
            assertEquals(setOf(slot), alarms.armed.keys, "the original alarm is still armed")
        }

    @Test
    fun `moving a day nobody confirmed confirms it at that time`() =
        runTest {
            val result = confirmer.reschedule(CheckInType.OPENING, today, later)

            assertEquals(today.atTime(later), assertIs<ConfirmResult.Confirmed>(result).occurrence.localDateTime)
            assertEquals(listOf(1), history().map { it.revision })
        }

    @Test
    fun `a call that is ringing or in progress cannot be moved`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            store.claimForRinging(slot, Instant.ofEpochMilli(clock()))

            assertEquals(ConfirmResult.InProgress(CheckInStatus.RINGING), confirmer.reschedule(CheckInType.OPENING, today, later))

            store.transition(slot, CheckInStatus.RINGING, CheckInStatus.ANSWERED, Instant.ofEpochMilli(clock()))
            assertEquals(ConfirmResult.InProgress(CheckInStatus.ANSWERED), confirmer.reschedule(CheckInType.OPENING, today, later))
            assertEquals(1, history().size)
        }

    @Test
    fun `a call that already rang cannot be moved or confirmed again`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            store.claimForRinging(slot, Instant.ofEpochMilli(clock()))
            store.transition(slot, CheckInStatus.RINGING, CheckInStatus.DECLINED, Instant.ofEpochMilli(clock()))

            assertEquals(ConfirmResult.AlreadyConfirmed(CheckInStatus.DECLINED), confirmer.reschedule(CheckInType.OPENING, today, later))
            assertEquals(ConfirmResult.AlreadyConfirmed(CheckInStatus.DECLINED), confirmer.confirm(CheckInType.OPENING, today))
            assertEquals(1, history().size)
            assertEquals(1, alarms.armCalls.size)
        }

    // ---- Cancelling

    @Test
    fun `cancelling disarms the alarm and keeps the record`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)

            assertIs<CancelResult.Cancelled>(confirmer.cancel(CheckInType.OPENING, today))

            assertEquals(listOf(CheckInStatus.CANCELLED), statuses())
            assertEquals(OccurrenceReason.USER_CANCELLED, history()[0].reason)
            assertTrue(alarms.armed.isEmpty())
        }

    @Test
    fun `cancelling twice, or cancelling a day nobody confirmed, finds nothing to cancel`() =
        runTest {
            assertEquals(CancelResult.NothingToCancel, confirmer.cancel(CheckInType.OPENING, today))

            confirmer.confirm(CheckInType.OPENING, today)
            confirmer.cancel(CheckInType.OPENING, today)

            assertEquals(CancelResult.NothingToCancel, confirmer.cancel(CheckInType.OPENING, today))
            assertEquals(1, history().size)
        }

    @Test
    fun `a call that is ringing cannot be cancelled and one that rang has nothing to cancel`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            store.claimForRinging(slot, Instant.ofEpochMilli(clock()))

            assertEquals(CancelResult.InProgress(CheckInStatus.RINGING), confirmer.cancel(CheckInType.OPENING, today))
            assertEquals(listOf(CheckInStatus.RINGING), statuses())

            store.transition(slot, CheckInStatus.RINGING, CheckInStatus.DECLINED, Instant.ofEpochMilli(clock()))
            assertEquals(CancelResult.NothingToCancel, confirmer.cancel(CheckInType.OPENING, today))
        }

    // ---- Recovering a day whose occurrence ended

    @Test
    fun `a cancelled day can be confirmed again as a new revision and the history stays`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            confirmer.cancel(CheckInType.OPENING, today)

            val again = assertIs<ConfirmResult.Confirmed>(confirmer.confirm(CheckInType.OPENING, today)).occurrence

            assertEquals("2026-09-20-opening-r2", again.id)
            assertEquals(listOf(CheckInStatus.CANCELLED, CheckInStatus.SCHEDULED), statuses())
            assertEquals(setOf(again.id), alarms.armed.keys)
            assertEquals(ConfirmResult.AlreadyConfirmed(CheckInStatus.SCHEDULED), confirmer.confirm(CheckInType.OPENING, today))
            assertEquals(2, history().size)
        }

    @Test
    fun `cancel then confirm then cancel then confirm walks the revisions without ever having two live`() =
        runTest {
            repeat(3) {
                confirmer.confirm(CheckInType.OPENING, today)
                assertAtMostOneLive()
                confirmer.cancel(CheckInType.OPENING, today)
                assertAtMostOneLive()
            }
            confirmer.confirm(CheckInType.OPENING, today)

            assertEquals(listOf(1, 2, 3, 4), history().map { it.revision })
            assertEquals(1, live().size)
            assertEquals(1, alarms.armed.size)
        }

    @Test
    fun `a day that could not be armed for want of permission can be confirmed once permission returns`() =
        runTest {
            alarms.permitted = false
            assertEquals(ConfirmResult.NotPermitted, confirmer.confirm(CheckInType.OPENING, today))
            assertEquals(OccurrenceReason.NOT_PERMITTED, history()[0].reason)

            alarms.permitted = true
            val again = assertIs<ConfirmResult.Confirmed>(confirmer.confirm(CheckInType.OPENING, today)).occurrence

            assertEquals(2, again.revision)
            assertEquals(setOf(again.id), alarms.armed.keys)
        }

    @Test
    fun `a day that was missed without ringing can be given a later time`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            alarms.loseEverything()
            clock.set(DUE.plusSeconds(600))
            Reconciler(store, alarms, { null }, clock).reconcile(ReconcileTrigger.BOOT)
            assertEquals(OccurrenceReason.ALARM_MISSING, history()[0].reason)

            assertIs<ConfirmResult.TimePassed>(confirmer.confirm(CheckInType.OPENING, today), "the plan's time has gone")
            val result = confirmer.reschedule(CheckInType.OPENING, today, later)

            assertEquals(2, assertIs<ConfirmResult.Confirmed>(result).occurrence.revision)
            assertEquals(listOf(CheckInStatus.MISSED, CheckInStatus.SCHEDULED), statuses())
        }

    @Test
    fun `a slot that is switched off cannot be moved and a live one is cancelled by the reconcile`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            store.savePlan(CheckInPlan.default(KUALA_LUMPUR).withEnabled(CheckInType.OPENING, enabled = false))

            assertEquals(ConfirmResult.SlotOff, confirmer.reschedule(CheckInType.OPENING, today, later))

            Reconciler(store, alarms, { null }, clock).reconcile(ReconcileTrigger.MANUAL)
            assertEquals(OccurrenceReason.PLAN_DISABLED, history()[0].reason)

            store.savePlan(CheckInPlan.default(KUALA_LUMPUR))
            assertEquals(2, assertIs<ConfirmResult.Confirmed>(confirmer.confirm(CheckInType.OPENING, today)).occurrence.revision)
        }

    // ---- Permission changes

    @Test
    fun `moving without permission changes nothing`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            alarms.permitted = false

            assertEquals(ConfirmResult.NotPermitted, confirmer.reschedule(CheckInType.OPENING, today, later))

            assertEquals(listOf(CheckInStatus.SCHEDULED), statuses(), "the existing call is not lost by trying to move it")
            assertEquals(setOf(slot), alarms.armed.keys)
        }

    @Test
    fun `permission lost between the check and the arming leaves the slot honestly empty and recoverable`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            alarms.revokeAfterNextCheck = true

            assertEquals(ConfirmResult.NotPermitted, confirmer.reschedule(CheckInType.OPENING, today, later))

            assertEquals(listOf(CheckInStatus.CANCELLED, CheckInStatus.CANCELLED), statuses())
            assertEquals(listOf(OccurrenceReason.SUPERSEDED, OccurrenceReason.NOT_PERMITTED), history().map { it.reason })
            assertTrue(alarms.armed.isEmpty(), "nothing is left armed for a retired revision")

            alarms.permitted = true
            assertEquals(3, assertIs<ConfirmResult.Confirmed>(confirmer.confirm(CheckInType.OPENING, today)).occurrence.revision)
            assertAtMostOneLive()
        }

    // ---- Alarm and move racing

    @Test
    fun `an alarm that wins the claim before the move lands leaves the call ringing and records no new revision`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            val racing =
                object : OccurrenceStore by store {
                    override suspend fun supersede(
                        oldId: String,
                        replacement: ScheduledOccurrence,
                        at: Instant,
                    ): Boolean {
                        store.claimForRinging(oldId, at) // the alarm fires between the read and the move
                        return store.supersede(oldId, replacement, at)
                    }
                }
            val racingConfirmer = CheckInConfirmer(racing, OccurrenceScheduler(racing, alarms, clock), clock)

            val result = racingConfirmer.reschedule(CheckInType.OPENING, today, later)

            assertEquals(ConfirmResult.InProgress(CheckInStatus.RINGING), result)
            assertEquals(listOf(CheckInStatus.RINGING), statuses())
            assertEquals(1, alarms.armCalls.size, "no replacement was armed")
        }

    @Test
    fun `a stale alarm for a moved revision claims nothing and rings nothing, and the new one rings once`() =
        runTest {
            val first = assertIs<ConfirmResult.Confirmed>(confirmer.confirm(CheckInType.OPENING, today)).occurrence
            val second = assertIs<ConfirmResult.Rescheduled>(confirmer.reschedule(CheckInType.OPENING, today, later)).occurrence
            val starter = RecordingStarter()

            // The old alarm is delivered anyway, as it would be if Android had not yet dropped it.
            val stale = CheckInFireHandler({ first.scheduledAt.toEpochMilli() }, store, starter).onAlarm(payload(first))
            assertEquals(FireOutcome.NotClaimed(first.id, CheckInStatus.CANCELLED), stale)
            assertTrue(starter.started.isEmpty())

            val current = CheckInFireHandler({ second.scheduledAt.toEpochMilli() }, store, starter)
            assertIs<FireOutcome.Ringing>(current.onAlarm(payload(second)))
            assertIs<FireOutcome.NotClaimed>(current.onAlarm(payload(second)), "delivered twice, rings once")
            assertEquals(listOf(second.id), starter.started)
        }

    @Test
    fun `a stale alarm for a cancelled revision rings nothing even when it arrives late enough to be marked missed`() =
        runTest {
            val first = assertIs<ConfirmResult.Confirmed>(confirmer.confirm(CheckInType.OPENING, today)).occurrence
            confirmer.cancel(CheckInType.OPENING, today)
            val starter = RecordingStarter()

            val onTime = CheckInFireHandler({ first.scheduledAt.toEpochMilli() }, store, starter).onAlarm(payload(first))
            val tooLate = CheckInFireHandler({ first.scheduledAt.toEpochMilli() + 600_000 }, store, starter).onAlarm(payload(first))

            assertIs<FireOutcome.NotClaimed>(onTime)
            assertEquals(FireOutcome.TooLate(first.id, 600_000, recorded = false), tooLate)
            assertTrue(starter.started.isEmpty())
            assertEquals(OccurrenceReason.USER_CANCELLED, store.get(first.id)?.reason, "the record is not rewritten as missed")
        }

    @Test
    fun `a second mover holding a stale view cannot make a second live revision`() =
        runTest {
            val first = assertIs<ConfirmResult.Confirmed>(confirmer.confirm(CheckInType.OPENING, today)).occurrence
            scheduler.reschedule(first, first.nextRevisionAt(LocalTime.of(11, 0)))

            val stale = scheduler.reschedule(first, first.nextRevisionAt(LocalTime.of(12, 0)))

            assertEquals(RescheduleResult.NotScheduled, stale)
            assertEquals(2, history().size)
            assertEquals(listOf(LocalTime.of(11, 0)), live().map { it.localDateTime.toLocalTime() })
            assertEquals(setOf("2026-09-20-opening-r2"), alarms.armed.keys)
        }

    // ---- Reconciliation

    @Test
    fun `a crash between recording the new revision and arming it is repaired by the next reconcile`() =
        runTest {
            val first = assertIs<ConfirmResult.Confirmed>(confirmer.confirm(CheckInType.OPENING, today)).occurrence
            // The move got as far as the store and no further.
            val second = first.nextRevisionAt(later)
            assertTrue(store.supersede(first.id, second, Instant.ofEpochMilli(clock())))
            assertEquals(setOf(first.id), alarms.armed.keys, "only the stale alarm exists")

            val report = Reconciler(store, alarms, { null }, clock).reconcile(ReconcileTrigger.APP_LAUNCH)

            assertTrue(second.id in alarms.armed.keys)
            assertEquals(1, report.restored)
            assertAtMostOneLive()
        }

    @Test
    fun `after a reboot only the live revision is restored and retired ones stay retired`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            confirmer.reschedule(CheckInType.OPENING, today, LocalTime.of(11, 0))
            confirmer.cancel(CheckInType.OPENING, today)
            confirmer.confirm(CheckInType.OPENING, today)
            alarms.loseEverything()

            val report = Reconciler(store, alarms, { null }, clock).reconcile(ReconcileTrigger.BOOT)

            assertEquals(setOf("2026-09-20-opening-r3"), alarms.armed.keys)
            assertTrue(report.changes.isEmpty(), "history is not touched")
            assertEquals(3, history().size)
        }

    @Test
    fun `reconciling with nothing to do leaves the history exactly as it was`() =
        runTest {
            confirmer.confirm(CheckInType.OPENING, today)
            confirmer.reschedule(CheckInType.OPENING, today, later)
            val before = history()

            repeat(3) { Reconciler(store, alarms, { null }, clock).reconcile(ReconcileTrigger.MANUAL) }

            assertEquals(before, history())
            assertEquals(1, alarms.armed.size)
        }
}
