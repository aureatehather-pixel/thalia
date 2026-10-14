package com.thalia.planning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Why an archive or its payload was refused. Nothing is written when any of these is found. */
enum class ImportProblem {
    /** The file is larger than [PlanningArchive.MAX_ARCHIVE_BYTES]. It was not read. */
    TOO_LARGE,

    /** No Thalia planning export header: the wrong file, or one damaged before its payload. */
    NOT_AN_EXPORT,

    /** A format or schema version this Thalia does not know: the export was made by a newer one. */
    NEWER_VERSION,

    /** Key derivation or cipher parameters outside what is accepted, refused before any key is derived. */
    UNSUPPORTED_PARAMETERS,

    /** AES-GCM refused the payload. A wrong password and a damaged file cannot be told apart. */
    WRONG_PASSWORD_OR_DAMAGED,

    /** The decrypted payload does not match the format: a field, a value, a limit or a count. */
    INVALID_CONTENT,

    /** A row refers to a row that is not in the export, or two rows share an id. */
    BROKEN_REFERENCE,
}

class ImportRefusedException(
    val problem: ImportProblem,
    detail: String,
) : Exception("${problem.name}: $detail")

/**
 * The encrypted container of a planning export. ADR 0009 decision 8.
 *
 * Layout, format 1:
 *
 * | Bytes | Content |
 * | --- | --- |
 * | 22 | the ASCII magic `THALIA-PLANNING-EXPORT` |
 * | 2 | the header's length, unsigned big-endian |
 * | that length | the header, a JSON object: `format`, `kdf`, `iterations`, `salt`, `keyBits`, `cipher`, `nonce`, `tagBits` |
 * | the rest | the AES-256-GCM ciphertext of the payload, then its 128-bit tag |
 *
 * Every byte before the ciphertext is the GCM associated data, so changing the header fails
 * decryption. The key is PBKDF2-HMAC-SHA256 of the password, normalised to Unicode NFC and encoded
 * as UTF-8, so the same typed password gives the same key whichever keyboard produced it.
 *
 * Every bound is checked before the key is derived, so a crafted file cannot make the phone spend
 * minutes on one guess. All of this is blocking CPU work: callers run it off the main thread.
 */
object PlanningArchive {
    const val FORMAT_VERSION = 1
    const val MAGIC = "THALIA-PLANNING-EXPORT"
    const val FILE_EXTENSION = "thaliaexport"
    const val MIN_PASSWORD_LENGTH = 12

    const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
    const val MAX_HEADER_BYTES = 1024
    const val KDF = "PBKDF2-HMAC-SHA256"
    const val EXPORT_ITERATIONS = 600_000
    val ACCEPTED_ITERATIONS = 600_000..2_000_000
    const val SALT_BYTES = 16
    const val KEY_BITS = 256
    const val CIPHER = "AES-256-GCM"
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128

    private val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
    private val prefixBytes = magicBytes.size + 2
    private val headerKeys = setOf("format", "kdf", "iterations", "salt", "keyBits", "cipher", "nonce", "tagBits")

    /** The parameters read from an archive's header, all within the accepted bounds. */
    class Header internal constructor(
        val iterations: Int,
        internal val salt: ByteArray,
        internal val nonce: ByteArray,
        /** The bytes the GCM tag authenticates: the magic, the length and the header. */
        internal val associatedData: ByteArray,
    )

