package com.thalia.app.conversation

import com.thalia.app.ai.AiConsentStore
import com.thalia.app.ai.AudioPlayback
import com.thalia.app.ai.ConversationPrefs
import com.thalia.app.ai.SpeechConsentWording
import com.thalia.app.ai.SpeechOutcome
import com.thalia.app.ai.SpeechProblem
import com.thalia.app.ai.SpeechSynthesizer
import com.thalia.app.ai.SpokenReplies
import com.thalia.app.secret.ApiKeyStore
import com.thalia.app.secret.SealedSecret
import com.thalia.app.secret.SecretCipher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ControllerCipher : SecretCipher {
    override fun seal(plaintext: ByteArray) = SealedSecret(byteArrayOf(1), plaintext.reversedArray())

    override fun open(sealed: SealedSecret) = sealed.ciphertext.reversedArray()

    override fun destroyKey() = Unit
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpokenReplyControllerTest {
    @TempDir
    lateinit var dir: File

    private val spoken = mutableListOf<String>()
    private var fail: Boolean = false

    private val synth =
        object : SpeechSynthesizer {
            override suspend fun synthesize(
                text: String,
                sink: com.thalia.app.ai.PcmSink,
            ): SpeechOutcome {
                spoken += text
                return if (fail) {
                    SpeechOutcome.Failed(
                        com.thalia.app.ai.SpeechProblem.NETWORK,
                    )
                } else {
                    SpeechOutcome.Streamed(text.length, 5)
                }
            }
        }
    private val playback =
        object : AudioPlayback {
            override fun open() =
                com.thalia.app.ai
                    .PcmSink { _, _ -> true }

            override suspend fun awaitEnd() = true

            override fun stop() = Unit
        }

    private var usage: com.thalia.app.ai.SpeechUsage? = null

    private fun controller(
        scope: TestScope,
        cap: Long? = 2_000,
    ): SpokenReplyController {
        val consent = AiConsentStore(File(dir, "c.txt"), SpeechConsentWording.VERSION).also { it.accept(1) }
        val keys = ApiKeyStore(File(dir, "k.sealed"), ControllerCipher()).also { it.save("g-key") }
        val ledger =
            com.thalia.app.ai
                .SpeechUsage(File(dir, "u.log"))
                .also { u -> cap?.let { u.setCap(it, java.time.Instant.now()) } }
        usage = ledger
        val prefs = ConversationPrefs(File(dir, "p.txt"))
        return SpokenReplyController(SpokenReplies(synth, playback, consent, keys, ledger), playback, prefs, scope)
    }

    private fun state(vararg entries: Entry) = ConversationState(entries.toList())

    @Test
    fun `only Thalia's new lines are spoken, never the person's own words or what was already there`() =
        runTest(StandardTestDispatcher()) {
            val c = controller(this)
            c.onState(state(Entry.User(1, "old words")))
            c.onState(state(Entry.User(1, "old words"), Entry.User(2, "make the report a must")))
            advanceUntilIdle()
            assertTrue(spoken.isEmpty())
            c.onState(
                state(
                    Entry.User(1, "old"),
                    Entry.User(2, "make the report a must"),
                    Entry.Saved(3, "Priority of Write report is now must."),
                ),
            )
            advanceUntilIdle()
            assertEquals(listOf("Priority of Write report is now must."), spoken)
            assertNull(c.ui.value.note)
        }

    @Test
    fun `a proposal is spoken with what to do next, and never as done`() {
        val row = Presentation.row(Entry.Note(5, "Proposed, not applied yet. Make Write report a must."))
        val text = SpokenReplyController.spokenText(listOf(row))!!
        assertTrue(text.contains("Tap Approve"))
        assertFalse(text.lowercase().contains("saved"))
    }

    @Test
    fun `the model's explanation is left on screen and an empty batch says nothing`() {
        val explanation = Presentation.row(Entry.Assistant(2, ReplyText.AI_EXPLANATION + "Because it is due soon."))
        assertNull(SpokenReplyController.spokenText(listOf(explanation)))
        assertNull(SpokenReplyController.spokenText(emptyList()))
    }

    @Test
    fun `a failure shows its reason and the next reply is tried afresh without a retry of the old one`() =
        runTest(StandardTestDispatcher()) {
            val c = controller(this)
            c.onState(state())
            fail = true
            c.onState(state(Entry.Assistant(1, "Which call do you mean?")))
            advanceUntilIdle()
            assertTrue(
                c.ui.value.note!!
                    .startsWith("Not spoken:"),
            )
            assertEquals(1, spoken.size)
            fail = false
            c.onState(state(Entry.Assistant(1, "Which call do you mean?"), Entry.Saved(2, "Done.")))
            advanceUntilIdle()
            assertEquals(listOf("Which call do you mean?", "Done."), spoken)
            assertNull(c.ui.value.note)
        }

    @Test
    fun `muted means nothing is sent for speech`() =
        runTest(StandardTestDispatcher()) {
            val c = controller(this)
            c.onState(state())
            c.setMuted(true)
            c.onState(state(Entry.Saved(1, "Done.")))
            advanceUntilIdle()
            assertTrue(spoken.isEmpty())
            assertTrue(c.ui.value.muted)
        }

    @Test
    fun `with no cap set nothing is sent for speech, the reason is shown, and setting a cap lets the next reply speak`() =
        runTest(StandardTestDispatcher()) {
            val c = controller(this, cap = null)
            c.onState(state())
            c.onState(state(Entry.Assistant(1, "Which call do you mean?")))
            advanceUntilIdle()
            assertTrue(spoken.isEmpty())
            assertEquals("Not spoken: ${SpeechProblem.NO_CAP.text}", c.ui.value.note)
            assertEquals(0, usage!!.summary(java.time.Instant.now()).used)
            usage!!.setCap(500, java.time.Instant.now())
            c.onState(state(Entry.Assistant(1, "Which call do you mean?"), Entry.Saved(2, "Done.")))
            advanceUntilIdle()
            assertEquals(listOf("Done."), spoken)
            assertNull(c.ui.value.note)
            // Clearing the cap stops speech again and keeps what was counted.
            usage!!.clearCap(java.time.Instant.now())
            c.onState(state(Entry.Saved(3, "More.")))
            advanceUntilIdle()
            assertEquals(listOf("Done."), spoken)
            assertEquals("Done.".length.toLong(), usage!!.summary(java.time.Instant.now()).used)
        }
}
