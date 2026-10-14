package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInPlan
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The one path that brings the stored occurrences and Android's alarms back into agreement.
 *
 * Nothing here can ring a call, because the reconciler has no way to: it holds a store and an alarm
 * scheduler, and never a call starter. The tests still check that nothing it does leaves an
 * occurrence ringing, and that everything overdue is marked missed.
 */
class ReconcilerTest {
    private val clock = TestClock(DUE.toEpochMilli() - 3_600_000)
    private val store = InMemoryOccurrenceStore()
    private val alarms = FakeAlarmScheduler(clock)
    private var active: String? = null
    private val reconciler = Reconciler(store, alarms, activeOccurrenceId = { active }, clock = clock)
    private val id = "2026-09-20-morning"

    private suspend fun scheduled(
        id: String = this.id,
        at: Instant = DUE,
        type: CheckInType = CheckInType.OPENING,
    ): ScheduledOccurrence = occurrence(id, type, at).also { store.insert(it) }

    private suspend fun reconcile(trigger: ReconcileTrigger = ReconcileTrigger.MANUAL) = reconciler.reconcile(trigger)

    private suspend fun status(id: String = this.id) = store.get(id)!!

    // A future alarm is armed, and restored when it is gone

    @Test
    fun `a future scheduled occurrence with no alarm is armed, and counted as restored`() =
        runTest {
            scheduled()

            val report = reconcile()

            assertTrue(alarms.isArmed(id))
            assertEquals(1, report.armed)
            assertEquals(1, report.restored)
            assertTrue(report.changes.isEmpty())
            assertEquals(CheckInStatus.SCHEDULED, status().status)
        }

    @Test
    fun `a future scheduled occurrence that already has its alarm is armed again without being doubled`() =
        runTest {
            scheduled()
            alarms.armed[id] = DUE.toEpochMilli()

            val report = reconcile()

            assertEquals(1, alarms.armed.size, "one alarm per occurrence, never two")
            assertEquals(1, report.armed)
            assertEquals(0, report.restored, "it was already there")
        }

    @Test
    fun `after a reboot every future alarm is restored`() =
        runTest {
            scheduled("a", DUE)
            scheduled("b", DUE.plusSeconds(18_000), CheckInType.CLOSING)
            reconcile()
            assertEquals(2, alarms.armed.size)

            alarms.loseEverything()
            val report = reconcile(ReconcileTrigger.BOOT)

            assertEquals(setOf("a", "b"), alarms.armed.keys)
            assertEquals(DUE.toEpochMilli(), alarms.armed["a"])
            assertEquals(DUE.plusSeconds(18_000).toEpochMilli(), alarms.armed["b"])
            assertEquals(2, report.restored)
            assertTrue(report.changes.isEmpty(), "nothing was overdue, so nothing was marked")
        }

    @Test
    fun `after a reboot, alarms still to come are restored and overdue ones are marked missed, silently`() =
        runTest {
            scheduled("passed", DUE)
            scheduled("future", DUE.plusSeconds(18_000), CheckInType.CLOSING)
            reconcile()
            alarms.loseEverything()
            clock.set(DUE.plusSeconds(3600)) // the phone was off, and came back an hour later

            val report = reconcile(ReconcileTrigger.BOOT)

            assertEquals(CheckInStatus.MISSED, status("passed").status)
            assertEquals(OccurrenceReason.ALARM_MISSING, status("passed").reason)
            assertTrue(alarms.isArmed("future"), "the one still to come is restored")
            assertFalse(alarms.isArmed("passed"), "the overdue one is not armed, so it cannot ring late")
            assertEquals(listOf("passed"), report.changes.map { it.occurrenceId })
        }

    @Test
    fun `without the permission a future occurrence is left scheduled and counted as unarmed`() =
        runTest {
            scheduled()
            alarms.permitted = false

            val report = reconcile()

            assertEquals(1, report.unarmed)
            assertEquals(0, report.armed)
            assertEquals(CheckInStatus.SCHEDULED, status().status, "it is not lost, only not armed yet")
            assertFalse(alarms.isArmed(id))
        }

