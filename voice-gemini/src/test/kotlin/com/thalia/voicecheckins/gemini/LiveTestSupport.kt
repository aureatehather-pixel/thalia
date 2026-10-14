package com.thalia.voicecheckins.gemini

import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.voice.TranscriptLine
import com.thalia.voicecheckins.voice.VoiceSessionConfig
import com.thalia.voicecheckins.voice.VoiceSessionEvent
import com.thalia.voicecheckins.voice.VoiceSessionHandle
import com.thalia.voicecheckins.voice.VoiceSessionResult
import com.thalia.voicecheckins.voice.VoiceSessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A key shaped like a real one, so every leak check has something distinctive to look for. */
internal const val SECRET = "AIzaTestOnly-Secret_123/+="

internal val WAIT = 5.seconds

internal val manualConfig =
    VoiceSessionConfig(
        occurrenceId = "m3a-manual-1790800000000",
        checkInType = CheckInType.OPENING,
        contextNotes = listOf("Synthetic test note."),
    )

/**
 * One test's world: a local server standing in for Gemini, fake audio, the client's log lines,
 * and a scope for collecting sessions. Closed at the end of every test, so no thread outlives it.
 */
internal class LiveHarness : AutoCloseable {
    val gemini = FakeGemini()
    val audio = FakeAudioIo()
    val logs = CopyOnWriteArrayList<String>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()

    fun client(
        setupTimeout: Duration = WAIT,
        endpoint: HttpUrl = gemini.endpoint,
    ) = GeminiLiveVoiceSessionClient(
        apiKey = GeminiApiKey(SECRET),
        audioIo = audio,
        systemInstruction = "You are Thalia, testing a voice connection.",
        openingPrompt = "Say hello.",
        model = GeminiLiveVoiceSessionClient.DEFAULT_MODEL,
        log = { logs += it },
        httpClient = http,
        endpoint = endpoint,
        setupTimeout = setupTimeout,
        dispatcher = Dispatchers.IO,
    )

    fun record(handle: VoiceSessionHandle) = Recorder(handle, scope)

    override fun close() {
        scope.cancel()
        gemini.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}

internal fun liveTest(block: suspend LiveHarness.() -> Unit) =
    runBlocking {
        LiveHarness().use { harness -> withTimeout(30.seconds) { harness.block() } }
    }

/** Collects one session's events, and lets a test wait for the ones it cares about. */
internal class Recorder(
    handle: VoiceSessionHandle,
    scope: CoroutineScope,
) {
    val events = CopyOnWriteArrayList<VoiceSessionEvent>()
    private val incoming = Channel<VoiceSessionEvent>(Channel.UNLIMITED)
    val job =
        scope.launch {
            handle.events.collect {
                events += it
                incoming.send(it)
            }
        }

    suspend fun awaitState(state: VoiceSessionState) {
        withTimeout(WAIT) {
            while (true) {
                val event = incoming.receive()
                if (event is VoiceSessionEvent.StateChanged && event.to == state) return@withTimeout
            }
        }
    }

    suspend fun awaitLine(): TranscriptLine =
        withTimeout(WAIT) {
            var line: TranscriptLine? = null
            while (line == null) line = (incoming.receive() as? VoiceSessionEvent.TranscriptAppended)?.line
            line
        }

    suspend fun finished(): VoiceSessionResult =
        withTimeout(WAIT) {
            var result: VoiceSessionResult? = null
            while (result == null) result = (incoming.receive() as? VoiceSessionEvent.Finished)?.result
            result
        }

    fun states(): List<VoiceSessionState> = events.filterIsInstance<VoiceSessionEvent.StateChanged>().map { it.to }
}

/** A local WebSocket server that plays Gemini's side of the protocol, one connection at a time. */
internal class FakeGemini : AutoCloseable {
    val server = MockWebServer().apply { start() }

    val endpoint: HttpUrl = server.url("/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")

    /** The next connection is accepted and upgraded. */
    fun acceptSession(): GeminiConnection = GeminiConnection().also { server.enqueue(MockResponse.Builder().webSocketUpgrade(it).build()) }

