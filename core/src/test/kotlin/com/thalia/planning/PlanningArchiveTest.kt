package com.thalia.planning

import java.io.ByteArrayInputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR 0009 decision 8: the encrypted container, its header and every bound in its table. */
class PlanningArchiveTest {
    private val password = "correct horse battery".toCharArray()
    private val payload = """{"secret":"Stay curious"}""".toByteArray()

    private val sealed: ByteArray by lazy { PlanningArchive.seal(payload, password) }

    private fun refusedWith(
        problem: ImportProblem,
        block: () -> Unit,
    ) = assertEquals(problem, assertFailsWith<ImportRefusedException> { block() }.problem)

    private fun headerJson(archive: ByteArray): String {
        val length = ((archive[22].toInt() and 0xff) shl 8) or (archive[23].toInt() and 0xff)
        return String(archive, 24, length)
    }

    /** [archive] with its header replaced, and its length prefix updated to match. */
    private fun withHeader(
        archive: ByteArray,
        header: String,
    ): ByteArray {
        val oldLength = headerJson(archive).toByteArray().size
        val bytes = header.toByteArray()
        return archive.copyOfRange(0, 22) + byteArrayOf((bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes +
            archive.copyOfRange(24 + oldLength, archive.size)
    }

    @Test
    fun `a sealed payload opens with its password`() {
        assertContentEquals(payload, PlanningArchive.open(sealed, password))
    }

    @Test
    fun `the header names the exact parameters, and nothing in the file is the payload in the clear`() {
        val header = headerJson(sealed)
        assertTrue(sealed.copyOfRange(0, 22).contentEquals("THALIA-PLANNING-EXPORT".toByteArray()))
        assertTrue("\"kdf\":\"PBKDF2-HMAC-SHA256\"" in header)
        assertTrue("\"iterations\":600000" in header)
        assertTrue("\"cipher\":\"AES-256-GCM\"" in header)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("Stay curious"))
        assertEquals(600_000, PlanningArchive.readHeader(sealed).iterations)
    }

    @Test
    fun `each export has a fresh salt and nonce`() {
        assertFalse(PlanningArchive.seal(payload, password).contentEquals(PlanningArchive.seal(payload, password)))
    }

    @Test
    fun `a wrong password is refused as wrong password or damaged file`() {
        refusedWith(ImportProblem.WRONG_PASSWORD_OR_DAMAGED) { PlanningArchive.open(sealed, "wrong horse battery".toCharArray()) }
    }

    @Test
    fun `a changed payload byte is refused`() {
        for (index in listOf(sealed.size - 1, sealed.size - 17, sealed.size - payload.size - 16)) {
            val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            refusedWith(ImportProblem.WRONG_PASSWORD_OR_DAMAGED) { PlanningArchive.open(tampered, password) }
        }
    }

    @Test
    fun `a changed header is refused, because the header is authenticated`() {
        // Same length, still valid and within every bound, but no longer the header that was sealed.
        val header = headerJson(sealed)
        val salt = Regex("\"salt\":\"([^\"]+)\"").find(header)!!.groupValues[1]
        val otherSalt = Base64.getEncoder().encodeToString(ByteArray(16) { 1 })
        refusedWith(ImportProblem.WRONG_PASSWORD_OR_DAMAGED) {
            PlanningArchive.open(withHeader(sealed, header.replace(salt, otherSalt)), password)
        }
        refusedWith(ImportProblem.WRONG_PASSWORD_OR_DAMAGED) {
            PlanningArchive.open(withHeader(sealed, header.replace("\"tagBits\":128", "\"tagBits\":128 ")), password)
        }
    }

    @Test
    fun `a truncated file is refused, wherever it is cut`() {
        for (length in listOf(0, 10, 23, 30, 24 + headerJson(sealed).length, sealed.size - 1)) {
            val problem = assertFailsWith<ImportRefusedException> { PlanningArchive.open(sealed.copyOf(length), password) }.problem
            assertTrue(problem in setOf(ImportProblem.NOT_AN_EXPORT, ImportProblem.WRONG_PASSWORD_OR_DAMAGED), "cut at $length: $problem")
        }
    }

    @Test
    fun `an oversized file is refused before anything is derived`() {
        refusedWith(ImportProblem.TOO_LARGE) { PlanningArchive.readHeader(ByteArray(PlanningArchive.MAX_ARCHIVE_BYTES + 1)) }
        refusedWith(ImportProblem.TOO_LARGE) {
            PlanningArchive.readBounded(ByteArrayInputStream(ByteArray(PlanningArchive.MAX_ARCHIVE_BYTES + 1)))
        }
        assertEquals(
            PlanningArchive.MAX_ARCHIVE_BYTES,
            PlanningArchive.readBounded(ByteArrayInputStream(ByteArray(PlanningArchive.MAX_ARCHIVE_BYTES))).size,
        )
    }

    @Test
    fun `a header over 1 KiB, a wrong magic, or a header that is not JSON is not an export`() {
        val padded = withHeader(sealed, headerJson(sealed).replace("{", "{" + " ".repeat(1000)))
        refusedWith(ImportProblem.NOT_AN_EXPORT) { PlanningArchive.readHeader(padded) }
        refusedWith(ImportProblem.NOT_AN_EXPORT) {
            PlanningArchive.readHeader(
                "THALIA-PLANNING-EXPORX".toByteArray() + sealed.copyOfRange(22, sealed.size),
            )
        }
        refusedWith(ImportProblem.NOT_AN_EXPORT) { PlanningArchive.readHeader(withHeader(sealed, "not json")) }
        refusedWith(ImportProblem.NOT_AN_EXPORT) { PlanningArchive.readHeader(withHeader(sealed, "[[[]]]")) }
        refusedWith(ImportProblem.NOT_AN_EXPORT) {
            PlanningArchive.readHeader(withHeader(sealed, headerJson(sealed).replace("}", ",\"extra\":1}")))
        }
    }

    @Test
    fun `a newer format is refused as made by a newer Thalia`() {
        refusedWith(ImportProblem.NEWER_VERSION) {
            PlanningArchive.readHeader(withHeader(sealed, headerJson(sealed).replace("\"format\":1", "\"format\":2")))
        }
    }

    @Test
    fun `key derivation and cipher parameters out of range are refused before deriving`() {
        val header = headerJson(sealed)
        val salt = Regex("\"salt\":\"([^\"]+)\"").find(header)!!.groupValues[1]
        val nonce = Regex("\"nonce\":\"([^\"]+)\"").find(header)!!.groupValues[1]
        listOf(
            header.replace("600000", "599999"),
            header.replace("600000", "2000001"),
            header.replace("600000", "\"600000\""),
            header.replace("PBKDF2-HMAC-SHA256", "PBKDF2-HMAC-SHA1"),
            header.replace("\"keyBits\":256", "\"keyBits\":128"),
            header.replace("AES-256-GCM", "AES-128-GCM"),
            header.replace("\"tagBits\":128", "\"tagBits\":96"),
            header.replace(salt, Base64.getEncoder().encodeToString(ByteArray(15))),
            header.replace(salt, "not base64!"),
            header.replace(nonce, Base64.getEncoder().encodeToString(ByteArray(16))),
        ).forEach { changed ->
            // readHeader derives no key, so a refusal here is a refusal before any work on the password.
            refusedWith(ImportProblem.UNSUPPORTED_PARAMETERS) { PlanningArchive.readHeader(withHeader(sealed, changed)) }
            refusedWith(ImportProblem.UNSUPPORTED_PARAMETERS) { PlanningArchive.open(withHeader(sealed, changed), password) }
        }
    }

    @Test
    fun `the upper iteration bound is accepted, so the count can be raised later`() {
        val header = headerJson(sealed).replace("600000", "2000000")
        assertEquals(2_000_000, PlanningArchive.readHeader(withHeader(sealed, header)).iterations)
    }

    @Test
    fun `an export password needs at least 12 characters`() {
        assertFailsWith<IllegalArgumentException> { PlanningArchive.seal(payload, "elevenchars".toCharArray()) }
    }

    @Test
    fun `key derivation matches independent PBKDF2-HMAC-SHA256 vectors, including a non-ASCII password`() {
        // RFC 7914 section 11, first 32 bytes.
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc",
            PlanningArchive.deriveKey("passwd".toCharArray(), "salt".toByteArray(), 1).toHex(),
        )
        // Computed with Python's hashlib.pbkdf2_hmac on the NFC form of this decomposed password.
        assertEquals(
            NON_ASCII_KEY,
            PlanningArchive.deriveKey(NON_ASCII_PASSWORD.toCharArray(), ByteArray(16) { it.toByte() }, 600_000).toHex(),
        )
    }

    companion object {
        /** "pässwörd-日本語-12", with each umlaut typed as a base letter and a combining mark. */
        const val NON_ASCII_PASSWORD = "pässwörd-日本語-12"
        const val NON_ASCII_KEY = "ac84849bb3e5643e3031b21169c6c1936a64721d691b9bb678e6dec4a8eb6f8a"

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
