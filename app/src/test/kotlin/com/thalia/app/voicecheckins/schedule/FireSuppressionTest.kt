package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.domain.QuietPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two ways Thalia withholds a call when its alarm arrives: a quiet period, and another call
 * already on the phone. In both the occurrence is kept and recorded as suppressed with its own reason,
 * and the [CallStarter], the only door to the service, the notification, the sound and the screen, is
 * never touched.
 */
class FireSuppressionTest {
    private val id = "2026-09-20-opening"
    private val payload = AlarmPayload(id, CheckInType.OPENING, DUE.toEpochMilli())
    private val store = InMemoryOccurrenceStore()

    /** Every call started, from any thread. */
    private val started: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val starter = CallStarter { _, occurrenceId -> started.add(occurrenceId) }

    private fun handler(
        deliveredAt: Instant = DUE,
        callHere: () -> String? = { null },
    ) = CheckInFireHandler(clock = { deliveredAt.toEpochMilli() }, store = store, callStarter = starter, callHere = callHere)

    private fun quiet(
        start: String,
        end: String,
        enabled: Boolean = true,
    ) = QuietPeriod(LocalTime.parse(start), LocalTime.parse(end), enabled)

    private suspend fun assertSuppressed(
        occurrenceId: String,
        reason: OccurrenceReason,
        at: Instant,
    ) {
        val record = store.get(occurrenceId)!!
        assertEquals(CheckInStatus.SUPPRESSED, record.status, occurrenceId)
        assertEquals(reason, record.reason, occurrenceId)
        assertNull(record.firedAt, "$occurrenceId never rang")
        assertEquals(at, record.resolvedAt, occurrenceId)
    }

    // Quiet period

    @Test
    fun `an alarm whose moment is inside the quiet period is suppressed and starts nothing`() =
        runTest {
            store.insert(occurrence(id))
            store.saveQuietPeriod(quiet("09:30", "10:30"))

            val outcome = handler().onAlarm(payload)

            assertEquals(FireOutcome.Suppressed(id, OccurrenceReason.QUIET_PERIOD, latenessMillis = 0), outcome)
            assertTrue(started.isEmpty(), "no service, notification, sound, vibration or screen")
            assertSuppressed(id, OccurrenceReason.QUIET_PERIOD, DUE)
        }

    @Test
    fun `a quiet period set after the day was confirmed still suppresses its call`() =
        runTest {
            val clock = TestClock()
            val confirmer = CheckInConfirmer(store, OccurrenceScheduler(store, FakeAlarmScheduler(clock), clock), clock)
            assertIs<ConfirmResult.Confirmed>(confirmer.confirm(CheckInType.OPENING, LocalDate.of(2026, 9, 20)))

            store.saveQuietPeriod(quiet("09:00", "11:00"))
            val outcome = handler().onAlarm(payload)

            assertIs<FireOutcome.Suppressed>(outcome)
            assertTrue(started.isEmpty())
            assertSuppressed(id, OccurrenceReason.QUIET_PERIOD, DUE)
        }

    @Test
    fun `an alarm that arrives inside the quiet period is suppressed even if its moment was just before it`() =
        runTest {
            store.insert(occurrence(id))
            store.saveQuietPeriod(quiet("10:01", "12:00"))

            // Ninety seconds late is within the lateness limit, but it would ring at 10:01:30.
            val outcome = handler(deliveredAt = DUE.plusSeconds(90)).onAlarm(payload)

            assertEquals(FireOutcome.Suppressed(id, OccurrenceReason.QUIET_PERIOD, latenessMillis = 90_000), outcome)
            assertTrue(started.isEmpty())
        }

    @Test
    fun `a quiet period spanning midnight suppresses a late evening call and not a morning one`() =
        runTest {
            store.saveQuietPeriod(quiet("22:00", "07:00"))
            // 23:30 in Kuala Lumpur on 20 September.
            val lateEvening = DUE.plusSeconds(13 * 3600 + 30 * 60)
            store.insert(occurrence("late", at = lateEvening))
            store.insert(occurrence(id))

            val late = handler(deliveredAt = lateEvening).onAlarm(AlarmPayload("late", CheckInType.CLOSING, lateEvening.toEpochMilli()))
            val morning = handler().onAlarm(payload)

            assertEquals(OccurrenceReason.QUIET_PERIOD, assertIs<FireOutcome.Suppressed>(late).reason)
            assertIs<FireOutcome.Ringing>(morning)
            assertEquals(listOf(id), started)
        }

    @Test
    fun `a disabled quiet period suppresses nothing`() =
        runTest {
            store.insert(occurrence(id))
            store.saveQuietPeriod(quiet("09:30", "10:30", enabled = false))

            assertIs<FireOutcome.Ringing>(handler().onAlarm(payload))
            assertEquals(listOf(id), started)
        }

    @Test
    fun `an alarm too late to ring is recorded too late, even inside the quiet period`() =
        runTest {
            store.insert(occurrence(id))
            store.saveQuietPeriod(quiet("09:30", "11:00"))

            val outcome = handler(deliveredAt = DUE.plusSeconds(600)).onAlarm(payload)

            assertIs<FireOutcome.TooLate>(outcome)
            assertEquals(OccurrenceReason.TOO_LATE, store.get(id)!!.reason)
            assertTrue(started.isEmpty())
        }

