package com.thalia.app.chatgptcheck

import com.thalia.app.planning.data.PassphraseSealer
import com.thalia.chatgptplan.CredentialStore
import com.thalia.chatgptplan.PlanCredentials
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The plan credentials, sealed by an Android Keystore key and written atomically to this app's private files: a temporary file is
 * written and synced, then moved over the old one, so a crash leaves either the old pair or the new pair and never half of one (a
 * rotated refresh token cannot be asked for twice). The host id and the issued client id are not secrets and live in a small plain
 * file that survives sign-out. Nothing here logs or prints a token.
 */
internal class SealedFileCredentialStore(
    private val directory: File,
    private val sealer: PassphraseSealer,
) : CredentialStore {
    private val sealed = File(directory, "chatgpt-credentials.sealed")
    private val meta = File(directory, "chatgpt-meta.txt")

    /** Why the last load found nothing usable, for display. Never content. */
    @Volatile var lastLoadProblem: String? = null
        private set

    override fun load(): PlanCredentials? {
        lastLoadProblem = null
        if (!sealed.exists()) return null
        return try {
            PlanCredentials.fromJson(String(sealer.open(sealed.readBytes()), Charsets.UTF_8)) ?: run {
                lastLoadProblem = "unreadable"
                null
            }
        } catch (e: Exception) {
            lastLoadProblem = e.javaClass.simpleName
            null
        }
    }

    override fun save(credentials: PlanCredentials) {
        directory.mkdirs()
        val temp = File(directory, "chatgpt-credentials.tmp")
        FileOutputStream(temp).use {
            it.write(sealer.seal(credentials.toJson().toByteArray(Charsets.UTF_8)))
            it.fd.sync()
        }
        Files.move(temp.toPath(), sealed.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun clear() {
        sealed.delete()
        File(directory, "chatgpt-credentials.tmp").delete()
    }

    private fun metaLines(): MutableMap<String, String> =
        if (meta.exists()) {
            meta
                .readLines()
                .mapNotNull {
                    it
                        .split('=', limit = 2)
                        .takeIf { p ->
                            p.size == 2
                        }?.let { p -> p[0] to p[1] }
                }.toMap()
                .toMutableMap()
        } else {
            mutableMapOf()
        }

    private fun writeMeta(values: Map<String, String>) {
        directory.mkdirs()
        meta.writeText(values.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    override fun hostId(): String {
        val values = metaLines()
        return values["host"] ?: "urn:uuid:${UUID.randomUUID()}".also { writeMeta(values + ("host" to it)) }
    }

    override fun issuedClientId(): String? = metaLines()["client"]

    override fun rememberClientId(clientId: String) = writeMeta(metaLines() + ("client" to clientId))
}
