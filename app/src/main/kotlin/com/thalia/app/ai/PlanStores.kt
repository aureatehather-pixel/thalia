package com.thalia.app.ai

import com.thalia.app.secret.SecretCipher
import com.thalia.app.secret.decodeSealed
import com.thalia.app.secret.encodeSealed
import com.thalia.app.secret.writeAtomically
import java.io.File

/**
 * The ChatGPT-plan route's sign-in material, held as one opaque string (the provider module's credential JSON) sealed by a
 * [SecretCipher] and written atomically to an app-private file. This class knows nothing about tokens, OpenAI or the provider
 * module, so `:app` main keeps no transport dependency. The content is never logged, shown or put in an exception or result.
 *
 * Anything unreadable (a missing or changed file, a lost Keystore key, a wrong format) reads as nothing stored: signed out.
 */
internal class PlanSecretStore(
    private val file: File,
    private val cipher: SecretCipher,
) {
    private val lock = Any()

    enum class SaveResult { SAVED, FAILED }

    /** Seals [secret] over any earlier one. On failure the earlier one, if any, is left as it was. */
    fun save(secret: String): SaveResult {
        if (secret.isEmpty()) return SaveResult.FAILED
        return synchronized(lock) {
            val plaintext = secret.toByteArray(Charsets.UTF_8)
            try {
                writeAtomically(file, encodeSealed(cipher.seal(plaintext)))
                SaveResult.SAVED
            } catch (e: Exception) {
                SaveResult.FAILED
            } finally {
                plaintext.fill(0)
            }
        }
    }

    /** The stored string, or null when none is stored or it cannot be opened. */
    fun load(): String? =
        synchronized(lock) {
            if (!file.isFile) return null
            try {
                val plaintext = cipher.open(decodeSealed(file.readBytes()))
                try {
                    String(plaintext, Charsets.UTF_8).takeIf { it.isNotEmpty() }
                } finally {
                    plaintext.fill(0)
                }
            } catch (e: Exception) {
                null
            }
        }

    /** Whether the stored string can be opened. It says nothing about whether the sign-in it holds still works. */
    fun isReadable(): Boolean = load() != null

    /** Deletes the file and the cipher's key. True only when both are gone; the file is gone either way. */
    fun clear(): Boolean =
        synchronized(lock) {
            val fileGone = !file.exists() || file.delete()
            File(file.parentFile, "${file.name}.staging").delete()
            val keyGone =
                try {
                    cipher.destroyKey()
                    true
                } catch (e: Exception) {
                    false
                }
            fileGone && keyGone
        }
}

/**
 * The chosen model identifier for the ChatGPT-plan route. There is no default and nothing here says the model exists or is
 * available to anyone; it is only what was chosen. A missing, unreadable or invalid file reads as none chosen.
 */
internal class PlanModelStore(
    private val file: File,
) {
    private val lock = Any()

    fun selected(): String? =
        synchronized(lock) {
            if (!file.isFile) return null
            try {
                file.readText().trim().takeIf { valid(it) }
            } catch (e: java.io.IOException) {
                null
            }
        }

    /** False, and nothing changed, when [id] is not a plausible identifier or the write failed. */
    fun select(id: String): Boolean {
        val trimmed = id.trim()
        if (!valid(trimmed)) return false
        return synchronized(lock) {
            try {
                writeAtomically(file, trimmed.toByteArray(Charsets.UTF_8))
                true
            } catch (e: java.io.IOException) {
                false
            }
        }
    }

    fun clear() {
        synchronized(lock) { file.delete() }
    }

    private fun valid(id: String) = id.length in 1..MAX_LENGTH && id.all { it.isLetterOrDigit() || it in "._:-" }

    private companion object {
        const val MAX_LENGTH = 100
    }
}
