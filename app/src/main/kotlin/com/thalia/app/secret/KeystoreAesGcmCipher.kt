package com.thalia.app.secret

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key generated inside, and never exported from, the Android Keystore.
 *
 * The sealed bytes are bound to [associatedData], so a file sealed for another purpose would not open as this secret. The
 * defaults are the M3A spike's; Thalia's own key passes its own alias and data (ADR 0013).
 */
class KeystoreAesGcmCipher(
    private val alias: String = DEFAULT_ALIAS,
    private val associatedData: ByteArray = ASSOCIATED_DATA,
) : SecretCipher {
    override fun seal(plaintext: ByteArray): SealedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        return SealedSecret(cipher.iv, ciphertext)
    }

    override fun open(sealed: SealedSecret): ByteArray {
        val key = key(create = false) ?: throw IllegalStateException("no Keystore key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(sealed.ciphertext)
    }

    override fun destroyKey() {
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "thalia-m3a-gemini-api-key"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private val ASSOCIATED_DATA = "thalia-m3a-gemini-api-key-v1".toByteArray()
    }
}
