package com.thalia.app.ai

import com.thalia.app.secret.writeAtomically
import java.io.File

/**
 * What the person agrees to before Thalia may send anything to an AI service. ADR 0013, under ADR 0003 decision 2.
 * Changing a word of [TEXT] means changing [VERSION], which withdraws every consent given to the earlier wording.
 * 2026-10-13: the Approve-only rule replaced the earlier "stated facts are saved at once" sentence, so the version moved on.
 */
object AiConsentWording {
    const val VERSION: String = "ai-text-paid-2026-10-13"

    const val TEXT: String =
        "Thalia can ask Google's Gemini (model gemini-3.8-flash, through the Gemini Developer API) to help you plan, using the " +
            "API key you save on this screen.\n\n" +
            "Nothing is sent until you press Send in a conversation. Each time you do, Thalia sends Google:\n" +
            "- a fixed instruction telling the model its job and limits;\n" +
            "- a snapshot of your planning data: your direction, active goals, projects with the purpose, done-when, " +
            "constraints and blocker you recorded, open actions with priority, estimate and due date, today's plan and the " +
            "outcomes recorded for it, today's fixed commitments, your stated focus capacity and the last closing note;\n" +
            "- up to the last 10 lines of this conversation, including what you type;\n" +
            "- a list of the function names the model may suggest.\n" +
            "Inbox captures are never included, except the up to 5 you choose with Triage with Thalia, each cut to its first " +
            "400 characters, and the screen shows you which before you press Send.\n\n" +
            "Google returns text and suggestions, and reports how many tokens were used. Thalia does not store the " +
            "conversation. Every change the model proposes, including an outcome, a focus capacity or an Inbox filing taken from " +
            "what you said or typed, changes nothing until you tap Approve. Typing or saying yes never approves.\n\n" +
            "Use a key from a project with billing enabled. Google's Gemini API terms, as read on 2026-10-06, say content " +
            "sent on paid services is not used to improve Google's products, while on the Free tier it may be and human " +
            "reviewers may read it. Thalia cannot check which tier your key is on. Do not use a Free-tier key.\n\n" +
            "Each request is counted against a monthly budget kept on this phone. That count is an estimate, not Google's " +
            "invoice. Turning AI off stops all requests; planning, outcomes, check-ins and the Inbox work without it."
}

/** Consent to [AiConsentWording], given at [acceptedAtMillis]. */
data class AiConsent(
    val wordingVersion: String,
    val acceptedAtMillis: Long,
)

/** Whether the person has accepted the current wording. Off until accepted; any other stored wording counts as none. */
class AiConsentStore(
    private val file: File,
    private val wordingVersion: String = AiConsentWording.VERSION,
) {
    private val lock = Any()

    fun current(): AiConsent? =
        synchronized(lock) {
            read()?.takeIf { it.wordingVersion == wordingVersion }
        }

    fun isAccepted(): Boolean = current() != null

    fun accept(nowMillis: Long): AiConsent =
        synchronized(lock) {
            val consent = AiConsent(wordingVersion, nowMillis)
            writeAtomically(file, "version=${consent.wordingVersion}\naccepted=${consent.acceptedAtMillis}\n".toByteArray())
            consent
        }

    /** One tap withdraws it. */
    fun withdraw() {
        synchronized(lock) { file.delete() }
    }

    private fun read(): AiConsent? {
        if (!file.isFile) return null
        val fields =
            try {
                file.readLines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            } catch (e: java.io.IOException) {
                return null
            }
        val version = fields["version"]?.takeIf { it.isNotBlank() } ?: return null
        val accepted = fields["accepted"]?.toLongOrNull() ?: return null
        return AiConsent(version, accepted)
    }
}