    /** Encrypts [payload] under [password]. The password must be at least [MIN_PASSWORD_LENGTH] characters. */
    fun seal(
        payload: ByteArray,
        password: CharArray,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(password.size >= MIN_PASSWORD_LENGTH) { "an export password needs at least $MIN_PASSWORD_LENGTH characters" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header =
            buildJsonObject {
                put("format", FORMAT_VERSION)
                put("kdf", KDF)
                put("iterations", EXPORT_ITERATIONS)
                put("salt", base64(salt))
                put("keyBits", KEY_BITS)
                put("cipher", CIPHER)
                put("nonce", base64(nonce))
                put("tagBits", TAG_BITS)
            }.toString().toByteArray(Charsets.UTF_8)
        val associatedData = magicBytes + byteArrayOf((header.size ushr 8).toByte(), header.size.toByte()) + header
        check(associatedData.size <= MAX_HEADER_BYTES)
        val cipher = cipher(Cipher.ENCRYPT_MODE, deriveKey(password, salt, EXPORT_ITERATIONS), nonce, associatedData)
        return associatedData + cipher.doFinal(payload)
    }

    /**
     * Checks the header of [archive] without deriving a key: the size, the magic, the format and every
     * parameter. Cheap, so it runs before the password is tried.
     */
    fun readHeader(archive: ByteArray): Header {
        if (archive.size > MAX_ARCHIVE_BYTES) refuse(ImportProblem.TOO_LARGE, "archive of ${archive.size} bytes")
        if (archive.size < prefixBytes || !archive.copyOfRange(0, magicBytes.size).contentEquals(magicBytes)) {
            refuse(ImportProblem.NOT_AN_EXPORT, "no magic")
        }
        val headerLength = ((archive[magicBytes.size].toInt() and 0xff) shl 8) or (archive[magicBytes.size + 1].toInt() and 0xff)
        val headerEnd = prefixBytes + headerLength
        if (headerEnd > MAX_HEADER_BYTES) refuse(ImportProblem.NOT_AN_EXPORT, "header of $headerLength bytes")
        if (archive.size < headerEnd) refuse(ImportProblem.NOT_AN_EXPORT, "truncated header")
        val header =
            try {
                val text = String(archive, prefixBytes, headerLength, Charsets.UTF_8)
                JsonLimits.requireDepth(text, maxDepth = 1)
                Json.parseToJsonElement(text) as? JsonObject ?: refuse(ImportProblem.NOT_AN_EXPORT, "header is not an object")
            } catch (e: IllegalArgumentException) {
                refuse(ImportProblem.NOT_AN_EXPORT, "header is not JSON")
            }

        val format = header.int("format") ?: refuse(ImportProblem.NOT_AN_EXPORT, "no format")
        if (format > FORMAT_VERSION) refuse(ImportProblem.NEWER_VERSION, "format $format")
        if (format < 1) refuse(ImportProblem.NOT_AN_EXPORT, "format $format")
        if (header.keys != headerKeys) refuse(ImportProblem.NOT_AN_EXPORT, "header fields ${header.keys.sorted()}")

        if (header.string("kdf") != KDF) refuse(ImportProblem.UNSUPPORTED_PARAMETERS, "kdf")
        val iterations = header.int("iterations")
        if (iterations == null || iterations !in ACCEPTED_ITERATIONS) refuse(ImportProblem.UNSUPPORTED_PARAMETERS, "iterations")
        if (header.int("keyBits") != KEY_BITS) refuse(ImportProblem.UNSUPPORTED_PARAMETERS, "key size")
        if (header.string("cipher") != CIPHER) refuse(ImportProblem.UNSUPPORTED_PARAMETERS, "cipher")
        if (header.int("tagBits") != TAG_BITS) refuse(ImportProblem.UNSUPPORTED_PARAMETERS, "tag size")
        val salt = header.bytes("salt")
        if (salt == null || salt.size != SALT_BYTES) refuse(ImportProblem.UNSUPPORTED_PARAMETERS, "salt")
        val nonce = header.bytes("nonce")
        if (nonce == null || nonce.size != NONCE_BYTES) refuse(ImportProblem.UNSUPPORTED_PARAMETERS, "nonce")
        if (archive.size < headerEnd + TAG_BITS / 8) refuse(ImportProblem.WRONG_PASSWORD_OR_DAMAGED, "no room for the tag")

        return Header(iterations, salt, nonce, archive.copyOfRange(0, headerEnd))
    }

    /** The decrypted payload of [archive], after [readHeader]'s checks. Derives the key: slow by design. */
    fun open(
        archive: ByteArray,
        password: CharArray,
    ): ByteArray {
        val header = readHeader(archive)
        val cipher = cipher(Cipher.DECRYPT_MODE, deriveKey(password, header.salt, header.iterations), header.nonce, header.associatedData)
        return try {
            cipher.doFinal(archive, header.associatedData.size, archive.size - header.associatedData.size)
        } catch (e: AEADBadTagException) {
            refuse(ImportProblem.WRONG_PASSWORD_OR_DAMAGED, "tag mismatch")
        }
    }

    /**
     * Reads [input] whole, refusing it as soon as it passes [MAX_ARCHIVE_BYTES], so an oversized file is
     * never held in memory. Callers that know the size beforehand refuse it unread.
     */
    fun readBounded(input: InputStream): ByteArray {
        // Not InputStream.readNBytes, which Android has only from API 33.
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > MAX_ARCHIVE_BYTES) refuse(ImportProblem.TOO_LARGE, "more than $MAX_ARCHIVE_BYTES bytes")
        }
    }

    /** PBKDF2-HMAC-SHA256 of the NFC-normalised password. Exposed so devices can check it against reference vectors. */
    fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        val normalised = Normalizer.normalize(String(password), Normalizer.Form.NFC).toCharArray()
        val spec = PBEKeySpec(normalised, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            normalised.fill('\u0000')
        }
    }

    private fun cipher(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
    ): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(associatedData)
            key.fill(0)
        }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun JsonObject.primitive(name: String): JsonPrimitive? = this[name] as? JsonPrimitive

    private fun JsonObject.int(name: String): Int? = primitive(name)?.takeUnless { it.isString }?.content?.toIntOrNull()

    private fun JsonObject.string(name: String): String? = primitive(name)?.takeIf { it.isString }?.content

    private fun JsonObject.bytes(name: String): ByteArray? =
        string(name)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
}

internal fun refuse(
    problem: ImportProblem,
    detail: String,
): Nothing = throw ImportRefusedException(problem, detail)

/** A nesting limit checked on the raw text before parsing, so a deeply nested document cannot exhaust the stack. */
internal object JsonLimits {
    fun requireDepth(
        text: String,
        maxDepth: Int,
    ) {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' || c == '[' -> if (++depth > maxDepth) throw IllegalArgumentException("nested deeper than $maxDepth")
                c == '}' || c == ']' -> depth--
            }
        }
    }
}
