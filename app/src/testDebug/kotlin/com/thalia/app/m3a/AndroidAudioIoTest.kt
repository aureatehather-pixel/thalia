package com.thalia.app.m3a

import android.media.AudioManager
import com.thalia.voicecheckins.gemini.AudioUnavailableException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val PRIOR_MODE = AudioManager.MODE_NORMAL

/** Records every platform call in order, and fails the one it is told to. */
private class FakePlatform : AudioPlatform {
    val calls = CopyOnWriteArrayList<String>()
    var currentMode = PRIOR_MODE
    val failing = mutableSetOf<String>()
    var focusGranted = true
    var speakerAvailable = true
    var echoAvailable = true
    var onLoss: (() -> Unit)? = null
    val written = LinkedBlockingQueue<ByteArray>()
    val captured = LinkedBlockingQueue<ByteArray>()
    var writeResult: Int? = null

    private fun call(name: String) {
        calls += name
        if (name in failing) throw IllegalStateException("$name failed")
    }

    override fun requestTransientFocus(onLoss: () -> Unit): FocusHold? {
        call("focus")
        if (!focusGranted) return null
        this.onLoss = onLoss
        return FocusHold { call("abandon focus") }
    }

    override fun mode(): Int = currentMode

    override fun setMode(mode: Int) {
        call("mode $mode")
        currentMode = mode
    }

    override fun routeToSpeaker(): Boolean {
        call("route speaker")
        return speakerAvailable
    }

    override fun clearRoute() = call("clear route")

    override fun openRecorder(): Recorder {
        call("open recorder")
        return object : Recorder {
            override val audioSessionId = 7

            override fun start() = call("start recorder")

            override fun read(buffer: ByteArray): Int {
                val next = captured.poll(100, TimeUnit.MILLISECONDS) ?: return 0
                if (next.isEmpty()) return -3
                next.copyInto(buffer)
                return next.size
            }

            override fun stop() = call("stop recorder")

            override fun release() = call("release recorder")
        }
    }

    override fun openPlayer(): Player {
        call("open player")
        return object : Player {
            override fun play() = call("play")

            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                writeResult?.let { return it }
                written.put(bytes.copyOfRange(offset, offset + length))
                return length
            }

            override fun pause() = call("pause")

            override fun flush() = call("flush")

            override fun stop() = call("stop player")

            override fun release() = call("release player")
        }
    }

    override fun attachEchoCanceler(audioSessionId: Int): AutoCloseable? {
        call("attach echo $audioSessionId")
        return if (echoAvailable) AutoCloseable { call("release echo") } else null
    }
}

class AndroidAudioIoTest {
    private val platform = FakePlatform()
    private val focusLosses = CopyOnWriteArrayList<Unit>()
    private val reports = CopyOnWriteArrayList<AudioRouteReport>()
    private val io = AndroidAudioIo(platform, onFocusLost = { focusLosses += Unit }, onOpened = { reports += it })

    private val comm = AudioManager.MODE_IN_COMMUNICATION

    private val fullOpen =
        listOf("focus", "mode $comm", "route speaker", "open recorder", "attach echo 7", "open player", "play", "start recorder")

    private val fullRelease =
        listOf(
            "stop recorder",
            "release echo",
            "release recorder",
            "pause",
            "flush",
            "stop player",
            "release player",
            "clear route",
            "mode $PRIOR_MODE",
            "abandon focus",
        )

    @Test
    fun `opening takes focus, switches to communication, routes to the speaker and opens both directions`() {
        io.open()

        assertEquals(fullOpen, platform.calls)
        assertEquals(comm, platform.currentMode)
        assertEquals(listOf(AudioRouteReport(speakerRouted = true, echoCancelerAttached = true)), reports)
    }

    @Test
    fun `releasing undoes everything in reverse and restores the prior mode, once`() {
        val session = io.open()
        platform.calls.clear()

        session.release()
        session.release()

        assertEquals(fullRelease, platform.calls)
        assertEquals(PRIOR_MODE, platform.currentMode)
    }

    @Test
    fun `refused focus opens nothing`() {
        platform.focusGranted = false

        assertFailsWith<AudioUnavailableException> { io.open() }

        assertEquals(listOf("focus"), platform.calls)
        assertEquals(PRIOR_MODE, platform.currentMode)
    }

    @Test
    fun `a microphone that cannot open releases what was taken before it`() {
        platform.failing += "open recorder"

        assertFailsWith<AudioUnavailableException> { io.open() }

        assertEquals(
            listOf("focus", "mode $comm", "route speaker", "open recorder", "clear route", "mode $PRIOR_MODE", "abandon focus"),
            platform.calls,
        )
        assertEquals(PRIOR_MODE, platform.currentMode)
    }

