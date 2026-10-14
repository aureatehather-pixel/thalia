package com.thalia.app.ai

import android.content.Context
import com.thalia.app.secret.ApiKeyStore
import com.thalia.app.secret.KeystoreAesGcmCipher
import com.thalia.app.secret.SecretCipher
import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.GeminiPrices
import com.thalia.conversation.PriceSchedule
import java.io.File
import java.time.Instant

/**
 * A transport placed behind the real gates by an isolated check build, before the first AI request: a fake that sends
 * nothing (conversationCheck), or the real Gemini transport (geminiCheck, with [fake] false). Production builds never set it.
 */
internal object AiTransportOverride {
    @Volatile
    var transport: ConversationProvider? = null

    /** True for a fake that sends nothing; the isolated live check build sets it false for the real transport. */
    @Volatile
    var fake: Boolean = true

    /** The isolated live check's own consent wording and its version, so its consent is separate from production's. Null in every other build. */
    @Volatile
    var consentVersion: String? = null

    @Volatile
    var consentText: String? = null

    /** A line shown under the budget in a check build: what its amounts are. Null in every other build. */
    @Volatile
    var estimateNote: String? = null
}

/** What stands behind the gates: nothing, a fake inside an isolated check build, or a live service. */
internal enum class AiConnection { NONE, FAKE, LIVE }

/**
 * The AI pieces of one installation, in one private folder: the consent, the sealed key, the budget ledger and the gated
 * provider (ADR 0013). [transport] is null in a build that cannot reach a service; no build wires one in Slice 4A, so
 * [provider] answers every request as unavailable, and a saved key or consent changes nothing until one does.
 */