    @Test
    fun `a repeat delivery of a suppressed alarm changes nothing and starts nothing`() =
        runTest {
            store.insert(occurrence(id))
            store.saveQuietPeriod(quiet("09:30", "10:30"))
            handler().onAlarm(payload)

            val again = handler(deliveredAt = DUE.plusSeconds(1)).onAlarm(payload)

            assertEquals(FireOutcome.NotClaimed(id, CheckInStatus.SUPPRESSED), again)
            assertSuppressed(id, OccurrenceReason.QUIET_PERIOD, DUE)
        }

    // One call at a time

    @Test
    fun `an alarm arriving while a stored call is ringing is suppressed and starts nothing`() =
        runTest {
            store.insert(occurrence("first"))
            store.insert(occurrence(id, at = DUE.plusSeconds(20)))
            handler().onAlarm(AlarmPayload("first", CheckInType.OPENING, DUE.toEpochMilli()))

            val second = DUE.plusSeconds(20)
            val outcome =
                handler(deliveredAt = second, callHere = { "first" })
                    .onAlarm(AlarmPayload(id, CheckInType.OPENING, second.toEpochMilli()))

            assertEquals(OccurrenceReason.CALL_IN_PROGRESS, assertIs<FireOutcome.Suppressed>(outcome).reason)
            assertEquals(listOf("first"), started, "one call only")
            assertSuppressed(id, OccurrenceReason.CALL_IN_PROGRESS, second)
            assertEquals(CheckInStatus.RINGING, store.get("first")!!.status)
        }

    @Test
    fun `an alarm arriving during a call the store cannot see is suppressed too`() =
        runTest {
            store.insert(occurrence(id))

            // A call started by a development button has no stored occurrence.
            val outcome = handler(callHere = { "manual" }).onAlarm(payload)

            assertEquals(OccurrenceReason.CALL_IN_PROGRESS, assertIs<FireOutcome.Suppressed>(outcome).reason)
            assertTrue(started.isEmpty())
            assertSuppressed(id, OccurrenceReason.CALL_IN_PROGRESS, DUE)
        }

    @Test
    fun `two alarms delivered at once start exactly one call, and the other is suppressed`() =
        runTest {
            repeat(40) { round ->
                val a = "pair-$round-a"
                val b = "pair-$round-b"
                store.insert(occurrence(a))
                store.insert(occurrence(b))
                started.clear()

                val outcomes =
                    withContext(Dispatchers.Default) {
                        listOf(a, b)
                            .map { racer -> async { handler().onAlarm(AlarmPayload(racer, CheckInType.OPENING, DUE.toEpochMilli())) } }
                            .awaitAll()
                    }

                assertEquals(1, started.size, "round $round: exactly one call started, got $started")
                assertEquals(1, outcomes.count { it is FireOutcome.Ringing }, "round $round: $outcomes")
                val loser = outcomes.filterIsInstance<FireOutcome.Suppressed>().single()
                assertEquals(OccurrenceReason.CALL_IN_PROGRESS, loser.reason)
                assertEquals(setOf(a, b), setOf(started.single(), loser.occurrenceId), "round $round")
                assertSuppressed(loser.occurrenceId, OccurrenceReason.CALL_IN_PROGRESS, DUE)

                store.transition(started.single(), CheckInStatus.RINGING, CheckInStatus.DECLINED, DUE.plusSeconds(5))
            }
        }

    @Test
    fun `a call lost with its process does not block the next alarm`() =
        runTest {
            // Left ringing by a process that died a minute ago, with nothing reconciled since.
            store.insert(occurrence("lost").transitionTo(CheckInStatus.RINGING, DUE.minusSeconds(60)))
            store.insert(occurrence(id))

            val outcome = handler().onAlarm(payload)

            assertIs<FireOutcome.Ringing>(outcome)
            assertEquals(listOf(id), started)
            val lost = store.get("lost")!!
            assertEquals(CheckInStatus.MISSED, lost.status)
            assertEquals(OccurrenceReason.INTERRUPTED, lost.reason)
        }

    @Test
    fun `a call claimed a moment ago that has not started yet still holds the phone`() =
        runTest {
            store.insert(occurrence("just-claimed").transitionTo(CheckInStatus.RINGING, DUE.minusSeconds(5)))
            store.insert(occurrence(id))

            val outcome = handler().onAlarm(payload)

            assertEquals(OccurrenceReason.CALL_IN_PROGRESS, assertIs<FireOutcome.Suppressed>(outcome).reason)
            assertTrue(started.isEmpty())
            assertEquals(CheckInStatus.RINGING, store.get("just-claimed")!!.status, "it is not treated as lost")
        }

    @Test
    fun `a long call this process is carrying is never mistaken for a lost one`() =
        runTest {
            store.insert(
                occurrence("talking")
                    .transitionTo(CheckInStatus.RINGING, DUE.minusSeconds(600))
                    .transitionTo(CheckInStatus.ANSWERED, DUE.minusSeconds(590)),
            )
            store.insert(occurrence(id))

            val outcome = handler(callHere = { "talking" }).onAlarm(payload)

            assertIs<FireOutcome.Suppressed>(outcome)
            assertEquals(CheckInStatus.ANSWERED, store.get("talking")!!.status)
            assertTrue(started.isEmpty())
        }
}
