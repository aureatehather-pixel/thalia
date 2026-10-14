package com.thalia.app.planning.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals and opens the planning database's passphrase. Tests use a fake; the app uses [KeystorePassphraseSealer]. */
internal interface PassphraseSealer {
    fun seal(passphrase: ByteArray): ByteArray

    /** The passphrase, or an exception if it cannot be opened on this phone. */
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key generated inside, and never exported from, the Android Keystore. ADR 0009
 * decision 7.
 *
 * The key needs no user authentication, so a lock-screen change does not invalidate it, and nothing
 * in the app deletes it. The sealed bytes are bound to [ASSOCIATED_DATA], so a file sealed for
 * another purpose would not open as this passphrase. Layout: one version byte, one IV length byte,
 * the IV, then the ciphertext with its tag.
 *
 * [alias] names the Keystore key. Production uses [PRODUCTION_ALIAS]; the isolated `recoveryCheck`
 * build uses its own, so nothing it does can reach the real app's key even by mistake.
 */
internal class KeystorePassphraseSealer(
    private val alias: String,
) : PassphraseSealer {
    override fun seal(passphrase: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(ASSOCIATED_DATA)
        val ciphertext = cipher.doFinal(passphrase)
        val iv = cipher.iv
        return byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + ciphertext
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > 2 && sealed[0] == FORMAT_VERSION) { "unknown sealed passphrase format" }
        val ivLength = sealed[1].toInt()
        require(ivLength in 12..16 && sealed.size > 2 + ivLength) { "damaged sealed passphrase" }
        val key = key(create = false) ?: throw IllegalStateException("the planning key is not in the Keystore")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 2, ivLength))
        cipher.updateAAD(ASSOCIATED_DATA)
        return cipher.doFinal(sealed, 2 + ivLength, sealed.size - 2 - ivLength)
    }

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
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

    /** Whether this phone's Keystore holds the key. It is never created by asking. */
    fun hasKey(): Boolean = key(create = false) != null

    companion object {
        /** The real app's alias. Changing it would make every user's planning data unreadable. */
        const val PRODUCTION_ALIAS = "thalia-planning-db-v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val FORMAT_VERSION: Byte = 1
        private val ASSOCIATED_DATA = "thalia-planning-db-passphrase-v1".toByteArray()
    }
}