    @Test
    fun `playback that cannot open releases the microphone and its echo canceller too`() {
        platform.failing += "open player"

        assertFailsWith<AudioUnavailableException> { io.open() }

        assertEquals(
            listOf("stop recorder", "release echo", "release recorder", "clear route", "mode $PRIOR_MODE", "abandon focus"),
            platform.calls.drop(6),
        )
    }

    @Test
    fun `a microphone that will not start releases everything`() {
        platform.failing += "start recorder"

        assertFailsWith<AudioUnavailableException> { io.open() }

        assertEquals(fullOpen + fullRelease, platform.calls)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `a cleanup failure after a failed open still reports the failure to open`() {
        platform.failing += "start recorder"
        platform.failing += "release recorder"

        val error = assertFailsWith<AudioUnavailableException> { io.open() }

        assertEquals("start recorder failed", error.cause?.message)
        assertEquals(1, error.suppressed.size)
        assertEquals(fullOpen + fullRelease, platform.calls, "every cleanup step is still attempted")
    }

    @Test
    fun `a failure while switching mode still gives back focus`() {
        platform.failing += "mode $comm"

        assertFailsWith<AudioUnavailableException> { io.open() }

        assertEquals(listOf("focus", "mode $comm", "mode $PRIOR_MODE", "abandon focus"), platform.calls)
    }

    @Test
    fun `without a speaker or an echo canceller the session still opens, and nothing is unrouted`() {
        platform.speakerAvailable = false
        platform.echoAvailable = false

        val session = io.open()
        session.release()

        assertEquals(listOf(AudioRouteReport(speakerRouted = false, echoCancelerAttached = false)), reports)
        assertFalse("clear route" in platform.calls)
        assertFalse("release echo" in platform.calls)
    }

    @Test
    fun `an echo canceller that fails to attach does not stop the session`() {
        platform.failing += "attach echo 7"

        io.open()

        assertEquals(listOf(AudioRouteReport(speakerRouted = true, echoCancelerAttached = false)), reports)
    }

    @Test
    fun `losing focus ends the session, but not after release`() {
        val session = io.open()

        platform.onLoss!!()
        assertEquals(1, focusLosses.size)

        session.release()
        platform.onLoss!!()
        assertEquals(1, focusLosses.size)
    }

    @Test
    fun `a failing release step does not stop the rest`() {
        val session = io.open()
        platform.calls.clear()
        platform.failing += "release recorder"

        assertFailsWith<AudioUnavailableException> { session.release() }

        assertEquals(fullRelease, platform.calls)
        session.release()
        assertEquals(fullRelease, platform.calls, "and a second release does nothing")
    }

    @Test
    fun `model audio is played in order`() {
        val session = io.open()
        val a = ByteArray(100) { 1 }
        val b = ByteArray(2000) { 2 }

        session.play(a)
        session.play(b)

        val out = generateSequence { platform.written.poll(5, TimeUnit.SECONDS) }.take(4).toList()
        assertContentEquals(a + b, out.reduce { x, y -> x + y })
        session.release()
    }

    @Test
    fun `an interruption flushes playback at once`() {
        val session = io.open()
        platform.calls.clear()

        session.flushPlayback()

        assertEquals(listOf("pause", "flush", "play"), platform.calls)
        session.release()
    }

    @Test
    fun `a playback failure surfaces on the next chunk`() {
        val session = io.open()
        platform.writeResult = -6

        session.play(ByteArray(10))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var failed: Throwable? = null
        while (failed == null && System.nanoTime() < deadline) {
            failed = runCatching { session.play(ByteArray(10)) }.exceptionOrNull()
            if (failed == null) Thread.sleep(10)
        }

        assertIs<AudioUnavailableException>(failed)
        session.release()
    }

    @Test
    fun `playing after release is ignored`() {
        val session = io.open()
        session.release()

        session.play(ByteArray(10))
        session.flushPlayback()

        assertTrue(platform.written.isEmpty())
    }

    @Test
    fun `capture hands on what the microphone reads, and a read error fails it`() =
        runBlocking {
            val session = io.open()
            platform.captured.put(byteArrayOf(1, 2))
            platform.captured.put(byteArrayOf(3))

            val chunks = withTimeout(5.seconds) { session.capture.take(2).toList() }
            assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3)), chunks.map { it.toList() })

            platform.captured.put(ByteArray(0))
            assertFailsWith<AudioUnavailableException> { withTimeout(5.seconds) { session.capture.toList() } }
            session.release()
        }
}
