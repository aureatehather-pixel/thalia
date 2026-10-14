package com.thalia.app.m3a

import com.thalia.voicecheckins.gemini.GeminiLiveSessionHandle
import com.thalia.voicecheckins.gemini.LiveUsage
import com.thalia.voicecheckins.gemini.UsageReport
import com.thalia.voicecheckins.voice.CancellationReason
import com.thalia.voicecheckins.voice.FailureReason
import com.thalia.voicecheckins.voice.Speaker
import com.thalia.voicecheckins.voice.TranscriptLine
import com.thalia.voicecheckins.voice.VoiceSessionEvent
import com.thalia.voicecheckins.voice.VoiceSessionResult
import com.thalia.voicecheckins.voice.VoiceSessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val USER_LINE = TranscriptLine(Speaker.USER, "Can you hear me?")

/**
 * A session that opens nothing. Collecting its events stands in for opening the socket, and
 * records how many attempts the ledger held at that moment.
 */
private class FakeSession(
    private val ledger: ReservationLedger,
    private val steps: MutableList<String>,
    private val script: suspend FlowCollector<VoiceSessionEvent>.(FakeSession) -> Unit,
) : GeminiLiveSessionHandle {
    val endRequests = CopyOnWriteArrayList<CancellationReason>()
    val ended = CompletableDeferred<CancellationReason>()

    @Volatile
    var attemptsAtOpen: Int? = null

    @Volatile
    var opened = false

    @Volatile
    var cleanedUp = 0

    val usageState = MutableStateFlow(LiveUsage())
    override val usage: StateFlow<LiveUsage> = usageState

    override val events: Flow<VoiceSessionEvent> =
        flow {
            attemptsAtOpen = ledger.attemptsUsed()
            opened = true
            steps += "open"
            try {
                emit(VoiceSessionEvent.StateChanged(VoiceSessionState.IDLE, VoiceSessionState.CONNECTING))
                script(this@FakeSession)
            } finally {
                cleanedUp++
            }
        }

    override fun endSession(reason: CancellationReason) {
        endRequests += reason
        ended.complete(reason)
    }
}

/** Runs until ended, then settles as cancelled with the reason given, as the real client does. */
private val untilEnded: suspend FlowCollector<VoiceSessionEvent>.(FakeSession) -> Unit = { session ->
    emit(VoiceSessionEvent.StateChanged(VoiceSessionState.CONNECTING, VoiceSessionState.ACTIVE))
    emit(VoiceSessionEvent.TranscriptAppended(USER_LINE))
    val reason = session.ended.await()
    emit(VoiceSessionEvent.StateChanged(VoiceSessionState.ACTIVE, VoiceSessionState.ENDING))
    emit(VoiceSessionEvent.StateChanged(VoiceSessionState.ENDING, VoiceSessionState.ENDED))
    emit(VoiceSessionEvent.Finished(VoiceSessionResult.Cancelled(reason, listOf(USER_LINE))))
}

