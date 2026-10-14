package com.thalia.app.planning

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.CallAuthorization
import com.thalia.app.planning.data.PlanCall
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.plainPlanningDatabase
import com.thalia.app.voicecheckins.schedule.AlarmScheduler
import com.thalia.app.voicecheckins.schedule.CheckInConfirmer
import com.thalia.app.voicecheckins.schedule.FakeAlarmScheduler
import com.thalia.app.voicecheckins.schedule.InMemoryOccurrenceStore
import com.thalia.app.voicecheckins.schedule.KUALA_LUMPUR
import com.thalia.app.voicecheckins.schedule.OccurrenceScheduler
import com.thalia.app.voicecheckins.schedule.OccurrenceStore
import com.thalia.app.voicecheckins.schedule.ReconcileTrigger
import com.thalia.app.voicecheckins.schedule.Reconciler
import com.thalia.app.voicecheckins.schedule.ScheduleResult
import com.thalia.app.voicecheckins.schedule.TestClock
import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.voicecheckins.domain.CheckInSchedule
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.domain.QuietPeriod
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import com.thalia.voicecheckins.domain.occurrenceAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.thalia.app.voicecheckins.schedule.occurrence as other

/**
 * ADR 0009 slice 3, the opening-call link, against M2's real scheduler, confirmer and in-memory store, a fake alarm
 * scheduler, and a real planning store. Every behavior is asserted through those interfaces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OpeningCallLinkTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val opened = mutableListOf<PlanningDatabase>()
    private var stamp = 1L

    private val day = ActiveDay(LocalDate.of(2026, 9, 20))
    private val nextDay = day.plusDays(1)
    private val ten = LocalTime.of(10, 0)
    private val slot = "2026-09-20-opening"

    private val clock = TestClock(Instant.parse("2026-09-20T00:00:00Z").toEpochMilli()) // 08:00 in Kuala Lumpur
    private val inner = InMemoryOccurrenceStore()
    private val alarms = FakeAlarmScheduler(clock)
    private val crash = CrashAfterArm(alarms)
    private val m2 = CountingStore(inner)
    private val scheduler = OccurrenceScheduler(m2, crash, clock)
    private val confirmer = CheckInConfirmer(m2, scheduler, clock)

    /** Another path into M2: the user, the debug screen, or the reconcile, acting on the same store. */
    private val otherScheduler = OccurrenceScheduler(inner, alarms, clock)
    private val otherConfirmer = CheckInConfirmer(inner, otherScheduler, clock)

    private var zone: CheckInZone = CheckInZone.Known(KUALA_LUMPUR)
    private val link = OpeningCallLink({ zone }, m2, scheduler, confirmer, clock)

    /** Records which writes the link made to M2, and runs a hook just before an insert. */
    private class CountingStore(
        private val real: OccurrenceStore,
    ) : OccurrenceStore by real {
        var inserts = 0
        var supersedes = 0
        var transitions = 0
        var planReads = 0
        var beforeInsert: suspend () -> Unit = {}
        var beforeSupersede: suspend () -> Unit = {}

        override suspend fun insert(occurrence: ScheduledOccurrence): Boolean {
            val hook = beforeInsert
            beforeInsert = {}
            hook()
            inserts++
            return real.insert(occurrence)
        }

        override suspend fun supersede(
            oldId: String,
            replacement: ScheduledOccurrence,
            at: Instant,
        ): Boolean {
            val hook = beforeSupersede
            beforeSupersede = {}
            hook()
            return real.supersede(oldId, replacement, at).also { supersedes++ }
        }

        override suspend fun transition(
            id: String,
            from: CheckInStatus,
            to: CheckInStatus,
            at: Instant,
            reason: OccurrenceReason?,
        ): Boolean = real.transition(id, from, to, at, reason).also { transitions++ }

        override suspend fun plan() = real.plan().also { planReads++ }
    }

    /** An alarm scheduler that dies just after arming, as the process can between M2's arming and the status write. */
    private class CrashAfterArm(
        private val real: AlarmScheduler,
    ) : AlarmScheduler by real {
        var crash = false

        override fun schedule(occurrence: ScheduledOccurrence): ScheduleResult {
            val result = real.schedule(occurrence)
            check(!crash) { "the process died after arming" }
            return result
        }
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    // --- the planning side ---

    private suspend fun planningStore(): PlanningStore {
        val name = "opening-call-test-${names.size}.db"
        names += name
        val db = plainPlanningDatabase(context, context.getDatabasePath(name)).also { opened += it }
        return PlanningStore(db) { stamp++ }.also {
            it.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
            it.saveAction(Action("a", "Write"))
        }
    }

    private suspend fun PlanningStore.approve(
        on: ActiveDay = day,
        time: LocalTime? = ten,
    ): DayPlanWithItems {
        val proposal = assertNotNull(propose(on, KUALA_LUMPUR, null, setOf("a"), emptyMap()))
        return assertIs<ApprovalResult.Approved>(approve(proposal, KUALA_LUMPUR, openingTime = time)).plan
    }

    /** An approved plan whose call the user has authorized, so it is REQUESTED and nothing has run yet. */
    private suspend fun PlanningStore.requested(
        on: ActiveDay = day,
        time: LocalTime? = ten,
    ): DayPlanWithItems = approve(on, time).also { assertEquals(CallAuthorization.AUTHORIZED, authorizeOpeningCall(it.plan.id, on)) }

    private suspend fun PlanningStore.state(plan: DayPlanWithItems) =
        calls().single { it.planId == plan.plan.id }.let {
            it.status to
                it.detail
        }

    private fun at(
        time: LocalTime,
        revision: Int = 1,
    ) = CheckInSchedule(CheckInType.OPENING, time, KUALA_LUMPUR).occurrenceAt(day.date, time, revision)

    private suspend fun slotRows() = inner.slot(slot)

    private suspend fun allRows() = inner.observeRecent(100).first()

    // --- create-if-absent: every row of the classification table ---

    @Test
    fun `an empty slot gets revision one at the plan's time, armed once, and is CONFIRMED`() =
        runTest {
            val planning = planningStore()
            val plan = planning.approve()
            val attempt = link.confirm(planning, plan.plan.id, ticked = true)

            assertEquals(CallAttempt.Done(CallVerdict(CallStatus.CONFIRMED)), attempt)
            assertEquals("CONFIRMED" to null, planning.state(plan))
            val rows = slotRows()
            assertEquals(listOf(slot), rows.map { it.id })
            assertEquals(CheckInStatus.SCHEDULED, rows.single().status)
            assertEquals(DUE_AT, rows.single().scheduledAt)
            assertEquals(listOf(slot), alarms.armCalls)
            assertEquals(0, m2.supersedes)
            assertEquals(0, m2.transitions)
            assertEquals(0, m2.planReads, "the link never reads M2's plan, which would create a default one")
        }

    @Test
    fun `a call already scheduled at the plan's time is CONFIRMED and nothing is written to M2`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            assertEquals(ScheduleResult.Scheduled(DUE_AT.toEpochMilli()), otherScheduler.schedule(at(ten)))
            val before = allRows()

            link.retry(planning)

            assertEquals("CONFIRMED" to null, planning.state(plan))
            assertEquals(before, allRows())
            assertEquals(0, m2.inserts)
            assertEquals(listOf(slot), alarms.armCalls) // only the other path's own arming
        }

    @Test
    fun `scheduled at another time, cancelled, missed unrung, ringing and ended each classify and change nothing`() =
        runTest {
            fun case(
                detail: CallDetail,
                setup: suspend InMemoryOccurrenceStore.() -> Unit,
            ) = setup to detail
            val cases =
                listOf(
                    case(CallDetail.OTHER_TIME_CONFIRMED) { insert(at(LocalTime.of(11, 0))) },
                    case(CallDetail.CALL_CANCELLED) {
                        insert(at(ten))
                        transition(slot, CheckInStatus.SCHEDULED, CheckInStatus.CANCELLED, DUE_AT, OccurrenceReason.USER_CANCELLED)
                    },
                    case(CallDetail.CALL_CANCELLED) {
                        insert(at(ten))
                        transition(slot, CheckInStatus.SCHEDULED, CheckInStatus.MISSED, DUE_AT, OccurrenceReason.ALARM_MISSING)
                    },
                    case(CallDetail.CALL_IN_PROGRESS) {
                        insert(at(ten))
                        claimForRinging(slot, DUE_AT)
                    },
                    case(CallDetail.CALL_ENDED) {
                        insert(at(ten))
                        claimForRinging(slot, DUE_AT)
                        transition(slot, CheckInStatus.RINGING, CheckInStatus.ANSWERED, DUE_AT)
                        transition(slot, CheckInStatus.ANSWERED, CheckInStatus.COMPLETED, DUE_AT)
                    },
                )
            cases.forEach { (setup, expected) ->
                val world = World(planningStore())
                world.inner.setup()
                val plan = world.planning.requested()
                val before = world.inner.observeRecent(100).first()

                world.link.retry(world.planning)

                assertEquals("NEEDS_ATTENTION" to expected.name, world.planning.state(plan), expected.name)
                assertEquals(before, world.inner.observeRecent(100).first(), "M2 changed for $expected")
                assertEquals(0, world.m2.inserts)
                assertEquals(0, world.m2.supersedes)
            }
        }

    @Test
    fun `an arming refused for permission uses up revision one and is NEEDS_ATTENTION, never retried`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            alarms.permitted = false

            link.retry(planning)

            assertEquals("NEEDS_ATTENTION" to "PERMISSION_REFUSED", planning.state(plan))
            val rev1 = slotRows().single()
            assertEquals(CheckInStatus.CANCELLED, rev1.status)
            assertEquals(OccurrenceReason.NOT_PERMITTED, rev1.reason)

            alarms.permitted = true
            assertEquals(RetryReport(0, 0), link.retry(planning)) // not REQUESTED any more
            assertEquals(1, slotRows().size)
        }

    // --- refusals: nothing is recorded in M2 ---

    @Test
    fun `each precondition refuses with its reason, in order, writing nothing to M2`() =
        runTest {
            fun call(
                time: LocalTime = ten,
                zone: ZoneId = KUALA_LUMPUR,
            ) = OpeningCall(day, zone, LocalTime.of(4, 0), time)

            zone = CheckInZone.Unusable(CheckInZoneProblem.NO_CHECK_IN_PLAN)
            assertEquals(CallDetail.M2_UNAVAILABLE, link.refusal(call()))
            zone = CheckInZone.Known(ZoneId.of("UTC"))
            assertEquals(CallDetail.ZONE_CHANGED, link.refusal(call()))
            zone = CheckInZone.Known(KUALA_LUMPUR, openingEnabled = false)
            assertEquals(CallDetail.SLOT_OFF, link.refusal(call()))
            assertEquals(
                CallDetail.TIME_NOT_ALLOWED,
                link.refusal(call(time = LocalTime.of(3, 0))),
                "before the rollover; checked before the slot",
            )

            zone = CheckInZone.Known(KUALA_LUMPUR)
            inner.saveQuietPeriod(QuietPeriod(LocalTime.of(9, 30), LocalTime.of(10, 30), enabled = true))
            assertEquals(CallDetail.IN_QUIET_PERIOD, link.refusal(call()))
            inner.saveQuietPeriod(QuietPeriod.DEFAULT)

            clock.set(DUE_AT)
            assertEquals(CallDetail.TIME_PASSED, link.refusal(call()))
            clock.set(DUE_AT.minusSeconds(3600))
            assertNull(link.refusal(call()))

            assertEquals(emptyList(), allRows())
            assertEquals(emptyList(), alarms.armCalls)
            assertEquals(0, m2.inserts)
        }

    @Test
    fun `a time the clocks skip is not allowed`() =
        runTest {
            val newYork = ZoneId.of("America/New_York")
            zone = CheckInZone.Known(newYork)
            val springForward = OpeningCall(ActiveDay(LocalDate.of(2027, 3, 14)), newYork, LocalTime.of(4, 0), LocalTime.of(2, 30))
            assertEquals(CallDetail.TIME_NOT_ALLOWED, link.refusal(springForward))
        }

    @Test
    fun `a refusal is recorded as REFUSED with its reason and the slot stays empty, and Try again re-runs only create-if-absent`() =
        runTest {
            val planning = planningStore()
            val plan = planning.approve()
            inner.saveQuietPeriod(QuietPeriod(LocalTime.of(9, 30), LocalTime.of(10, 30), enabled = true))

            assertEquals(
                CallAttempt.Done(CallVerdict(CallStatus.REFUSED, CallDetail.IN_QUIET_PERIOD)),
                link.confirm(planning, plan.plan.id, true),
            )
            assertEquals("REFUSED" to "IN_QUIET_PERIOD", planning.state(plan))
            assertEquals(emptyList(), slotRows())

            assertEquals(CallAttempt.NotTicked, link.tryAgain(planning, plan.plan.id, ticked = false))
            assertEquals(RetryReport(0, 0), link.retry(planning), "REFUSED is never retried automatically")

            inner.saveQuietPeriod(QuietPeriod.DEFAULT) // the user fixed the cause
            assertEquals(CallAttempt.Done(CallVerdict(CallStatus.CONFIRMED)), link.tryAgain(planning, plan.plan.id, ticked = true))
            assertEquals("CONFIRMED" to null, planning.state(plan))
            assertEquals(1, slotRows().size)
            assertEquals(0, m2.supersedes)
        }

    // --- authority comes from stored state and the clock now, never from a screen ---

    @Test
    fun `without the tick nothing is authorized or called`() =
        runTest {
            val planning = planningStore()
            val plan = planning.approve()
            assertEquals(CallAttempt.NotTicked, link.confirm(planning, plan.plan.id, ticked = false))
            assertEquals("NOT_LINKED" to null, planning.state(plan))
            assertEquals(emptyList(), allRows())
        }

    @Test
    fun `a past day, an earlier revision or a plan without a time gets no authority`() =
        runTest {
            val planning = planningStore()
            val older = planning.approve()
            val latest = planning.approve() // revision 2 of the same day
            val noTime = planning.approve(nextDay, time = null)
            assertEquals(CallAttempt.NotEligible, link.confirm(planning, older.plan.id, true))
            assertEquals(CallAttempt.NotEligible, link.confirm(planning, noTime.plan.id, true))
            assertEquals("NOT_LINKED" to null, planning.state(older))

            clock.set(Instant.parse("2026-09-21T01:00:00Z")) // the next morning: the day has passed
            assertEquals(CallAttempt.NotEligible, link.confirm(planning, latest.plan.id, true))
            assertEquals(emptyList(), allRows())
            assertEquals("NOT_LINKED" to null, planning.state(latest))
        }

    @Test
    fun `a stale REQUESTED call acts only if it is still the latest revision of a day that has not passed`() =
        runTest {
            val planning = planningStore()
            val older = planning.requested()
            planning.approve() // a newer revision of the same day
            assertEquals(RetryReport(0, 0), link.retry(planning), "an earlier revision supplies no authority")
            assertEquals("REQUESTED" to null, planning.state(older))
        }

    // --- expired REQUESTED rows ---

    @Test
    fun `a REQUESTED call whose day has passed is recorded REFUSED TIME_PASSED, schedules nothing, and shows it`() =
        runTest {
            val planning = planningStore()
            val pastDay = planning.requested()
            val tomorrow = planning.requested(nextDay)
            clock.set(Instant.parse("2026-09-21T01:00:00Z")) // the morning of the 21st: day 20 has passed, 21 has not

            val report = link.retry(planning)

            assertEquals(1, report.expired)
            assertEquals("REFUSED" to "TIME_PASSED", planning.state(pastDay))
            assertTrue("the time has passed" in callStatusText(planning.calls().single { it.planId == pastDay.plan.id }))
            assertEquals("CONFIRMED" to null, planning.state(tomorrow))
            assertEquals(listOf("2026-09-21-opening"), allRows().map { it.slot }, "only the live day's call was created")
            assertEquals(listOf("2026-09-21-opening"), alarms.armCalls)
            assertEquals(RetryReport(0, 0), link.retry(planning), "an expired call is terminal and not retried")
        }

    @Test
    fun `an expired call whose revision was replaced is also resolved, and an unexpired replaced one is left alone`() =
        runTest {
            val planning = planningStore()
            val replacedToday = planning.requested()
            planning.approve() // replaces it; today has not passed
            val replacedYesterday = planning.requested(nextDay.plusDays(-2))
            planning.approve(nextDay.plusDays(-2))
            clock.set(Instant.parse("2026-09-20T00:00:00Z"))

            val report = link.retry(planning)

            assertEquals(1, report.expired)
            assertEquals("REQUESTED" to null, planning.state(replacedToday))
            assertEquals("REFUSED" to "TIME_PASSED", planning.state(replacedYesterday))
            assertEquals(emptyList(), allRows())
        }

    @Test
    fun `before the retry runs, an expired REQUESTED call is described as expired, not as waiting`() {
        val waiting = PlanCall("p", ten, "REQUESTED", null, 0)
        assertTrue("created when Thalia next runs" in callStatusText(waiting))
        assertTrue("its day passed" in callStatusText(waiting, dayPassed = true))
        assertTrue("No call was scheduled" in callStatusText(waiting, dayPassed = true))
    }

    // --- planning-side interleaving: no M2 effect after its authorizing state is invalidated ---

    /** A planning write started from another thread, as the screen or an import would, while the link is mid-flight. */
    private val writer = CoroutineScope(Dispatchers.Default)

    @Test
    fun `a new revision approved while REQUESTED is being processed waits, so the call never follows its replacement`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            var approval: Deferred<DayPlanWithItems>? = null
            var callExistedWhenApprovalLanded: Boolean? = null
            m2.beforeInsert = {
                approval =
                    writer.async {
                        planning.approve().also { callExistedWhenApprovalLanded = inner.slot(slot).isNotEmpty() }
                    }
                withContext(Dispatchers.IO) { delay(200) } // time for the write to try, if nothing stops it
            }

            link.retry(planning)
            val revisionTwo = approval!!.await()

            assertEquals(2, revisionTwo.plan.revision)
            assertEquals(true, callExistedWhenApprovalLanded, "the approval landed only after the call it could have invalidated finished")
            assertEquals("CONFIRMED" to null, planning.state(plan))
            // The next launch finds nothing to do: the replaced revision is no longer eligible and rev 2 was never authorized.
            assertEquals(RetryReport(0, 0), link.retry(planning))
            assertEquals(1, slotRows().size)
        }

    @Test
    fun `a revision approved before processing begins gives the old authorization no effect`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            planning.approve()

            link.retry(planning)

            assertEquals("REQUESTED" to null, planning.state(plan))
            assertEquals(emptyList(), allRows())
            assertEquals(emptyList(), alarms.armCalls)
        }

    @Test
    fun `a planning write started after recovery's last read waits for a move to finish`() =
        runTest {
            val (planning, plan) = needingAttention { otherScheduler.schedule(at(LocalTime.of(11, 0))) }
            var approval: Deferred<DayPlanWithItems>? = null
            var moveExisted: Boolean? = null
            m2.beforeSupersede = {
                approval = writer.async { planning.approve().also { moveExisted = slotRows().size == 2 } }
                withContext(Dispatchers.IO) { delay(200) }
            }

            assertEquals(CallAttempt.Done(CallVerdict(CallStatus.CONFIRMED)), link.recover(planning, plan.plan.id, Recovery.MOVE, true))
            assertEquals(2, approval!!.await().plan.revision)

            assertEquals(true, moveExisted, "the write landed only after the move it could have invalidated")
            assertEquals("CONFIRMED" to null, planning.state(plan))
        }

    @Test
    fun `a planning write started after recovery's last read waits for a new revision to finish`() =
        runTest {
            val (planning, plan) =
                needingAttention {
                    otherScheduler.schedule(at(ten))
                    otherConfirmer.cancel(CheckInType.OPENING, day.date)
                }
            var approval: Deferred<DayPlanWithItems>? = null
            var revisionExisted: Boolean? = null
            m2.beforeInsert = {
                approval = writer.async { planning.approve().also { revisionExisted = slotRows().size == 2 } }
                withContext(Dispatchers.IO) { delay(200) }
            }

            assertEquals(CallAttempt.Done(CallVerdict(CallStatus.CONFIRMED)), link.recover(planning, plan.plan.id, Recovery.NEW_CALL, true))
            approval!!.await()

            assertEquals(true, revisionExisted)
            assertEquals(listOf(1, 2), slotRows().map { it.revision })
        }

    // --- the launch retry ---

    @Test
    fun `retry acts on REQUESTED only, once, and ignores NOT_LINKED including imported rows`() =
        runTest {
            val planning = planningStore()
            val eligible = planning.requested()
            val unconfirmed = planning.approve(nextDay) // a time, never authorized: NOT_LINKED
            val importedName = "opening-call-imported.db".also { names += it }
            val imported =
                PlanningStore(plainPlanningDatabase(context, context.getDatabasePath(importedName)).also { opened += it }) { stamp++ }
            assertTrue(imported.restoreIntoEmpty(planning.snapshot()))
            assertEquals(setOf("NOT_LINKED"), imported.calls().map { it.status }.toSet())

            assertEquals(RetryReport(0, 0), link.retry(imported), "imported rows never trigger a call")
            assertEquals(emptyList(), allRows())

            assertEquals(RetryReport(1, 0), link.retry(planning))
            assertEquals("CONFIRMED" to null, planning.state(eligible))
            assertEquals("NOT_LINKED" to null, planning.state(unconfirmed))
            assertEquals(1, allRows().size)

            assertEquals(RetryReport(0, 0), link.retry(planning), "CONFIRMED is not retried")
            assertEquals(listOf(slot), alarms.armCalls)
        }

    @Test
    fun `retry whose time has passed records REFUSED, so nothing stays REQUESTED, and never reschedules`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            clock.set(DUE_AT.plusSeconds(60))

            assertEquals(RetryReport(1, 0), link.retry(planning))

            assertEquals("REFUSED" to "TIME_PASSED", planning.state(plan))
            assertEquals(emptyList(), allRows())
            assertEquals(0, m2.supersedes)
        }

    // --- concurrency and races between the read and the insert ---

    @Test
    fun `a second retry running inside the first one's read-to-insert window creates one occurrence, armed once`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            var nested: RetryReport? = null
            m2.beforeInsert = { nested = link.retry(planning) } // the same work, completing in the window

            val outer = link.retry(planning)

            assertEquals(RetryReport(1, 0), nested)
            assertEquals(RetryReport(1, 0), outer)
            assertEquals(1, slotRows().size)
            assertEquals(listOf(slot), alarms.armCalls)
            assertEquals("CONFIRMED" to null, planning.state(plan))
            assertEquals(0, m2.supersedes)
        }

    @Test
    fun `two retries launched together create one occurrence`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            listOf(async { link.retry(planning) }, async { link.retry(planning) }).forEach { it.await() }
            assertEquals(1, slotRows().size)
            assertEquals(listOf(slot), alarms.armCalls)
            assertEquals("CONFIRMED" to null, planning.state(plan))
        }

    @Test
    fun `another path recording revision one at the plan's time between the read and the insert gives one occurrence and CONFIRMED`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            m2.beforeInsert = { otherScheduler.schedule(at(ten)) }

            link.retry(planning)

            assertEquals("CONFIRMED" to null, planning.state(plan))
            assertEquals(1, slotRows().size)
            assertEquals(listOf(slot), alarms.armCalls, "only the other path armed")
        }

    @Test
    fun `another path at another time wins the race, and the call is not moved`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            m2.beforeInsert = { otherScheduler.schedule(at(LocalTime.of(11, 0))) }

            link.retry(planning)

            assertEquals("NEEDS_ATTENTION" to "OTHER_TIME_CONFIRMED", planning.state(plan))
            assertEquals(LocalTime.of(11, 0), slotRows().single().localDateTime.toLocalTime())
            assertEquals(0, m2.supersedes)
        }

    @Test
    fun `a user cancel landing first is not revived`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            m2.beforeInsert = {
                otherConfirmer.confirm(CheckInType.OPENING, day.date)
                otherConfirmer.cancel(CheckInType.OPENING, day.date)
            }

            link.retry(planning)

            assertEquals("NEEDS_ATTENTION" to "CALL_CANCELLED", planning.state(plan))
            assertEquals(listOf(CheckInStatus.CANCELLED), slotRows().map { it.status })
            assertEquals(0, m2.supersedes)
        }

    @Test
    fun `a move landing first is not moved back`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            m2.beforeInsert = {
                otherConfirmer.confirm(CheckInType.OPENING, day.date) // 10:00 from M2's own plan
                otherConfirmer.reschedule(CheckInType.OPENING, day.date, LocalTime.of(12, 0))
            }

            link.retry(planning)

            assertEquals("NEEDS_ATTENTION" to "OTHER_TIME_CONFIRMED", planning.state(plan))
            val rows = slotRows()
            assertEquals(listOf(CheckInStatus.CANCELLED, CheckInStatus.SCHEDULED), rows.map { it.status })
            assertEquals(LocalTime.of(12, 0), rows.last().localDateTime.toLocalTime())
        }

    @Test
    fun `the time passing between the checks and the insert is decided by arming, and recorded as M2 reports`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            m2.beforeInsert = { clock.set(DUE_AT.plusSeconds(1)) }

            link.retry(planning)

            assertEquals("NEEDS_ATTENTION" to "TIME_PASSED", planning.state(plan))
            val rev1 = slotRows().single()
            assertEquals(CheckInStatus.CANCELLED, rev1.status)
            assertEquals(OccurrenceReason.NOT_IN_THE_FUTURE, rev1.reason)
        }

    @Test
    fun `a quiet period set or a slot switched off in that window is not decided by the link, which records what M2 reports`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            m2.beforeInsert = {
                inner.saveQuietPeriod(QuietPeriod(LocalTime.of(9, 30), LocalTime.of(10, 30), enabled = true))
                zone = CheckInZone.Known(KUALA_LUMPUR, openingEnabled = false)
            }

            link.retry(planning)

            assertEquals("CONFIRMED" to null, planning.state(plan)) // M2's fire-time checks keep that call quiet
            assertEquals(CheckInStatus.SCHEDULED, slotRows().single().status)
        }

    // --- the four cross-database crash windows ---

    @Test
    fun `window 1, after the plan commits and before M2 records, the retry creates the one call`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            assertEquals(emptyList(), allRows())

            link.retry(planning)

            assertEquals("CONFIRMED" to null, planning.state(plan))
            assertEquals(1, slotRows().size)
            assertEquals(listOf(slot), alarms.armCalls)
            assertEquals(0, m2.supersedes)
        }

    @Test
    fun `window 2, after M2 records and before the alarm is armed, the retry does not arm, and M2's reconcile does`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            alarms.beforeArm = { error("the process died before arming") }

            assertEquals(RetryReport(0, 1), link.retry(planning)) // the launch that died
            assertEquals("REQUESTED" to null, planning.state(plan))
            assertEquals(1, slotRows().size) // recorded, without an alarm
            assertEquals(emptyList(), alarms.armCalls)

            alarms.beforeArm = {}
            link.retry(planning) // the next launch, before any reconcile
            assertEquals("CONFIRMED" to null, planning.state(plan))
            assertEquals(1, slotRows().size)
            assertEquals(emptyList(), alarms.armCalls, "the link never arms a recorded call")

            Reconciler(inner, alarms, { null }, clock).reconcile(ReconcileTrigger.APP_LAUNCH)
            assertEquals(listOf(slot), alarms.armCalls)
            assertEquals(mapOf(slot to DUE_AT.toEpochMilli()), alarms.armed)
        }

    @Test
    fun `window 3, after M2 arms and before the status is written, the retry finds it and writes nothing to M2`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested()
            crash.crash = true

            assertEquals(RetryReport(0, 1), link.retry(planning))
            assertEquals("REQUESTED" to null, planning.state(plan))
            assertEquals(listOf(slot), alarms.armCalls)

            crash.crash = false
            val insertsBefore = m2.inserts
            link.retry(planning)

            assertEquals("CONFIRMED" to null, planning.state(plan))
            assertEquals(insertsBefore, m2.inserts)
            assertEquals(1, slotRows().size)
            assertEquals(listOf(slot), alarms.armCalls)
            assertEquals(0, m2.supersedes)
            assertEquals(0, m2.transitions)
        }

    @Test
    fun `window 4, before the plan commits, there is nothing to retry`() =
        runTest {
            val planning = planningStore()
            assertEquals(RetryReport(0, 0), link.retry(planning))
            assertEquals(emptyList(), allRows())
        }

    // --- the crash model of the explicit paths, with the real planning transaction ---

    @Test
    fun `explicit confirm that dies after the M2 write leaves REQUESTED committed, and the retry links it without arming again`() =
        runTest {
            val planning = planningStore()
            val plan = planning.approve() // NOT_LINKED
            crash.crash = true // the process dies right after M2 records and arms the call

            assertFailsWith<IllegalStateException> { link.confirm(planning, plan.plan.id, ticked = true) }

            // What survived: M2's committed write, and an authorization that was committed before it.
            assertEquals(1, slotRows().size)
            assertEquals(CheckInStatus.SCHEDULED, slotRows().single().status)
            assertEquals(listOf(slot), alarms.armCalls)
            assertEquals("REQUESTED" to null, planning.state(plan), "the authorization is durable, not rolled back with the failed write")

            // The next launch.
            crash.crash = false
            val inserts = m2.inserts
            assertEquals(RetryReport(1, 0), link.retry(planning))

            assertEquals("CONFIRMED" to null, planning.state(plan))
            assertEquals(inserts, m2.inserts, "no second occurrence is recorded")
            assertEquals(listOf(slot), alarms.armCalls, "no second arming")
            assertEquals(0, m2.supersedes)
            assertEquals(0, m2.transitions)
        }

    @Test
    fun `explicit confirm that dies before the alarm is armed is found by the retry, and M2's reconcile arms it`() =
        runTest {
            val planning = planningStore()
            val plan = planning.approve()
            alarms.beforeArm = { error("the process died before arming") }

            assertFailsWith<IllegalStateException> { link.confirm(planning, plan.plan.id, ticked = true) }
            assertEquals("REQUESTED" to null, planning.state(plan))
            assertEquals(1, slotRows().size)
            assertEquals(emptyList(), alarms.armCalls)

            alarms.beforeArm = {}
            assertEquals(RetryReport(1, 0), link.retry(planning))
            assertEquals("CONFIRMED" to null, planning.state(plan))
            assertEquals(emptyList(), alarms.armCalls)
            Reconciler(inner, alarms, { null }, clock).reconcile(ReconcileTrigger.APP_LAUNCH)
            assertEquals(listOf(slot), alarms.armCalls)
        }

    @Test
    fun `without the tick a crash leaves nothing authorized, so the retry has nothing to link`() =
        runTest {
            val planning = planningStore()
            val plan = planning.approve()
            crash.crash = true
            assertEquals(CallAttempt.NotTicked, link.confirm(planning, plan.plan.id, ticked = false))
            assertEquals("NOT_LINKED" to null, planning.state(plan))
            assertEquals(RetryReport(0, 0), link.retry(planning))
            assertEquals(emptyList(), allRows())
        }

    @Test
    fun `an explicit recovery that dies after the M2 write leaves a stale log, which the retry reconciles without any M2 write`() =
        runTest {
            val (planning, plan) = needingAttention { otherScheduler.schedule(at(LocalTime.of(11, 0))) }
            crash.crash = true // the move is recorded and armed, then the process dies

            assertFailsWith<IllegalStateException> { link.recover(planning, plan.plan.id, Recovery.MOVE, ticked = true) }

            val rows = slotRows()
            assertEquals(listOf(CheckInStatus.CANCELLED, CheckInStatus.SCHEDULED), rows.map { it.status })
            assertEquals(DUE_AT, rows.last().scheduledAt)
            assertEquals(mapOf("$slot-r2" to DUE_AT.toEpochMilli()), alarms.armed, "only the new revision is armed")
            assertEquals(
                "NEEDS_ATTENTION" to "OTHER_TIME_CONFIRMED",
                planning.state(plan),
                "the log was not updated, and nothing authorizes more",
            )

            crash.crash = false
            val armed = alarms.armCalls.toList()
            val rowsBefore = allRows()
            val counts = listOf(m2.inserts, m2.supersedes, m2.transitions)
            val live = link.preview(OpeningCall(day, KUALA_LUMPUR, LocalTime.of(4, 0), ten))
            assertEquals(CallPreview(null, SlotState.ScheduledAtTime), live)
            assertNull(recoveryOf(assertNotNull(live.slot)), "nothing is left to offer")

            assertEquals(RetryReport(0, 0, 0, 1), link.retry(planning), "NEEDS_ATTENTION is never processed, only reconciled")

            assertEquals("CONFIRMED" to null, planning.state(plan))
            assertEquals(rowsBefore, allRows())
            assertEquals(counts, listOf(m2.inserts, m2.supersedes, m2.transitions))
            assertEquals(armed, alarms.armCalls)
        }

    @Test
    fun `a Try again that dies after the M2 write leaves the log REFUSED, and the retry then reconciles it without any M2 write`() =
        runTest {
            val planning = planningStore()
            val plan = planning.approve()
            inner.saveQuietPeriod(QuietPeriod(LocalTime.of(9, 30), LocalTime.of(10, 30), enabled = true))
            link.confirm(planning, plan.plan.id, true)
            assertEquals("REFUSED" to "IN_QUIET_PERIOD", planning.state(plan))
            inner.saveQuietPeriod(QuietPeriod.DEFAULT)
            crash.crash = true

            assertFailsWith<IllegalStateException> { link.tryAgain(planning, plan.plan.id, ticked = true) }

            assertEquals(1, slotRows().size)
            assertEquals(listOf(slot), alarms.armCalls)
            assertEquals("REFUSED" to "IN_QUIET_PERIOD", planning.state(plan))
            crash.crash = false
            val rowsBefore = allRows()
            val counts = listOf(m2.inserts, m2.supersedes, m2.transitions)
            assertEquals(
                CallPreview(null, SlotState.ScheduledAtTime),
                link.preview(OpeningCall(day, KUALA_LUMPUR, LocalTime.of(4, 0), ten)),
            )

            assertEquals(RetryReport(0, 0, 0, 1), link.retry(planning), "the stale REFUSED log is reconciled, nothing else")

            assertEquals("CONFIRMED" to null, planning.state(plan))
            assertEquals(rowsBefore, allRows())
            assertEquals(counts, listOf(m2.inserts, m2.supersedes, m2.transitions))
            assertEquals(listOf(slot), alarms.armCalls)
        }

    // --- read-only status reconciliation ---

    @Test
    fun `reconciliation only ever changes a displayed status to CONFIRMED, and writes nothing to M2`() =
        runTest {
            val planning = planningStore()
            val plan = planning.approve()
            inner.saveQuietPeriod(QuietPeriod(LocalTime.of(9, 30), LocalTime.of(10, 30), enabled = true))
            link.confirm(planning, plan.plan.id, true) // REFUSED, IN_QUIET_PERIOD
            inner.saveQuietPeriod(QuietPeriod.DEFAULT)
            otherScheduler.schedule(at(ten)) // another path put the call at the plan's time
            val rows = allRows()
            val armed = alarms.armCalls.toList()

            assertEquals(RetryReport(0, 0, 0, 1), link.retry(planning))

            assertEquals("CONFIRMED" to null, planning.state(plan))
            assertEquals(rows, allRows())
            assertEquals(armed, alarms.armCalls)
            assertEquals(listOf(0, 0, 0), listOf(m2.inserts, m2.supersedes, m2.transitions))
            assertEquals(RetryReport(0, 0), link.retry(planning), "CONFIRMED is left alone")
        }

    @Test
    fun `a call at another time, cancelled, ringing or ended keeps its accurate attention status, and M2 is untouched`() =
        runTest {
            fun case(
                detail: CallDetail,
                setup: suspend InMemoryOccurrenceStore.() -> Unit,
            ) = setup to detail
            val cases =
                listOf(
                    case(CallDetail.OTHER_TIME_CONFIRMED) { insert(at(LocalTime.of(11, 0))) },
                    case(CallDetail.CALL_CANCELLED) {
                        insert(at(ten))
                        transition(slot, CheckInStatus.SCHEDULED, CheckInStatus.CANCELLED, DUE_AT, OccurrenceReason.USER_CANCELLED)
                    },
                    case(CallDetail.CALL_IN_PROGRESS) {
                        insert(at(ten))
                        claimForRinging(slot, DUE_AT)
                    },
                    case(CallDetail.CALL_ENDED) {
                        insert(at(ten))
                        claimForRinging(slot, DUE_AT)
                        transition(slot, CheckInStatus.RINGING, CheckInStatus.ANSWERED, DUE_AT)
                        transition(slot, CheckInStatus.ANSWERED, CheckInStatus.COMPLETED, DUE_AT)
                    },
                )
            cases.forEach { (setup, expected) ->
                val world = World(planningStore())
                world.inner.setup()
                val plan = world.planning.requested()
                world.link.retry(world.planning)
                val before = world.inner.observeRecent(100).first()

                assertEquals(RetryReport(0, 0), world.link.retry(world.planning), expected.name)
                assertEquals(false, world.link.reconcile(world.planning, plan.plan.id))

                assertEquals("NEEDS_ATTENTION" to expected.name, world.planning.state(plan))
                assertEquals(before, world.inner.observeRecent(100).first())
            }
        }

    @Test
    fun `reconciliation leaves NOT_LINKED rows untouched, imported or approved, even with a call at the plan's time`() =
        runTest {
            val planning = planningStore()
            val approved = planning.approve() // never authorized
            otherScheduler.schedule(at(ten))
            val importedName = "reconcile-imported.db".also { names += it }
            val imported =
                PlanningStore(plainPlanningDatabase(context, context.getDatabasePath(importedName)).also { opened += it }) { stamp++ }
            assertTrue(imported.restoreIntoEmpty(planning.snapshot()))
            val rows = allRows()

            assertEquals(RetryReport(0, 0), link.retry(planning))
            assertEquals(RetryReport(0, 0), link.retry(imported))
            assertEquals(false, link.reconcile(planning, approved.plan.id))
            assertEquals(false, link.reconcile(imported, approved.plan.id))

            assertEquals("NOT_LINKED" to null, planning.state(approved))
            assertEquals(setOf("NOT_LINKED"), imported.calls().map { it.status }.toSet())
            assertEquals(rows, allRows())
        }

    @Test
    fun `a replaced plan or a past day is not reconciled, and a failing precondition keeps the status`() =
        runTest {
            val (planning, plan) = needingAttention { otherScheduler.schedule(at(LocalTime.of(11, 0))) }
            otherConfirmer.reschedule(CheckInType.OPENING, day.date, ten) // the call is now at the plan's time

            inner.saveQuietPeriod(QuietPeriod(LocalTime.of(9, 30), LocalTime.of(10, 30), enabled = true))
            assertEquals(false, link.reconcile(planning, plan.plan.id))
            assertEquals("NEEDS_ATTENTION" to "OTHER_TIME_CONFIRMED", planning.state(plan))
            inner.saveQuietPeriod(QuietPeriod.DEFAULT)

            planning.approve() // a replaced plan supplies no authority to relabel
            assertEquals(false, link.reconcile(planning, plan.plan.id))
            assertEquals("NEEDS_ATTENTION" to "OTHER_TIME_CONFIRMED", planning.state(plan))

            clock.set(Instant.parse("2026-09-21T01:00:00Z")) // a day that has passed
            assertEquals(false, link.reconcile(planning, plan.plan.id))
            assertEquals("NEEDS_ATTENTION" to "OTHER_TIME_CONFIRMED", planning.state(plan))
        }

    @Test
    fun `a zone that no longer matches keeps the status`() =
        runTest {
            val (planning, plan) = needingAttention { otherScheduler.schedule(at(LocalTime.of(11, 0))) }
            otherConfirmer.reschedule(CheckInType.OPENING, day.date, ten)
            zone = CheckInZone.Known(ZoneId.of("UTC"))
            assertEquals(false, link.reconcile(planning, plan.plan.id))
            assertEquals("NEEDS_ATTENTION" to "OTHER_TIME_CONFIRMED", planning.state(plan))
        }

    // --- explicit recovery through CheckInConfirmer ---

    private suspend fun needingAttention(setup: suspend () -> Unit): Pair<PlanningStore, DayPlanWithItems> {
        val planning = planningStore()
        setup()
        val plan = planning.requested()
        link.retry(planning)
        assertEquals(CallStatus.NEEDS_ATTENTION.name, planning.state(plan).first)
        return planning to plan
    }

    @Test
    fun `without the tick nothing is called, and with it a call at another time is moved once`() =
        runTest {
            val (planning, plan) = needingAttention { otherScheduler.schedule(at(LocalTime.of(11, 0))) }
            val before = allRows()

            assertEquals(CallAttempt.NotTicked, link.recover(planning, plan.plan.id, Recovery.MOVE, ticked = false))
            assertEquals(before, allRows())
            assertEquals(0, m2.supersedes)

            assertEquals(
                CallAttempt.Done(CallVerdict(CallStatus.CONFIRMED)),
                link.recover(planning, plan.plan.id, Recovery.MOVE, ticked = true),
            )
            assertEquals("CONFIRMED" to null, planning.state(plan))
            val rows = slotRows()
            assertEquals(listOf(CheckInStatus.CANCELLED, CheckInStatus.SCHEDULED), rows.map { it.status })
            assertEquals(OccurrenceReason.SUPERSEDED, rows.first().reason)
            assertEquals(DUE_AT, rows.last().scheduledAt)
            assertEquals(2, rows.last().revision)
            assertEquals(1, m2.supersedes)
        }

    @Test
    fun `a cancelled day gets a new revision only through the explicit action`() =
        runTest {
            val (planning, plan) =
                needingAttention {
                    otherScheduler.schedule(at(ten))
                    otherConfirmer.cancel(CheckInType.OPENING, day.date)
                }
            assertEquals(1, slotRows().size)

            assertEquals(CallAttempt.Done(CallVerdict(CallStatus.CONFIRMED)), link.recover(planning, plan.plan.id, Recovery.NEW_CALL, true))

            val rows = slotRows()
            assertEquals(listOf(1, 2), rows.map { it.revision })
            assertEquals(CheckInStatus.SCHEDULED, rows.last().status)
            assertEquals(listOf(slot, "$slot-r2"), alarms.armCalls)
            assertEquals("CONFIRMED" to null, planning.state(plan))
        }

    @Test
    fun `a used-up revision one moves to revision two only by explicit recovery, and a refusal there is NEEDS_ATTENTION again`() =
        runTest {
            val (planning, plan) = needingAttention { alarms.permitted = false } // revision 1 cancelled NOT_PERMITTED
            assertEquals("NEEDS_ATTENTION" to "PERMISSION_REFUSED", planning.state(plan))

            // Still without permission: revision 2 is recorded and cancelled for the same reason.
            assertEquals(
                CallAttempt.Done(CallVerdict(CallStatus.NEEDS_ATTENTION, CallDetail.PERMISSION_REFUSED)),
                link.recover(planning, plan.plan.id, Recovery.NEW_CALL, true),
            )
            assertEquals(listOf(1, 2), slotRows().map { it.revision })

            alarms.permitted = true
            assertEquals(CallAttempt.Done(CallVerdict(CallStatus.CONFIRMED)), link.recover(planning, plan.plan.id, Recovery.NEW_CALL, true))
            assertEquals(listOf(1, 2, 3), slotRows().map { it.revision })
            assertEquals(CheckInStatus.SCHEDULED, slotRows().last().status)
        }

    @Test
    fun `an old preview supplies no authority, so if the slot changed nothing is called`() =
        runTest {
            val (planning, plan) = needingAttention { otherScheduler.schedule(at(LocalTime.of(11, 0))) } // the user saw MOVE
            otherConfirmer.cancel(CheckInType.OPENING, day.date) // then the other call was cancelled
            val before = allRows()

            assertEquals(CallAttempt.Stale(Recovery.NEW_CALL), link.recover(planning, plan.plan.id, Recovery.MOVE, ticked = true))

            assertEquals(before, allRows())
            assertEquals("NEEDS_ATTENTION" to "OTHER_TIME_CONFIRMED", planning.state(plan))
        }

    @Test
    fun `recovery reruns the preconditions and a failing one records REFUSED without writing to M2`() =
        runTest {
            val (planning, plan) = needingAttention { otherScheduler.schedule(at(LocalTime.of(11, 0))) }
            inner.saveQuietPeriod(QuietPeriod(LocalTime.of(9, 30), LocalTime.of(10, 30), enabled = true))
            val before = allRows()

            assertEquals(
                CallAttempt.Done(CallVerdict(CallStatus.REFUSED, CallDetail.IN_QUIET_PERIOD)),
                link.recover(planning, plan.plan.id, Recovery.MOVE, ticked = true),
            )

            assertEquals(before, allRows())
            assertEquals(0, m2.supersedes)
            assertEquals("REFUSED" to "IN_QUIET_PERIOD", planning.state(plan))
        }

    @Test
    fun `recovery of a call that rang offers nothing and records what M2 holds`() =
        runTest {
            val (planning, plan) = needingAttention { otherScheduler.schedule(at(LocalTime.of(11, 0))) }
            inner.supersede(slot, at(ten, 2), DUE_AT)
            inner.claimForRinging("$slot-r2", DUE_AT)

            assertEquals(
                CallAttempt.Done(CallVerdict(CallStatus.NEEDS_ATTENTION, CallDetail.CALL_IN_PROGRESS)),
                link.recover(planning, plan.plan.id, Recovery.MOVE, ticked = true),
            )
            assertEquals(listOf(CheckInStatus.CANCELLED, CheckInStatus.RINGING), slotRows().map { it.status })
        }

    @Test
    fun `recovery needs a NEEDS_ATTENTION call that is still the latest revision of a day that has not passed`() =
        runTest {
            val planning = planningStore()
            val plan = planning.requested() // REQUESTED, not NEEDS_ATTENTION
            assertEquals(CallAttempt.NotEligible, link.recover(planning, plan.plan.id, Recovery.MOVE, true))
            assertEquals(CallAttempt.NotEligible, link.tryAgain(planning, plan.plan.id, true)) // not REFUSED either
            assertEquals("REQUESTED" to null, planning.state(plan))
            assertEquals(emptyList(), allRows())
        }

    // --- unrelated M2 rows ---

    @Test
    fun `unrelated M2 rows stay identical, and the only added row is the opening slot's revision one`() =
        runTest {
            val other = listOf(occurrence("2026-09-19-opening"), occurrence("2026-09-20-closing"), occurrence("2026-09-21-opening"))
            other.forEach { inner.insert(it) }
            val before = allRows()
            val planning = planningStore()
            val plan = planning.approve()

            link.confirm(planning, plan.plan.id, true)

            val after = allRows()
            assertEquals(before.toSet(), after.filter { it.slot != slot }.toSet())
            assertEquals(listOf(slot), after.filter { it.slot == slot }.map { it.id })
            assertEquals(before.size + 1, after.size)
            assertEquals(listOf(slot), alarms.armCalls)
        }

    private fun occurrence(id: String) = other(id, CheckInType.OPENING, Instant.parse("2026-09-25T02:00:00Z"))

    /** A fresh set of M2 doubles and planning storage, for tests that need several independent worlds. */
    private inner class World(
        val planning: PlanningStore,
    ) {
        val inner = InMemoryOccurrenceStore()
        private val alarms = FakeAlarmScheduler(clock)
        val m2 = CountingStore(inner)
        private val scheduler = OccurrenceScheduler(m2, alarms, clock)
        val link = OpeningCallLink({ CheckInZone.Known(KUALA_LUMPUR) }, m2, scheduler, CheckInConfirmer(m2, scheduler, clock), clock)
    }

    private companion object {
        val DUE_AT: Instant = Instant.parse("2026-09-20T02:00:00Z")
    }
}
