package com.thalia.app.ai

import com.thalia.app.secret.SealedSecret
import com.thalia.app.secret.SecretCipher
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanStoresTest {
    @TempDir
    lateinit var dir: File

    /** Reversible and fake, with switches for each failure. Not encryption. */
    private class FakeCipher : SecretCipher {
        var failSeal = false
        var failOpen = false
        var failDestroy = false
        var destroyed = 0

        override fun seal(plaintext: ByteArray): SealedSecret {
            if (failSeal) error("seal failed")
            return SealedSecret(byteArrayOf(1, 2, 3), plaintext.reversedArray())
        }

        override fun open(sealed: SealedSecret): ByteArray {
            if (failOpen) error("open failed")
            return sealed.ciphertext.reversedArray()
        }

        override fun destroyKey() {
            destroyed++
            if (failDestroy) error("destroy failed")
        }
    }

    private val secret = "{\"synthetic\":\"not-a-real-credential\"}"
    private val file get() = File(dir, "s.sealed")

    @Test
    fun `a synthetic secret is written, read back, replaced and cleared`() {
        val cipher = FakeCipher()
        val store = PlanSecretStore(file, cipher)
        assertNull(store.load())
        assertFalse(store.isReadable())
        assertEquals(PlanSecretStore.SaveResult.SAVED, store.save(secret))
        assertEquals(secret, store.load())
        assertTrue(store.isReadable())
        assertFalse(secret in file.readText(Charsets.ISO_8859_1), "the file holds the secret in the clear")
        assertEquals(PlanSecretStore.SaveResult.SAVED, store.save("second"))
        assertEquals("second", store.load())
        assertTrue(store.clear())
        assertNull(store.load())
        assertFalse(file.exists())
        assertEquals(1, cipher.destroyed)
    }

    @Test
    fun `a failed save leaves the earlier secret and an empty secret is refused`() {
        val cipher = FakeCipher()
        val store = PlanSecretStore(file, cipher)
        store.save(secret)
        cipher.failSeal = true
        assertEquals(PlanSecretStore.SaveResult.FAILED, store.save("second"))
        cipher.failSeal = false
        assertEquals(secret, store.load())
        assertEquals(PlanSecretStore.SaveResult.FAILED, store.save(""))
        assertEquals(secret, store.load())
    }

    @Test
    fun `unreadable or corrupt storage reads as signed out and does not throw`() {
        val cipher = FakeCipher()
        val store = PlanSecretStore(file, cipher)
        store.save(secret)
        cipher.failOpen = true
        assertNull(store.load())
        assertFalse(store.isReadable())
        cipher.failOpen = false
        listOf(byteArrayOf(), byteArrayOf(9, 9, 9), byteArrayOf(1, 0, 5), byteArrayOf(1, 3, 1, 2, 3), file.readBytes().copyOf(4)).forEach {
            file.writeBytes(it)
            assertNull(store.load(), it.size.toString())
        }
        file.delete()
        file.mkdir()
        assertNull(store.load())
    }

    @Test
    fun `clearing removes the file even when the key cannot be destroyed, and reports it`() {
        val cipher = FakeCipher()
        val store = PlanSecretStore(file, cipher)
        store.save(secret)
        cipher.failDestroy = true
        assertFalse(store.clear())
        assertFalse(file.exists())
        assertNull(store.load())
        assertTrue(PlanSecretStore(file, FakeCipher()).clear())
    }

    @Test
    fun `a secret never appears in a failure result or a file name`() {
        val cipher = FakeCipher().apply { failSeal = true }
        val result = PlanSecretStore(file, cipher).save(secret)
        assertFalse(secret in result.toString())
        assertTrue(dir.listFiles().orEmpty().none { secret in it.name })
    }

    @Test
    fun `the model choice is stored without a default and invalid identifiers change nothing`() {
        val store = PlanModelStore(File(dir, "m.txt"))
        assertNull(store.selected())
        assertTrue(store.select("  some-model_1.2:x  "))
        assertEquals("some-model_1.2:x", store.selected())
        listOf("", "   ", "has space", "new\nline", "a".repeat(101), "q;rm", "m\u0000").forEach {
            assertFalse(store.select(it), it)
            assertEquals("some-model_1.2:x", store.selected())
        }
        assertTrue(store.select("other"))
        assertEquals("other", PlanModelStore(File(dir, "m.txt")).selected())
        store.clear()
        assertNull(store.selected())
    }

    @Test
    fun `a corrupt model file reads as none chosen`() {
        val f = File(dir, "m.txt")
        f.writeText("bad value with spaces")
        assertNull(PlanModelStore(f).selected())
        f.delete()
        f.mkdir()
        assertNull(PlanModelStore(f).selected())
    }

    @Test
    fun `readiness reads the real stores and a stored secret is only stored`() {
        val consent = AiConsentStore(File(dir, "c.txt"), PlanRouteConsentWording.VERSION)
        val model = PlanModelStore(File(dir, "m.txt"))
        val secrets = PlanSecretStore(file, FakeCipher())
        val sources = PlanRouteSources(consent, model::selected, secrets::isReadable)
        assertEquals(PlanRouteReadiness.CONSENT_NEEDED, sources.readiness())
        consent.accept(1)
        assertEquals(PlanRouteReadiness.MODEL_NEEDED, sources.readiness())
        model.select("m")
        assertEquals(PlanRouteReadiness.SIGN_IN_NEEDED, sources.readiness())
        secrets.save(secret)
        assertEquals(PlanRouteReadiness.SIGN_IN_UNVERIFIED, sources.readiness())
        file.writeBytes(byteArrayOf(7))
        assertEquals(PlanRouteReadiness.SIGN_IN_NEEDED, sources.readiness())
    }

    @Test
    fun `the production wiring is unavailable-safe, consent stays unaccepted and the route is never ready`() {
        val services = AiServices(dir, null, cipher = FakeCipher(), planCipher = FakeCipher())
        assertEquals(PlanRouteReadiness.CONSENT_NEEDED, services.planRoute.readiness())
        services.planModel.select("m")
        services.planSecrets.save(secret)
        assertEquals(PlanRouteReadiness.CONSENT_NEEDED, services.planRoute.readiness())
        assertNull(ConversationRoutes.installed)
    }
}