    @Test
    fun `granting the permission arms what could not be armed before`() =
        runTest {
            scheduled()
            alarms.permitted = false
            reconcile()

            alarms.permitted = true
            val report = reconcile(ReconcileTrigger.EXACT_ALARM_PERMISSION_GRANTED)

            assertTrue(alarms.isArmed(id))
            assertEquals(1, report.restored)
            assertEquals(ReconcileTrigger.EXACT_ALARM_PERMISSION_GRANTED, report.trigger)
        }

    // Overdue, and the alarm gone

    @Test
    fun `an overdue occurrence whose alarm has gone is marked missed as alarm missing, and never rung`() =
        runTest {
            scheduled()
            clock.set(DUE.plusSeconds(30))

            val report = reconcile()

            val record = status()
            assertEquals(CheckInStatus.MISSED, record.status)
            assertEquals(OccurrenceReason.ALARM_MISSING, record.reason)
            assertNull(record.firedAt, "it never rang, and reconciling must not ring it")
            assertEquals(clock.millis, record.resolvedAt!!.toEpochMilli())
            assertEquals(
                listOf(ReconcileReport.Change(id, CheckInStatus.SCHEDULED, CheckInStatus.MISSED, OccurrenceReason.ALARM_MISSING)),
                report.changes,
            )
            assertTrue(alarms.armCalls.isEmpty(), "no alarm is armed for a moment that has passed")
        }

    @Test
    fun `an occurrence due exactly now with no alarm is missed, not armed`() =
        runTest {
            scheduled()
            clock.set(DUE)

            reconcile()

            assertEquals(OccurrenceReason.ALARM_MISSING, status().reason)
        }

    @Test
    fun `a force-stop and a reboot leave the same trace, and Thalia names neither`() =
        runTest {
            scheduled("after-force-stop", DUE)
            reconcile() // armed, as it would be
            alarms.loseEverything() // what a force-stop does
            clock.set(DUE.plusSeconds(60))
            reconcile(ReconcileTrigger.APP_LAUNCH)
            val afterForceStop = status("after-force-stop").reason

            scheduled("after-reboot", DUE.plusSeconds(90))
            reconcile() // armed, as it would be
            alarms.loseEverything() // what a reboot does
            clock.set(DUE.plusSeconds(200))
            reconcile(ReconcileTrigger.BOOT)
            val afterReboot = status("after-reboot").reason

            assertEquals(OccurrenceReason.ALARM_MISSING, afterForceStop)
            assertEquals(afterForceStop, afterReboot, "the record cannot tell them apart, so it must not claim to")
            assertTrue(OccurrenceReason.entries.none { it.name.contains("FORCE") || it.name.contains("REBOOT") })
        }

    @Test
    fun `an occurrence within the lateness limit with its alarm still registered is left for delivery`() =
        runTest {
            scheduled()
            alarms.armed[id] = DUE.toEpochMilli()
            clock.set(DUE.plusSeconds(30))

            val report = reconcile()

            assertEquals(CheckInStatus.SCHEDULED, status().status)
            assertTrue(report.changes.isEmpty())
            assertTrue(alarms.isArmed(id), "delivery is about to ring it, and reconciling must not get in the way")
        }

    @Test
    fun `exactly at the lateness limit with its alarm registered is still left alone`() =
        runTest {
            scheduled()
            alarms.armed[id] = DUE.toEpochMilli()
            clock.set(DUE.plusMillis(2.minutes.inWholeMilliseconds))

            reconcile()

            assertEquals(CheckInStatus.SCHEDULED, status().status)
        }

    @Test
    fun `one millisecond past the lateness limit with its alarm still registered is overdue, and the alarm is disarmed`() =
        runTest {
            scheduled()
            alarms.armed[id] = DUE.toEpochMilli()
            clock.set(DUE.plusMillis(2.minutes.inWholeMilliseconds + 1))

            reconcile()

            assertEquals(CheckInStatus.MISSED, status().status)
            assertEquals(OccurrenceReason.OVERDUE, status().reason)
            assertFalse(alarms.isArmed(id), "an alarm that could still fire would ring it late")
        }

