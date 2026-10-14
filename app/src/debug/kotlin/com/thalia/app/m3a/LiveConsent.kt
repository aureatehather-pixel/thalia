package com.thalia.app.m3a

import com.thalia.app.secret.writeAtomically
import java.io.File

/**
 * What the user agrees to before the M3A spike may send anything to Google.
 *
 * ADR 0008 decision 5. Changing a word of [TEXT] means changing [VERSION], which withdraws every
 * consent given to the earlier wording.
 */
object LiveConsentWording {
    const val VERSION: String = "m3a-consent-free-tier-2026-10-02"

    const val TEXT: String =
        "This test sends data to Google's Gemini Live API, on the Free tier of the Gemini Developer " +
            "API, using the API key saved below. While a test session runs, it sends Google:\n" +
            "- the audio from this phone's microphone, captured only while the session is running;\n" +
            "- a fixed text system instruction saying this is a connection test;\n" +
            "- one fixed, synthetic context line, which holds none of your goals, plans, calendar or " +
            "other personal data;\n" +
            "- one short text request asking the model to greet you.\n" +
            "Google returns, and so also processes, transcripts of what you and the model say, and usage " +
            "metadata about each session. Transcripts are shown here and kept only in memory; no " +
            "audio or transcript is stored on the phone. On the Free tier, Google may use what this " +
            "test sends and the responses to improve its products, and human reviewers may read them, " +
            "so say nothing personal or confidential during a test. Scheduled check-ins do not use Gemini."
}

/** Consent to [LiveConsentWording], given at [acceptedAtMillis]. */
data class LiveConsent(
    val wordingVersion: String,
    val acceptedAtMillis: Long,
)

/**
 * Whether the user has accepted the current consent wording. Off until accepted.
 *
 * Kept in an app-private file. A stored consent to any wording other than [wordingVersion] counts
 * as no consent at all.
 */
class LiveConsentStore(
    private val file: File,
    private val wordingVersion: String = LiveConsentWording.VERSION,
) {
    private val lock = Any()

    /** The stored consent, if it is to the current wording. Reads only. */
    fun current(): LiveConsent? =
        synchronized(lock) {
            val stored = read() ?: return null
            stored.takeIf { it.wordingVersion == wordingVersion }
        }

    fun isAccepted(): Boolean = current() != null

    fun accept(nowMillis: Long): LiveConsent =
        synchronized(lock) {
            val consent = LiveConsent(wordingVersion, nowMillis)
            writeAtomically(file, "$VERSION_KEY=${consent.wordingVersion}\n$ACCEPTED_KEY=${consent.acceptedAtMillis}\n".toByteArray())
            consent
        }

    /** One tap withdraws it. */
    fun withdraw() {
        synchronized(lock) { file.delete() }
    }

    private fun read(): LiveConsent? {
        if (!file.exists()) return null
        val fields =
            file
                .readLines()
                .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                .associate { (key, value) -> key to value }
        val version = fields[VERSION_KEY]?.takeIf { it.isNotBlank() } ?: return null
        val accepted = fields[ACCEPTED_KEY]?.toLongOrNull() ?: return null
        return LiveConsent(version, accepted)
    }

    private companion object {
        const val VERSION_KEY = "wordingVersion"
        const val ACCEPTED_KEY = "acceptedAtMillis"
    }
}
