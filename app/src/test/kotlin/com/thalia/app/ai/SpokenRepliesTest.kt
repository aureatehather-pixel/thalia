package com.thalia.app.ai

import com.thalia.app.secret.ApiKeyStore
import com.thalia.app.secret.SealedSecret
import com.thalia.app.secret.SecretCipher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class SpeechTestCipher : SecretCipher {
    override fun seal(plaintext: ByteArray) = SealedSecret(byteArrayOf(1), plaintext.reversedArray())

    override fun open(sealed: SealedSecret) = sealed.ciphertext.reversedArray()

    override fun destroyKey() = Unit
}

private class FakeSynthesizer(
    private val outcome: (String) -> SpeechOutcome = { SpeechOutcome.Streamed(it.length, 120) },
) : SpeechSynthesizer {
    val texts = mutableListOf<String>()

    override suspend fun synthesize(
        text: String,
        sink: PcmSink,
    ): SpeechOutcome {
        texts += text
        return outcome(text)
    }
}

private class FakePlayback(
    var plays: Boolean = true,
) : AudioPlayback {
    var played = 0
    var opened = 0
    var stops = 0

    override fun open(): PcmSink {
        opened++
        return PcmSink { _, _ -> true }
    }

    override suspend fun awaitEnd(): Boolean {
        played++
        return plays
    }

    override fun stop() {
        stops++
    }
}

class SpokenRepliesTest {
    @TempDir
    lateinit var dir: File

    private val at = Instant.parse("2026-10-12T08:00:00Z")
    private var builds = 0
    private val reports = mutableListOf<String>()

    private fun build(
        synth: SpeechSynthesizer?,
        playback: AudioPlayback,
        consent: Boolean = true,
        key: Boolean = true,
        cap: Long? = 2_000,
    ): Triple<SpokenReplies, SpeechUsage, AiConsentStore> {
        val here = File(dir, "b${builds++}").apply { mkdirs() }
        val consentStore = AiConsentStore(File(here, "c.txt"), SpeechConsentWording.VERSION)
        if (consent) consentStore.accept(1)
        val keys = ApiKeyStore(File(here, "k.sealed"), SpeechTestCipher())
        if (key) keys.save("g-test-key")
        val usage = SpeechUsage(File(here, "u.log")) { java.time.ZoneId.of("Asia/Kuala_Lumpur") }
        cap?.let { usage.setCap(it, at) }
        return Triple(SpokenReplies(synth, playback, consentStore, keys, usage, { at }, report = reports::add), usage, consentStore)
    }

    @Test
    fun `a reply is synthesized once, played, and counted by the character`() =
        runTest {
            val synth = FakeSynthesizer()
            val playback = FakePlayback()
            val (replies, ledger, _) = build(synth, playback)
            assertNull(replies.speak("Reminder set for Thursday."))
            assertEquals(listOf("Reminder set for Thursday."), synth.texts)
            assertEquals(1, playback.played)
            assertEquals(listOf("chars=26 result=STREAMED firstAudioMs=120"), reports)
            assertEquals("Reminder set for Thursday.".length.toLong(), ledger.summary(at).used)
        }

    @Test
    fun `each gate refuses before anything is sent`() =
        runTest {
            val synth = FakeSynthesizer()
            assertEquals(SpeechProblem.NOT_BUILT, build(null, FakePlayback()).first.speak("hi"))
            assertEquals(SpeechProblem.CONSENT, build(synth, FakePlayback(), consent = false).first.speak("hi"))
            assertEquals(SpeechProblem.NO_KEY, build(synth, FakePlayback(), key = false).first.speak("hi"))
            assertEquals(SpeechProblem.NO_CAP, build(synth, FakePlayback(), cap = null).first.speak("hi"))
            assertEquals(SpeechProblem.BUDGET, build(synth, FakePlayback(), cap = 5).first.speak("a long enough reply"))
            assertTrue(synth.texts.isEmpty())
        }