    /** The next connection is refused with [code], and a body that echoes the key, which must never surface. */
    fun refuse(code: Int) {
        val response =
            MockResponse
                .Builder()
                .code(code)
                .body("""{"error":"API key $SECRET not valid"}""")
                .build()
        server.enqueue(response)
    }

    /**
     * Severs every connection without a close frame, as a lost network would. A server-side
     * WebSocket in MockWebServer cannot be cancelled on its own, so the whole server is shut down.
     * Closing it again later is a no-op.
     */
    fun dropConnections() = server.close()

    override fun close() = server.close()
}

/** Gemini's end of one WebSocket. */
internal class GeminiConnection : WebSocketListener() {
    private val socket = CompletableDeferred<WebSocket>()
    private val received = Channel<JsonObject>(Channel.UNLIMITED)

    /** How the connection ended from the server's side: "closed <code>" or "failed". */
    val ended = CompletableDeferred<String>()

    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {
        socket.complete(webSocket)
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) {
        received.trySend(Json.parseToJsonElement(text).jsonObject)
    }

    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        ended.complete("closed $code")
        webSocket.close(code, null)
    }

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        ended.complete("failed")
    }

    suspend fun next(): JsonObject = withTimeout(WAIT) { received.receive() }

    suspend fun send(json: String) {
        socket.await().send(json)
    }

    suspend fun confirmSetup() = send("""{"setupComplete":{}}""")

    suspend fun close(
        code: Int,
        reason: String,
    ) = socket.await().close(code, reason)

    suspend fun awaitEnded(): String = withTimeout(WAIT) { ended.await() }
}

/** A `serverContent` message, with only the fields given. */
internal fun serverContent(
    audio: ByteArray? = null,
    input: String? = null,
    output: String? = null,
    interrupted: Boolean = false,
    turnComplete: Boolean = false,
    usageTotal: Long? = null,
): String =
    buildJsonObject {
        putJsonObject("serverContent") {
            if (audio != null) {
                putJsonObject("modelTurn") {
                    putJsonArray("parts") {
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", "audio/pcm;rate=24000")
                                put("data", Base64.getEncoder().encodeToString(audio))
                            }
                        }
                    }
                }
            }
            if (input != null) putJsonObject("inputTranscription") { put("text", input) }
            if (output != null) putJsonObject("outputTranscription") { put("text", output) }
            if (interrupted) put("interrupted", true)
            if (turnComplete) put("turnComplete", true)
        }
        if (usageTotal != null) putJsonObject("usageMetadata") { put("totalTokenCount", usageTotal) }
    }.toString()

internal class FakeAudioIo : AudioIo {
    @Volatile var openFailure: Exception? = null
    val openAttempts = AtomicInteger()
    val sessions = CopyOnWriteArrayList<FakeAudioSession>()

    override fun open(): AudioSession {
        openAttempts.incrementAndGet()
        openFailure?.let { throw it }
        return FakeAudioSession().also { sessions += it }
    }
}

internal class FakeAudioSession : AudioSession {
    /** What the microphone hears. Closing it with a cause makes capture fail. */
    val microphone = Channel<ByteArray>(Channel.UNLIMITED)
    val played = Channel<ByteArray>(Channel.UNLIMITED)
    val flushed = Channel<Unit>(Channel.UNLIMITED)
    val releases = AtomicInteger()
    val released = CompletableDeferred<Unit>()

    /** "capture stopped" and "released", in the order they happened. */
    val lifecycle = CopyOnWriteArrayList<String>()

    override val capture: Flow<ByteArray> =
        microphone.receiveAsFlow().onCompletion { lifecycle += "capture stopped" }

    override fun play(pcm: ByteArray) {
        played.trySend(pcm)
    }

    override fun flushPlayback() {
        flushed.trySend(Unit)
    }

    override fun release() {
        lifecycle += "released"
        releases.incrementAndGet()
        released.complete(Unit)
    }

    suspend fun awaitReleased() = withTimeout(WAIT) { released.await() }
}
