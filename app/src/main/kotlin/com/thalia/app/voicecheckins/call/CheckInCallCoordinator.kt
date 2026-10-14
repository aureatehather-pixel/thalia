package com.thalia.app.voicecheckins.call

import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.voice.CancellationReason
import com.thalia.voicecheckins.voice.VoiceSessionClient
import com.thalia.voicecheckins.voice.VoiceSessionConfig
import com.thalia.voicecheckins.voice.VoiceSessionEvent
import com.thalia.voicecheckins.voice.VoiceSessionHandle
import com.thalia.voicecheckins.voice.VoiceSessionResult
import com.thalia.voicecheckins.voice.VoiceSessionState
import com.thalia.voicecheckins.voice.fake.FakeVoiceSessionClient
import com.thalia.voicecheckins.voice.toCheckInStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The single place that decides what a check-in call is doing.
 *
 * Deliberately free of Android types. The service owns the notification and the
 * process lifetime, the activity owns pixels, and both read their truth from here.
 * That split is what lets the whole M1 state machine be unit tested on the JVM with no
 * device, which is most of the value of having written it this way.
 *
 * In M1 the client is always [FakeVoiceSessionClient]. Nothing here knows that.
 *
 * Whoever starts a call says which occurrence it is for, so the same call path serves a
 * button, the debug alarm and, later, the real schedule. The id is carried to the session and
 * reported to the [recorder] at each moment that decides the outcome. This class stores nothing
 * itself, and never waits for the recorder.
 *
 * @param ringTimeout how long an unanswered call rings before it is recorded as missed.
 *   A ringing call must never be able to hold the service and the vibrator forever.
 * @param recorder told what became of the occurrence: answered, declined, missed or finished.
 * @param handoffScheduled Slice 4C. When true, a call for a scheduled occurrence is answered by handing off to the conversation
 *   screen ([answerWithHandoff]) and never starts a session. A call started by a button, which carries
 *   [MANUAL_TRIGGER_OCCURRENCE_ID], keeps the simulated session. This class knows nothing of planning: it cannot open or
 *   close a working day, whatever happens to a call.
 */
