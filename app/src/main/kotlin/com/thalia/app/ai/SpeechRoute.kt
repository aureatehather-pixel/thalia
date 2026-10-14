package com.thalia.app.ai

import com.thalia.app.secret.ApiKeyStore
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** Why a reply was not spoken, in words for the person. The text on screen is always complete either way. */
internal enum class SpeechProblem(
    val text: String,
) {
    OFF("Spoken replies are off."),
    CONSENT("Spoken replies are not turned on in AI settings, so nothing was sent for speech."),
    NO_KEY("No speech key is saved, so nothing was sent for speech."),
    NO_CAP("No monthly voice limit is set in AI settings, so voice is off. The reply is on screen."),
    BUDGET("This month's voice limit is used up, so this reply was not spoken."),
    LEDGER("The voice usage record could not be written, so this reply was not spoken."),
    NETWORK("Could not reach the speech service, so this reply was not spoken."),
    AUTH("The speech service did not accept the key, so this reply was not spoken."),
    LIMITED("The speech service is limiting requests or has no credits left, so this reply was not spoken."),
    SERVICE("The speech service had a problem, so this reply was not spoken."),
    UNREADABLE("The speech service sent audio Thalia could not use, so this reply was not spoken."),
    PLAYBACK("The phone could not play the audio, so this reply was not spoken."),
    NOT_BUILT("This build has no connection to a speech service."),
}

internal sealed interface SpeechOutcome {
    /** All audio was handed to the sink. [characters] were submitted; [firstAudioMillis] is request start to first audio. */
    class Streamed(
        val characters: Int,
        val firstAudioMillis: Long,
    ) : SpeechOutcome

    /** The sink closed early (Stop or Mute); [characters] were still submitted. */
    class Stopped(
        val characters: Int,
    ) : SpeechOutcome

    data class Failed(
        val problem: SpeechProblem,
    ) : SpeechOutcome
}

/** Where streamed PCM goes (16 bit little-endian mono, [PcmFormat.SAMPLE_RATE] Hz). [write] returns false once playback is closed. */
internal fun interface PcmSink {
    fun write(
        pcm: ByteArray,
        length: Int,
    ): Boolean
}

internal object PcmFormat {
    const val SAMPLE_RATE = 24_000
}

/** Turns text into audio, handing it to [sink] as it arrives. The real one is Cartesia in a build that has a network; a test passes a fake. */
internal interface SpeechSynthesizer {
    suspend fun synthesize(
        text: String,
        sink: PcmSink,
    ): SpeechOutcome
}

/** Plays one streamed clip at a time. */
internal interface AudioPlayback {
    /** Opens a fresh stream; anything playing is stopped first. */
    fun open(): PcmSink

    /** Returns when everything written has been heard, or the stream was stopped (true), or the phone could not play it (false). */
    suspend fun awaitEnd(): Boolean

    fun stop()
}

/** What the person agrees to before reply text leaves the phone for speech. Changing a word changes [VERSION]. */
internal object SpeechConsentWording {
    const val VERSION: String = "speech-cartesia-2026-10-13"

    const val TEXT: String =
        "Thalia can read its replies aloud in the Cartesia voice Ella (Caring Scout), model sonic-3.6, using the Cartesia " +
            "API key you save on this screen.\n\n" +
            "Only the words of one reply are sent to Cartesia, only when Thalia is about to speak them: the line you can already " +
            "read on screen, such as a question, a proposal waiting for your tap, or the result of something you approved. " +
            "Your planning snapshot, your messages and your microphone audio are never sent for speech (your speech is " +
            "recognised on the phone). Thalia plays the audio as it arrives and does not store it.\n\n" +
            "Cartesia counts this in credits, about one per character. Every character submitted to Cartesia is counted against " +
            "a monthly limit that you set and that is kept on this phone, which is an estimate, not Cartesia's count. Until you set " +
            "a limit, nothing is sent for speech. Replies longer than ${SPEECH_MAX_CHARS} " +
            "characters are shortened for speech; the full text stays on screen. Turning spoken replies off stops all speech " +
            "requests. Thalia never says that something is saved until it has been saved."
}