    @Test
    fun `a service failure is reported once, not retried, and its allowance stays counted`() =
        runTest {
            val synth = FakeSynthesizer { SpeechOutcome.Failed(SpeechProblem.NETWORK) }
            val playback = FakePlayback()
            val (replies, ledger, _) = build(synth, playback)
            assertEquals(SpeechProblem.NETWORK, replies.speak("Which call?"))
            assertEquals(1, synth.texts.size)
            assertEquals(0, playback.played)
            assertEquals("Which call?".length.toLong(), ledger.summary(at).used)
        }

    @Test
    fun `a refused key still counts what was submitted and playback trouble is named`() =
        runTest {
            val (auth, ledger, _) = build(FakeSynthesizer { SpeechOutcome.Failed(SpeechProblem.AUTH) }, FakePlayback())
            assertEquals(SpeechProblem.AUTH, auth.speak("Which call?"))
            assertEquals("Which call?".length.toLong(), ledger.summary(at).used)
            val (broken, _, _) = build(FakeSynthesizer(), FakePlayback(plays = false))
            assertEquals(SpeechProblem.PLAYBACK, broken.speak("Done."))
        }

    @Test
    fun `long text is cut at a sentence end and never padded`() {
        val sentence = "This is a sentence about the plan. "
        val text = sentence.repeat(30)
        val cut = SpokenReplies.shorten(text)
        assertTrue(cut.length <= SPEECH_MAX_CHARS)
        assertTrue(cut.endsWith("plan."))
        assertEquals("Short.", SpokenReplies.shorten("  Short.  "))
        assertTrue(SpokenReplies.shorten("x".repeat(1000)).length <= SPEECH_MAX_CHARS)
    }

    @Test
    fun `the same reply spoken again is submitted and counted again, and the cap stops the repeat that does not fit`() =
        runTest {
            val synth = FakeSynthesizer()
            val (replies, usage, _) = build(synth, FakePlayback(), cap = 20)
            assertNull(replies.speak("Which call?"))
            assertEquals(SpeechProblem.BUDGET, replies.speak("Which call?"))
            assertEquals(1, synth.texts.size)
            assertEquals("Which call?".length.toLong(), usage.summary(at).used)
        }

    @Test
    fun `speaking stops what is playing first`() =
        runTest {
            val playback = FakePlayback()
            val (replies, _, _) = build(FakeSynthesizer(), playback)
            replies.speak("One.")
            replies.stop()
            assertTrue(playback.stops >= 2)
        }

    @Test
    fun `read aloud starts on, send after speaking starts off, and both persist and survive a damaged file`() {
        val file = File(dir, "p.txt")
        val prefs = ConversationPrefs(file)
        assertTrue(prefs.speakReplies)
        assertEquals(false, prefs.sendAfterSpeaking)
        assertNull(prefs.sendAfterSpeakingChoice)
        prefs.setSpeakReplies(false)
        assertEquals(false, ConversationPrefs(file).speakReplies)
        assertEquals(false, ConversationPrefs(file).sendAfterSpeaking)
        prefs.setSendAfterSpeaking(true)
        assertTrue(ConversationPrefs(file).sendAfterSpeaking)
        assertEquals(true, ConversationPrefs(file).sendAfterSpeakingChoice)
        prefs.setSendAfterSpeaking(false)
        assertEquals(false, ConversationPrefs(file).sendAfterSpeakingChoice)
        file.writeText("garbage\n===\n")
        assertTrue(ConversationPrefs(file).speakReplies)
        assertEquals(false, ConversationPrefs(file).sendAfterSpeaking)
    }

    @Test
    fun `an app from before this change with no explicit choice is off, and an explicit on or off is kept`() {
        // Older builds wrote only the speak switch until the person toggled; a file with no autoSend line was never a choice.
        val untouched = File(dir, "old1.txt").apply { writeText("speak=true\n") }
        assertEquals(false, ConversationPrefs(untouched).sendAfterSpeaking)
        assertNull(ConversationPrefs(untouched).sendAfterSpeakingChoice)
        val explicitOn = File(dir, "old2.txt").apply { writeText("speak=true\nautoSend=true\n") }
        assertTrue(ConversationPrefs(explicitOn).sendAfterSpeaking)
        val explicitOff = File(dir, "old3.txt").apply { writeText("autoSend=false\n") }
        assertEquals(false, ConversationPrefs(explicitOff).sendAfterSpeaking)
    }
}
