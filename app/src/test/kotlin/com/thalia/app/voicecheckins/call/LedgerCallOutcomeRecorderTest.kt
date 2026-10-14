package com.thalia.app.voicecheckins.call

import com.thalia.app.voicecheckins.schedule.DUE
import com.thalia.app.voicecheckins.schedule.InMemoryOccurrenceStore
import com.thalia.app.voicecheckins.schedule.OccurrenceStore
import com.thalia.app.voicecheckins.schedule.occurrence
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The call's outcomes reaching the store, in order.
 *
 * The order is the reason this class exists. An unpaced call is answered and finished in one
 * instant, and a launch per report could apply them the wrong way round and lose the finish.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedgerCallOutcomeRecorderTest {
    private val id = "2026-09-20-morning"
    private var now = DUE.toEpochMilli()

    private fun TestScope.recorder(
        store: OccurrenceStore,
        errors: MutableList<Throwable> = mutableListOf(),
    ): LedgerCallOutcomeRecorder {
        val scope = CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler))
        return LedgerCallOutcomeRecorder(scope, store, { now }, errors::add)
    }

    private suspend fun ringingOccurrence(): InMemoryOccurrenceStore =
        InMemoryOccurrenceStore().also {
            it.insert(occurrence())
            it.claimForRinging(id, DUE)
        }

    private suspend fun OccurrenceStore.record(): ScheduledOccurrence = assertNotNull(get(id))

    @Test
    fun `answering is recorded`() =
        runTest {
            val store = ringingOccurrence()
            recorder(store).answered(id)
            runCurrent()

            assertEquals(CheckInStatus.ANSWERED, store.record().status)
        }

    @Test
    fun `declining is recorded as declined with no reason`() =
        runTest {
            val store = ringingOccurrence()
            recorder(store).declined(id)
            runCurrent()

            assertEquals(CheckInStatus.DECLINED, store.record().status)
            assertNull(store.record().reason)
        }

    @Test
    fun `ringing out is recorded as missed with its reason`() =
        runTest {
            val store = ringingOccurrence()
            recorder(store).missed(id, OccurrenceReason.RING_TIMEOUT)
            runCurrent()

            assertEquals(CheckInStatus.MISSED, store.record().status)
            assertEquals(OccurrenceReason.RING_TIMEOUT, store.record().reason)
        }

    @Test
    fun `answered then finished in the same instant are applied in that order`() =
        runTest {
            val store = ringingOccurrence()
            val recorder = recorder(store)

            recorder.answered(id)
            recorder.finished(id, CheckInStatus.COMPLETED, null)
            runCurrent()

            val record = store.record()
            assertEquals(CheckInStatus.COMPLETED, record.status, "the finish must not be refused for arriving first")
            assertEquals(DUE, record.firedAt)
        }

    @Test
    fun `a failed call is recorded with its reason`() =
        runTest {
            val store = ringingOccurrence()
            val recorder = recorder(store)

            recorder.answered(id)
            recorder.finished(id, CheckInStatus.FAILED, OccurrenceReason.SESSION_FAILED)
            runCurrent()

            assertEquals(OccurrenceReason.SESSION_FAILED, store.record().reason)
        }

    @Test
    fun `many reports are applied in the order they were made`() =
        runTest {
            val store = ringingOccurrence()
            val recorder = recorder(store)

            recorder.answered(id)
            recorder.finished(id, CheckInStatus.COMPLETED, null)
            // A stray late report for a call that has ended changes nothing.
            recorder.missed(id, OccurrenceReason.INTERRUPTED)
            runCurrent()

            assertEquals(CheckInStatus.COMPLETED, store.record().status)
        }

    @Test
    fun `the moment of each report is read when it is made, not when it is written`() =
        runTest {
            val store = ringingOccurrence()
            val recorder = recorder(store)

            now = DUE.plusSeconds(30).toEpochMilli()
            recorder.missed(id, OccurrenceReason.RING_TIMEOUT)
            now = DUE.plusSeconds(999).toEpochMilli() // the write happens much later
            runCurrent()

            assertEquals(DUE.plusSeconds(30), store.record().resolvedAt)
        }

    @Test
    fun `a call with no stored occurrence reports without harm`() =
        runTest {
            val store = InMemoryOccurrenceStore()
            val errors = mutableListOf<Throwable>()
            val recorder = recorder(store, errors)

            recorder.answered("dev-trigger")
            recorder.declined("dev-trigger")
            recorder.missed("dev-trigger", OccurrenceReason.RING_TIMEOUT)
            recorder.finished("dev-trigger", CheckInStatus.COMPLETED, null)
            runCurrent()

            assertNull(store.get("dev-trigger"))
            assertEquals(emptyList(), errors, "a button-started call is normal and must not raise an error")
        }

    @Test
    fun `a failed write is reported and does not stop the ones behind it`() =
        runTest {
            val inner = ringingOccurrence()
            val failing =
                object : OccurrenceStore by inner {
                    var first = true

                    override suspend fun transition(
                        id: String,
                        from: CheckInStatus,
                        to: CheckInStatus,
                        at: Instant,
                        reason: OccurrenceReason?,
                    ): Boolean {
                        if (first) {
                            first = false
                            error("the disk is full")
                        }
                        return inner.transition(id, from, to, at, reason)
                    }
                }
            val errors = mutableListOf<Throwable>()
            val recorder = recorder(failing, errors)

            recorder.answered(id)
            recorder.declined(id)
            runCurrent()

            assertEquals(1, errors.size)
            assertEquals("the disk is full", errors.single().message)
            assertEquals(CheckInStatus.DECLINED, failing.get(id)!!.status, "the report behind the failure still landed")
        }
}