    // A slot switched off

    @Test
    fun `a scheduled occurrence whose slot is switched off is cancelled and its alarm disarmed`() =
        runTest {
            scheduled("morning-one", DUE, CheckInType.OPENING)
            scheduled("afternoon-one", DUE.plusSeconds(18_000), CheckInType.CLOSING)
            reconcile()
            store.savePlan(CheckInPlan.default(KUALA_LUMPUR).withEnabled(CheckInType.OPENING, false))

            val report = reconcile()

            assertEquals(CheckInStatus.CANCELLED, status("morning-one").status)
            assertEquals(OccurrenceReason.PLAN_DISABLED, status("morning-one").reason)
            assertFalse(alarms.isArmed("morning-one"))
            assertEquals(CheckInStatus.SCHEDULED, status("afternoon-one").status, "the other slot is untouched")
            assertTrue(alarms.isArmed("afternoon-one"))
            assertEquals(1, report.changes.size)
        }

    // Ringing and answered occurrences

    private suspend fun ringing(
        answered: Boolean = false,
        firedAt: Instant = DUE,
    ) {
        store.insert(occurrence())
        store.claimForRinging(id, firedAt)
        if (answered) store.transition(id, CheckInStatus.RINGING, CheckInStatus.ANSWERED, firedAt.plusSeconds(3))
    }

    @Test
    fun `a ringing occurrence that nothing is carrying is missed as interrupted`() =
        runTest {
            ringing()
            clock.set(DUE.plusSeconds(120))

            reconcile()

            assertEquals(CheckInStatus.MISSED, status().status)
            assertEquals(OccurrenceReason.INTERRUPTED, status().reason)
        }

    @Test
    fun `an answered occurrence that nothing is carrying is failed as interrupted`() =
        runTest {
            ringing(answered = true)
            clock.set(DUE.plusSeconds(120))

            reconcile()

            assertEquals(CheckInStatus.FAILED, status().status)
            assertEquals(OccurrenceReason.INTERRUPTED, status().reason)
        }

    @Test
    fun `a call this process is carrying is never interrupted, however long it has run`() =
        runTest {
            ringing(answered = true)
            active = id
            clock.set(DUE.plusSeconds(3600))

            reconcile()

            assertEquals(CheckInStatus.ANSWERED, status().status)
        }

    @Test
    fun `something claimed a moment ago is left alone, because the call may not have started yet`() =
        runTest {
            ringing()
            clock.set(DUE.plusSeconds(10))

            reconcile()

            assertEquals(CheckInStatus.RINGING, status().status)
        }

    @Test
    fun `the grace ends after fifteen seconds`() =
        runTest {
            ringing()

            clock.set(DUE.plusMillis(15.seconds.inWholeMilliseconds))
            reconcile()
            assertEquals(CheckInStatus.RINGING, status().status, "exactly fifteen seconds is still inside the grace")

            clock.set(DUE.plusMillis(15.seconds.inWholeMilliseconds + 1))
            reconcile()
            assertEquals(CheckInStatus.MISSED, status().status)
        }

    // The shape of the whole pass

    @Test
    fun `an occurrence that has ended is never touched`() =
        runTest {
            scheduled()
            store.claimForRinging(id, DUE)
            store.transition(id, CheckInStatus.RINGING, CheckInStatus.DECLINED, DUE.plusSeconds(4))
            val before = status()
            clock.set(DUE.plusSeconds(86_400))

            val report = reconcile()

            assertEquals(before, status())
            assertTrue(report.changes.isEmpty())
        }

