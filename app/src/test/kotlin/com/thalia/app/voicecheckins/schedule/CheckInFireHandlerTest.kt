package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What happens when an alarm is delivered, tested without Android.
 *
 * Two rules decide whether a call rings, and both are pinned here to the millisecond and to the
 * caller. The captain's: a check-in delivered more than two minutes late does not ring. And the
 * claim: an occurrence rings at most once, however many times its alarm is delivered.
 */
class CheckInFireHandlerTest {
    private val id = "2026-09-20-morning"
    private val scheduledAt = DUE.toEpochMilli()
    private val payload = AlarmPayload(id, CheckInType.OPENING, scheduledAt)

    private class RecordingStarter(
        var succeeds: Boolean = true,
        private val onStart: (String) -> Unit = {},
    ) : CallStarter {
        val started = mutableListOf<Pair<CheckInType, String>>()

        @Synchronized
        override fun startCall(
            checkInType: CheckInType,
            occurrenceId: String,
        ): Boolean {
            started += checkInType to occurrenceId
            onStart(occurrenceId)
            return succeeds
        }
    }

    private suspend fun stored(): InMemoryOccurrenceStore = InMemoryOccurrenceStore().also { it.insert(occurrence()) }

    private fun handler(
        store: OccurrenceStore,
        starter: CallStarter,
        deliveredAt: Long,
    ) = CheckInFireHandler(clock = { deliveredAt }, store = store, callStarter = starter)

    @Test
    fun `an alarm delivered on time claims the occurrence and starts the call`() =
        runTest {
            val store = stored()
            val starter = RecordingStarter()

            val outcome = handler(store, starter, deliveredAt = scheduledAt).onAlarm(payload)

            assertEquals(FireOutcome.Ringing(id, latenessMillis = 0), outcome)
            assertEquals(listOf(CheckInType.OPENING to id), starter.started)
            val record = store.get(id)!!
            assertEquals(CheckInStatus.RINGING, record.status)
            assertEquals(DUE, record.firedAt)
        }

    @Test
    fun `the occurrence is claimed before the call is started, never after`() =
        runTest {
            val store = stored()
            var statusWhenStarted: CheckInStatus? = null
            val starter = RecordingStarter(onStart = { statusWhenStarted = runBlocking { store.get(it)?.status } })

            handler(store, starter, deliveredAt = scheduledAt).onAlarm(payload)

            assertEquals(CheckInStatus.RINGING, statusWhenStarted, "the claim must already be in place when the call starts")
        }

    @Test
    fun `the call is started for the type and id the alarm carried`() =
        runTest {
            val store = InMemoryOccurrenceStore().also { it.insert(occurrence("2026-09-20-afternoon", CheckInType.CLOSING)) }
            val starter = RecordingStarter()

            handler(store, starter, deliveredAt = scheduledAt)
                .onAlarm(AlarmPayload("2026-09-20-afternoon", CheckInType.CLOSING, scheduledAt))

            assertEquals(listOf(CheckInType.CLOSING to "2026-09-20-afternoon"), starter.started)
        }

    @Test
    fun `an alarm a few seconds late still rings, and reports how late`() =
        runTest {
            val store = stored()
            val starter = RecordingStarter()

            val outcome = handler(store, starter, deliveredAt = scheduledAt + 7.seconds.inWholeMilliseconds).onAlarm(payload)

            assertEquals(FireOutcome.Ringing(id, latenessMillis = 7_000), outcome)
            assertEquals(1, starter.started.size)
        }

    @Test
    fun `exactly two minutes late still rings`() =
        runTest {
            val store = stored()
            val starter = RecordingStarter()

            val outcome = handler(store, starter, deliveredAt = scheduledAt + 2.minutes.inWholeMilliseconds).onAlarm(payload)

            assertEquals(FireOutcome.Ringing(id, latenessMillis = 120_000), outcome)
            assertEquals(1, starter.started.size)
        }

    @Test
    fun `one millisecond over two minutes late does not ring and is recorded missed as too late`() =
        runTest {
            val store = stored()
            val starter = RecordingStarter()

            val outcome = handler(store, starter, deliveredAt = scheduledAt + 2.minutes.inWholeMilliseconds + 1).onAlarm(payload)

            assertEquals(FireOutcome.TooLate(id, latenessMillis = 120_001, recorded = true), outcome)
            assertTrue(starter.started.isEmpty(), "a call that is over two minutes late must not start")
            val record = store.get(id)!!
            assertEquals(CheckInStatus.MISSED, record.status)
            assertEquals(OccurrenceReason.TOO_LATE, record.reason)
            assertNull(record.firedAt, "it never rang, so it was never fired")
        }

    @Test
    fun `an alarm hours late does not ring and is not retried`() =
        runTest {
            val store = stored()
            val starter = RecordingStarter()
            val late = handler(store, starter, deliveredAt = scheduledAt + 47.minutes.inWholeMilliseconds)

            assertEquals(FireOutcome.TooLate(id, latenessMillis = 47 * 60_000, recorded = true), late.onAlarm(payload))
            // A second delivery of the same late alarm is not rung either, and is not recorded twice.
            assertEquals(FireOutcome.TooLate(id, latenessMillis = 47 * 60_000, recorded = false), late.onAlarm(payload))
            assertTrue(starter.started.isEmpty())
        }

    @Test
    fun `the two minute limit is the default`() {
        assertEquals(2.minutes, CheckInFireHandler.MAX_LATENESS)
    }

