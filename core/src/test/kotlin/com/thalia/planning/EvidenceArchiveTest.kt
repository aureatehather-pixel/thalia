package com.thalia.planning

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A compatibility check against the real schema 1 archive kept as evidence from the slice 1 Pixel
 * acceptance. It runs only when `THALIA_EVIDENCE_ARCHIVE` names that file, and skips otherwise, so no
 * path on one machine is assumed. The file is only read, never written.
 *
 * It needs no password and takes none. It proves what the envelope allows: the file is the one that was
 * recorded, its header is accepted by the current reader, and a wrong password reaches the decryption step
 * and is refused as "wrong password or damaged file", not as a changed format. Decoding the original
 * payload needs the real password, which is entered privately in the device UI, never here.
 */
class EvidenceArchiveTest {
    private val recordedSha256 = "69003b71b1a65f69d4dab6b5b0617eca05ce363b9c48bbc4fa862b05c18bc235"

    private fun archive(): ByteArray? = System.getenv("THALIA_EVIDENCE_ARCHIVE")?.let { File(it).readBytes() }

    @Test
    fun `the preserved schema 1 archive is the recorded file and its envelope is still accepted`() {
        val bytes = archive()
        assumeTrue(bytes != null, "THALIA_EVIDENCE_ARCHIVE is not set")
        bytes!!
        assertEquals(recordedSha256, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val header = PlanningArchive.readHeader(bytes)
        assertEquals(PlanningArchive.EXPORT_ITERATIONS, header.iterations)
        assertEquals(PlanningArchive.SALT_BYTES, header.salt.size)
        assertEquals(PlanningArchive.NONCE_BYTES, header.nonce.size)
        val refused = runCatching { PlanningArchive.open(bytes, "definitely not the password".toCharArray()) }.exceptionOrNull()
        assertTrue(refused is ImportRefusedException)
        assertEquals(ImportProblem.WRONG_PASSWORD_OR_DAMAGED, refused.problem)
    }
}
