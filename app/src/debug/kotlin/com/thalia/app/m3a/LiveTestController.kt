package com.thalia.app.m3a

import com.thalia.voicecheckins.gemini.GeminiLiveSessionHandle
import com.thalia.voicecheckins.gemini.LiveUsage
import com.thalia.voicecheckins.voice.CancellationReason
import com.thalia.voicecheckins.voice.TranscriptLine
import com.thalia.voicecheckins.voice.VoiceSessionEvent
import com.thalia.voicecheckins.voice.VoiceSessionResult
import com.thalia.voicecheckins.voice.VoiceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Prepares one Gemini session without opening anything: no socket, no microphone. */
fun interface LiveSessionFactory {
    fun prepare(
        sessionId: String,
        callbacks: AudioCallbacks,
    ): Preparation
}

/** What the audio of a running session reports back. */
class AudioCallbacks(
    val onFocusLost: () -> Unit,
    val onOpened: (AudioRouteReport) -> Unit,
)

sealed interface Preparation {
    /** Ready. Collecting [handle]'s events is what opens the socket. */
    class Ready(
        val handle: GeminiLiveSessionHandle,
    ) : Preparation

    data class Refused(
        val reason: StartRefusal,
    ) : Preparation
}

/** What the M3A screen shows. Held in memory only, and gone with the screen. */
data class LiveTestState(
    val running: Boolean = false,
    val sessionState: VoiceSessionState? = null,
    val refusal: StartRefusal? = null,
    val result: VoiceSessionResult? = null,
    /** Set when a session stopped without a result, because its collector was cancelled. */
    val endedWithoutResult: Boolean = false,
    val transcript: List<TranscriptLine> = emptyList(),
    val usage: LiveUsage = LiveUsage(),
    val audio: AudioRouteReport? = null,
    val attemptsUsed: Int = 0,
)

/**
 * Runs the M3A spike's manual Gemini sessions, one at a time.
 *
 * A start goes: the pure [StartGate] on [inputs]; then preparing the session, which reads the key
 * and builds the client but opens nothing; then charging one attempt to the [ledger], durably;
 * and only then collecting the session's events, which is what opens the socket. A charge is never
 * refunded, whatever happens after it. While a start or a session is in progress, every other
 * start is ignored, so repeated or concurrent presses cannot start twice or charge twice.
 */
class LiveTestController(
    private val scope: CoroutineScope,
    private val inputs: () -> GateInputs,
    private val ledger: ReservationLedger,
    private val sessions: LiveSessionFactory,
    private val clock: () -> Long,
) {
    private val busy = AtomicBoolean(false)

    @Volatile
    private var handle: GeminiLiveSessionHandle? = null

    private val _state = MutableStateFlow(LiveTestState(attemptsUsed = ledger.attemptsUsed()))
    val state: StateFlow<LiveTestState> = _state.asStateFlow()

    /** Re-read what the screen shows from the stores, for example on returning to it. Charges nothing. */
    fun refresh() {
        _state.update { it.copy(attemptsUsed = ledger.attemptsUsed()) }
    }

    fun start() {
        if (!busy.compareAndSet(false, true)) return
        var launched = false
        try {
            val decision = StartGate.evaluate(inputs())
            if (decision is GateDecision.Refused) return refuse(decision.reason)

            val sessionId = "$SESSION_ID_PREFIX${clock()}"
            val callbacks =
                AudioCallbacks(
                    onFocusLost = ::endBySystem,
                    onOpened = { report -> _state.update { it.copy(audio = report) } },
                )
            val prepared =
                try {
                    sessions.prepare(sessionId, callbacks)
                } catch (e: Exception) {
                    Preparation.Refused(StartRefusal.PREPARATION_FAILED)
                }
            val session =
                when (prepared) {
                    is Preparation.Refused -> return refuse(prepared.reason)
                    is Preparation.Ready -> prepared.handle
                }

            // The charge is on disk before anything can reach Gemini.
            val reservation =
                try {
                    ledger.reserve(sessionId, clock())
                } catch (e: Exception) {
                    null
                }
            when (reservation) {
                null -> return refuse(StartRefusal.LEDGER_UNWRITABLE)
                ReservationLedger.Reservation.AllowanceExhausted -> return refuse(StartRefusal.ALLOWANCE_EXHAUSTED)
                is ReservationLedger.Reservation.Reserved -> Unit
            }

            handle = session
            _state.value = LiveTestState(running = true, attemptsUsed = ledger.attemptsUsed())
            scope.launch(start = CoroutineStart.UNDISPATCHED) { run(session) }
            launched = true
        } finally {
            if (!launched) {
                handle = null
                busy.set(false)
            }
        }
    }

    /** End pressed. Ignored when nothing is running, and repeat presses are ignored. */
    fun endByUser() {
        handle?.endSession(CancellationReason.ENDED_BY_USER)
    }

    /** The screen was left, the app went to the background, or audio focus was lost. */
    fun endBySystem() {
        handle?.endSession(CancellationReason.ENDED_BY_SYSTEM)
    }

    private fun refuse(reason: StartRefusal) {
        _state.update { it.copy(refusal = reason, attemptsUsed = ledger.attemptsUsed()) }
    }

    private suspend fun run(session: GeminiLiveSessionHandle) {
        try {
            coroutineScope {
                val usage = launch { session.usage.collect { report -> _state.update { it.copy(usage = report) } } }
                session.events.collect(::onEvent)
                usage.cancel()
            }
        } finally {
            _state.update {
                it.copy(
                    running = false,
                    usage = session.usage.value,
                    endedWithoutResult = it.result == null,
                    attemptsUsed = ledger.attemptsUsed(),
                )
            }
            handle = null
            busy.set(false)
        }
    }

    private fun onEvent(event: VoiceSessionEvent) {
        _state.update {
            when (event) {
                is VoiceSessionEvent.StateChanged -> it.copy(sessionState = event.to)
                is VoiceSessionEvent.TranscriptAppended -> it.copy(transcript = it.transcript + event.line)
                is VoiceSessionEvent.Finished -> it.copy(result = event.result, transcript = event.result.transcript)
            }
        }
    }

    companion object {
        /** ADR 0008: a manual session's synthetic occurrence id. Never written to the occurrences table. */
        const val SESSION_ID_PREFIX: String = "m3a-manual-"
    }
}
