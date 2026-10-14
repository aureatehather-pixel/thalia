package com.thalia.app.voicecheckins.devtools

import com.thalia.app.voicecheckins.call.CallUiState
import com.thalia.app.voicecheckins.call.CheckInCallCoordinator
import com.thalia.voicecheckins.domain.CheckInType
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The delayed test call's timer, on virtual time.
 *
 * Whether the timer keeps running while the phone is locked is not something a JVM test
 * can say. That is the service's job, and the controller tests prove the service does not
 * stop in the gap. What is proved here is the timer itself: it fires once, exactly on
 * time, cannot stack, and can be cancelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevTestRingTest {
    private class Rig(
        val coordinator: CheckInCallCoordinator,
        val testRing: DevTestRing,
    ) {
        val state: DevTestRingState get() = testRing.state.value
        val call: CallUiState get() = coordinator.state.value
    }

    private fun TestScope.rig(): Rig {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val coordinator =
            CheckInCallCoordinator(
                scope = scope,
                voiceClient = FakeVoiceSessionClient(lineDelay = 6.seconds),
            )
        return Rig(
            coordinator,
            DevTestRing(scope, coordinator, delay = 10.seconds, clock = { testScheduler.currentTime }),
        )
    }

    @Test
    fun `the default delay is ten seconds`() {
        assertEquals(10.seconds, DevTestRing.DEFAULT_DELAY)
    }

    @Test
    fun `arming reports when the call will ring`() =
        runTest {
            val rig = rig()
            advanceTimeBy(3.seconds)

            assertTrue(rig.testRing.arm(CheckInType.OPENING))

            val armed = assertIs<DevTestRingState.Armed>(rig.state)
            assertEquals(CheckInType.OPENING, armed.checkInType)
            assertEquals(13_000L, armed.fireAtMillis)
            // Arming alone rings nothing.
            assertEquals(CallUiState.Idle, rig.call)
        }

    @Test
    fun `it rings exactly when the delay ends, not a moment before`() =
        runTest {
            val rig = rig()
            rig.testRing.arm(CheckInType.CLOSING)

            advanceTimeBy(10.seconds - 1.milliseconds)
            runCurrent()
            assertIs<DevTestRingState.Armed>(rig.state)
            assertEquals(CallUiState.Idle, rig.call)

            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(CheckInType.CLOSING, assertIs<CallUiState.Ringing>(rig.call).checkInType)
            assertEquals(DevTestRingState.Off, rig.state)
        }

    @Test
    fun `it fires once and then can be armed again`() =
        runTest {
            val rig = rig()
            rig.testRing.arm(CheckInType.OPENING)
            advanceTimeBy(10.seconds)
            runCurrent()
            rig.coordinator.decline()
            rig.coordinator.dismiss()

            // Long after the delay, nothing rings again by itself.
            advanceTimeBy(60.seconds)
            runCurrent()
            assertEquals(CallUiState.Idle, rig.call)

            assertTrue(rig.testRing.arm(CheckInType.OPENING))
            advanceTimeBy(10.seconds)
            runCurrent()
            assertIs<CallUiState.Ringing>(rig.call)
        }

    @Test
    fun `arming again while armed is refused and does not move the deadline`() =
        runTest {
            val rig = rig()
            rig.testRing.arm(CheckInType.OPENING)
            val original = assertIs<DevTestRingState.Armed>(rig.state)
            advanceTimeBy(4.seconds)

            assertFalse(rig.testRing.arm(CheckInType.CLOSING))

            assertEquals(original, rig.state)
            advanceTimeBy(6.seconds)
            runCurrent()
            // One call, of the type first armed, at the first deadline.
            assertEquals(CheckInType.OPENING, assertIs<CallUiState.Ringing>(rig.call).checkInType)
        }

    @Test
    fun `tests cannot stack, not even after the first rings`() =
        runTest {
            val rig = rig()
            rig.testRing.arm(CheckInType.OPENING)
            rig.testRing.arm(CheckInType.OPENING)
            rig.testRing.arm(CheckInType.OPENING)
            advanceTimeBy(10.seconds)
            runCurrent()
            rig.coordinator.decline()

            // Had the extra arms each started a timer, a second call would ring now.
            rig.coordinator.dismiss()
            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(CallUiState.Idle, rig.call)
        }

    @Test
    fun `arming is refused while a call is ringing or active`() =
        runTest {
            val rig = rig()
            rig.coordinator.ring(CheckInType.OPENING)
            assertFalse(rig.testRing.arm(CheckInType.CLOSING))
            assertEquals(DevTestRingState.Off, rig.state)

            rig.coordinator.answer()
            assertIs<CallUiState.Active>(rig.call)
            assertFalse(rig.testRing.arm(CheckInType.CLOSING))
            assertEquals(DevTestRingState.Off, rig.state)
        }

    @Test
    fun `cancelling disarms and the call never rings`() =
        runTest {
            val rig = rig()
            rig.testRing.arm(CheckInType.OPENING)
            advanceTimeBy(5.seconds)

            rig.testRing.cancel()
            assertEquals(DevTestRingState.Off, rig.state)

            advanceTimeBy(60.seconds)
            runCurrent()
            assertEquals(CallUiState.Idle, rig.call)
        }

    @Test
    fun `after cancelling it can be armed afresh with a new deadline`() =
        runTest {
            val rig = rig()
            rig.testRing.arm(CheckInType.OPENING)
            advanceTimeBy(5.seconds)
            rig.testRing.cancel()

            assertTrue(rig.testRing.arm(CheckInType.OPENING))
            assertEquals(15_000L, assertIs<DevTestRingState.Armed>(rig.state).fireAtMillis)

            // The cancelled timer's original deadline, at ten seconds, must not fire it.
            advanceTimeBy(5.seconds)
            runCurrent()
            assertEquals(CallUiState.Idle, rig.call)

            advanceTimeBy(10.seconds)
            runCurrent()
            assertIs<CallUiState.Ringing>(rig.call)
        }

    @Test
    fun `cancelling when nothing is armed does nothing, and leaves a ringing call alone`() =
        runTest {
            val rig = rig()
            rig.testRing.cancel()
            assertEquals(DevTestRingState.Off, rig.state)
            assertEquals(CallUiState.Idle, rig.call)

            rig.testRing.arm(CheckInType.OPENING)
            advanceTimeBy(10.seconds)
            runCurrent()
            assertIs<CallUiState.Ringing>(rig.call)

            rig.testRing.cancel()
            assertIs<CallUiState.Ringing>(rig.call)
        }

    @Test
    fun `a call started by hand while armed wins, and the armed one does not ring over it`() =
        runTest {
            val rig = rig()
            rig.testRing.arm(CheckInType.OPENING)
            advanceTimeBy(2.seconds)
            rig.coordinator.ring(CheckInType.CLOSING)
            rig.coordinator.answer()

            advanceTimeBy(8.seconds)
            runCurrent()

            // The armed deadline passed, and the call in progress was neither replaced nor
            // restarted.
            val active = assertIs<CallUiState.Active>(rig.call)
            assertEquals(CheckInType.CLOSING, active.checkInType)
            assertEquals(DevTestRingState.Off, rig.state)
        }
}