class CheckInCallCoordinator(
    private val scope: CoroutineScope,
    private val voiceClient: VoiceSessionClient = FakeVoiceSessionClient(),
    private val ringTimeout: Duration = DEFAULT_RING_TIMEOUT,
    private val recorder: CallOutcomeRecorder = CallOutcomeRecorder.None,
    private val handoffScheduled: Boolean = false,
) {
    private val _state = MutableStateFlow<CallUiState>(CallUiState.Idle)
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private var handle: VoiceSessionHandle? = null
    private var sessionJob: Job? = null
    private var ringTimeoutJob: Job? = null

    /** The occurrence the current call belongs to. Set when the call starts ringing. */
    @Volatile
    private var occurrenceId: String = MANUAL_TRIGGER_OCCURRENCE_ID

    /**
     * The occurrence this process is ringing or talking for right now, or null when it is idle.
     * Read from other threads by the reconciliation, to tell a call that is in progress from one
     * that was lost with its process.
     */
    val activeOccurrenceId: String?
        get() =
            when (_state.value) {
                is CallUiState.Ringing, is CallUiState.Active -> occurrenceId
                is CallUiState.Ended, CallUiState.Idle -> null
            }

    /**
     * Start ringing for [occurrenceId]. Ignored if a check-in is already in progress, in
     * which case the call that is in progress keeps its own id.
     */
    fun ring(
        checkInType: CheckInType,
        occurrenceId: String = MANUAL_TRIGGER_OCCURRENCE_ID,
    ) {
        if (_state.value is CallUiState.Ringing || _state.value is CallUiState.Active) {
            // Another call has the phone. This occurrence cannot ring, and must not be left claiming to.
            recorder.missed(occurrenceId, OccurrenceReason.CALL_IN_PROGRESS)
            return
        }
        this.occurrenceId = occurrenceId
        _state.value = CallUiState.Ringing(checkInType, handoff = handoffScheduled && occurrenceId != MANUAL_TRIGGER_OCCURRENCE_ID)
        ringTimeoutJob =
            scope.launch {
                delay(ringTimeout)
                onRingTimeout()
            }
    }

    /** The user answered. Opens a session and begins streaming its events. */
    fun answer() {
        val ringing = _state.value as? CallUiState.Ringing ?: return
        // A handoff call is answered only through answerWithHandoff, so it can never start a simulated session by accident.
        if (ringing.handoff) return
        cancelRingTimeout()
        recorder.answered(occurrenceId)

        _state.value =
            CallUiState.Active(
                checkInType = ringing.checkInType,
                sessionState = VoiceSessionState.IDLE,
                transcript = emptyList(),
            )

        val session =
            voiceClient.start(
                VoiceSessionConfig(
                    occurrenceId = occurrenceId,
                    checkInType = ringing.checkInType,
                ),
            )
        handle = session

        val sessionOccurrenceId = occurrenceId
        sessionJob =
            scope.launch {
                session.events.collect { event -> onSessionEvent(ringing.checkInType, sessionOccurrenceId, event) }
            }
    }

    /**
     * Slice 4C. The user answered a handoff call: the call is recorded as answered and then completed, and no session is
     * opened. COMPLETED here means only that the call interaction ended. It says nothing about the working day, a plan or any
     * outcome. Returns false, and changes nothing, if the call is no longer ringing as a handoff call (a repeated delivery,
     * a timeout that won the race, a decline).
     */
    fun answerWithHandoff(): Boolean {
        val ringing = _state.value as? CallUiState.Ringing ?: return false
        if (!ringing.handoff) return false
        cancelRingTimeout()
        recorder.answered(occurrenceId)
        recorder.finished(occurrenceId, CheckInStatus.COMPLETED, null)
        _state.value =
            CallUiState.Ended(
                checkInType = ringing.checkInType,
                status = CheckInStatus.COMPLETED,
                summary = HANDOFF_SUMMARY,
                transcript = emptyList(),
            )
        return true
    }

    /** The user declined. No session is ever opened, so there is nothing to tear down. */
    fun decline() {
        val ringing = _state.value as? CallUiState.Ringing ?: return
        cancelRingTimeout()
        recorder.declined(occurrenceId)
        _state.value =
            CallUiState.Ended(
                checkInType = ringing.checkInType,
                status = CheckInStatus.DECLINED,
                summary = "Declined",
                transcript = emptyList(),
            )
    }

    /** The user pressed End Call. The session settles as cancelled and keeps its transcript. */
    fun endCall(reason: CancellationReason = CancellationReason.ENDED_BY_USER) {
        if (_state.value !is CallUiState.Active) return
        handle?.endSession(reason)
    }

    /**
     * The call has lost the thing that was carrying it, so it cannot continue.
     *
     * Called when the foreground service is destroyed while a call is still ringing or
     * active. A ringing call is recorded as missed and an active one is ended by the
     * system, so the state never claims a call is in progress that nothing is running.
     */
    fun interrupt() {
        when (val current = _state.value) {
            is CallUiState.Ringing -> {
                cancelRingTimeout()
                recorder.missed(occurrenceId, OccurrenceReason.INTERRUPTED)
                _state.value = current.missed("The call was interrupted")
            }

            is CallUiState.Active -> handle?.endSession(CancellationReason.ENDED_BY_SYSTEM)
            is CallUiState.Ended, CallUiState.Idle -> Unit
        }
    }

    /** Dismiss a finished call and return to idle. Only valid once the call is over. */
    fun dismiss() {
        if (_state.value !is CallUiState.Ended) return
        clearSession()
        _state.value = CallUiState.Idle
    }

    private fun onRingTimeout() {
        val ringing = _state.value as? CallUiState.Ringing ?: return
        ringTimeoutJob = null
        recorder.missed(occurrenceId, OccurrenceReason.RING_TIMEOUT)
        _state.value = ringing.missed("Missed call")
    }

    private fun cancelRingTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
    }

    private fun onSessionEvent(
        checkInType: CheckInType,
        sessionOccurrenceId: String,
        event: VoiceSessionEvent,
    ) {
        val active = _state.value as? CallUiState.Active ?: return
        _state.value =
            when (event) {
                is VoiceSessionEvent.StateChanged -> active.copy(sessionState = event.to)
                is VoiceSessionEvent.TranscriptAppended ->
                    active.copy(
                        transcript = active.transcript + event.line,
                    )

                is VoiceSessionEvent.Finished ->
                    CallUiState.Ended(
                        checkInType = checkInType,
                        status = event.result.toCheckInStatus(),
                        summary = event.result.summarise(),
                        transcript = event.result.transcript,
                    )
            }
        if (event is VoiceSessionEvent.Finished) {
            recordFinished(sessionOccurrenceId, event.result)
            clearSession()
        }
    }

    /**
     * What the record says about an answered call, which is not always what the screen says.
     * A call the user ended, or that ran out its time limit, is a check-in that happened. One the
     * system cut short is failed, so an interrupted call is not filed as a completed one. The
     * ended screen keeps its own wording for both.
     */
    private fun recordFinished(
        sessionOccurrenceId: String,
        result: VoiceSessionResult,
    ) {
        when {
            result is VoiceSessionResult.Failed ->
                recorder.finished(sessionOccurrenceId, CheckInStatus.FAILED, OccurrenceReason.SESSION_FAILED)

            result is VoiceSessionResult.Cancelled && result.reason == CancellationReason.ENDED_BY_SYSTEM ->
                recorder.finished(sessionOccurrenceId, CheckInStatus.FAILED, OccurrenceReason.INTERRUPTED)

            else -> recorder.finished(sessionOccurrenceId, CheckInStatus.COMPLETED, null)
        }
    }

    private fun clearSession() {
        sessionJob?.cancel()
        sessionJob = null
        handle = null
    }

    companion object {
        /** The id a call carries when a button, not a schedule, started it. */
        const val MANUAL_TRIGGER_OCCURRENCE_ID: String = "dev-trigger"

        /** What the ended call says after a handoff. It claims nothing about the day. */
        const val HANDOFF_SUMMARY: String = "Call handed to the conversation. Your day was not opened or closed."

        /**
         * A real phone rings for roughly half a minute. This is a little longer, so a
         * tester who is slow to notice the call can still answer it.
         */
        val DEFAULT_RING_TIMEOUT: Duration = 45.seconds
    }
}

private fun CallUiState.Ringing.missed(summary: String): CallUiState.Ended =
    CallUiState.Ended(
        checkInType = checkInType,
        status = CheckInStatus.MISSED,
        summary = summary,
        transcript = emptyList(),
    )

/** A short, plain-language line for the ended screen. */
internal fun VoiceSessionResult.summarise(): String =
    when (this) {
        is VoiceSessionResult.Completed -> "Check-in complete"
        is VoiceSessionResult.Cancelled ->
            when (reason) {
                CancellationReason.ENDED_BY_USER -> "You ended the call"
                CancellationReason.ENDED_BY_SYSTEM -> "The call was interrupted"
                CancellationReason.TIMED_OUT -> "The call reached its time limit"
            }

        is VoiceSessionResult.Failed -> "Could not connect: ${reason.name.lowercase().replace('_', ' ')}"
    }
