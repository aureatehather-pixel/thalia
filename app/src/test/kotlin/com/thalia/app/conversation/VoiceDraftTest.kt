package com.thalia.app.conversation

import android.app.Application
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSpeechSource(
    private val readiness: SpeechReadiness,
    override val deviceTag: String = "en-MY",
) : SpeechSource {
    val listened = mutableListOf<String>()
    private val callbacks = mutableListOf<(SpeechOutcome) -> Unit>()
    var cancels = 0

    override suspend fun readiness() = readiness

    override fun listen(
        languageTag: String,
        onDone: (SpeechOutcome) -> Unit,
    ) {
        listened += languageTag
        callbacks += onDone
    }

    /** Like the platform, cancel may report back; the controller must not depend on it. */
    override fun cancel() {
        cancels++
        callbacks.lastOrNull()?.invoke(SpeechOutcome.Cancelled)
    }

    override fun release() = Unit

    /** Delivers a result to the nth listen (default: the latest), including one that is already over. */
    fun finish(
        outcome: SpeechOutcome,
        listen: Int = callbacks.lastIndex,
    ) = callbacks[listen](outcome)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoiceDraftTest {
    private val heard = mutableListOf<String>()

    private suspend fun controller(source: FakeSpeechSource) = VoiceDraftController(source) { heard += it }.also { it.check() }

    private fun available(vararg tags: String) = SpeechReadiness.Available(tags.toList())

    @Test
    fun deviceLocaleInstalled_isTheDefault_andNeedsNoChoice() =
        runTest {
            val source = FakeSpeechSource(available("en-US", "en-my"))
            val c = controller(source)
            assertEquals("en-my", c.ui.value.selected)
            c.start()
            assertEquals(listOf("en-my"), source.listened)
        }

    @Test
    fun deviceLocaleMissing_offersSameBaseVariantsOnly_andNothingIsChosenForTheUser() =
        runTest {
            val source = FakeSpeechSource(available("en-US", "de-DE", "en-GB"))
            val c = controller(source)
            assertEquals(listOf("en-GB", "en-US"), c.ui.value.languages)
            assertNull(c.ui.value.selected)
            c.start()
            assertEquals(emptyList(), source.listened)
            c.select("de-DE")
            assertNull(c.ui.value.selected)
            c.select("en-US")
            c.start()
            assertEquals(listOf("en-US"), source.listened)
        }

    @Test
    fun nothingOfTheBaseLanguage_staysUnavailable_listsWhatIsInstalled_andNeverListens() =
        runTest {
            val source = FakeSpeechSource(available("de-DE", "fr-FR"))
            val c = controller(source)
            assertEquals(VoicePhase.UNAVAILABLE, c.ui.value.phase)
            val note = assertNotNull(c.ui.value.note)
            assertTrue(note.contains("de-DE, fr-FR") && note.contains("does not download it") && note.contains("Typing still works"))
            c.select("de-DE")
            c.start()
            assertEquals(emptyList(), source.listened)
        }

    @Test
    fun platformUnavailable_neverListens() =
        runTest {
            val source = FakeSpeechSource(SpeechReadiness.Unavailable("no on-device recognizer"))
            val c = controller(source)
            c.start()
            assertEquals(VoicePhase.UNAVAILABLE, c.ui.value.phase)
            assertEquals("no on-device recognizer", c.ui.value.note)
            assertEquals(emptyList(), source.listened)
        }

    @Test
    fun heard_reachesTheDraftOnly_failureCancelAndDenialLeaveItAlone() =
        runTest {
            val source = FakeSpeechSource(available("en-MY"))
            val c = controller(source)
            c.start()
            c.start()
            assertEquals(1, source.listened.size)
            source.finish(SpeechOutcome.Failed(speechErrorMessage(SpeechRecognizer.ERROR_NETWORK)))
            assertTrue(assertNotNull(c.ui.value.note).contains("on-device recognition only"))
            c.start()
            c.stop()
            assertNull(c.ui.value.note)
            c.permissionDenied()
            assertTrue(assertNotNull(c.ui.value.note).contains("Typing still works"))
            assertEquals(emptyList(), heard)
            c.start()
            source.finish(SpeechOutcome.Heard("write the weekly summary"))
            assertEquals(listOf("write the weekly summary"), heard)
            assertEquals(VoicePhase.READY, c.ui.value.phase)
            assertEquals("en-MY", c.ui.value.selected)
        }

    @Test
    fun requestAsksForExactlyTheSelectedLocale_andOnlyAnExactInstalledTagPasses() {
        val intent = speechIntent("en-GB")
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals("en-GB", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE))
        assertTrue(languageInstalled(listOf("en-US", "en-gb"), "en-GB"))
        assertFalse(languageInstalled(listOf("en-US"), "en-GB"))
        assertFalse(languageInstalled(listOf("en"), "en-US"))
        assertFalse(languageInstalled(null, "en-US"))
    }

    @Test
    fun mergeDraft_keepsWhatWasTyped() {
        assertEquals("/new call the dentist", mergeDraft("", " /new call the dentist "))
        assertEquals("/new and then call the dentist", mergeDraft("/new and then  ", "call the dentist"))
    }

    @Test
    fun platformGuard_requiresAndroid13_aRecognizer_andAReadableLanguageList() {
        assertNotNull(voiceUnavailableReason(32, true, listOf("en-US")))
        assertTrue(assertNotNull(voiceUnavailableReason(36, false, null)).contains("does not use a cloud one"))
        assertNotNull(voiceUnavailableReason(36, true, null))
        assertNull(voiceUnavailableReason(36, true, emptyList()))
    }

    @Test
    fun lateResults_afterStopOrRestart_changeNothing() =
        runTest {
            val source = FakeSpeechSource(available("en-MY"))
            val c = controller(source)
            c.start()
            c.stop()
            source.finish(SpeechOutcome.Heard("late words"), listen = 0)
            assertEquals(emptyList(), heard)
            assertEquals(VoicePhase.READY, c.ui.value.phase)
            c.start()
            c.stop()
            c.start()
            source.finish(SpeechOutcome.Failed("stale failure"), listen = 1)
            assertEquals(VoicePhase.LISTENING, c.ui.value.phase)
            assertNull(c.ui.value.note)
            source.finish(SpeechOutcome.Heard("current words"), listen = 2)
            assertEquals(listOf("current words"), heard)
        }

    @Test
    fun checkRunsOnce_soAnotherCompositionKeepsTheChosenLanguage() =
        runTest {
            val c = controller(FakeSpeechSource(available("en-US")))
            c.select("en-US")
            c.check()
            assertEquals("en-US", c.ui.value.selected)
        }

    @Test
    fun composer_sendTakesAndClearsTheDraft_andLateSpeechCannotRefillIt() =
        runTest {
            val source = FakeSpeechSource(available("en-MY"))
            val composer = ConversationComposer(source, initial = "typed")
            val voice = assertNotNull(composer.voice)
            voice.check()
            voice.start()
            assertEquals("typed", composer.take())
            assertEquals("", composer.draft.value)
            assertNull(voice.ui.value.note)
            assertEquals(1, source.cancels)
            source.finish(SpeechOutcome.Heard("late words"), listen = 0)
            assertEquals("", composer.draft.value)
            assertNull(composer.take())
        }

    @Test
    fun composer_aNewerDraftIsNotTouchedByWhatHappensToTheEarlierOne() =
        runTest {
            val source = FakeSpeechSource(available("en-MY"))
            val composer = ConversationComposer(source)
            val voice = assertNotNull(composer.voice)
            voice.check()
            composer.edit("first")
            assertEquals("first", composer.take())
            voice.start()
            source.finish(SpeechOutcome.Heard("second"))
            assertTrue(assertNotNull(voice.ui.value.note).contains("press Send"))
            assertEquals("second", composer.draft.value)
            composer.edit(composer.draft.value + " edited")
            assertEquals("second edited", composer.take())
            assertNull(voice.ui.value.note)
        }

    @Test
    fun composer_withNoSpeechSource_isJustATextBox() {
        val composer = ConversationComposer(null, initial = "hello")
        assertNull(composer.voice)
        assertEquals("hello", composer.take())
        composer.release()
    }
}
