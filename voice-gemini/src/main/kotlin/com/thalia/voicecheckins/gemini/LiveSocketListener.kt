package com.thalia.voicecheckins.gemini

import com.thalia.voicecheckins.voice.CancellationReason
import kotlinx.coroutines.channels.SendChannel
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Everything that can happen to a session, as one stream.
 *
 * OkHttp's callbacks, the user's End, the timers and the microphone all arrive on their own
 * threads. Each becomes a signal on one channel, and the session acts on them one at a time on
 * its own coroutine, so its state is never touched from two places at once.
 */
internal sealed interface LiveSignal {
    data object Opened : LiveSignal

    class Message(
        val text: String,
    ) : LiveSignal

    class Failure(
        val error: Throwable,
        val httpCode: Int?,
    ) : LiveSignal

    class Closing(
        val code: Int,
        val reason: String,
    ) : LiveSignal

    class End(
        val reason: CancellationReason,
    ) : LiveSignal

    data object TimedOut : LiveSignal

    data object SetupTimedOut : LiveSignal

    class AudioFailed(
        val error: Throwable?,
    ) : LiveSignal
}

/** OkHttp's WebSocket callbacks, turned into [LiveSignal]s. It keeps nothing, and never the response. */
internal class LiveSocketListener(
    private val signals: SendChannel<LiveSignal>,
) : WebSocketListener() {
    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {
        signals.trySend(LiveSignal.Opened)
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) {
        signals.trySend(LiveSignal.Message(text))
    }

    // Gemini may send its JSON in binary frames.
    override fun onMessage(
        webSocket: WebSocket,
        bytes: ByteString,
    ) {
        signals.trySend(LiveSignal.Message(bytes.utf8()))
    }

    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        signals.trySend(LiveSignal.Closing(code, reason))
    }

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        signals.trySend(LiveSignal.Failure(t, response?.code))
    }
}
