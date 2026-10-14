package com.thalia.voicecheckins.gemini

import com.thalia.voicecheckins.voice.CancellationReason
import com.thalia.voicecheckins.voice.FailureReason
import com.thalia.voicecheckins.voice.TranscriptLine
import com.thalia.voicecheckins.voice.VoiceSessionClient
import com.thalia.voicecheckins.voice.VoiceSessionConfig
import com.thalia.voicecheckins.voice.VoiceSessionEvent
import com.thalia.voicecheckins.voice.VoiceSessionHandle
import com.thalia.voicecheckins.voice.VoiceSessionResult
import com.thalia.voicecheckins.voice.VoiceSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A Gemini Live session handle, which also exposes what Gemini reported about usage. */
interface GeminiLiveSessionHandle : VoiceSessionHandle {
    /** Usage as Gemini reported it for this session, unsummed. See [LiveUsage]. */
    val usage: StateFlow<LiveUsage>
}

/**
 * A [VoiceSessionClient] that holds a live, two-way voice conversation with Gemini.
 *
 * It speaks the documented Live API protocol over one WebSocket per session, as ADR 0008
 * decision 2 sets out for the M3A spike, and it reaches the microphone and speaker only through
 * [AudioIo]. Nothing above [VoiceSessionClient] knows it exists.
 *
 * One session at a time per client: a session collected while another is running ends at once as
 * failed, without opening a socket or the microphone. Every way a session ends that the process
 * survives, including the collector being cancelled, goes through one cleanup path that stops
 * capture, releases the audio, closes the socket and frees the client for the next session.
 *
 * Turn-taking is Gemini's own voice activity detection. When the server reports that the user
 * interrupted, queued playback is flushed; the client adds no detection of its own.
 *
 * The API key is sent as the `key` query parameter, as Gemini's WebSocket guide documents. It is
 * never written to a log, an event or a result: all of those pass through [SecretRedactor].
 * ADR 0008 decision 4 permits a standard key on the phone for this private spike only.
 */
