package com.thalia.voicecheckins.voice.fake

import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.voice.CancellationReason
import com.thalia.voicecheckins.voice.FailureReason
import com.thalia.voicecheckins.voice.Speaker
import com.thalia.voicecheckins.voice.TranscriptLine
import com.thalia.voicecheckins.voice.VoiceSessionClient
import com.thalia.voicecheckins.voice.VoiceSessionConfig
import com.thalia.voicecheckins.voice.VoiceSessionEvent
import com.thalia.voicecheckins.voice.VoiceSessionHandle
import com.thalia.voicecheckins.voice.VoiceSessionResult
import com.thalia.voicecheckins.voice.VoiceSessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * A [VoiceSessionClient] that plays a scripted conversation from memory.
 *
 * No network, no API key, no microphone, no randomness. The same inputs always produce
 * the same event sequence, which is what makes it usable both as the subject of unit
 * tests and as the backing for a Compose preview or an on-device walkthrough of the
 * call UI before any real provider exists.
 *
 * Timing is injected rather than built in. With the default [lineDelay] of zero the
 * whole script plays without waiting, which is what unit tests and previews want. A
 * caller that wants a conversation a person can watch, such as the M1 on-device build,
 * passes a positive delay and each line then arrives that long after the previous one.
 * Under a coroutine test scheduler the delay is virtual, so a paced session still costs
 * no real time in a test.
 *
 * It deliberately implements the *whole* contract, including the state machine and the
 * three result shapes, so that M1 can build the entire call experience against it and
 * M3 can swap in Gemini without the UI noticing.
 *
 * @param lineDelay how long to wait before each scripted line. An End Call that arrives
 *   during the wait is honoured at once rather than after the wait.
 */
class FakeVoiceSessionClient(
    private val behaviour: Behaviour = Behaviour.CompletesNormally,
    private val script: (CheckInType) -> List<TranscriptLine> = ::defaultScriptFor,
    private val lineDelay: Duration = Duration.ZERO,
) : VoiceSessionClient {
    /** What this fake should pretend happens. */
    sealed interface Behaviour {
        /** Play the script through and finish as [VoiceSessionResult.Completed]. */
        data object CompletesNormally : Behaviour

        /** Never reach [VoiceSessionState.ACTIVE]; finish as [VoiceSessionResult.Failed]. */
        data class FailsWhileConnecting(
            val reason: FailureReason = FailureReason.PROVIDER_UNAVAILABLE,
            val message: String = "Fake provider was configured to fail while connecting",
        ) : Behaviour
    }

    override fun start(config: VoiceSessionConfig): VoiceSessionHandle = FakeHandle(config)

    private inner class FakeHandle(
        private val config: VoiceSessionConfig,
    ) : VoiceSessionHandle {
        /** Set at most once. First writer wins, so repeat End Call taps are ignored. */
        private val endRequest = AtomicReference<CancellationReason?>(null)

        /** Completed alongside [endRequest], so a paced wait can end the moment End Call lands. */
        private val endSignal = CompletableDeferred<Unit>()

        override val events: Flow<VoiceSessionEvent> = flow { runSession() }

        override fun endSession(reason: CancellationReason) {
            if (endRequest.compareAndSet(null, reason)) endSignal.complete(Unit)
        }

        /**
         * Wait before the next line, unless the call is ended first.
         *
         * The yield gives the collector a chance to run, so an End Call issued from inside
         * the collector is observed on the very next line rather than after the whole
         * script has played. The timed wait, when there is one, is what spaces the lines.
         */
        private suspend fun pause() {
            yield()
            if (lineDelay.isPositive()) withTimeoutOrNull(lineDelay) { endSignal.await() }
        }

        private suspend fun FlowCollector<VoiceSessionEvent>.runSession() {
            var state = VoiceSessionState.IDLE
            val transcript = mutableListOf<TranscriptLine>()

            suspend fun moveTo(next: VoiceSessionState) {
                check(state.canTransitionTo(next)) {
                    "fake produced an illegal transition: $state -> $next"
                }
                emit(VoiceSessionEvent.StateChanged(state, next))
                state = next
            }

            suspend fun settle(result: VoiceSessionResult) {
                if (state != VoiceSessionState.ENDING) moveTo(VoiceSessionState.ENDING)
                moveTo(VoiceSessionState.ENDED)
                emit(VoiceSessionEvent.Finished(result))
            }

            moveTo(VoiceSessionState.CONNECTING)

            val failure = behaviour as? Behaviour.FailsWhileConnecting
            if (failure != null) {
                settle(
                    VoiceSessionResult.Failed(
                        reason = failure.reason,
                        message = failure.message,
                        transcript = transcript.toList(),
                    ),
                )
                return
            }

            // An End Call that lands before the conversation opens still hangs up.
            endRequest.get()?.let { reason ->
                settle(VoiceSessionResult.Cancelled(reason, transcript.toList()))
                return
            }

            moveTo(VoiceSessionState.ACTIVE)

            for (line in script(config.checkInType)) {
                pause()
                endRequest.get()?.let { reason ->
                    settle(VoiceSessionResult.Cancelled(reason, transcript.toList()))
                    return
                }
                transcript += line
                emit(VoiceSessionEvent.TranscriptAppended(line))
            }

            yield()
            endRequest.get()?.let { reason ->
                settle(VoiceSessionResult.Cancelled(reason, transcript.toList()))
                return
            }

            settle(VoiceSessionResult.Completed(transcript.toList()))
        }
    }
}

/**
 * The stand-in conversation, one script per check-in type.
 *
 * Kept short and boring on purpose. It is here to exercise the plumbing, not to
 * pre-empt what Thalia should actually say once a real model is answering.
 */
fun defaultScriptFor(type: CheckInType): List<TranscriptLine> =
    when (type) {
        CheckInType.OPENING ->
            listOf(
                TranscriptLine(Speaker.THALIA, "Good morning. What are the few things that matter most today?"),
                TranscriptLine(Speaker.USER, "Finish the voice check-in foundation, then review the PRD."),
                TranscriptLine(Speaker.THALIA, "Anything already at risk of slipping?"),
                TranscriptLine(Speaker.USER, "The review, if the build takes longer than I expect."),
                TranscriptLine(Speaker.THALIA, "Understood. I will ask about it this afternoon."),
            )

        CheckInType.CLOSING ->
            listOf(
                TranscriptLine(Speaker.THALIA, "Afternoon. How did this morning's plan hold up?"),
                TranscriptLine(Speaker.USER, "The foundation is done. The review has not started."),
                TranscriptLine(Speaker.THALIA, "Do you want to move the review, or keep it for tonight?"),
                TranscriptLine(Speaker.USER, "Keep it for tonight."),
                TranscriptLine(Speaker.THALIA, "Noted. I will start there tomorrow morning."),
            )
    }
