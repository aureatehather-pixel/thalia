package com.thalia.app.m3a

import com.thalia.app.secret.ApiKeyStore
import com.thalia.app.secret.SealedSecret
import com.thalia.app.secret.SecretCipher
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fake key, never a real one. Distinctive, so a leak is easy to find. */
private const val FAKE_KEY = "fake-m3a-test-value-not-a-key-0123456789"

/** Real AES-GCM with a key held in memory, standing in for the Android Keystore. */
private class InMemoryCipher : SecretCipher {
    private var key: SecretKey? = null
    var failSeal = false
    var failDestroy = false
    var destroyed = 0

    override fun seal(plaintext: ByteArray): SealedSecret {
        if (failSeal) throw IllegalStateException("keystore unavailable")
        val secret =
            key ?: KeyGenerator
                .getInstance("AES")
                .apply { init(256) }
                .generateKey()
                .also { key = it }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, iv))
        return SealedSecret(iv, cipher.doFinal(plaintext))
    }

    override fun open(sealed: SealedSecret): ByteArray {
        val secret = key ?: throw IllegalStateException("no key")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, sealed.iv))
        return cipher.doFinal(sealed.ciphertext)
    }

    override fun destroyKey() {
        destroyed++
        if (failDestroy) throw IllegalStateException("keystore unavailable")
        key = null
    }
}

class ApiKeyStoreTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "m3a/gemini-key.sealed")
    private val cipher = InMemoryCipher()
    private val store get() = ApiKeyStore(file, cipher)

    @Test
    fun `nothing is saved at first`() {
        assertFalse(store.hasKey())
        assertNull(store.load())
    }

    @Test
    fun `a saved key is encrypted on disk and opens again after a reload`() {
        assertEquals(ApiKeyStore.SaveResult.Saved, store.save("  $FAKE_KEY\n"))

        assertTrue(store.hasKey())
        val onDisk = file.readBytes()
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains(FAKE_KEY), "the file must not hold the key in the clear")
        assertEquals(FAKE_KEY, ApiKeyStore(file, cipher).load(), "trimmed, and readable by a fresh store")
    }

    @Test
    fun `a blank entry or one with spaces is refused without echoing it or touching the cipher`() {
        val blank = store.save("   ")
        val spaced = store.save("fake secret part")

        assertIs<ApiKeyStore.SaveResult.Invalid>(blank)
        assertIs<ApiKeyStore.SaveResult.Invalid>(spaced)
        assertFalse(spaced.toString().contains("secret"), "the message must not repeat the entry")
        assertFalse(store.hasKey())
    }

    @Test
    fun `an encryption failure saves nothing and says nothing about the key`() {
        cipher.failSeal = true

        val result = store.save(FAKE_KEY)

        assertEquals(ApiKeyStore.SaveResult.EncryptionFailed, result)
        assertFalse(result.toString().contains(FAKE_KEY))
        assertFalse(store.hasKey())
    }

    @Test
    fun `saving again replaces the key`() {
        store.save(FAKE_KEY)
        store.save("${FAKE_KEY}2")

        assertEquals("${FAKE_KEY}2", store.load())
    }

    @Test
    fun `clear deletes the file and the cipher key, and is safe to repeat`() {
        store.save(FAKE_KEY)

        assertEquals(ApiKeyStore.ClearResult.Cleared, store.clear())
        assertEquals(ApiKeyStore.ClearResult.Cleared, store.clear())

        assertFalse(file.exists())
        assertFalse(store.hasKey())
        assertNull(store.load())
        assertEquals(2, cipher.destroyed)
    }

    @Test
    fun `when the keystore refuses to delete its key, the file is still gone`() {
        store.save(FAKE_KEY)
        cipher.failDestroy = true

        assertEquals(ApiKeyStore.ClearResult.Failed, store.clear())
        assertFalse(store.hasKey())
    }

    @Test
    fun `a key that can no longer be opened loads as nothing, without throwing`() {
        store.save(FAKE_KEY)
        cipher.destroyKey()

        assertTrue(store.hasKey(), "the file is still there for the user to clear")
        assertNull(store.load())
    }

    @Test
    fun `a corrupted file loads as nothing`() {
        store.save(FAKE_KEY)
        file.writeBytes(byteArrayOf(9, 9, 9))

        assertNull(store.load())
    }
}
