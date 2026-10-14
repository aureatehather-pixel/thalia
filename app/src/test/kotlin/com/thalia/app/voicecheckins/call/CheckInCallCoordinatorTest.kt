package com.thalia.app.voicecheckins.call

import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.voice.CancellationReason
import com.thalia.voicecheckins.voice.FailureReason
import com.thalia.voicecheckins.voice.VoiceSessionClient
import com.thalia.voicecheckins.voice.VoiceSessionResult
import com.thalia.voicecheckins.voice.VoiceSessionState
import com.thalia.voicecheckins.voice.fake.FakeVoiceSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The M1 call state machine, tested without Android.
 *
 * This is the whole point of keeping [CheckInCallCoordinator] free of platform types:
 * ringing, answering, declining, timing out and hanging up are all provable here, so the
 * physical device is only needed for the one thing it alone can answer, which is whether
 * the full-screen surface actually appears.
 *
 * Time is virtual. A 45 second ring timeout and a half-minute fake conversation cost no
 * real time, and every timing assertion is exact rather than approximate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckInCallCoordinatorTest {
    private val ringTimeout = 45.seconds

    private fun TestScope.coordinator(
        client: VoiceSessionClient = FakeVoiceSessionClient(),
        ringTimeout: Duration = this@CheckInCallCoordinatorTest.ringTimeout,
        recorder: CallOutcomeRecorder = CallOutcomeRecorder.None,
        handoffScheduled: Boolean = false,
    ) = CheckInCallCoordinator(
        // Tied to backgroundScope so nothing the coordinator launches outlives the test.
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        voiceClient = client,
        ringTimeout = ringTimeout,
        recorder = recorder,
        handoffScheduled = handoffScheduled,
    )

    /** Remembers every report the coordinator makes, as a short line each, in order. */
    private class RecordingRecorder : CallOutcomeRecorder {
        val events = mutableListOf<String>()

        override fun answered(occurrenceId: String) {
            events += "answered:$occurrenceId"
        }

        override fun declined(occurrenceId: String) {
            events += "declined:$occurrenceId"
        }

        override fun missed(
            occurrenceId: String,
            reason: OccurrenceReason,
        ) {
            events += "missed:$occurrenceId:$reason"
        }

        override fun finished(
            occurrenceId: String,
            status: CheckInStatus,
            reason: OccurrenceReason?,
        ) {
            events += "finished:$occurrenceId:$status:$reason"
        }
    }

    /** A conversation that takes a while, so the call is still active when the test looks. */
    private val slowCall get() = FakeVoiceSessionClient(lineDelay = 5.seconds)

    @Test
    fun `starts idle`() =
        runTest {
            assertEquals(CallUiState.Idle, coordinator().state.value)
        }

    @Test
    fun `ringing announces Thalia and the check-in`() =
        runTest {
            val c = coordinator()
            c.ring(CheckInType.CLOSING)

            val ringing = assertIs<CallUiState.Ringing>(c.state.value)
            assertEquals(CheckInType.CLOSING, ringing.checkInType)
            assertEquals("Thalia", ringing.callerName)
            assertTrue(ringing.needsForegroundService)
        }

    @Test
    fun `a second ring cannot interrupt a call in progress`() =
        runTest {
            val c = coordinator()
            c.ring(CheckInType.OPENING)
            c.ring(CheckInType.CLOSING)

            assertEquals(CheckInType.OPENING, assertIs<CallUiState.Ringing>(c.state.value).checkInType)
        }

    @Test
    fun `answering opens a session and fills the transcript`() =
        runTest {
            val c = coordinator()
            c.ring(CheckInType.OPENING)
            c.answer()

            // The unpaced fake has no delays, so the scripted call has already run to its end.
            val ended = assertIs<CallUiState.Ended>(c.state.value)
            assertEquals(CheckInStatus.COMPLETED, ended.status)
            assertEquals("Check-in complete", ended.summary)
            assertTrue(ended.transcript.isNotEmpty())
        }

    @Test
    fun `the session is opened for the occurrence the call rang for`() =
        runTest {
            val client = RecordingVoiceSessionClient()
            val c = coordinator(client)
            c.ring(CheckInType.CLOSING, occurrenceId = "2026-09-19-afternoon")
            c.answer()

            val config = client.configs.single()
            assertEquals("2026-09-19-afternoon", config.occurrenceId)
            assertEquals(CheckInType.CLOSING, config.checkInType)
        }

    @Test
    fun `a call started by a button carries the manual trigger id`() =
        runTest {
            val client = RecordingVoiceSessionClient()
            val c = coordinator(client)
            c.ring(CheckInType.OPENING)
            c.answer()

            assertEquals(CheckInCallCoordinator.MANUAL_TRIGGER_OCCURRENCE_ID, client.configs.single().occurrenceId)
        }

    @Test
    fun `a ring that is ignored does not change the occurrence of the call in progress`() =
        runTest {
            val client = RecordingVoiceSessionClient()
            val c = coordinator(client)
            c.ring(CheckInType.OPENING, occurrenceId = "first")
            c.ring(CheckInType.CLOSING, occurrenceId = "second")
            c.answer()

            assertEquals("first", client.configs.single().occurrenceId)
        }

    @Test
    fun `the next call after one ends is opened for its own occurrence`() =
        runTest {
            val client = RecordingVoiceSessionClient()
            val c = coordinator(client)
            c.ring(CheckInType.OPENING, occurrenceId = "first")
            c.decline()
            c.dismiss()
            c.ring(CheckInType.CLOSING, occurrenceId = "second")
            c.answer()

            assertEquals("second", client.configs.single().occurrenceId)
        }

    // What the call reports to the recorder

    @Test
    fun `answering and finishing a call are reported for its occurrence`() =
        runTest {
            val recorder = RecordingRecorder()
            val c = coordinator(recorder = recorder)
            c.ring(CheckInType.OPENING, "2026-09-20-morning")
            c.answer()

            assertEquals(
                listOf("answered:2026-09-20-morning", "finished:2026-09-20-morning:COMPLETED:null"),
                recorder.events,
            )
        }

    @Test
    fun `declining is reported`() =
        runTest {
            val recorder = RecordingRecorder()
            val c = coordinator(recorder = recorder)
            c.ring(CheckInType.OPENING, "x")
            c.decline()

            assertEquals(listOf("declined:x"), recorder.events)
        }

    @Test
    fun `ringing out is reported as missed with the ring timeout reason`() =
        runTest {
            val recorder = RecordingRecorder()
            val c = coordinator(recorder = recorder)
            c.ring(CheckInType.OPENING, "x")

            advanceTimeBy(ringTimeout)
            runCurrent()

            assertEquals(listOf("missed:x:RING_TIMEOUT"), recorder.events)
        }

    @Test
    fun `answering before the timeout means the timeout never reports a miss`() =
        runTest {
            val recorder = RecordingRecorder()
            val c = coordinator(FakeVoiceSessionClient(lineDelay = 60.seconds), recorder = recorder)
            c.ring(CheckInType.OPENING, "x")
            c.answer()

            advanceTimeBy(ringTimeout * 2)
            runCurrent()

            assertEquals(listOf("answered:x"), recorder.events)
        }

    @Test
    fun `interrupting a ringing call is reported as missed and interrupted`() =
        runTest {
            val recorder = RecordingRecorder()
            val c = coordinator(recorder = recorder)
            c.ring(CheckInType.OPENING, "x")
            c.interrupt()

            assertEquals(listOf("missed:x:INTERRUPTED"), recorder.events)
        }

    @Test
    fun `interrupting an answered call is recorded as failed and interrupted, not as completed`() =
        runTest {
            val recorder = RecordingRecorder()
            val c = coordinator(slowCall, recorder = recorder)
            c.ring(CheckInType.OPENING, "x")
            c.answer()
            advanceTimeBy(5.seconds)
            runCurrent()

            c.interrupt()
            runCurrent()

            assertEquals(listOf("answered:x", "finished:x:FAILED:INTERRUPTED"), recorder.events)
        }

    @Test
    fun `a user hang-up is recorded as completed`() =
        runTest {
            val recorder = RecordingRecorder()
            val c = coordinator(slowCall, recorder = recorder)
            c.ring(CheckInType.OPENING, "x")
            c.answer()
            advanceTimeBy(5.seconds)
            runCurrent()

            c.endCall()
            runCurrent()

            assertEquals(listOf("answered:x", "finished:x:COMPLETED:null"), recorder.events)
        }

    @Test
    fun `a provider failure is recorded as failed with the session reason`() =
        runTest {
            val recorder = RecordingRecorder()
            val failing = FakeVoiceSessionClient(FakeVoiceSessionClient.Behaviour.FailsWhileConnecting(FailureReason.NO_NETWORK))
            val c = coordinator(failing, recorder = recorder)
            c.ring(CheckInType.OPENING, "x")
            c.answer()

            assertEquals(listOf("answered:x", "finished:x:FAILED:SESSION_FAILED"), recorder.events)
        }

    @Test
    fun `a ring that cannot ring because a call is in progress is reported for that occurrence, and the call is untouched`() =
        runTest {
            val recorder = RecordingRecorder()
            val c = coordinator(slowCall, recorder = recorder)
            c.ring(CheckInType.OPENING, "first")
            c.answer()

            c.ring(CheckInType.CLOSING, "second")

            assertEquals(listOf("answered:first", "missed:second:CALL_IN_PROGRESS"), recorder.events)
            assertIs<CallUiState.Active>(c.state.value)
            assertEquals("first", c.activeOccurrenceId)
        }

    @Test
    fun `the active occurrence is known while ringing and talking, and not otherwise`() =
        runTest {
            val c = coordinator(slowCall)
            assertNull(c.activeOccurrenceId)

            c.ring(CheckInType.OPENING, "x")
            assertEquals("x", c.activeOccurrenceId)

            c.answer()
            assertEquals("x", c.activeOccurrenceId)

            c.endCall()
            runCurrent()
            assertNull(c.activeOccurrenceId, "an ended call is no longer being carried")
        }

    @Test
    fun `a paced call stays active while its transcript fills in`() =
        runTest {
            val c = coordinator(slowCall)
            c.ring(CheckInType.OPENING)
            c.answer()

            val opening = assertIs<CallUiState.Active>(c.state.value)
            assertTrue(opening.transcript.isEmpty())

            advanceTimeBy(5.seconds)
            runCurrent()
            assertEquals(1, assertIs<CallUiState.Active>(c.state.value).transcript.size)

            advanceTimeBy(10.seconds)
            runCurrent()
            val midCall = assertIs<CallUiState.Active>(c.state.value)
            assertEquals(3, midCall.transcript.size)
            assertEquals(VoiceSessionState.ACTIVE, midCall.sessionState)

            // And it does finish by itself, once the script is over.
            advanceTimeBy(10.seconds)
            runCurrent()
            assertEquals(CheckInStatus.COMPLETED, assertIs<CallUiState.Ended>(c.state.value).status)
        }

    @Test
    fun `declining records a decline and opens no session`() =
        runTest {
            val c = coordinator()
            c.ring(CheckInType.OPENING)
            c.decline()

            val ended = assertIs<CallUiState.Ended>(c.state.value)
            assertEquals(CheckInStatus.DECLINED, ended.status)
            assertTrue(ended.transcript.isEmpty())
            assertFalse(ended.needsForegroundService)
        }

    @Test
    fun `declining is only possible while ringing`() =
        runTest {
            val c = coordinator()
            c.decline()
            assertEquals(CallUiState.Idle, c.state.value)
        }

    @Test
    fun `answering is only possible while ringing`() =
        runTest {
            val c = coordinator()
            c.answer()
            assertEquals(CallUiState.Idle, c.state.value)
        }

    @Test
    fun `a provider failure is reported rather than shown as a normal call`() =
        runTest {
            val c =
                coordinator(
                    FakeVoiceSessionClient(
                        FakeVoiceSessionClient.Behaviour.FailsWhileConnecting(FailureReason.NO_NETWORK),
                    ),
                )
            c.ring(CheckInType.OPENING)
            c.answer()

            val ended = assertIs<CallUiState.Ended>(c.state.value)
            assertEquals(CheckInStatus.FAILED, ended.status)
            assertTrue(ended.summary.startsWith("Could not connect"))
            assertTrue(ended.transcript.isEmpty())
        }

    @Test
    fun `End Call mid-conversation hangs up at once and keeps what was said`() =
        runTest {
            val c = coordinator(slowCall)
            c.ring(CheckInType.OPENING)
            c.answer()
            advanceTimeBy(10.seconds)
            runCurrent()
            assertEquals(2, assertIs<CallUiState.Active>(c.state.value).transcript.size)
            val pressedAt = testScheduler.currentTime

            c.endCall()
            runCurrent()

            val ended = assertIs<CallUiState.Ended>(c.state.value)
            assertEquals("You ended the call", ended.summary)
            // The user answered and chose to stop. That is a check-in that happened.
            assertEquals(CheckInStatus.COMPLETED, ended.status)
            assertEquals(2, ended.transcript.size)
            // The call ended when the button was pressed, not when the next line was due.
            assertEquals(pressedAt, testScheduler.currentTime)
        }

    @Test
    fun `End Call after the call has finished never rewrites it`() =
        runTest {
            val c = coordinator()
            c.ring(CheckInType.OPENING)
            c.answer()
            c.endCall()

            val ended = assertIs<CallUiState.Ended>(c.state.value)
            assertEquals("Check-in complete", ended.summary)
        }

    @Test
    fun `End Call is ignored unless a call is active`() =
        runTest {
            val c = coordinator()
            c.ring(CheckInType.OPENING)
            c.endCall()

            assertIs<CallUiState.Ringing>(c.state.value)
        }

    @Test
    fun `dismissing returns to idle and releases the service`() =
        runTest {
            val c = coordinator()
            c.ring(CheckInType.OPENING)
            c.decline()
            c.dismiss()

            assertEquals(CallUiState.Idle, c.state.value)
            assertFalse(c.state.value.needsForegroundService)
        }

    @Test
    fun `dismissing mid-call does nothing`() =
        runTest {
            val c = coordinator()
            c.ring(CheckInType.OPENING)
            c.dismiss()

            assertIs<CallUiState.Ringing>(c.state.value)
        }

    @Test
    fun `the default ring timeout is finite and shorter than a minute`() {
        assertTrue(CheckInCallCoordinator.DEFAULT_RING_TIMEOUT.isPositive())
        assertTrue(CheckInCallCoordinator.DEFAULT_RING_TIMEOUT.isFinite())
        assertTrue(CheckInCallCoordinator.DEFAULT_RING_TIMEOUT < 60.seconds)
    }

    @Test
    fun `an unanswered call rings until the timeout and is then recorded as missed`() =
        runTest {
            val c = coordinator()
            c.ring(CheckInType.OPENING)

            // Still ringing one millisecond before the deadline.
            advanceTimeBy(ringTimeout - 1.milliseconds)
            runCurrent()
            assertIs<CallUiState.Ringing>(c.state.value)

            advanceTimeBy(1.milliseconds)
            runCurrent()
            val ended = assertIs<CallUiState.Ended>(c.state.value)
            assertEquals(CheckInStatus.MISSED, ended.status)
            assertEquals("Missed call", ended.summary)
            assertTrue(ended.transcript.isEmpty())
            assertFalse(ended.needsForegroundService)
        }

    @Test
    fun `answering cancels the ring timeout`() =
        runTest {
            val c = coordinator(FakeVoiceSessionClient(lineDelay = 60.seconds))
            c.ring(CheckInType.OPENING)
            advanceTimeBy(30.seconds)
            c.answer()

            // Well past the moment the unanswered call would have been marked missed.
            advanceTimeBy(ringTimeout)
            runCurrent()

            assertIs<CallUiState.Active>(c.state.value)
        }

    @Test
    fun `declining cancels the ring timeout so it cannot touch the next call`() =
        runTest {
            val c = coordinator()
            c.ring(CheckInType.OPENING)
            advanceTimeBy(30.seconds)
            c.decline()
            c.dismiss()

            // A second call begins. The first call's deadline, 15 seconds away, must not
            // cut it short.
            c.ring(CheckInType.CLOSING)
            advanceTimeBy(20.seconds)
            runCurrent()
            assertIs<CallUiState.Ringing>(c.state.value)

            // The second call has its own full timeout.
            advanceTimeBy(ringTimeout - 20.seconds)
            runCurrent()
            assertEquals(CheckInStatus.MISSED, assertIs<CallUiState.Ended>(c.state.value).status)
        }

    @Test
    fun `a missed call can be dismissed and the phone can ring again`() =
        runTest {
            val c = coordinator()
            c.ring(CheckInType.OPENING)
            advanceTimeBy(ringTimeout)
            runCurrent()
            assertEquals(CheckInStatus.MISSED, assertIs<CallUiState.Ended>(c.state.value).status)

            c.dismiss()
            assertEquals(CallUiState.Idle, c.state.value)
            c.ring(CheckInType.CLOSING)
            assertIs<CallUiState.Ringing>(c.state.value)
        }

    @Test
    fun `interrupting a ringing call records it as missed and stops the timeout`() =
        runTest {
            val c = coordinator()
            c.ring(CheckInType.OPENING)
            c.interrupt()

            val ended = assertIs<CallUiState.Ended>(c.state.value)
            assertEquals(CheckInStatus.MISSED, ended.status)
            assertEquals("The call was interrupted", ended.summary)

            // The cancelled timeout must not fire later and overwrite anything.
            advanceTimeBy(ringTimeout * 2)
            runCurrent()
            assertEquals("The call was interrupted", assertIs<CallUiState.Ended>(c.state.value).summary)
        }

    @Test
    fun `interrupting an active call ends the session as cancelled by the system`() =
        runTest {
            val c = coordinator(slowCall)
            c.ring(CheckInType.OPENING)
            c.answer()
            advanceTimeBy(5.seconds)
            runCurrent()

            c.interrupt()
            runCurrent()

            val ended = assertIs<CallUiState.Ended>(c.state.value)
            assertEquals("The call was interrupted", ended.summary)
            assertEquals(1, ended.transcript.size)
        }

    @Test
    fun `interrupting when nothing is happening does nothing`() =
        runTest {
            val c = coordinator()
            c.interrupt()
            assertEquals(CallUiState.Idle, c.state.value)

            c.ring(CheckInType.OPENING)
            c.decline()
            val declined = c.state.value
            c.interrupt()
            assertEquals(declined, c.state.value)
        }

    @Test
    fun `the foreground service is wanted only while ringing or talking`() {
        assertFalse(CallUiState.Idle.needsForegroundService)
        assertTrue(CallUiState.Ringing(CheckInType.OPENING).needsForegroundService)
        assertTrue(
            CallUiState
                .Active(
                    checkInType = CheckInType.OPENING,
                    sessionState = VoiceSessionState.ACTIVE,
                    transcript = emptyList(),
                ).needsForegroundService,
        )
        assertFalse(
            CallUiState
                .Ended(
                    checkInType = CheckInType.OPENING,
                    status = CheckInStatus.COMPLETED,
                    summary = "",
                    transcript = emptyList(),
                ).needsForegroundService,
        )
    }

    @Test
    fun `every cancellation reason has a plain-language summary`() {
        CancellationReason.entries.forEach { reason ->
            val summary =
                VoiceSessionResult
                    .Cancelled(reason, emptyList())
                    .summarise()
            assertTrue(summary.isNotBlank(), "no summary for $reason")
        }
    }

    // Slice 4C: the call-to-conversation handoff.

    @Test
    fun `a scheduled call hands off, is recorded answered then completed, and opens no session`() =
        runTest {
            val recorder = RecordingRecorder()
            val c = coordinator(client = slowCall, recorder = recorder, handoffScheduled = true)
            c.ring(CheckInType.CLOSING, "occ-1")
            assertTrue(assertIs<CallUiState.Ringing>(c.state.value).handoff)

            c.answer()
            assertIs<CallUiState.Ringing>(c.state.value)
            assertEquals(emptyList(), recorder.events)

            assertTrue(c.answerWithHandoff())
            assertEquals(listOf("answered:occ-1", "finished:occ-1:COMPLETED:null"), recorder.events)
            val ended = assertIs<CallUiState.Ended>(c.state.value)
            assertEquals(CheckInStatus.COMPLETED, ended.status)
            assertEquals(CheckInCallCoordinator.HANDOFF_SUMMARY, ended.summary)
            assertEquals(emptyList(), ended.transcript)
            assertEquals(null, c.activeOccurrenceId)
        }

    @Test
    fun `a repeated or late handoff changes nothing, and a decline or timeout wins cleanly`() =
        runTest {
            val recorder = RecordingRecorder()
            val c = coordinator(recorder = recorder, handoffScheduled = true)
            c.ring(CheckInType.OPENING, "occ-1")
            assertTrue(c.answerWithHandoff())
            assertFalse(c.answerWithHandoff())
            advanceTimeBy(ringTimeout + 1.seconds)
            runCurrent()
            assertEquals(listOf("answered:occ-1", "finished:occ-1:COMPLETED:null"), recorder.events)

            val declined = RecordingRecorder()
            val d = coordinator(recorder = declined, handoffScheduled = true)
            d.ring(CheckInType.OPENING, "occ-2")
            d.decline()
            assertFalse(d.answerWithHandoff())
            assertEquals(listOf("declined:occ-2"), declined.events)

            val missed = RecordingRecorder()
            val m = coordinator(recorder = missed, handoffScheduled = true)
            m.ring(CheckInType.OPENING, "occ-3")
            advanceTimeBy(ringTimeout + 1.seconds)
            runCurrent()
            assertFalse(m.answerWithHandoff())
            assertEquals(listOf("missed:occ-3:RING_TIMEOUT"), missed.events)
        }

    @Test
    fun `a call started by a button keeps the simulated session, handoff or not`() =
        runTest {
            val c = coordinator(client = slowCall, handoffScheduled = true)
            c.ring(CheckInType.OPENING)
            assertFalse(assertIs<CallUiState.Ringing>(c.state.value).handoff)
            assertFalse(c.answerWithHandoff())
            c.answer()
            assertIs<CallUiState.Active>(c.state.value)
        }
}
