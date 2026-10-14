package com.thalia.app.m3a

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveConsentStoreTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "m3a/consent.properties")

    @Test
    fun `consent is off by default`() {
        val store = LiveConsentStore(file)

        assertNull(store.current())
        assertFalse(store.isAccepted())
        assertFalse(file.exists(), "reading must not create anything")
    }

    @Test
    fun `accepting stores the wording version and the moment it was given`() {
        LiveConsentStore(file).accept(nowMillis = 1_790_000_000_123)

        // A fresh store on the same file, as after the process restarts.
        val reloaded = LiveConsentStore(file).current()
        assertEquals(LiveConsent(LiveConsentWording.VERSION, 1_790_000_000_123), reloaded)
    }

    @Test
    fun `a new wording version invalidates consent given to the old one`() {
        LiveConsentStore(file, wordingVersion = "v1").accept(nowMillis = 1)

        val newer = LiveConsentStore(file, wordingVersion = "v2")
        assertNull(newer.current())
        assertFalse(newer.isAccepted())

        newer.accept(nowMillis = 2)
        assertEquals(LiveConsent("v2", 2), newer.current())
        assertNull(LiveConsentStore(file, wordingVersion = "v1").current(), "and going back does not revive it")
    }

    @Test
    fun `withdrawing turns it off, and is safe to repeat`() {
        val store = LiveConsentStore(file)
        store.accept(nowMillis = 5)
        assertTrue(store.isAccepted())

        store.withdraw()
        store.withdraw()

        assertNull(LiveConsentStore(file).current())
    }

    @Test
    fun `an unreadable file is no consent`() {
        file.parentFile!!.mkdirs()
        file.writeText("wordingVersion=${LiveConsentWording.VERSION}\nacceptedAtMillis=yesterday\n")

        assertNull(LiveConsentStore(file).current())
    }

    @Test
    fun `the wording names the provider and everything that leaves the phone`() {
        val text = LiveConsentWording.TEXT
        listOf(
            "Gemini",
            "Google",
            "microphone",
            "system instruction",
            "synthetic",
            "transcripts",
            "usage metadata",
            "Free tier",
            "improve",
            "human reviewers",
        ).forEach { assertTrue(text.contains(it, ignoreCase = true), "the wording must mention $it") }
    }
}
