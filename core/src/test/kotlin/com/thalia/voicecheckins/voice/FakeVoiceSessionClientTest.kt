package com.thalia.voicecheckins.voice

import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.voice.fake.FakeVoiceSessionClient
import com.thalia.voicecheckins.voice.fake.defaultScriptFor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FakeVoiceSessionClientTest {
    private val config =
        VoiceSessionConfig(
            occurrenceId = "2026-09-19-morning",
            checkInType = CheckInType.OPENING,
        )

    private fun List<VoiceSessionEvent>.states(): List<VoiceSessionState> = filterIsInstance<VoiceSessionEvent.StateChanged>().map { it.to }

    private fun List<VoiceSessionEvent>.transcript(): List<TranscriptLine> =
        filterIsInstance<VoiceSessionEvent.TranscriptAppended>().map { it.line }

    private fun List<VoiceSessionEvent>.result(): VoiceSessionResult = filterIsInstance<VoiceSessionEvent.Finished>().single().result

    @Test
    fun `a normal session walks the full state machine and completes`() =
        runTest {
            val events = FakeVoiceSessionClient().start(config).events.toList()

            assertEquals(
                listOf(
                    VoiceSessionState.CONNECTING,
                    VoiceSessionState.ACTIVE,
                    VoiceSessionState.ENDING,
                    VoiceSessionState.ENDED,
                ),
                events.states(),
            )

            val result = events.result()
            assertIs<VoiceSessionResult.Completed>(result)
            assertEquals(events.transcript(), result.transcript)
            assertTrue(result.transcript.isNotEmpty())
            assertEquals(Speaker.THALIA, result.transcript.first().speaker)
            assertEquals(CheckInStatus.COMPLETED, result.toCheckInStatus())
        }

    @Test
    fun `the finished event is always last`() =
        runTest {
            val events = FakeVoiceSessionClient().start(config).events.toList()

            assertIs<VoiceSessionEvent.Finished>(events.last())
            assertEquals(1, events.filterIsInstance<VoiceSessionEvent.Finished>().size)
        }

    @Test
    fun `the same config replays identically`() =
        runTest {
            val first = FakeVoiceSessionClient().start(config).events.toList()
            val second = FakeVoiceSessionClient().start(config).events.toList()

            assertEquals(first, second)
        }

    @Test
    fun `morning and afternoon run different scripts`() =
        runTest {
            val morning =
                FakeVoiceSessionClient()
                    .start(config)
                    .events
                    .toList()
                    .transcript()
            val afternoon =
                FakeVoiceSessionClient()
                    .start(config.copy(checkInType = CheckInType.CLOSING))
                    .events
                    .toList()
                    .transcript()

            assertTrue(morning.isNotEmpty() && afternoon.isNotEmpty())
            assertTrue(morning != afternoon)
        }

    @Test
    fun `ending the call mid-conversation keeps what was said so far`() =
        runTest {
            val handle = FakeVoiceSessionClient().start(config)
            val events = mutableListOf<VoiceSessionEvent>()

            handle.events.collect { event ->
                events += event
                if (events.transcript().size == 2) handle.endSession()
            }

            val result = events.result()
            assertIs<VoiceSessionResult.Cancelled>(result)
            assertEquals(CancellationReason.ENDED_BY_USER, result.reason)
            assertEquals(2, result.transcript.size)
            assertEquals(
                listOf(
                    VoiceSessionState.CONNECTING,
                    VoiceSessionState.ACTIVE,
                    VoiceSessionState.ENDING,
                    VoiceSessionState.ENDED,
                ),
                events.states(),
            )
            // The user answered and chose to stop. That is a check-in that happened.
            assertEquals(CheckInStatus.COMPLETED, result.toCheckInStatus())
        }

    @Test
    fun `ending before the conversation opens hangs up with an empty transcript`() =
        runTest {
            val handle = FakeVoiceSessionClient().start(config)
            handle.endSession(CancellationReason.ENDED_BY_SYSTEM)

            val events = handle.events.toList()

            val result = events.result()
            assertIs<VoiceSessionResult.Cancelled>(result)
            assertEquals(CancellationReason.ENDED_BY_SYSTEM, result.reason)
            assertTrue(result.transcript.isEmpty())
            // Never became a conversation, so ACTIVE is never reached.
            assertTrue(VoiceSessionState.ACTIVE !in events.states())
        }

    @Test
    fun `repeat end requests keep the first reason`() =
        runTest {
            val handle = FakeVoiceSessionClient().start(config)
            handle.endSession(CancellationReason.ENDED_BY_USER)
            handle.endSession(CancellationReason.TIMED_OUT)

            val result = handle.events.toList().result()

            assertIs<VoiceSessionResult.Cancelled>(result)
            assertEquals(CancellationReason.ENDED_BY_USER, result.reason)
        }

    @Test
    fun `a connection failure never reaches active and reports a failure`() =
        runTest {
            val client =
                FakeVoiceSessionClient(
                    FakeVoiceSessionClient.Behaviour.FailsWhileConnecting(FailureReason.NO_NETWORK),
                )

            val events = client.start(config).events.toList()

            assertEquals(
                listOf(
                    VoiceSessionState.CONNECTING,
                    VoiceSessionState.ENDING,
                    VoiceSessionState.ENDED,
                ),
                events.states(),
            )
            assertTrue(events.transcript().isEmpty())

            val result = events.result()
            assertIs<VoiceSessionResult.Failed>(result)
            assertEquals(FailureReason.NO_NETWORK, result.reason)
            assertEquals(CheckInStatus.FAILED, result.toCheckInStatus())
        }

    @Test
    fun `every state change the fake emits is a legal transition`() =
        runTest {
            val events = FakeVoiceSessionClient().start(config).events.toList()

            events.filterIsInstance<VoiceSessionEvent.StateChanged>().forEach { change ->
                assertTrue(
                    change.from.canTransitionTo(change.to),
                    "illegal transition emitted: ${change.from} -> ${change.to}",
                )
            }
        }

    @Test
    fun `a custom script is used verbatim`() =
        runTest {
            val line = TranscriptLine(Speaker.THALIA, "Preview line")
            val client = FakeVoiceSessionClient(script = { listOf(line) })

            val result =
                client
                    .start(config)
                    .events
                    .toList()
                    .result()

            assertIs<VoiceSessionResult.Completed>(result)
            assertEquals(listOf(line), result.transcript)
        }

    @Test
    fun `with no delay the whole script plays without waiting`() =
        runTest {
            FakeVoiceSessionClient().start(config).events.toList()

            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun `a paced session releases one line per interval and then finishes`() =
        runTest {
            val lineDelay = 5.seconds
            val script = defaultScriptFor(CheckInType.OPENING)
            val events = mutableListOf<VoiceSessionEvent>()
            val collecting =
                launch {
                    FakeVoiceSessionClient(lineDelay = lineDelay).start(config).events.collect { events += it }
                }

            // Connected, but nothing has been said yet.
            runCurrent()
            assertEquals(listOf(VoiceSessionState.CONNECTING, VoiceSessionState.ACTIVE), events.states())
            assertTrue(events.transcript().isEmpty())

            // Just short of the first interval: still nothing.
            advanceTimeBy(lineDelay - 1.milliseconds)
            runCurrent()
            assertTrue(events.transcript().isEmpty())

            // Then exactly one new line for every further interval.
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(script.take(1), events.transcript())
            script.indices.drop(1).forEach { index ->
                advanceTimeBy(lineDelay)
                runCurrent()
                assertEquals(script.take(index + 1), events.transcript())
            }

            collecting.join()
            assertIs<VoiceSessionResult.Completed>(events.result())
            assertEquals(script, events.result().transcript)
            assertEquals(lineDelay.inWholeMilliseconds * script.size, testScheduler.currentTime)
        }

    @Test
    fun `ending a paced call hangs up at once instead of after the wait`() =
        runTest {
            val events = mutableListOf<VoiceSessionEvent>()
            val handle = FakeVoiceSessionClient(lineDelay = 5.seconds).start(config)
            val collecting = launch { handle.events.collect { events += it } }

            // Mid-conversation: two lines in, waiting on the third.
            runCurrent()
            advanceTimeBy(10.seconds)
            runCurrent()
            assertEquals(2, events.transcript().size)
            val endedAt = testScheduler.currentTime

            handle.endSession()
            runCurrent()

            collecting.join()
            val result = events.result()
            assertIs<VoiceSessionResult.Cancelled>(result)
            assertEquals(CancellationReason.ENDED_BY_USER, result.reason)
            assertEquals(2, result.transcript.size)
            // No virtual time passed between pressing End Call and the call ending.
            assertEquals(endedAt, testScheduler.currentTime)
        }

    @Test
    fun `an end request before the first line of a paced call is honoured immediately`() =
        runTest {
            val handle = FakeVoiceSessionClient(lineDelay = 5.seconds).start(config)
            val events = mutableListOf<VoiceSessionEvent>()
            val collecting = launch { handle.events.collect { events += it } }

            runCurrent()
            handle.endSession(CancellationReason.ENDED_BY_SYSTEM)
            runCurrent()

            collecting.join()
            val result = events.result()
            assertIs<VoiceSessionResult.Cancelled>(result)
            assertEquals(CancellationReason.ENDED_BY_SYSTEM, result.reason)
            assertTrue(result.transcript.isEmpty())
            assertEquals(0L, testScheduler.currentTime)
        }
}