internal const val SPEECH_MAX_CHARS = 400

/**
 * The 2,000-character allowance of the authorized livecheck test (`speech-budget.log`). Kept only so that old ledger can still be
 * read for the record; it is not the monthly cap and nothing resets it.
 */
internal const val SPEECH_TEST_LEDGER_CHARS = 2_000L

/**
 * The gates in front of one spoken reply, in order: consent, a saved key, a monthly cap that is set, and room under it. The
 * characters are counted before the request is made and are never returned, so a failed request and a repeat both count.
 * A refusal at any gate means nothing was sent. Exactly one request is made per reply; a failed one is not retried, and a reply that is already speaking
 * is replaced, never queued.
 */
internal class SpokenReplies(
    private val synthesizer: SpeechSynthesizer?,
    private val playback: AudioPlayback,
    private val consent: AiConsentStore,
    private val keys: ApiKeyStore,
    private val usage: SpeechUsage,
    private val clock: () -> Instant = Instant::now,
    /** One line per request: characters, result, milliseconds to first audio. Never any text or key. */
    private val report: (String) -> Unit = {},
) {
    private val busy = AtomicBoolean(false)

    @Volatile
    private var generation = 0

    /** Speaks [text] (shortened to [SPEECH_MAX_CHARS] at a sentence end where possible). Returns null when it was spoken. */
    suspend fun speak(text: String): SpeechProblem? {
        if (synthesizer == null) return SpeechProblem.NOT_BUILT
        if (!consent.isAccepted()) return SpeechProblem.CONSENT
        if (!keys.hasKey()) return SpeechProblem.NO_KEY
        val clean = shorten(text)
        if (clean.isEmpty()) return null
        // A newer reply replaces whatever is speaking now.
        val mine = ++generation
        playback.stop()
        if (!busy.compareAndSet(false, true)) return null
        try {
            if (mine != generation) return null
            val now = clock()
            when (usage.count(clean.length, now)) {
                SpeechCount.Counted -> Unit
                SpeechCount.NoCap -> return SpeechProblem.NO_CAP
                is SpeechCount.CapReached -> return SpeechProblem.BUDGET
                SpeechCount.Unwritable -> return SpeechProblem.LEDGER
            }
            val sink = playback.open()
            val outcome = synthesizer.synthesize(clean, sink)
            when (outcome) {
                is SpeechOutcome.Failed -> {
                    playback.stop()
                    report("chars=${clean.length} result=${outcome.problem}")
                    return outcome.problem
                }
                is SpeechOutcome.Stopped -> {
                    report("chars=${outcome.characters} result=STOPPED")
                    return null
                }
                is SpeechOutcome.Streamed -> {
                    report("chars=${outcome.characters} result=STREAMED firstAudioMs=${outcome.firstAudioMillis}")
                    if (mine != generation) return null
                    return if (playback.awaitEnd()) null else SpeechProblem.PLAYBACK
                }
            }
        } finally {
            busy.set(false)
        }
    }

    fun stop() {
        generation++
        playback.stop()
    }

    companion object {
        /** At most [SPEECH_MAX_CHARS], cut after the last sentence end that fits, else at a space. Nothing is added. */
        fun shorten(text: String): String {
            val t = text.replace(Regex("\\s+"), " ").trim()
            if (t.length <= SPEECH_MAX_CHARS) return t
            val head = t.take(SPEECH_MAX_CHARS)
            val sentence = head.lastIndexOfAny(charArrayOf('.', '?', '!'))
            return when {
                sentence >= 40 -> head.take(sentence + 1)
                head.lastIndexOf(' ') > 0 -> head.take(head.lastIndexOf(' '))
                else -> head
            }
        }
    }
}
