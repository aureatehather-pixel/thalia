package com.thalia.app.m3a

import android.media.AudioManager
import com.thalia.voicecheckins.gemini.AudioIo
import com.thalia.voicecheckins.gemini.AudioSession
import com.thalia.voicecheckins.gemini.AudioUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The few platform calls a live audio session makes. [AndroidAudioPlatform] is the real one; the
 * session's ordering and cleanup are tested on the JVM against a fake.
 */
internal interface AudioPlatform {
    /** Ask for transient audio focus. Null when it is refused. [onLoss] runs if it is later lost. */
    fun requestTransientFocus(onLoss: () -> Unit): FocusHold?

    fun mode(): Int

    fun setMode(mode: Int)

    /** Ask for communication audio on the built-in speaker. False when no request was made. */
    fun routeToSpeaker(): Boolean

    /** Withdraw this app's routing request, which returns routing to what it was before it. */
    fun clearRoute()

    /** A microphone recorder, 16 kHz mono 16-bit PCM, from the voice communication source. */
    fun openRecorder(): Recorder

    /** A streaming player, 24 kHz mono 16-bit PCM, with voice communication usage. */
    fun openPlayer(): Player

    /** An echo canceller on the recorder's audio session, or null when the device has none. */
    fun attachEchoCanceler(audioSessionId: Int): AutoCloseable?
}

internal fun interface FocusHold {
    fun abandon()
}

internal interface Recorder {
    val audioSessionId: Int

    fun start()

    /** Blocks until [buffer] has audio. Negative is an error. */
    fun read(buffer: ByteArray): Int

    fun stop()

    fun release()
}

internal interface Player {
    fun play()

    /** Blocks until written. A pause from another thread may cut it short. Negative is an error. */
    fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    fun pause()

    fun flush()

    fun stop()

    fun release()
}

/** What one opened session got from the device. A diagnostic for the debug screen. */
data class AudioRouteReport(
    val speakerRouted: Boolean,
    val echoCancelerAttached: Boolean,
)

/**
 * [AudioIo] on Android's recorder and player, for the M3A spike.
 *
 * Opening a session takes transient audio focus, switches the audio mode to communication, routes
 * to the speaker, and opens the microphone with an echo canceller where the device has one, and
 * playback. Losing focus calls [onFocusLost], which ends the session as `ENDED_BY_SYSTEM`.
 * Releasing undoes each of those that was done, in reverse, once, whether opening finished or not.
 */