class GeminiLiveVoiceSessionClient internal constructor(
    private val apiKey: GeminiApiKey,
    private val audioIo: AudioIo,
    private val systemInstruction: String,
    private val openingPrompt: String,
    private val model: String,
    private val log: (String) -> Unit,
    private val httpClient: OkHttpClient,
    private val endpoint: HttpUrl,
    private val setupTimeout: Duration,
    private val dispatcher: CoroutineDispatcher,
) : VoiceSessionClient {
    /**
     * @param systemInstruction what the model is told about the session. The config's context
     *   notes follow it, one paragraph each.
     * @param openingPrompt the text turn sent once the session is confirmed, so the model speaks
     *   first and the time to its first audio can be measured.
     * @param log receives one line per notable step, already redacted. Never audio or payloads.
     */
    constructor(
        apiKey: GeminiApiKey,
        audioIo: AudioIo,
        systemInstruction: String,
        openingPrompt: String,
        model: String = DEFAULT_MODEL,
        log: (String) -> Unit = {},
    ) : this(
        apiKey = apiKey,
        audioIo = audioIo,
        systemInstruction = systemInstruction,
        openingPrompt = openingPrompt,
        model = model,
        log = log,
        httpClient = defaultHttpClient(),
        endpoint = DEFAULT_ENDPOINT.toHttpUrl(),
        setupTimeout = DEFAULT_SETUP_TIMEOUT,
        dispatcher = Dispatchers.IO,
    )

    init {
        require(systemInstruction.isNotBlank()) { "systemInstruction must not be blank" }
        require(openingPrompt.isNotBlank()) { "openingPrompt must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
    }

    private val redactor = SecretRedactor(apiKey.value)

    /** Held from the moment a session starts connecting until its cleanup is done. */
    private val busy = AtomicBoolean(false)

    override fun start(config: VoiceSessionConfig): GeminiLiveSessionHandle = Session(config)

    private fun say(message: String) {
        try {
            log(redactor.redact(message))
        } catch (e: Exception) {
            // A failing logger must never end a conversation.
        }
    }

    /** An exception as text a person can read, with the key removed. */
    private fun describe(error: Throwable?): String {
        if (error == null) return "no further detail"
        val message = error.message?.let { ": ${redactor.redact(it)}" }.orEmpty()
        return "${error.javaClass.simpleName}$message"
    }

    private fun request(): Request =
        Request
            .Builder()
            .url(endpoint.newBuilder().setQueryParameter("key", apiKey.value).build())
            .build()

    private inner class Session(
        private val config: VoiceSessionConfig,
    ) : GeminiLiveSessionHandle {
        private val signals = Channel<LiveSignal>(Channel.UNLIMITED)
        private val endRequest = AtomicReference<CancellationReason?>(null)
        private val collected = AtomicBoolean(false)
        private val usageState = MutableStateFlow(LiveUsage())

        override val usage: StateFlow<LiveUsage> = usageState.asStateFlow()

        override val events: Flow<VoiceSessionEvent> =
            flow {
                check(collected.compareAndSet(false, true)) { "a session's events can be collected only once" }
                run()
            }.flowOn(dispatcher)

        override fun endSession(reason: CancellationReason) {
            if (endRequest.compareAndSet(null, reason)) signals.trySend(LiveSignal.End(reason))
        }

        override fun toString(): String = "GeminiLiveSession(${config.occurrenceId})"

        private suspend fun FlowCollector<VoiceSessionEvent>.run() {
            // Ended before it started: nothing is opened.
            endRequest.get()?.let { reason ->
                emit(VoiceSessionEvent.Finished(VoiceSessionResult.Cancelled(reason, emptyList())))
                return
            }
            if (!busy.compareAndSet(false, true)) {
                say("refused a session while another is running")
                emit(
                    VoiceSessionEvent.Finished(
                        VoiceSessionResult.Failed(
                            reason = FailureReason.UNKNOWN,
                            message = "Another Gemini Live session is already running.",
                            transcript = emptyList(),
                        ),
                    ),
                )
                return
            }
            val live = LiveRun(this)
            try {
                live.execute()
            } finally {
                // Reached on every exit, including cancellation of the collector, which emits nothing.
                withContext(NonCancellable) { live.releaseAll() }
            }
        }

        /** One connected session. Its methods run only on the flow's own coroutine, one at a time. */
        private inner class LiveRun(
            private val out: FlowCollector<VoiceSessionEvent>,
        ) {
            private var state = VoiceSessionState.IDLE
            private var socket: WebSocket? = null
            private var connected = false
            private var audio: AudioSession? = null
            private var capture: Job? = null
            private val timers = mutableListOf<Job>()
            private var holdsClient = true
            private val transcript = TranscriptAssembler()

            suspend fun execute() =
                coroutineScope {
                    moveTo(VoiceSessionState.CONNECTING)
                    timers +=
                        launch {
                            delay(config.maxDuration)
                            signals.trySend(LiveSignal.TimedOut)
                        }
                    timers +=
                        launch {
                            delay(setupTimeout)
                            signals.trySend(LiveSignal.SetupTimedOut)
                        }
                    say("connecting to ${endpoint.host} with model $model")
                    val webSocket = httpClient.newWebSocket(request(), LiveSocketListener(signals))
                    socket = webSocket
                    // OkHttp holds this until the handshake completes. Nothing else is sent until
                    // Gemini confirms the setup, as the reference requires.
                    val instruction = listOf(systemInstruction) + config.contextNotes.filter { it.isNotBlank() }
                    webSocket.send(LiveProtocol.setup(model, instruction))

                    var outcome: Outcome? = null
                    while (outcome == null) outcome = handle(signals.receive(), this)
                    settle(outcome)
                }

            private suspend fun handle(
                signal: LiveSignal,
                scope: CoroutineScope,
            ): Outcome? =
                when (signal) {
                    LiveSignal.Opened -> {
                        connected = true
                        say("connected")
                        null
                    }
                    is LiveSignal.Message -> onMessage(LiveProtocol.decode(signal.text), scope)
                    is LiveSignal.Failure -> failure(signal)
                    is LiveSignal.Closing -> {
                        val reason = redactor.redact(signal.reason).ifBlank { "no reason given" }
                        Outcome.Fail(FailureReason.PROVIDER_UNAVAILABLE, "Gemini closed the session (code ${signal.code}: $reason).")
                    }
                    is LiveSignal.End -> Outcome.Cancel(signal.reason)
                    LiveSignal.TimedOut -> Outcome.Cancel(CancellationReason.TIMED_OUT)
                    LiveSignal.SetupTimedOut ->
                        if (state == VoiceSessionState.CONNECTING) {
                            Outcome.Fail(FailureReason.PROVIDER_UNAVAILABLE, "Gemini did not confirm the session within $setupTimeout.")
                        } else {
                            null
                        }
                    is LiveSignal.AudioFailed ->
                        if (state == VoiceSessionState.ACTIVE) {
                            Outcome.Fail(FailureReason.AUDIO_UNAVAILABLE, "The microphone stopped: ${describe(signal.error)}")
                        } else {
                            null
                        }
                }

            private suspend fun onMessage(
                message: ServerMessage,
                scope: CoroutineScope,
            ): Outcome? {
                record(message.usage)
                return when (val body = message.body) {
                    ServerBody.SetupComplete -> if (state == VoiceSessionState.CONNECTING) begin(scope) else null
                    is ServerBody.Content -> if (state == VoiceSessionState.ACTIVE) play(body) else null
                    is ServerBody.GoAway -> {
                        val left = body.timeLeft?.let { " in ${redactor.redact(it)}" }.orEmpty()
                        Outcome.Fail(FailureReason.PROVIDER_UNAVAILABLE, "Gemini announced it will close the session$left.")
                    }
                    ServerBody.Ignored -> null
                    ServerBody.Unreadable -> {
                        say("ignored an unreadable server message")
                        null
                    }
                }
            }

            /** Gemini confirmed the session: open the audio, start capture, and ask for a greeting. */
            private suspend fun begin(scope: CoroutineScope): Outcome? {
                val opened =
                    try {
                        audioIo.open()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return Outcome.Fail(FailureReason.AUDIO_UNAVAILABLE, "Could not open the microphone or playback: ${describe(e)}")
                    }
                audio = opened
                val webSocket = checkNotNull(socket)
                capture =
                    scope.launch {
                        try {
                            opened.capture.collect { pcm ->
                                if (webSocket.send(LiveProtocol.realtimeAudio(pcm))) {
                                    usageState.update { it.copy(audioBytesSent = it.audioBytesSent + pcm.size) }
                                }
                            }
                            signals.trySend(LiveSignal.AudioFailed(null))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            signals.trySend(LiveSignal.AudioFailed(e))
                        }
                    }
                webSocket.send(LiveProtocol.userText(openingPrompt))
                moveTo(VoiceSessionState.ACTIVE)
                say("session active")
                return null
            }

            private suspend fun play(content: ServerBody.Content): Outcome? {
                val speaker = audio ?: return null
                // The transcript first, so a playback failure below cannot lose what was said.
                emitLines(
                    transcript.accept(
                        input = content.inputTranscription,
                        output = content.outputTranscription,
                        modelSpoke = content.audio.isNotEmpty(),
                        turnEnded = content.interrupted || content.turnComplete,
                    ),
                )
                if (content.unreadableAudioParts > 0) say("skipped ${content.unreadableAudioParts} unplayable audio part(s)")
                try {
                    for (pcm in content.audio) {
                        speaker.play(pcm)
                        usageState.update { it.copy(audioBytesReceived = it.audioBytesReceived + pcm.size) }
                    }
                    if (content.interrupted) speaker.flushPlayback()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Outcome.Fail(FailureReason.AUDIO_UNAVAILABLE, "Playback failed: ${describe(e)}")
                }
                return null
            }

            private fun record(usage: UsageField) {
                when (usage) {
                    UsageField.Absent -> Unit
                    UsageField.Unreadable -> usageState.update { it.copy(unreadableReports = it.unreadableReports + 1) }
                    is UsageField.Report -> usageState.update { it.copy(reports = it.reports + usage.report) }
                }
            }

            private fun failure(signal: LiveSignal.Failure): Outcome {
                val code = signal.httpCode
                return when {
                    code == 401 || code == 403 ->
                        Outcome.Fail(FailureReason.NOT_AUTHENTICATED, "Gemini refused the API key (HTTP $code).")
                    code != null ->
                        Outcome.Fail(FailureReason.PROVIDER_UNAVAILABLE, "Gemini refused the connection (HTTP $code).")
                    signal.error is IOException && connected ->
                        Outcome.Fail(FailureReason.NO_NETWORK, "The connection to Gemini was lost: ${describe(signal.error)}")
                    signal.error is IOException ->
                        Outcome.Fail(FailureReason.NO_NETWORK, "Could not reach Gemini: ${describe(signal.error)}")
                    else ->
                        Outcome.Fail(FailureReason.UNKNOWN, "The connection to Gemini failed: ${describe(signal.error)}")
                }
            }

            private suspend fun settle(outcome: Outcome) {
                moveTo(VoiceSessionState.ENDING)
                stopCapture()
                emitLines(transcript.finish())
                releaseAll()
                val lines = transcript.lines
                val result =
                    when (outcome) {
                        is Outcome.Cancel -> VoiceSessionResult.Cancelled(outcome.reason, lines)
                        is Outcome.Fail -> VoiceSessionResult.Failed(outcome.reason, outcome.message, lines)
                    }
                say(
                    when (outcome) {
                        is Outcome.Cancel -> "session ended: ${outcome.reason}"
                        is Outcome.Fail -> "session failed: ${outcome.reason}: ${outcome.message}"
                    },
                )
                moveTo(VoiceSessionState.ENDED)
                out.emit(VoiceSessionEvent.Finished(result))
            }

            private suspend fun stopCapture() {
                val job = capture ?: return
                capture = null
                job.cancel()
                // Capture must respond to cancellation. If it does not, the audio is released anyway.
                withTimeoutOrNull(CAPTURE_STOP_TIMEOUT) { job.join() } ?: say("capture did not stop within $CAPTURE_STOP_TIMEOUT")
            }

            /** The one cleanup path. Safe to run more than once. */
            suspend fun releaseAll() {
                stopCapture()
                timers.forEach { it.cancel() }
                timers.clear()
                audio?.let { opened ->
                    audio = null
                    try {
                        opened.release()
                    } catch (e: Exception) {
                        say("releasing the audio failed: ${describe(e)}")
                    }
                }
                socket?.let { webSocket ->
                    socket = null
                    if (!webSocket.close(NORMAL_CLOSURE, "session ended")) webSocket.cancel()
                }
                if (holdsClient) {
                    holdsClient = false
                    busy.set(false)
                }
            }

            private suspend fun emitLines(lines: List<TranscriptLine>) {
                for (line in lines) out.emit(VoiceSessionEvent.TranscriptAppended(line))
            }

            private suspend fun moveTo(next: VoiceSessionState) {
                check(state.canTransitionTo(next)) { "illegal session transition: $state -> $next" }
                out.emit(VoiceSessionEvent.StateChanged(state, next))
                state = next
            }
        }
    }

    private sealed interface Outcome {
        class Cancel(
            val reason: CancellationReason,
        ) : Outcome

        class Fail(
            val reason: FailureReason,
            val message: String,
        ) : Outcome
    }

    companion object {
        /** The stable default Live API model, per Gemini's official model list on 2026-10-02. */
        const val DEFAULT_MODEL = "gemini-3.8-live"

        /**
         * The documented endpoint, `wss://…/BidiGenerateContent`, written as its `https` form
         * because OkHttp addresses a WebSocket by the URL of its upgrade request.
         */
        internal const val DEFAULT_ENDPOINT =
            "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        internal val DEFAULT_SETUP_TIMEOUT = 15.seconds

        private val CAPTURE_STOP_TIMEOUT = 2.seconds

        private const val NORMAL_CLOSURE = 1000

        /** Pings every ten seconds, so a dead network is noticed during a conversation rather than minutes later. */
        internal fun defaultHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .pingInterval(10, TimeUnit.SECONDS)
                .build()
    }
}
