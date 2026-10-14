package com.thalia.app.m3a

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.thalia.voicecheckins.gemini.GeminiLiveSessionHandle
import com.thalia.voicecheckins.gemini.LiveUsage
import com.thalia.voicecheckins.voice.CancellationReason
import com.thalia.voicecheckins.voice.VoiceSessionEvent
import com.thalia.voicecheckins.voice.VoiceSessionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The screen's lifecycle, driven by hand: a session never outlives the screen being visible. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycleBindingTest {
    @TempDir
    lateinit var dir: File

    private class Session : GeminiLiveSessionHandle {
        val ends = mutableListOf<CancellationReason>()
        private val ended = CompletableDeferred<CancellationReason>()
        override val usage: StateFlow<LiveUsage> = MutableStateFlow(LiveUsage())
        override val events: Flow<VoiceSessionEvent> =
            flow { emit(VoiceSessionEvent.Finished(VoiceSessionResult.Cancelled(ended.await(), emptyList()))) }

        override fun endSession(reason: CancellationReason) {
            ends += reason
            ended.complete(reason)
        }
    }

    private class Screen : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    private fun TestScope.running(): Triple<Screen, LiveTestController, Session> {
        val session = Session()
        val ledger = ReservationLedger(File(dir, "ledger.jsonl"))
        val controller =
            LiveTestController(
                scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                inputs = { GateInputs(true, true, true, true, false, ledger.attemptsUsed()) },
                ledger = ledger,
                sessions = { _, _ -> Preparation.Ready(session) },
                clock = { 1 },
            )
        val screen = Screen()
        screen.lifecycle.addObserver(SessionLifecycleBinding(controller))
        screen.lifecycle.currentState = Lifecycle.State.RESUMED
        controller.start()
        assertTrue(controller.state.value.running)
        return Triple(screen, controller, session)
    }

    @Test
    fun `leaving the screen, or the app going to the background, ends the session as ended by the system`() =
        runTest {
            val (screen, controller, session) = running()

            // onPause alone, as when a permission dialog covers the screen, ends nothing.
            screen.lifecycle.currentState = Lifecycle.State.STARTED
            assertTrue(controller.state.value.running)

            screen.lifecycle.currentState = Lifecycle.State.CREATED

            assertEquals(listOf(CancellationReason.ENDED_BY_SYSTEM), session.ends)
            assertEquals(CancellationReason.ENDED_BY_SYSTEM, assertIs<VoiceSessionResult.Cancelled>(controller.state.value.result).reason)
            assertFalse(controller.state.value.running)
        }

    @Test
    fun `destroying the screen ends the session too`() =
        runTest {
            val (screen, controller, session) = running()

            screen.lifecycle.currentState = Lifecycle.State.DESTROYED

            assertEquals(CancellationReason.ENDED_BY_SYSTEM, session.ends.first())
            assertFalse(controller.state.value.running)
        }

    @Test
    fun `an explicit End before leaving keeps its own reason`() =
        runTest {
            val (screen, controller, session) = running()

            controller.endByUser()
            screen.lifecycle.currentState = Lifecycle.State.DESTROYED

            assertEquals(listOf(CancellationReason.ENDED_BY_USER), session.ends)
            assertEquals(CancellationReason.ENDED_BY_USER, assertIs<VoiceSessionResult.Cancelled>(controller.state.value.result).reason)
        }
}
