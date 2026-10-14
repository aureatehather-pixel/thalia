package com.thalia.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpokenReplyPolicyTest {
    private val claim =
        Regex(
            """(?i)\b(done|saved|added|created|updated|scheduled|recorded|marked|completed|deleted|removed|booked|changed|finished|logged|stored)\b""",
        )

    @Test
    fun `a voice session speaks replies by default and a typed one stays quiet`() {
        assertEquals(
            listOf("Start with the watering screen."),
            SpokenReplyPolicy(SessionMode.VOICE).lines(SpeechEvent.Reply("Start with the watering screen.")),
        )
        assertEquals(emptyList(), SpokenReplyPolicy(SessionMode.TYPED).lines(SpeechEvent.Reply("Start with the watering screen.")))
    }

    @Test
    fun `mute silences a voice session, and only the person's own replay is still heard`() {
        val policy = SpokenReplyPolicy(SessionMode.VOICE, muted = true)
        assertEquals(emptyList(), policy.lines(SpeechEvent.Reply("Hello there.")))
        assertEquals(emptyList(), policy.lines(SpeechEvent.ProposalWaiting(2)))
        assertEquals(emptyList(), policy.lines(SpeechEvent.Executed(true)))
        assertEquals(listOf("Hello there."), policy.lines(SpeechEvent.Replay("Hello there.")))
        assertEquals(listOf("Hello there."), SpokenReplyPolicy(SessionMode.TYPED).lines(SpeechEvent.Replay("Hello there.")))
    }

    @Test
    fun `a proposal is announced, never read out`() {
        val policy = SpokenReplyPolicy(SessionMode.VOICE)
        assertEquals(listOf("I have a proposal. Tap to review it."), policy.lines(SpeechEvent.ProposalWaiting(1)))
        assertEquals(listOf("I have 3 proposals. Tap to review them."), policy.lines(SpeechEvent.ProposalWaiting(3)))
        assertEquals(emptyList(), policy.lines(SpeechEvent.ProposalWaiting(0)))
    }

    @Test
    fun `done is spoken only for an execution that succeeded, and a failure says nothing was changed`() {
        val policy = SpokenReplyPolicy(SessionMode.VOICE)
        assertEquals(listOf("Done."), policy.lines(SpeechEvent.Executed(true)))
        val failure = policy.lines(SpeechEvent.Executed(false)).joinToString(" ")
        assertEquals("That did not go through. Everything is as it was.", failure)
        assertFalse(claim.containsMatchIn(failure))
    }

    @Test
    fun `a model's own claim of completion is never read out`() {
        val policy = SpokenReplyPolicy(SessionMode.VOICE)
        assertEquals(
            listOf("The demo script is due next week."),
            policy.lines(SpeechEvent.Reply("I have added it to your plan. The demo script is due next week. All set!")),
        )
        assertEquals(emptyList(), policy.lines(SpeechEvent.Reply("Done! Your task is saved.")))
        assertEquals(emptyList(), policy.lines(SpeechEvent.Replay("I've marked it as completed.")))
    }

    @Test
    fun `before any successful execution no event sequence ever speaks a completion word`() {
        val texts = listOf("Saved! It is done.", "I will add it. It is booked.", "Plain advice here.", "The task was completed and logged.")
        val events =
            texts.flatMap { listOf(SpeechEvent.Reply(it), SpeechEvent.Replay(it)) } +
                listOf(SpeechEvent.ProposalWaiting(1), SpeechEvent.ProposalWaiting(4), SpeechEvent.Executed(false))
        for (mode in SessionMode.entries) {
            for (muted in listOf(false, true)) {
                for (a in events) {
                    for (b in events) {
                        for (c in events) {
                            val policy = SpokenReplyPolicy(mode, muted)
                            val spoken = listOf(a, b, c).flatMap { policy.lines(it) }
                            assertTrue(spoken.none { claim.containsMatchIn(it) }, "$mode muted=$muted $spoken")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `markup and links are removed and a long reply is cut at a sentence`() {
        val lines =
            SpokenReplyPolicy.clean(
                "**Start** with [the screen](https://x.example/a) first. See https://x.example now.",
                dropClaims = true,
            )
        assertEquals(listOf("Start with the screen first.", "See now."), lines)
        val long = (1..100).joinToString(" ") { "Sentence number $it is here." }
        val cut = SpokenReplyPolicy.clean(long, dropClaims = true)
        assertTrue(cut.sumOf { it.length } <= SpokenReplyPolicy.MAX_CHARS)
        assertTrue(cut.all { it.endsWith(".") })
    }
}