    @Test
    fun `reconciling twice gives the same result as reconciling once`() =
        runTest {
            scheduled("passed", DUE)
            scheduled("future", DUE.plusSeconds(18_000), CheckInType.CLOSING)
            clock.set(DUE.plusSeconds(3600))

            val first = reconcile()
            val storedAfterFirst = store.open() + listOf(status("passed"))
            val armedAfterFirst = alarms.armed.toMap()
            val second = reconcile()

            assertEquals(1, first.changes.size)
            assertTrue(second.changes.isEmpty(), "nothing is left to do the second time")
            assertEquals(storedAfterFirst, store.open() + listOf(status("passed")))
            assertEquals(armedAfterFirst, alarms.armed.toMap())
        }

    @Test
    fun `every trigger does exactly the same work`() =
        runTest {
            val results =
                ReconcileTrigger.entries.map { trigger ->
                    val s = InMemoryOccurrenceStore()
                    val c = TestClock(DUE.toEpochMilli() + 30_000)
                    val a = FakeAlarmScheduler(c)
                    s.insert(occurrence("passed", at = DUE))
                    s.insert(occurrence("future", CheckInType.CLOSING, DUE.plusSeconds(18_000)))
                    val report = Reconciler(s, a, { null }, c).reconcile(trigger)
                    Triple(report.changes, a.armed.toMap(), s.open().map { it.id to it.status })
                }

            assertEquals(1, results.distinct().size, "the trigger changes what is logged and nothing else")
        }

    @Test
    fun `reconciling never leaves anything ringing that was not already`() =
        runTest {
            scheduled("a", DUE)
            scheduled("b", DUE.plusSeconds(60))
            clock.set(DUE.plusSeconds(30))

            reconcile()

            assertTrue(store.open().none { it.status == CheckInStatus.RINGING }, "reconciling cannot ring a call")
            assertTrue(alarms.armCalls.none { it == "a" }, "and does not arm an alarm for a moment that has passed")
        }

    @Test
    fun `when the alarm receiver gets to an occurrence first, the reconciliation loses and records nothing`() =
        runTest {
            scheduled()
            clock.set(DUE.plusSeconds(30))
            // The receiver claims the occurrence in the instant between the reconciler reading it and moving it.
            val racing = RacingStore(store) { store.claimForRinging(id, DUE) }
            val reconciler = Reconciler(racing, alarms, { null }, clock)

            val report = reconciler.reconcile(ReconcileTrigger.APP_LAUNCH)

            assertEquals(CheckInStatus.RINGING, status().status, "the claim won, and the reconciliation must not undo it")
            assertTrue(report.changes.isEmpty(), "a move that was lost is not reported as made")
        }

    @Test
    fun `the plan is read once per pass and an occurrence of a disabled type is cancelled even when overdue`() =
        runTest {
            scheduled()
            store.savePlan(CheckInPlan.default(KUALA_LUMPUR).withEnabled(CheckInType.OPENING, false))
            clock.set(DUE.plusSeconds(60))

            reconcile()

            assertEquals(OccurrenceReason.PLAN_DISABLED, status().reason)
        }

    @Test
    fun `the report summary names the trigger, the counts and every change`() =
        runTest {
            scheduled()
            clock.set(DUE.plusSeconds(30))

            val summary = reconcile(ReconcileTrigger.BOOT).summary()

            assertTrue(summary.startsWith("BOOT: "))
            assertTrue(summary.contains("changes=1"))
            assertTrue(summary.contains("SCHEDULED->MISSED ALARM_MISSING"))
        }

    /** A store that lets a test act just before the reconciler's first move, to stage a race. */
    private class RacingStore(
        private val inner: OccurrenceStore,
        private val beforeFirstMove: suspend () -> Unit,
    ) : OccurrenceStore by inner {
        private var raced = false

        override suspend fun transition(
            id: String,
            from: CheckInStatus,
            to: CheckInStatus,
            at: Instant,
            reason: OccurrenceReason?,
        ): Boolean {
            if (!raced) {
                raced = true
                beforeFirstMove()
            }
            return inner.transition(id, from, to, at, reason)
        }
    }
}