private fun failsWith(reason: FailureReason): suspend FlowCollector<VoiceSessionEvent>.(FakeSession) -> Unit =
    {
        emit(VoiceSessionEvent.StateChanged(VoiceSessionState.CONNECTING, VoiceSessionState.ENDING))
        emit(VoiceSessionEvent.StateChanged(VoiceSessionState.ENDING, VoiceSessionState.ENDED))
        emit(VoiceSessionEvent.Finished(VoiceSessionResult.Failed(reason, "scripted failure", emptyList())))
    }

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTestControllerTest {
    @TempDir
    lateinit var dir: File

    private val ledgerFile get() = File(dir, "m3a/reservations.jsonl")

    private inner class Rig(
        scope: CoroutineScope,
        var script: suspend FlowCollector<VoiceSessionEvent>.(FakeSession) -> Unit = untilEnded,
        val ledger: ReservationLedger = ReservationLedger(ledgerFile),
    ) {
        val steps = CopyOnWriteArrayList<String>()
        val sessions = CopyOnWriteArrayList<FakeSession>()
        var gate =
            GateInputs(
                consentAccepted = true,
                keySaved = true,
                microphoneGranted = true,
                networkValidated = true,
                checkInCallInProgress = false,
                attemptsUsed = 0,
            )
        var preparation: ((String) -> Preparation)? = null
        var callbacks: AudioCallbacks? = null
        val attemptsAtPrepare = CopyOnWriteArrayList<Int>()
        private val now = AtomicLong(1_790_000_000_000)

        val controller =
            LiveTestController(
                scope = scope,
                inputs = {
                    steps += "gate"
                    gate.copy(attemptsUsed = ledger.attemptsUsed())
                },
                ledger = ledger,
                sessions = { sessionId, audio ->
                    steps += "prepare"
                    attemptsAtPrepare += ledger.attemptsUsed()
                    callbacks = audio
                    preparation?.invoke(sessionId) ?: Preparation.Ready(FakeSession(ledger, steps, script).also { sessions += it })
                },
                clock = { now.incrementAndGet() },
            )

        val state get() = controller.state.value
        val session get() = sessions.last()
    }

    private fun TestScope.rig(script: suspend FlowCollector<VoiceSessionEvent>.(FakeSession) -> Unit = untilEnded) =
        Rig(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), script)

    @Test
    fun `an allowed start charges one attempt before the socket opens`() =
        runTest {
            val rig = rig()

            rig.controller.start()

            assertEquals(listOf("gate", "prepare", "open"), rig.steps)
            assertEquals(listOf(0), rig.attemptsAtPrepare, "preparing charges nothing")
            assertEquals(1, rig.session.attemptsAtOpen, "the charge is on disk before the socket opens")
            assertTrue(rig.state.running)
            assertEquals(VoiceSessionState.ACTIVE, rig.state.sessionState)
            assertEquals(1, rig.state.attemptsUsed)
            assertEquals(LiveUsage(), rig.state.usage)
        }

    @Test
    fun `the session id is the synthetic manual one`() =
        runTest {
            val rig = rig()
            val ids = mutableListOf<String>()
            rig.preparation = { id ->
                ids += id
                Preparation.Ready(FakeSession(rig.ledger, rig.steps, untilEnded).also { rig.sessions += it })
            }

            rig.controller.start()

            assertTrue(ids.single().matches(Regex("m3a-manual-\\d+")))
            assertTrue(ledgerFile.readText().contains(ids.single()))
        }

    @Test
    fun `every gate refusal is shown and charges nothing, prepares nothing and opens nothing`() =
        runTest {
            val refusals =
                mapOf<StartRefusal, (GateInputs) -> GateInputs>(
                    StartRefusal.CONSENT_NOT_GIVEN to { it.copy(consentAccepted = false) },
                    StartRefusal.NO_KEY to { it.copy(keySaved = false) },
                    StartRefusal.MICROPHONE_NOT_GRANTED to { it.copy(microphoneGranted = false) },
                    StartRefusal.NO_VALIDATED_NETWORK to { it.copy(networkValidated = false) },
                    StartRefusal.CHECK_IN_CALL_IN_PROGRESS to { it.copy(checkInCallInProgress = true) },
                )
            for ((reason, change) in refusals) {
                val rig = rig()
                rig.gate = change(rig.gate)

                rig.controller.start()

                assertEquals(reason, rig.state.refusal)
                assertEquals(listOf("gate"), rig.steps, "$reason must stop at the gate")
                assertFalse(rig.state.running)
                assertFalse(ledgerFile.exists(), "$reason must charge nothing")
            }
        }

    @Test
    fun `a key that cannot be opened is refused after the gate and charges nothing`() =
        runTest {
            val rig = rig()
            rig.preparation = { Preparation.Refused(StartRefusal.KEY_UNREADABLE) }

            rig.controller.start()

            assertEquals(StartRefusal.KEY_UNREADABLE, rig.state.refusal)
            assertEquals(0, rig.ledger.attemptsUsed())
            assertFalse(rig.state.running)
        }

    @Test
    fun `a session that cannot be prepared charges nothing`() =
        runTest {
            val rig = rig()
            rig.preparation = { throw IllegalStateException("no audio service") }

            rig.controller.start()

            assertEquals(StartRefusal.PREPARATION_FAILED, rig.state.refusal)
            assertEquals(0, rig.ledger.attemptsUsed())
        }

    @Test
    fun `a ledger that cannot be written opens nothing`() =
        runTest {
            ledgerFile.mkdirs()
            val rig = rig()

            rig.controller.start()

            assertEquals(StartRefusal.LEDGER_UNWRITABLE, rig.state.refusal)
            assertTrue(rig.sessions.none { it.opened })
            assertFalse(rig.state.running)
        }

    @Test
    fun `End ends the session as ended by the user, keeps the transcript, and refunds nothing`() =
        runTest {
            val rig = rig()
            rig.controller.start()

            rig.controller.endByUser()
            rig.controller.endByUser()

            val result = assertIs<VoiceSessionResult.Cancelled>(rig.state.result)
            assertEquals(CancellationReason.ENDED_BY_USER, result.reason)
            // The repeat press came after the session had ended, so it reached nothing.
            assertEquals(listOf(CancellationReason.ENDED_BY_USER), rig.session.endRequests)
            assertEquals(listOf(USER_LINE), rig.state.transcript)
            assertEquals(VoiceSessionState.ENDED, rig.state.sessionState)
            assertFalse(rig.state.running)
            assertEquals(1, rig.session.cleanedUp)
            assertEquals(1, rig.ledger.attemptsUsed())
        }

    @Test
    fun `leaving the screen ends the session as ended by the system`() =
        runTest {
            val rig = rig()
            rig.controller.start()

            rig.controller.endBySystem()

            assertEquals(CancellationReason.ENDED_BY_SYSTEM, assertIs<VoiceSessionResult.Cancelled>(rig.state.result).reason)
            assertEquals(1, rig.ledger.attemptsUsed())
        }

    @Test
    fun `losing audio focus ends the session as ended by the system`() =
        runTest {
            val rig = rig()
            rig.controller.start()

            rig.callbacks!!.onFocusLost()

            assertEquals(CancellationReason.ENDED_BY_SYSTEM, assertIs<VoiceSessionResult.Cancelled>(rig.state.result).reason)
            assertFalse(rig.state.running)
        }

    @Test
    fun `the audio report is shown`() =
        runTest {
            val rig = rig()
            rig.controller.start()

            rig.callbacks!!.onOpened(AudioRouteReport(speakerRouted = true, echoCancelerAttached = false))

            assertEquals(AudioRouteReport(speakerRouted = true, echoCancelerAttached = false), rig.state.audio)
        }

    @Test
    fun `End and leaving with nothing running do nothing`() =
        runTest {
            val rig = rig()

            rig.controller.endByUser()
            rig.controller.endBySystem()

            assertNull(rig.state.result)
            assertTrue(rig.steps.isEmpty())
        }

    @Test
    fun `every failure keeps its charge and shows its reason`() =
        runTest {
            for (reason in FailureReason.entries) {
                ledgerFile.delete()
                val rig = rig(failsWith(reason))

                rig.controller.start()

                val result = assertIs<VoiceSessionResult.Failed>(rig.state.result)
                assertEquals(reason, result.reason)
                assertFalse(rig.state.running)
                assertEquals(1, rig.ledger.attemptsUsed(), "$reason must not be refunded")
                assertEquals(1, rig.state.attemptsUsed)
            }
        }

    @Test
    fun `a cancelled collector keeps its charge, cleans up, and frees the controller`() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            val rig = Rig(scope)
            rig.controller.start()

            scope.cancel()

            assertEquals(1, rig.session.cleanedUp)
            assertFalse(rig.state.running)
            assertTrue(rig.state.endedWithoutResult)
            assertEquals(1, rig.ledger.attemptsUsed())
        }

    @Test
    fun `a start while a session runs is ignored and charges nothing`() =
        runTest {
            val rig = rig()
            rig.controller.start()

            repeat(5) { rig.controller.start() }

            assertEquals(1, rig.sessions.size)
            assertEquals(1, rig.ledger.attemptsUsed())
            assertEquals(listOf("gate", "prepare", "open"), rig.steps)
        }

    @Test
    fun `after a session ends, the next start charges again`() =
        runTest {
            val rig = rig()
            rig.controller.start()
            rig.controller.endByUser()

            rig.controller.start()

            assertEquals(2, rig.sessions.size)
            assertEquals(2, rig.ledger.attemptsUsed())
            assertNull(rig.state.result, "a new session starts with a clean slate")
        }

    @Test
    fun `eight sessions are allowed and the ninth is refused`() =
        runTest {
            val rig = rig()
            repeat(8) {
                rig.controller.start()
                assertTrue(rig.state.running)
                rig.controller.endByUser()
            }

            rig.controller.start()

            assertEquals(StartRefusal.ALLOWANCE_EXHAUSTED, rig.state.refusal)
            assertEquals(8, rig.sessions.size)
            assertEquals(8, rig.ledger.attemptsUsed())
            assertEquals(8, rig.state.attemptsUsed)
        }

    @Test
    fun `the ledger enforces the allowance even when the gate saw room`() =
        runTest {
            val rig = rig()
            repeat(8) { n -> rig.ledger.reserve("earlier-$n", n.toLong()) }
            // A gate that read a stale count, as a concurrent start could.
            val stale = rig.gate
            val controller =
                LiveTestController(
                    scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
                    inputs = { stale },
                    ledger = rig.ledger,
                    sessions = { _, _ -> Preparation.Ready(FakeSession(rig.ledger, rig.steps, untilEnded).also { rig.sessions += it }) },
                    clock = { 1 },
                )

            controller.start()

            assertEquals(StartRefusal.ALLOWANCE_EXHAUSTED, controller.state.value.refusal)
            assertTrue(rig.sessions.none { it.opened })
            assertEquals(8, rig.ledger.attemptsUsed())
        }

    @Test
    fun `charges survive a process restart`() =
        runTest {
            val first = rig()
            repeat(3) {
                first.controller.start()
                first.controller.endByUser()
            }

            // A new process: new ledger, new controller, the same file.
            val second = Rig(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
            assertEquals(3, second.state.attemptsUsed)
            second.controller.start()
            assertEquals(4, second.ledger.attemptsUsed())
        }

    @Test
    fun `usage reports are shown in the order they arrived`() =
        runTest {
            val rig = rig()
            rig.controller.start()
            val first = report(total = 30)
            val second = report(total = 10)

            rig.session.usageState.value = LiveUsage(reports = listOf(first))
            rig.session.usageState.value = LiveUsage(reports = listOf(first, second), unreadableReports = 1)
            rig.controller.endByUser()

            assertEquals(listOf(first, second), rig.state.usage.reports)
            assertEquals(1, rig.state.usage.unreadableReports)
            val lines = rig.state.usage.summaryLines()
            assertTrue(lines[0].startsWith("#1 ") && lines[0].contains("total=30"))
            assertTrue(lines[1].startsWith("#2 ") && lines[1].contains("total=10"))
        }

    @Test
    fun `concurrent starts from many threads open one session and charge once`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pool = Executors.newFixedThreadPool(16)
        try {
            val rig = Rig(scope)
            val go = CountDownLatch(1)
            val presses = (1..16).map { pool.submit { go.await().also { rig.controller.start() } } }
            go.countDown()
            presses.forEach { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, rig.sessions.size)
            assertEquals(1, rig.ledger.attemptsUsed())

            rig.controller.endByUser()
            runBlocking { withTimeout(5.seconds) { rig.controller.state.first { !it.running } } }
            assertEquals(1, rig.ledger.attemptsUsed())
        } finally {
            pool.shutdownNow()
            scope.cancel()
        }
    }

    private fun report(total: Long) =
        UsageReport(
            promptTokenCount = null,
            cachedContentTokenCount = null,
            responseTokenCount = null,
            toolUsePromptTokenCount = null,
            thoughtsTokenCount = null,
            totalTokenCount = total,
            promptTokensDetails = emptyList(),
            cacheTokensDetails = emptyList(),
            responseTokensDetails = emptyList(),
            toolUsePromptTokensDetails = emptyList(),
            invalidFields = emptyList(),
        )
}
