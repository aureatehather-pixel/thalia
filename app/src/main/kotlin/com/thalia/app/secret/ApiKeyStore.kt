package com.thalia.app.secret

import java.io.File

/**
 * Seals and opens a small secret with a key that never leaves the cipher.
 *
 * On the phone this is [KeystoreAesGcmCipher]. Tests pass their own, so nothing here depends on
 * a real Android Keystore.
 */
interface SecretCipher {
    fun seal(plaintext: ByteArray): SealedSecret

    fun open(sealed: SealedSecret): ByteArray

    /** Delete the key, so whatever it sealed can never be opened again. */
    fun destroyKey()
}

/** An AES-GCM ciphertext and the nonce it was sealed with. Neither is secret. */
class SealedSecret(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

/**
 * The dedicated M3A Gemini API key, entered at runtime and stored encrypted in an app-private file.
 *
 * ADR 0008 decision 4. The key is never logged, never shown again once saved, and never put in
 * an exception message: every failure below is described in fixed words, and no cause that could
 * carry the key is kept.
 */
class ApiKeyStore(
    private val file: File,
    private val cipher: SecretCipher,
) {
    private val lock = Any()

    sealed interface SaveResult {
        data object Saved : SaveResult

        /** The entry cannot be a key. The message describes the problem and never echoes the entry. */
        data class Invalid(
            val message: String,
        ) : SaveResult

        data object EncryptionFailed : SaveResult
    }

    sealed interface ClearResult {
        data object Cleared : ClearResult

        data object Failed : ClearResult
    }

    /** Whether a sealed key is on disk. It does not try to open it. */
    fun hasKey(): Boolean = synchronized(lock) { file.isFile }

    fun save(entry: String): SaveResult {
        val key = entry.trim()
        if (key.isEmpty()) return SaveResult.Invalid("The key must not be blank.")
        if (key.any { it.isWhitespace() }) return SaveResult.Invalid("The key must not contain spaces or line breaks.")
        return synchronized(lock) {
            val plaintext = key.toByteArray(Charsets.UTF_8)
            try {
                val sealed = cipher.seal(plaintext)
                writeAtomically(file, encode(sealed))
                SaveResult.Saved
            } catch (e: Exception) {
                SaveResult.EncryptionFailed
            } finally {
                plaintext.fill(0)
            }
        }
    }

    /**
     * Delete the sealed key and the Keystore key that sealed it. Safe to repeat. Even when the
     * Keystore refuses, the file is gone, so the key can no longer be used.
     */
    fun clear(): ClearResult =
        synchronized(lock) {
            val fileGone = !file.exists() || file.delete()
            val keyGone =
                try {
                    cipher.destroyKey()
                    true
                } catch (e: Exception) {
                    false
                }
            if (fileGone && keyGone) ClearResult.Cleared else ClearResult.Failed
        }

    /**
     * Open the stored key. Null when none is stored, or when it can no longer be opened, for
     * example after the Keystore key was lost; the user then clears it and enters it again.
     */
    internal fun load(): String? =
        synchronized(lock) {
            if (!file.isFile) return null
            try {
                val plaintext = cipher.open(decode(file.readBytes()))
                try {
                    String(plaintext, Charsets.UTF_8)
                } finally {
                    plaintext.fill(0)
                }
            } catch (e: Exception) {
                null
            }
        }

    private fun encode(sealed: SealedSecret): ByteArray = encodeSealed(sealed)

    private fun decode(bytes: ByteArray): SealedSecret = decodeSealed(bytes)
}