internal class AndroidAudioIo(
    private val platform: AudioPlatform,
    private val onFocusLost: () -> Unit,
    private val onOpened: (AudioRouteReport) -> Unit = {},
) : AudioIo {
    override fun open(): AudioSession {
        val session = PlatformAudioSession(platform, onFocusLost)
        try {
            session.acquire()
        } catch (e: Exception) {
            val failure = e as? AudioUnavailableException ?: AudioUnavailableException("The microphone or playback could not be opened.", e)
            try {
                session.release()
            } catch (cleanup: Exception) {
                // Every step was still attempted. The failure to open is the one to report.
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        onOpened(session.report())
        return session
    }
}

internal class PlatformAudioSession(
    private val platform: AudioPlatform,
    private val onFocusLost: () -> Unit,
) : AudioSession {
    private val released = AtomicBoolean(false)
    private val playerLock = Any()

    @Volatile
    private var focus: FocusHold? = null

    @Volatile
    private var priorMode: Int? = null

    @Volatile
    private var routed = false

    @Volatile
    private var recorder: Recorder? = null

    @Volatile
    private var echo: AutoCloseable? = null

    @Volatile
    private var player: Player? = null

    @Volatile
    private var writer: Thread? = null

    /** Bumped by every flush. Queued audio from an earlier generation is never played. */
    private val generation = AtomicInteger(0)
    private val queue = LinkedBlockingQueue<Chunk>()

    @Volatile
    private var playbackFailed = false

    private class Chunk(
        val generation: Int,
        val pcm: ByteArray,
    )

    fun acquire() {
        focus =
            platform.requestTransientFocus {
                if (!released.get()) onFocusLost()
            } ?: throw AudioUnavailableException("Audio focus was refused.")
        priorMode = platform.mode()
        platform.setMode(AudioManager.MODE_IN_COMMUNICATION)
        routed = platform.routeToSpeaker()

        val mic = platform.openRecorder()
        recorder = mic
        echo =
            try {
                platform.attachEchoCanceler(mic.audioSessionId)
            } catch (e: Exception) {
                // The canceller is best effort; the session runs without one, and says so.
                null
            }

        val speaker = platform.openPlayer()
        player = speaker
        speaker.play()
        writer = Thread(::writeLoop, "m3a-playback").apply { start() }

        mic.start()
    }

    fun report(): AudioRouteReport = AudioRouteReport(speakerRouted = routed, echoCancelerAttached = echo != null)

    override val capture: Flow<ByteArray> =
        flow {
            val mic = recorder ?: throw AudioUnavailableException("The microphone is not open.")
            val buffer = ByteArray(CAPTURE_CHUNK_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (released.get()) return@flow
                val read = mic.read(buffer)
                if (released.get()) return@flow
                if (read < 0) throw AudioUnavailableException("Reading the microphone failed ($read).")
                if (read > 0) emit(buffer.copyOf(read))
            }
        }.flowOn(Dispatchers.IO)

    override fun play(pcm: ByteArray) {
        if (released.get()) return
        if (playbackFailed) throw AudioUnavailableException("Playback stopped working.")
        queue.put(Chunk(generation.get(), pcm))
    }

    override fun flushPlayback() {
        synchronized(playerLock) {
            if (released.get()) return
            generation.incrementAndGet()
            queue.clear()
            val speaker = player ?: return
            // Pausing cuts short a write in progress on the playback thread.
            speaker.pause()
            speaker.flush()
            speaker.play()
        }
    }

    /** Undo everything [acquire] did, in reverse. Runs once; later calls do nothing. */
    override fun release() {
        if (!released.compareAndSet(false, true)) return
        val failed = mutableListOf<String>()

        fun step(
            name: String,
            action: () -> Unit,
        ) {
            try {
                action()
            } catch (e: Exception) {
                failed += name
            }
        }

        recorder?.let { mic ->
            step("stop the microphone") { mic.stop() }
            step("release the echo canceller") { echo?.close() }
            step("release the microphone") { mic.release() }
        }
        player?.let { speaker ->
            step("stop playback") {
                synchronized(playerLock) {
                    queue.clear()
                    speaker.pause()
                    speaker.flush()
                }
            }
            writer?.let { thread ->
                thread.interrupt()
                step("stop the playback thread") { thread.join(WRITER_JOIN_MILLIS) }
            }
            step("release playback") {
                speaker.stop()
                speaker.release()
            }
        }
        if (routed) step("restore audio routing") { platform.clearRoute() }
        priorMode?.let { mode -> step("restore the audio mode") { platform.setMode(mode) } }
        focus?.let { hold -> step("abandon audio focus") { hold.abandon() } }

        recorder = null
        echo = null
        player = null
        writer = null
        focus = null
        if (failed.isNotEmpty()) throw AudioUnavailableException("Releasing audio did not fully succeed: ${failed.joinToString()}.")
    }

    private fun writeLoop() {
        val speaker = player ?: return
        while (!released.get()) {
            val chunk =
                try {
                    queue.take()
                } catch (e: InterruptedException) {
                    return
                }
            var offset = 0
            while (offset < chunk.pcm.size && !released.get() && chunk.generation == generation.get()) {
                val written = speaker.write(chunk.pcm, offset, minOf(PLAYBACK_SLICE_BYTES, chunk.pcm.size - offset))
                when {
                    written < 0 -> {
                        playbackFailed = true
                        return
                    }
                    // Cut short by a pause. The generation check above decides whether to go on.
                    written == 0 ->
                        try {
                            Thread.sleep(PAUSED_RETRY_MILLIS)
                        } catch (e: InterruptedException) {
                            return
                        }
                    else -> offset += written
                }
            }
        }
    }

    internal companion object {
        /** 40 ms of 16 kHz mono 16-bit audio. */
        const val CAPTURE_CHUNK_BYTES = 1280

        /** 20 ms of 24 kHz mono 16-bit audio, so a flush stops at most this much stale audio. */
        const val PLAYBACK_SLICE_BYTES = 960

        private const val WRITER_JOIN_MILLIS = 1000L
        private const val PAUSED_RETRY_MILLIS = 5L
    }
}