internal class AiServices(
    directory: File,
    transport: ConversationProvider?,
    fake: Boolean = false,
    cipher: SecretCipher = KeystoreAesGcmCipher(KEY_ALIAS, KEY_AAD),
    consentVersion: String = AiConsentWording.VERSION,
    /** The words shown beside the consent button. Whoever changes them changes [consentVersion] too. */
    val consentText: String = AiConsentWording.TEXT,
    /** Shown under the budget in a check build; production leaves it null. */
    val estimateNote: String? = null,
    /** Seals the ChatGPT-plan route's stored sign-in. Last of the original parameters, so existing positional callers are unaffected. */
    planCipher: SecretCipher = KeystoreAesGcmCipher(PLAN_KEY_ALIAS, PLAN_KEY_AAD),
    /**
     * The everyday route's live reasoning transport, built over the stored key. Null where the build has no live route; then
     * [transport] alone decides, exactly as before.
     */
    liveTransport: ((ApiKeyStore) -> ConversationProvider)? = null,
    /** The price table the budget is counted in. Gemini's unless the live route says otherwise. */
    val prices: PriceSchedule = GeminiPrices.TEXT,
    keyFileName: String = "gemini-text-key.sealed",
    /** The label of the key field, and the service the notices name. */
    val keyLabel: String = "Gemini API key",
    val providerName: String = "Google's Gemini",
    initialBudgetMicros: Long = Usd.DEFAULT_MONTHLY_BUDGET_MICROS,
    /** Builds the spoken-reply synthesizer over the stored speech key; null where the build has none. */
    speechFactory: ((ApiKeyStore) -> SpeechSynthesizer)? = null,
    speechCipher: SecretCipher = KeystoreAesGcmCipher(SPEECH_KEY_ALIAS, SPEECH_KEY_AAD),
) {
    val keys = ApiKeyStore(File(directory, keyFileName), cipher)
    private val resolved: ConversationProvider? = transport ?: liveTransport?.invoke(keys)
    val connection: AiConnection =
        when {
            resolved == null -> AiConnection.NONE
            fake -> AiConnection.FAKE
            else -> AiConnection.LIVE
        }
    val consent = AiConsentStore(File(directory, "consent.txt"), consentVersion)

    /** The ChatGPT-plan route's stores and read-only setup state. Nothing writes the sign-in or shows it works, and consent is a draft. */
    val planModel = PlanModelStore(File(directory, "chatgpt-plan-model.txt"))
    val planSecrets = PlanSecretStore(File(directory, "chatgpt-plan-credentials.sealed"), planCipher)
    val planRoute = PlanRouteSources(planRouteConsentStore(directory), planModel::selected, planSecrets::isReadable)
    val ledger = BudgetLedger(File(directory, "budget.log"), initialBudgetMicros)
    private val gates = AiGates(consent, keys, ledger, prices)
    val provider: ConversationProvider = GatedConversationProvider(resolved, gates)

    /** Spoken replies: their own key, consent and small budget, all separate from the reasoning route's. */
    val speechKeys = ApiKeyStore(File(directory, "speech-key.sealed"), speechCipher)
    val speechConsent = AiConsentStore(File(directory, "speech-consent.txt"), SpeechConsentWording.VERSION)

    /** The monthly voice cap and meter. Unset means voice is off. */
    val speechUsage = SpeechUsage(File(directory, "speech-usage.log"))

    /** The 2,000-character livecheck test ledger, kept as evidence. Read for the record only; nothing resets it or adds to it. */
    val speechTestLedger = BudgetLedger(File(directory, "speech-budget.log"), SPEECH_TEST_LEDGER_CHARS)

    val synthesizer: SpeechSynthesizer? = speechFactory?.invoke(speechKeys)
    val prefs = ConversationPrefs(File(directory, "conversation-prefs.txt"))

    private val speechReport = File(directory, "speech-report.txt")

    fun spokenReplies(playback: AudioPlayback) =
        SpokenReplies(
            synthesizer,
            playback,
            speechConsent,
            speechKeys,
            speechUsage,
            report = { line -> runCatching { speechReport.appendText("${Instant.now()} $line\n") } },
        )

    companion object {
        /** The folder under the files directory that holds every AI file. data_extraction_rules.xml excludes it by this name. */
        const val DIRECTORY_NAME = "ai"
        const val KEY_ALIAS = "thalia-ai-gemini-text-key"
        private val KEY_AAD = "thalia-ai-gemini-text-key-v1".toByteArray()
        const val OPENAI_KEY_ALIAS = "thalia-ai-openai-key"
        val OPENAI_KEY_AAD = "thalia-ai-openai-key-v1".toByteArray()
        const val SPEECH_KEY_ALIAS = "thalia-ai-speech-key"
        private val SPEECH_KEY_AAD = "thalia-ai-speech-key-v1".toByteArray()
        const val PLAN_KEY_ALIAS = "thalia-ai-chatgpt-plan-credentials"
        private val PLAN_KEY_AAD = "thalia-ai-chatgpt-plan-credentials-v1".toByteArray()

        fun create(
            context: Context,
            transport: ConversationProvider?,
            fake: Boolean = false,
            consentVersion: String = AiConsentWording.VERSION,
            consentText: String = AiConsentWording.TEXT,
            estimateNote: String? = null,
        ): AiServices {
            val live = if (transport == null) LiveRoute.load() else null
            val directory = File(context.filesDir, DIRECTORY_NAME)
            return if (live == null) {
                AiServices(
                    directory,
                    transport,
                    fake,
                    consentVersion = consentVersion,
                    consentText = consentText,
                    estimateNote = estimateNote,
                )
            } else {
                // The everyday route: OpenAI's API for reasoning and Cartesia's Ella for speech, both with keys the person
                // types into AI settings. Its own wording, key file, Keystore key and a USD 1 starting budget.
                AiServices(
                    directory,
                    null,
                    cipher = KeystoreAesGcmCipher(OPENAI_KEY_ALIAS, OPENAI_KEY_AAD),
                    consentVersion = OpenAiConsentWording.VERSION,
                    consentText = OpenAiConsentWording.TEXT,
                    estimateNote = estimateNote,
                    liveTransport = { keys -> live.reasoning { keys.load() } },
                    prices = com.thalia.conversation.OpenAiPrices.TEXT,
                    keyFileName = "openai-key.sealed",
                    keyLabel = "OpenAI API key",
                    providerName = "OpenAI",
                    initialBudgetMicros = OpenAiConsentWording.INITIAL_BUDGET_MICROS,
                    speechFactory = { keys -> live.speech { keys.load() } },
                )
            }
        }
    }
}
