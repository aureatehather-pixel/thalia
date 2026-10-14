package com.thalia.app.geminicheck

import com.thalia.app.ai.AiConnection
import com.thalia.app.ai.AiConsentStore
import com.thalia.app.ai.AiConsentWording
import com.thalia.app.ai.AiServices
import com.thalia.app.ai.AiTransportOverride
import com.thalia.app.ai.connectionText
import com.thalia.app.conversation.aiNotice
import com.thalia.app.secret.SealedSecret
import com.thalia.app.secret.SecretCipher
import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class PlainCipher : SecretCipher {
    override fun seal(plaintext: ByteArray) = SealedSecret(byteArrayOf(1), plaintext.reversedArray())

    override fun open(sealed: SealedSecret) = sealed.ciphertext.reversedArray()

    override fun destroyKey() = Unit
}

private object Silent : ConversationProvider {
    override suspend fun respond(request: ProviderRequest): ProviderResponse = ProviderResponse.Failed(ProviderFailure.UNAVAILABLE)
}

/** How the live check labels itself: external or local, its own consent version, and its amounts as estimates. No network. */
class GeminiCheckLabelsTest {
    @TempDir
    lateinit var dir: File

    private fun services(
        transport: ConversationProvider?,
        fake: Boolean,
    ) = AiServices(dir, transport, fake, PlainCipher())

    @Test
    fun `a real transport is labelled external on both screens, and a fake stays local`() {
        val live = services(Silent, fake = false)
        assertEquals(AiConnection.LIVE, live.connection)
        val settings = connectionText(live.connection)
        assertTrue("Google's Gemini" in settings && "external service" in settings && "over the internet" in settings)
        val notice = aiNotice(live.connection, consent = true, key = true)
        assertTrue("shares a snapshot" in notice && "Google's Gemini" in notice)
        assertFalse("fake" in notice || "CHECK BUILD" in notice)

        val fake = services(Silent, fake = true)
        assertEquals(AiConnection.FAKE, fake.connection)
        assertTrue("fake transport" in connectionText(fake.connection) && "not to Google" in connectionText(fake.connection))
        assertTrue("Nothing leaves the phone" in aiNotice(fake.connection, consent = true, key = true))
        assertEquals(AiConnection.NONE, services(null, fake = false).connection)
    }

    @Test
    fun `the override defaults to a fake and the check sets nothing until it runs`() {
        assertTrue(AiTransportOverride.fake)
        assertNull(AiTransportOverride.consentVersion)
        assertNull(AiTransportOverride.consentText)
        assertNull(AiTransportOverride.estimateNote)
    }

    @Test
    fun `changed consent needs fresh approval, in both directions`() {
        val file = File(dir, "consent.txt")
        val check = AiConsentStore(file, GeminiCheckWording.VERSION)
        check.accept(1)
        assertTrue(check.isAccepted())
        assertFalse(AiConsentStore(file).isAccepted(), "production wording must not count the check's consent")
        assertFalse(AiConsentStore(file, "ai-text-live-check-free-tier-earlier").isAccepted())

        val production = File(dir, "production.txt")
        AiConsentStore(production).accept(1)
        assertFalse(AiConsentStore(production, GeminiCheckWording.VERSION).isAccepted(), "production consent must not count here")
        assertTrue(GeminiCheckWording.VERSION != AiConsentWording.VERSION)
    }

    @Test
    fun `services built with the check wording use its version and text and production keeps its own`() {
        val check =
            AiServices(
                File(dir, "check"),
                Silent,
                false,
                PlainCipher(),
                GeminiCheckWording.VERSION,
                GeminiCheckWording.TEXT,
                GeminiCheckWording.ESTIMATE_NOTE,
            )
        check.consent.accept(1)
        assertEquals(GeminiCheckWording.VERSION, check.consent.current()?.wordingVersion)
        assertEquals(GeminiCheckWording.TEXT, check.consentText)
        val production = AiServices(File(dir, "prod"), Silent, false, PlainCipher())
        assertEquals(AiConsentWording.TEXT, production.consentText)
        assertNull(production.estimateNote)
        assertFalse(production.consent.isAccepted())
        production.consent.accept(1)
        assertEquals(AiConsentWording.VERSION, production.consent.current()?.wordingVersion)
    }

    @Test
    fun `the check wording says what the owner asked and claims nothing about billing`() {
        val t = GeminiCheckWording.TEXT
        listOf(
            "intended for a key from a free-tier",
            "does not verify billing status",
            "invented text only",
            "human reviewers may read",
            "improve its products",
            "gemini-3.8-flash",
            "external service",
            "at most 8 Sends",
            "USD 0.42",
        ).forEach { assertTrue(it in t, it) }
        assertFalse("billing is enabled" in t || "billing has been verified" in t)
        assertTrue("paid-equivalent" in GeminiCheckWording.ESTIMATE_NOTE && "not charges" in GeminiCheckWording.ESTIMATE_NOTE)
        assertTrue("Do not use a Free-tier key" in AiConsentWording.TEXT, "production wording is unchanged")
    }
}
