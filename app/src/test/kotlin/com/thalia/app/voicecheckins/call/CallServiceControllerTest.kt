package com.thalia.app.voicecheckins.call

import com.thalia.app.voicecheckins.devtools.DevTestRing
import com.thalia.app.voicecheckins.devtools.DevTestRingState
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.voice.VoiceSessionClient
import com.thalia.voicecheckins.voice.fake.FakeVoiceSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The foreground service's lifecycle, tested without Android.
 *
 * The service itself is a thin shell over [CallServiceController], so this is where its
 * behaviour is proved: when it enters the foreground, when it makes and silences noise,
 * and every way it can stop. Each test reads as a script of the side effects Android
 * would be asked to perform, in order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallServiceControllerTest {
    private val ringTimeout = 45.seconds
    private val testRingDelay = 10.seconds

    /** Records every side effect the controller asks of Android, in order. */
    private class RecordingHost : CallServiceHost {
        val events = mutableListOf<String>()

        override fun showArmed(
            checkInType: CheckInType,
            fireAtMillis: Long,
        ) {
            events += "armed:${checkInType.name.lowercase()}"
        }

        override fun showRinging(checkInType: CheckInType) {
            events += "ringing:${checkInType.name.lowercase()}"
        }

        override fun showOngoing(checkInType: CheckInType) {
            events += "ongoing:${checkInType.name.lowercase()}"
        }

        override fun startRingAlert() {
            events += "alert:start"
        }

        override fun stopRingAlert() {
            events += "alert:stop"
        }

        override fun shutDown() {
            events += "shutDown"
        }
    }

    private class Rig(
        val coordinator: CheckInCallCoordinator,
        val testRing: DevTestRing,
        val host: RecordingHost,
        val controller: CallServiceController,
    ) {
        val events: MutableList<String> get() = host.events
        val state: CallUiState get() = coordinator.state.value

        fun ring(
            type: CheckInType = CheckInType.OPENING,
            occurrenceId: String = CheckInCallCoordinator.MANUAL_TRIGGER_OCCURRENCE_ID,
        ) = controller.onCommand(CallServiceCommand.Ring(type, occurrenceId))

        fun decline() = controller.onCommand(CallServiceCommand.Decline)

        fun endCall() = controller.onCommand(CallServiceCommand.EndCall)

        fun armTestRing(type: CheckInType = CheckInType.OPENING) = controller.onCommand(CallServiceCommand.ArmTestRing(type))

        fun cancelTestRing() = controller.onCommand(CallServiceCommand.CancelTestRing)

        /** What the activity does when the user taps Answer. The service is not involved. */
        fun answer() = coordinator.answer()
    }

    /**
     * A coordinator, a controller and a recording host, wired the way the service wires them.
     *
     * Observation starts here, before any command, exactly as it does in the service's
     * onCreate. That ordering is the one that used to stop the service before it rang.
     */
    private fun TestScope.rig(
        lineDelay: Duration = 6.seconds,
        client: VoiceSessionClient = FakeVoiceSessionClient(lineDelay = lineDelay),
    ): Rig {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val coordinator =
            CheckInCallCoordinator(
                scope = scope,
                voiceClient = client,
                ringTimeout = ringTimeout,
            )
        val testRing = DevTestRing(scope, coordinator, testRingDelay, clock = { testScheduler.currentTime })
        val host = RecordingHost()
        val controller = CallServiceController(coordinator, testRing, host)
        scope.launch { controller.observe() }
        return Rig(coordinator, testRing, host, controller)
    }

    // The occurrence id

    @Test
    fun `a ring command carries its occurrence id through to the session`() =
        runTest {
            // Paced like the app's fake, so the call is still going when the events are read.
            val client = RecordingVoiceSessionClient(FakeVoiceSessionClient(lineDelay = 6.seconds))
            val rig = rig(client = client)

            rig.ring(CheckInType.OPENING, occurrenceId = "2026-09-19-morning")
            rig.answer()

            assertEquals("2026-09-19-morning", client.configs.single().occurrenceId)
            // Carrying an id changes nothing about how the call is presented.
            assertEquals(listOf("ringing:opening", "alert:start", "alert:stop", "ongoing:opening"), rig.events)
        }

    // The creation-time race

    @Test
    fun `state seen before the first command is ignored, idle or ended`() =
        runTest {
            val rig = rig()

            // What the service's observer sees at onCreate, before onStartCommand runs.
            rig.controller.onStateChanged()
            assertEquals(emptyList(), rig.events)

            // And when the previous call's ended screen is what it finds.
            rig.coordinator.ring(CheckInType.OPENING)
            rig.coordinator.decline()
            assertIs<CallUiState.Ended>(rig.state)
            assertEquals(emptyList(), rig.events)
        }

    @Test
    fun `a new service rings even though the coordinator was idle when it was created`() =
        runTest {
            val rig = rig()
            assertEquals(CallUiState.Idle, rig.state)

            rig.ring()

            assertEquals(listOf("ringing:opening", "alert:start"), rig.events)
            assertFalse("shutDown" in rig.events)
        }

    @Test
    fun `a new service rings even though the previous call's ended screen is still showing`() =
        runTest {
            val rig = rig()
            rig.ring()
            rig.decline()
            assertIs<CallUiState.Ended>(rig.state)

            // The first service stopped after that call. Android creates a new instance
            // for the next one, and the coordinator is still in the previous call's Ended
            // state when that instance first looks at it.
            val freshHost = RecordingHost()
            val fresh = CallServiceController(rig.coordinator, rig.testRing, freshHost)
            fresh.onStateChanged()
            assertEquals(emptyList(), freshHost.events)

            fresh.onCommand(CallServiceCommand.Ring(CheckInType.CLOSING))

            assertEquals(listOf("ringing:closing", "alert:start"), freshHost.events)
        }

    @Test
    fun `the observer's first emission after the ring command changes nothing`() =
        runTest {
            val rig = rig()
            rig.ring()

            // The collector delivers the current state, which the command already presented.
            rig.controller.onStateChanged()
            rig.controller.onStateChanged()

            assertEquals(listOf("ringing:opening", "alert:start"), rig.events)
        }

    // One owner, no duplicate rendering

    @Test
    fun `the ringing notification is posted once`() =
        runTest {
            val rig = rig()
            rig.ring()
            advanceTimeBy(20.seconds)
            runCurrent()

            assertEquals(1, rig.events.count { it.startsWith("ringing:") })
            assertEquals(1, rig.events.count { it == "alert:start" })
        }

    @Test
    fun `answering swaps to the ongoing notification once and silences the alert`() =
        runTest {
            val rig = rig()
            rig.ring()
            rig.answer()

            assertEquals(
                listOf("ringing:opening", "alert:start", "alert:stop", "ongoing:opening"),
                rig.events,
            )
        }

    @Test
    fun `transcript lines do not repost the ongoing notification`() =
        runTest {
            val rig = rig(lineDelay = 6.seconds)
            rig.ring()
            rig.answer()
            advanceTimeBy(20.seconds)
            runCurrent()

            // Three lines have arrived and each was new coordinator state.
            assertEquals(3, assertIs<CallUiState.Active>(rig.state).transcript.size)
            assertEquals(1, rig.events.count { it.startsWith("ongoing:") })
            assertFalse("shutDown" in rig.events)
        }

    @Test
    fun `a repeat ring while ringing re-enters the foreground without restarting the alert`() =
        runTest {
            val rig = rig()
            rig.ring()
            rig.events.clear()

            rig.ring()

            // Android requires startForeground() after every startForegroundService().
            assertEquals(listOf("ringing:opening"), rig.events)
        }

    @Test
    fun `a ring during an active call re-enters the foreground as ongoing and does not ring`() =
        runTest {
            val rig = rig()
            rig.ring()
            rig.answer()
            rig.events.clear()

            rig.ring(CheckInType.CLOSING)

            assertEquals(listOf("ongoing:opening"), rig.events)
            assertEquals(CheckInType.OPENING, assertIs<CallUiState.Active>(rig.state).checkInType)
        }

    // Every way the service stops

    @Test
    fun `declining silences the alert and stops the service`() =
        runTest {
            val rig = rig()
            rig.ring()
            rig.decline()

            assertEquals(
                listOf("ringing:opening", "alert:start", "alert:stop", "shutDown"),
                rig.events,
            )
            assertEquals(CheckInStatus.DECLINED, assertIs<CallUiState.Ended>(rig.state).status)
        }

    @Test
    fun `an unanswered call times out, silences the alert and stops the service`() =
        runTest {
            val rig = rig()
            rig.ring()

            advanceTimeBy(ringTimeout - 1.seconds)
            runCurrent()
            assertFalse("shutDown" in rig.events)

            advanceTimeBy(1.seconds)
            runCurrent()

            assertEquals(
                listOf("ringing:opening", "alert:start", "alert:stop", "shutDown"),
                rig.events,
            )
            assertEquals(CheckInStatus.MISSED, assertIs<CallUiState.Ended>(rig.state).status)
        }

    @Test
    fun `an answered call does not time out and stops only when it ends`() =
        runTest {
            val rig = rig(lineDelay = 60.seconds)
            rig.ring()
            rig.answer()

            advanceTimeBy(ringTimeout * 2)
            runCurrent()

            assertIs<CallUiState.Active>(rig.state)
            assertFalse("shutDown" in rig.events)
        }

    @Test
    fun `End Call ends the session and stops the service`() =
        runTest {
            val rig = rig()
            rig.ring()
            rig.answer()
            advanceTimeBy(12.seconds)
            runCurrent()

            rig.endCall()
            runCurrent()

            assertEquals("You ended the call", assertIs<CallUiState.Ended>(rig.state).summary)
            assertEquals(listOf("alert:stop", "shutDown"), rig.events.takeLast(2))
            assertEquals(1, rig.events.count { it == "shutDown" })
        }

    @Test
    fun `a call that runs to completion stops the service`() =
        runTest {
            val rig = rig()
            rig.ring()
            rig.answer()
            advanceTimeBy(60.seconds)
            runCurrent()

            assertEquals("Check-in complete", assertIs<CallUiState.Ended>(rig.state).summary)
            assertEquals("shutDown", rig.events.last())
            assertEquals(1, rig.events.count { it == "shutDown" })
        }

    @Test
    fun `a stray decline on a service with nothing to carry stops it without posting anything`() =
        runTest {
            val rig = rig()

            rig.decline()

            assertEquals(listOf("alert:stop", "shutDown"), rig.events)
            assertTrue(rig.events.none { it.startsWith("ringing:") || it.startsWith("ongoing:") })
        }

    @Test
    fun `an unrecognised start with nothing to carry stops the service`() =
        runTest {
            val rig = rig()

            rig.controller.onCommand(CallServiceCommand.Unrecognised)

            assertEquals("shutDown", rig.events.last())
        }

    @Test
    fun `a command after the call ended asks the service to stop again`() =
        runTest {
            val rig = rig()
            rig.ring()
            rig.decline()
            rig.events.clear()

            // A second Decline tap arrives after the first stop was requested. The later
            // start can cancel the earlier stop, so it must be asked for again.
            rig.decline()

            assertEquals("shutDown", rig.events.last())
        }

    // Reuse, and destruction

    @Test
    fun `the same service can ring again after a finished call`() =
        runTest {
            val rig = rig()
            rig.ring()
            rig.decline()
            rig.events.clear()

            rig.ring(CheckInType.CLOSING)

            assertEquals(listOf("ringing:closing", "alert:start"), rig.events)
        }

    @Test
    fun `destroying the service silences the alert and ends a call still in progress`() =
        runTest {
            val rig = rig()
            rig.ring()
            rig.events.clear()

            rig.controller.onDestroy()
            runCurrent()

            assertTrue("alert:stop" in rig.events)
            val ended = assertIs<CallUiState.Ended>(rig.state)
            assertEquals(CheckInStatus.MISSED, ended.status)
            assertEquals("The call was interrupted", ended.summary)
        }

    @Test
    fun `destroying the service during an active call ends the session`() =
        runTest {
            val rig = rig()
            rig.ring()
            rig.answer()
            advanceTimeBy(6.seconds)
            runCurrent()

            rig.controller.onDestroy()
            runCurrent()

            val ended = assertIs<CallUiState.Ended>(rig.state)
            assertEquals("The call was interrupted", ended.summary)
            assertEquals(1, ended.transcript.size)
        }

    @Test
    fun `destroying the service after a finished call leaves that call's result alone`() =
        runTest {
            val rig = rig()
            rig.ring()
            rig.decline()
            val declined = rig.state

            rig.controller.onDestroy()

            assertEquals(declined, rig.state)
        }

    // The armed test call

    @Test
    fun `arming enters the foreground quietly and starts no ringing`() =
        runTest {
            val rig = rig()

            rig.armTestRing()

            assertEquals(listOf("armed:opening"), rig.events)
            assertIs<DevTestRingState.Armed>(rig.testRing.state.value)
        }

    @Test
    fun `the armed call rings when the delay ends and the service never stops in between`() =
        runTest {
            val rig = rig()
            rig.armTestRing()

            advanceTimeBy(testRingDelay - 1.seconds)
            runCurrent()
            assertEquals(listOf("armed:opening"), rig.events)

            advanceTimeBy(1.seconds)
            runCurrent()

            // Armed straight into ringing, with no shutDown and no gap between them. This
            // is the whole point: the service carries the phone through the locked wait.
            assertEquals(listOf("armed:opening", "ringing:opening", "alert:start"), rig.events)
            assertIs<CallUiState.Ringing>(rig.state)
            assertEquals(DevTestRingState.Off, rig.testRing.state.value)
        }

    @Test
    fun `an armed call that is then answered and ended stops the service exactly once at the end`() =
        runTest {
            val rig = rig()
            rig.armTestRing()
            advanceTimeBy(testRingDelay)
            runCurrent()
            rig.answer()
            advanceTimeBy(60.seconds)
            runCurrent()

            assertEquals("Check-in complete", assertIs<CallUiState.Ended>(rig.state).summary)
            assertEquals(1, rig.events.count { it == "shutDown" })
            assertEquals("shutDown", rig.events.last())
        }

    @Test
    fun `cancelling from the notification disarms, stops the service and never rings`() =
        runTest {
            val rig = rig()
            rig.armTestRing()
            advanceTimeBy(4.seconds)

            rig.cancelTestRing()
            advanceTimeBy(testRingDelay * 2)
            runCurrent()

            assertEquals(listOf("armed:opening", "alert:stop", "shutDown"), rig.events)
            assertEquals(CallUiState.Idle, rig.state)
            assertEquals(DevTestRingState.Off, rig.testRing.state.value)
        }

    @Test
    fun `cancelling from the screen stops the service too`() =
        runTest {
            val rig = rig()
            rig.armTestRing()

            // The screen's Cancel button calls the timer directly, with no service command.
            rig.testRing.cancel()
            advanceTimeBy(testRingDelay * 2)
            runCurrent()

            assertEquals("shutDown", rig.events.last())
            assertEquals(CallUiState.Idle, rig.state)
        }

    @Test
    fun `arming twice does not stack, and re-enters the foreground without restarting the timer`() =
        runTest {
            val rig = rig()
            rig.armTestRing()
            val firstFire = assertIs<DevTestRingState.Armed>(rig.testRing.state.value).fireAtMillis
            advanceTimeBy(6.seconds)

            rig.armTestRing()

            assertEquals(firstFire, assertIs<DevTestRingState.Armed>(rig.testRing.state.value).fireAtMillis)
            assertEquals(listOf("armed:opening", "armed:opening"), rig.events)

            // One call rings, at the original time, and only one.
            advanceTimeBy(testRingDelay - 6.seconds)
            runCurrent()
            assertEquals(1, rig.events.count { it.startsWith("ringing:") })
        }

    @Test
    fun `arming while a call is ringing is refused and the service just keeps ringing`() =
        runTest {
            val rig = rig()
            rig.ring()
            rig.events.clear()

            rig.armTestRing()

            assertEquals(DevTestRingState.Off, rig.testRing.state.value)
            assertEquals(listOf("ringing:opening"), rig.events)
        }

    @Test
    fun `a stray cancel on a service with nothing armed stops it without posting anything`() =
        runTest {
            val rig = rig()

            rig.cancelTestRing()

            assertEquals(listOf("alert:stop", "shutDown"), rig.events)
        }

    @Test
    fun `destroying the service while armed disarms it so it cannot ring into nothing`() =
        runTest {
            val rig = rig()
            rig.armTestRing()

            rig.controller.onDestroy()
            advanceTimeBy(testRingDelay * 2)
            runCurrent()

            assertEquals(DevTestRingState.Off, rig.testRing.state.value)
            assertEquals(CallUiState.Idle, rig.state)
            assertFalse(rig.events.any { it.startsWith("ringing:") })
        }
}
