package com.thalia.voicecheckins.gemini

import com.thalia.voicecheckins.voice.CancellationReason
import com.thalia.voicecheckins.voice.FailureReason
import com.thalia.voicecheckins.voice.Speaker
import com.thalia.voicecheckins.voice.TranscriptLine
import com.thalia.voicecheckins.voice.VoiceSessionEvent
import com.thalia.voicecheckins.voice.VoiceSessionResult
import com.thalia.voicecheckins.voice.VoiceSessionState
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class GeminiLiveVoiceSessionClientTest {
    private val fullOrder =
        listOf(VoiceSessionState.CONNECTING, VoiceSessionState.ACTIVE, VoiceSessionState.ENDING, VoiceSessionState.ENDED)

    private val endedBeforeActive =
        listOf(VoiceSessionState.CONNECTING, VoiceSessionState.ENDING, VoiceSessionState.ENDED)

    /** Exactly one Finished, and it is the last event. */
    private fun Recorder.assertSingleFinishedLast() {
        assertEquals(1, events.count { it is VoiceSessionEvent.Finished })
        assertIs<VoiceSessionEvent.Finished>(events.last())
    }

    /** Starts a session and brings it to ACTIVE, with the setup and the opening prompt read off the wire. */
    private suspend fun LiveHarness.activeSession(client: GeminiLiveVoiceSessionClient = client()): ActiveSession {
        val connection = gemini.acceptSession()
        val handle = client.start(manualConfig)
        val recorder = record(handle)
        connection.next() // setup
        connection.confirmSetup()
        recorder.awaitState(VoiceSessionState.ACTIVE)
        connection.next() // opening prompt
        return ActiveSession(handle, recorder, connection, audio.sessions.last())
    }

    private class ActiveSession(
        val handle: GeminiLiveSessionHandle,
        val recorder: Recorder,
        val connection: GeminiConnection,
        val audio: FakeAudioSession,
    )

    @Test
    fun `a session talks both ways and ends cleanly when the user ends it`() =
        liveTest {
            val connection = gemini.acceptSession()
            val handle = client().start(manualConfig)
            val recorder = record(handle)

            val setup = connection.next()["setup"]!!.jsonObject
            assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)
            assertEquals(0, audio.openAttempts.get(), "the microphone stays closed until Gemini confirms the session")

            connection.confirmSetup()
            recorder.awaitState(VoiceSessionState.ACTIVE)
            val opening = connection.next()["clientContent"]!!.jsonObject
            assertTrue("Say hello." in opening.toString())
            val mic = audio.sessions.single()

            // The user's voice reaches Gemini.
            mic.microphone.send(byteArrayOf(1, 2, 3, 4))
            val sent = connection.next()["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
            assertEquals("audio/pcm;rate=16000", sent["mimeType"]!!.jsonPrimitive.content)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), Base64.getDecoder().decode(sent["data"]!!.jsonPrimitive.content))

            // Gemini's voice reaches the speaker.
            connection.send(serverContent(audio = byteArrayOf(9, 8, 7), output = "Hello.", turnComplete = true))
            assertContentEquals(byteArrayOf(9, 8, 7), withTimeout(WAIT) { mic.played.receive() })
            assertEquals(TranscriptLine(Speaker.THALIA, "Hello."), recorder.awaitLine())

            connection.send(serverContent(input = "Hi "))
            connection.send(serverContent(input = "there."))
            connection.send(serverContent(audio = byteArrayOf(5), output = "Nice"))
            assertEquals(TranscriptLine(Speaker.USER, "Hi there."), recorder.awaitLine())
            connection.send(serverContent(output = " to meet you.", turnComplete = true))
            assertEquals(TranscriptLine(Speaker.THALIA, "Nice to meet you."), recorder.awaitLine())

            handle.endSession()
            val result = assertIs<VoiceSessionResult.Cancelled>(recorder.finished())

            assertEquals(CancellationReason.ENDED_BY_USER, result.reason)
            assertEquals(
                listOf(
                    TranscriptLine(Speaker.THALIA, "Hello."),
                    TranscriptLine(Speaker.USER, "Hi there."),
                    TranscriptLine(Speaker.THALIA, "Nice to meet you."),
                ),
                result.transcript,
            )
            assertEquals(fullOrder, recorder.states())
            recorder.assertSingleFinishedLast()
            assertEquals(4L, handle.usage.value.audioBytesSent)
            assertEquals(4L, handle.usage.value.audioBytesReceived)
        }

    @Test
    fun `ending releases the audio once, after capture stops, and closes the socket normally`() =
        liveTest {
            val session = activeSession()

            session.handle.endSession()
            session.handle.endSession(CancellationReason.ENDED_BY_SYSTEM) // repeat End is ignored
            val result = assertIs<VoiceSessionResult.Cancelled>(session.recorder.finished())

            assertEquals(CancellationReason.ENDED_BY_USER, result.reason)
            assertEquals(listOf("capture stopped", "released"), session.audio.lifecycle)
            assertEquals(1, session.audio.releases.get())
            assertEquals("closed 1000", session.connection.awaitEnded())
            session.recorder.assertSingleFinishedLast()
        }

    @Test
    fun `a server interruption flushes playback and settles what the model had said`() =
        liveTest {
            val session = activeSession()

            session.connection.send(serverContent(audio = byteArrayOf(1, 1), output = "Let me tell you a long"))
            withTimeout(WAIT) { session.audio.played.receive() }
            session.connection.send(serverContent(interrupted = true))

            withTimeout(WAIT) { session.audio.flushed.receive() }
            assertEquals(TranscriptLine(Speaker.THALIA, "Let me tell you a long"), session.recorder.awaitLine())
            assertEquals(VoiceSessionState.ACTIVE, session.recorder.states().last(), "an interruption does not end the session")

            session.handle.endSession()
            session.recorder.finished()
        }

    @Test
    fun `cancelling the collector releases everything and emits nothing more`() =
        liveTest {
            val client = client()
            val session = activeSession(client)

            session.recorder.job.cancelAndJoin()

            session.audio.awaitReleased()
            assertEquals(listOf("capture stopped", "released"), session.audio.lifecycle)
            assertEquals("closed 1000", session.connection.awaitEnded())
            assertFalse(session.recorder.events.any { it is VoiceSessionEvent.Finished })

            // The client is free again.
            val next = activeSession(client)
            next.handle.endSession()
            next.recorder.finished()
            assertEquals(1, session.audio.releases.get())
        }

    @Test
    fun `HTTP 401 and 403 are authentication failures, and the microphone is never opened`() =
        liveTest {
            for (code in listOf(401, 403)) {
                gemini.refuse(code)
                val recorder = record(client().start(manualConfig))

                val result = assertIs<VoiceSessionResult.Failed>(recorder.finished())

                assertEquals(FailureReason.NOT_AUTHENTICATED, result.reason, "HTTP $code")
                assertEquals(endedBeforeActive, recorder.states())
                recorder.assertSingleFinishedLast()
            }
            assertEquals(0, audio.openAttempts.get())
        }

    @Test
    fun `an unreachable server is no network, before anything is opened`() =
        liveTest {
            val unreachable = FakeGemini().use { it.endpoint } // the port is closed again on leaving `use`

            val recorder = record(client(endpoint = unreachable).start(manualConfig))
            val result = assertIs<VoiceSessionResult.Failed>(recorder.finished())

            assertEquals(FailureReason.NO_NETWORK, result.reason)
            assertTrue(result.message.startsWith("Could not reach Gemini"), result.message)
            assertEquals(endedBeforeActive, recorder.states())
            assertEquals(0, audio.openAttempts.get())
        }

    @Test
    fun `a server that never confirms the setup fails as provider unavailable`() =
        liveTest {
            val connection = gemini.acceptSession()
            val recorder = record(client(setupTimeout = 300.milliseconds).start(manualConfig))
            connection.next()

            val result = assertIs<VoiceSessionResult.Failed>(recorder.finished())

            assertEquals(FailureReason.PROVIDER_UNAVAILABLE, result.reason)
            assertEquals(endedBeforeActive, recorder.states())
            assertEquals("closed 1000", connection.awaitEnded())
            assertEquals(0, audio.openAttempts.get())
        }

    @Test
    fun `a dropped connection mid-session is network loss, and keeps the transcript`() =
        liveTest {
            val session = activeSession()
            session.connection.send(serverContent(output = "Half a sen"))
            session.connection.send(serverContent(audio = byteArrayOf(2)))
            withTimeout(WAIT) { session.audio.played.receive() }

            gemini.dropConnections()
            val result = assertIs<VoiceSessionResult.Failed>(session.recorder.finished())

            assertEquals(FailureReason.NO_NETWORK, result.reason)
            assertTrue(result.message.startsWith("The connection to Gemini was lost"), result.message)
            assertEquals(listOf(TranscriptLine(Speaker.THALIA, "Half a sen")), result.transcript)
            assertEquals(fullOrder, session.recorder.states())
            assertEquals(1, session.audio.releases.get())
        }

    @Test
    fun `the server closing the session, or announcing it will, is provider unavailable`() =
        liveTest {
            val closed = activeSession()
            closed.connection.close(1011, "internal error")
            val closedResult = assertIs<VoiceSessionResult.Failed>(closed.recorder.finished())
            assertEquals(FailureReason.PROVIDER_UNAVAILABLE, closedResult.reason)
            assertTrue("1011" in closedResult.message, closedResult.message)
            assertEquals(1, closed.audio.releases.get())

            val goingAway = activeSession()
            goingAway.connection.send("""{"goAway": {"timeLeft": "5s"}}""")
            val goAwayResult = assertIs<VoiceSessionResult.Failed>(goingAway.recorder.finished())
            assertEquals(FailureReason.PROVIDER_UNAVAILABLE, goAwayResult.reason)
            assertEquals("closed 1000", goingAway.connection.awaitEnded())
            assertEquals(1, goingAway.audio.releases.get())
        }

    @Test
    fun `audio that cannot be opened fails the session and closes the socket`() =
        liveTest {
            audio.openFailure = AudioUnavailableException("microphone busy")
            val connection = gemini.acceptSession()
            val recorder = record(client().start(manualConfig))
            connection.next()
            connection.confirmSetup()

            val result = assertIs<VoiceSessionResult.Failed>(recorder.finished())

            assertEquals(FailureReason.AUDIO_UNAVAILABLE, result.reason)
            assertTrue("microphone busy" in result.message, result.message)
            assertEquals(endedBeforeActive, recorder.states(), "never ACTIVE without audio")
            assertEquals("closed 1000", connection.awaitEnded())
            assertTrue(audio.sessions.isEmpty())
        }

    @Test
    fun `capture failing mid-session fails the session and releases the audio`() =
        liveTest {
            val session = activeSession()

            session.audio.microphone.close(IOException("microphone disconnected"))
            val result = assertIs<VoiceSessionResult.Failed>(session.recorder.finished())

            assertEquals(FailureReason.AUDIO_UNAVAILABLE, result.reason)
            assertEquals(1, session.audio.releases.get())
            assertEquals("closed 1000", session.connection.awaitEnded())
        }

    @Test
    fun `a second session started while one is running is refused without touching anything`() =
        liveTest {
            val client = client()
            val first = activeSession(client)

            val second = record(client.start(manualConfig))
            val refused = assertIs<VoiceSessionResult.Failed>(second.finished())

            assertTrue("already running" in refused.message, refused.message)
            assertEquals(emptyList(), second.states())
            assertEquals(1, gemini.server.requestCount)
            assertEquals(1, audio.openAttempts.get())

            // The first session is unaffected.
            first.connection.send(serverContent(audio = byteArrayOf(3)))
            withTimeout(WAIT) { first.audio.played.receive() }
            first.handle.endSession()
            assertIs<VoiceSessionResult.Cancelled>(first.recorder.finished())
        }

    @Test
    fun `start, end, start again reacquires fresh resources each time and leaks none`() =
        liveTest {
            val client = client()

            // Ended while still connecting: nothing opened, socket closed.
            val connecting = gemini.acceptSession()
            val earlyHandle = client.start(manualConfig)
            val early = record(earlyHandle)
            connecting.next()
            earlyHandle.endSession()
            assertIs<VoiceSessionResult.Cancelled>(early.finished())
            assertEquals(endedBeforeActive, early.states())
            assertEquals("closed 1000", connecting.awaitEnded())

            // Then twice through a full session, each started the moment the last one finished.
            val one = activeSession(client)
            one.handle.endSession()
            one.recorder.finished()
            val two = activeSession(client)
            two.handle.endSession()
            two.recorder.finished()

            assertEquals(2, audio.sessions.size)
            assertEquals(listOf(1, 1), audio.sessions.map { it.releases.get() })
            assertEquals(3, gemini.server.requestCount)
        }

    @Test
    fun `a session ended before it is collected opens nothing`() =
        liveTest {
            val handle = client().start(manualConfig)
            handle.endSession()

            val result = assertIs<VoiceSessionResult.Cancelled>(record(handle).finished())

            assertEquals(CancellationReason.ENDED_BY_USER, result.reason)
            assertEquals(0, gemini.server.requestCount)
        }

    @Test
    fun `a session's events can be collected only once`() =
        liveTest {
            val handle = client().start(manualConfig)
            handle.endSession()
            handle.events.collect()

            assertFailsWith<IllegalStateException> { handle.events.first() }
        }

    @Test
    fun `the maximum duration ends the session as timed out`() =
        liveTest {
            val connection = gemini.acceptSession()
            val recorder = record(client().start(manualConfig.copy(maxDuration = 700.milliseconds)))
            connection.next()
            connection.confirmSetup()

            val result = assertIs<VoiceSessionResult.Cancelled>(recorder.finished())

            assertEquals(CancellationReason.TIMED_OUT, result.reason)
            assertEquals(fullOrder, recorder.states())
            val opened = audio.sessions.single()
            assertEquals(1, opened.releases.get())
        }

    @Test
    fun `the key reaches Gemini but never an event, a result, a log line or a string`() =
        liveTest {
            val client = client()
            val session = activeSession(client)
            val handshake = gemini.server.takeRequest()
            assertEquals(SECRET, handshake.url.queryParameter("key"), "the key is sent as the documented query parameter")

            session.connection.close(1008, "API key $SECRET is not valid")
            val closed = assertIs<VoiceSessionResult.Failed>(session.recorder.finished())
            assertTrue(SecretRedactor.REDACTED in closed.message, closed.message)

            // An exception whose message carries the key, as a careless platform layer might write.
            audio.openFailure = AudioUnavailableException("could not open audio for key=$SECRET")
            val connection = gemini.acceptSession()
            val failing = record(client.start(manualConfig))
            connection.next()
            connection.confirmSetup()
            val audioFailure = assertIs<VoiceSessionResult.Failed>(failing.finished())

            gemini.refuse(401)
            val refused = record(client.start(manualConfig))
            refused.finished()

            val everything =
                listOf(session.recorder, failing, refused).flatMap { recorder -> recorder.events.map { it.toString() } } +
                    logs + listOf(closed.message, audioFailure.message, session.handle.toString(), GeminiApiKey(SECRET).toString())
            val encoded = java.net.URLEncoder.encode(SECRET, Charsets.UTF_8)
            for (text in everything) {
                assertFalse(SECRET in text, text)
                assertFalse(encoded in text, text)
            }
            assertTrue(logs.isNotEmpty())
        }

    @Test
    fun `the redactor removes the key from URLs and text in every form`() {
        val redactor = SecretRedactor(SECRET)
        val encoded = java.net.URLEncoder.encode(SECRET, Charsets.UTF_8)

        for (text in listOf(
            "wss://example.test/ws?key=$SECRET",
            "https://example.test/ws?alt=json&key=$encoded&x=1",
            "https://example.test/ws?access_token=anything-at-all",
            "plain $SECRET in a sentence",
            "encoded $encoded in a sentence",
        )) {
            val redacted = redactor.redact(text)
            assertFalse(SECRET in redacted, redacted)
            assertFalse(encoded in redacted, redacted)
            assertFalse("anything-at-all" in redacted, redacted)
            assertTrue(SecretRedactor.REDACTED in redacted, redacted)
        }
        assertEquals("https://example.test/ws?alt=json&x=1", redactor.redact("https://example.test/ws?alt=json&x=1"))
    }

    @Test
    fun `usage reports are kept raw, unsummed, and malformed or partial ones never break the session`() =
        liveTest {
            val session = activeSession()

            session.connection.send("""{"usageMetadata": "not an object"}""")
            session.connection.send("""{"usageMetadata": {"responseTokenCount": "12", "totalTokenCount": "lots"}}""")
            session.connection.send(serverContent(usageTotal = 10))
            session.connection.send(serverContent(audio = byteArrayOf(4), usageTotal = 25))
            withTimeout(WAIT) { session.audio.played.receive() }

            val usage = session.handle.usage.value
            assertEquals(1, usage.unreadableReports)
            assertEquals(listOf(null, 10L, 25L), usage.reports.map { it.totalTokenCount }, "kept as sent, never added up")
            assertEquals(12L, usage.reports.first().responseTokenCount)
            assertEquals(listOf("totalTokenCount"), usage.reports.first().invalidFields)
            assertEquals(25L, usage.latest?.totalTokenCount)
            assertEquals(VoiceSessionState.ACTIVE, session.recorder.states().last())

            session.handle.endSession()
            session.recorder.finished()
        }

    @Test
    fun `unknown and unreadable server messages are ignored and the session carries on`() =
        liveTest {
            val session = activeSession()

            session.connection.send("""{"someTypeAddedLater": {"x": 1}}""")
            session.connection.send("this is not JSON")
            session.connection.send("""{"sessionResumptionUpdate": {"newHandle": "h"}}""")
            session.connection.send(
                """{"serverContent": {"futureField": 1, "outputTranscription": {"text": "Still here.", "extra": true},
                   "turnComplete": true}, "alsoNew": []}""",
            )

            assertEquals(TranscriptLine(Speaker.THALIA, "Still here."), session.recorder.awaitLine())
            assertEquals(VoiceSessionState.ACTIVE, session.recorder.states().last())
            session.handle.endSession()
            assertIs<VoiceSessionResult.Cancelled>(session.recorder.finished())
        }
}