    @Test
    fun `an alarm that reads as slightly early is rung, not lost`() =
        runTest {
            val store = stored()
            val starter = RecordingStarter()

            val outcome = handler(store, starter, deliveredAt = scheduledAt - 1.seconds.inWholeMilliseconds).onAlarm(payload)

            assertEquals(FireOutcome.Ringing(id, latenessMillis = -1_000), outcome)
            assertEquals(1, starter.started.size)
        }

    // Duplicate delivery

    @Test
    fun `the same alarm delivered twice rings once`() =
        runTest {
            val store = stored()
            val starter = RecordingStarter()
            val handler = handler(store, starter, deliveredAt = scheduledAt)

            val first = handler.onAlarm(payload)
            val second = handler.onAlarm(payload)

            assertEquals(FireOutcome.Ringing(id, 0), first)
            assertEquals(FireOutcome.NotClaimed(id, found = CheckInStatus.RINGING), second)
            assertEquals(1, starter.started.size, "the second delivery must not start a second call")
        }

    @Test
    fun `many deliveries of one alarm at once ring exactly once`() =
        runTest {
            val store = stored()
            val starter = RecordingStarter()

            val outcomes =
                withContext(Dispatchers.Default) {
                    (1..48).map { async { handler(store, starter, deliveredAt = scheduledAt).onAlarm(payload) } }.awaitAll()
                }

            assertEquals(1, starter.started.size, "one call, however many deliveries")
            assertEquals(1, outcomes.count { it is FireOutcome.Ringing })
            assertEquals(47, outcomes.count { it is FireOutcome.NotClaimed })
        }

    @Test
    fun `a delivery after the occurrence was marked missed does not ring`() =
        runTest {
            val store = stored()
            store.transition(id, CheckInStatus.SCHEDULED, CheckInStatus.MISSED, DUE.plusSeconds(1), OccurrenceReason.ALARM_MISSING)
            val starter = RecordingStarter()

            val outcome = handler(store, starter, deliveredAt = scheduledAt).onAlarm(payload)

            assertEquals(FireOutcome.NotClaimed(id, found = CheckInStatus.MISSED), outcome)
            assertTrue(starter.started.isEmpty())
        }

    @Test
    fun `a delivery for an occurrence that was never recorded does not ring`() =
        runTest {
            val store = InMemoryOccurrenceStore()
            val starter = RecordingStarter()

            val outcome = handler(store, starter, deliveredAt = scheduledAt).onAlarm(payload)

            assertEquals(FireOutcome.NotClaimed(id, found = null), outcome)
            assertTrue(starter.started.isEmpty())
        }

    @Test
    fun `a delivery for an occurrence that already ended does not ring again`() =
        runTest {
            val store = stored()
            val starter = RecordingStarter()
            val handler = handler(store, starter, deliveredAt = scheduledAt)
            handler.onAlarm(payload)
            store.transition(id, CheckInStatus.RINGING, CheckInStatus.DECLINED, DUE.plusSeconds(5))

            val outcome = handler.onAlarm(payload)

            assertEquals(FireOutcome.NotClaimed(id, found = CheckInStatus.DECLINED), outcome)
            assertEquals(1, starter.started.size)
        }

    @Test
    fun `a refused start is recorded as missed, never left claiming to ring`() =
        runTest {
            val store = stored()
            val starter = RecordingStarter(succeeds = false)

            val outcome = handler(store, starter, deliveredAt = scheduledAt).onAlarm(payload)

            assertEquals(FireOutcome.StartRefused(id, latenessMillis = 0), outcome)
            val record = store.get(id)!!
            assertEquals(CheckInStatus.MISSED, record.status)
            assertEquals(OccurrenceReason.START_REFUSED, record.reason)
            assertEquals(DUE, record.firedAt, "it was claimed, so the fired time stays")
        }

    @Test
    fun `a refused start is not retried by a second delivery`() =
        runTest {
            val store = stored()
            val starter = RecordingStarter(succeeds = false)
            val handler = handler(store, starter, deliveredAt = scheduledAt)

            handler.onAlarm(payload)
            val second = handler.onAlarm(payload)

            assertEquals(FireOutcome.NotClaimed(id, found = CheckInStatus.MISSED), second)
            assertEquals(1, starter.started.size)
        }

    @Test
    fun `an alarm that does not say what it is for starts nothing and touches nothing`() =
        runTest {
            val store = stored()
            val starter = RecordingStarter()

            val outcome = handler(store, starter, deliveredAt = scheduledAt).onAlarm(null)

            assertEquals(FireOutcome.Malformed, outcome)
            assertTrue(starter.started.isEmpty())
            assertEquals(CheckInStatus.SCHEDULED, store.get(id)!!.status)
        }

    @Test
    fun `the starter is never asked more than once even under a mix of late and on-time deliveries`() =
        runTest {
            val store = stored()
            val calls = AtomicInteger()
            val starter = CallStarter { _, _ -> calls.incrementAndGet() > 0 }

            withContext(Dispatchers.Default) {
                (1..32)
                    .map { n ->
                        async {
                            val at = if (n % 2 == 0) scheduledAt else scheduledAt + 3.minutes.inWholeMilliseconds
                            handler(store, starter, deliveredAt = at).onAlarm(payload)
                        }
                    }.awaitAll()
            }

            assertTrue(calls.get() <= 1, "at most one call, got ${calls.get()}")
        }
}
