package com.thalia.app.conversation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.thalia.app.ai.AudioPlayback
import com.thalia.app.ai.PcmFormat
import com.thalia.app.ai.PcmSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Plays streamed 16 bit mono PCM through the phone's media stream as speech, starting as soon as the first chunk is written,
 * with transient audio focus so other audio ducks. Nothing is written to disk. [stop] releases the track at once, which is what
 * Stop and Mute call. One stream at a time; opening a new one stops the last.
 */
internal class StreamingPlayback(
    context: Context,
) : AudioPlayback {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private class Stream(
        val track: AudioTrack,
        val focus: AudioFocusRequest,
    ) {
        @Volatile var closed = false

        @Volatile var failed = false

        @Volatile var frames = 0L

        @Volatile var started = false
    }

    @Volatile private var current: Stream? = null

    override fun open(): PcmSink {
        stop()
        val format =
            AudioFormat
                .Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(PcmFormat.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        val minimum = AudioTrack.getMinBufferSize(PcmFormat.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track =
            AudioTrack
                .Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, PcmFormat.SAMPLE_RATE * 2))
                .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
        audio.requestAudioFocus(focus)
        val stream = Stream(track, focus)
        current = stream
        return object : PcmSink {
            override fun write(
                pcm: ByteArray,
                length: Int,
            ): Boolean {
                if (stream.closed) return false
                val wrote = runCatching { track.write(pcm, 0, length) }.getOrDefault(-1)
                if (wrote < 0) {
                    stream.failed = true
                    return false
                }
                stream.frames += wrote / 2
                if (!stream.started && !stream.closed) {
                    stream.started = true
                    runCatching { track.play() }
                }
                return !stream.closed
            }
        }
    }

    override suspend fun awaitEnd(): Boolean {
        val stream = current ?: return true
        // Wait until the play head passes everything written, or the stream is stopped.
        while (coroutineContext.isActive && !stream.closed && !stream.failed) {
            val head = runCatching { stream.track.playbackHeadPosition.toLong() }.getOrDefault(stream.frames)
            if (!stream.started || head >= stream.frames) break
            delay(40)
        }
        val ok = !stream.failed
        if (current === stream) close(stream)
        return ok
    }

    override fun stop() {
        current?.let(::close)
    }

    private fun close(stream: Stream) {
        if (stream.closed) return
        stream.closed = true
        runCatching { stream.track.pause() }
        runCatching { stream.track.flush() }
        runCatching { stream.track.release() }
        audio.abandonAudioFocusRequest(stream.focus)
        if (current === stream) current = null
    }
}
