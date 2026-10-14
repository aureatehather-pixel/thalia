package com.thalia.app.ai

import com.thalia.app.secret.writeAtomically
import com.thalia.chatgptplan.CredentialStore
import com.thalia.chatgptplan.PlanCredentials
import java.io.File
import java.util.UUID

/**
 * The provider module's [CredentialStore] over [PlanSecretStore]. It lives in the debug source set, the one place `:app` takes the
 * provider module, so `:app` main and a release build keep no trace of it (ReleaseIsolationTest). It uses the module's own
 * representation, [PlanCredentials.toJson] and [PlanCredentials.fromJson], and adds no format of its own.
 *
 * Nothing here is installed: no code calls it outside tests, and having credentials stored does not make the route ready.
 * Unreadable or invalid storage is signed out. A failed [save] throws with a fixed message and leaves the earlier credentials in
 * place, which is what the session expects of a [RuntimeException]: it keeps the renewed pair in memory for the process. The host
 * id and the issued client id are not secrets and live in a small plain file beside the sealed one, which sign-out keeps.
 */
internal class SealedPlanCredentialStore(
    private val secrets: PlanSecretStore,
    private val meta: File,
) : CredentialStore {
    override fun load(): PlanCredentials? = secrets.load()?.let { PlanCredentials.fromJson(it) }

    override fun save(credentials: PlanCredentials) {
        check(secrets.save(credentials.toJson()) == PlanSecretStore.SaveResult.SAVED) { "the sign-in could not be stored" }
    }

    override fun clear() {
        secrets.clear()
    }

    override fun hostId(): String {
        val values = metaValues()
        values["host"]?.let { return it }
        val created = "urn:uuid:${UUID.randomUUID()}"
        writeMeta(values + ("host" to created))
        return created
    }

    override fun issuedClientId(): String? = metaValues()["client"]

    override fun rememberClientId(clientId: String) = writeMeta(metaValues() + ("client" to clientId))

    private fun metaValues(): Map<String, String> =
        try {
            if (!meta.isFile) {
                emptyMap()
            } else {
                meta
                    .readLines()
                    .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() } }
                    .associate { it[0] to it[1] }
            }
        } catch (e: java.io.IOException) {
            emptyMap()
        }

    private fun writeMeta(values: Map<String, String>) {
        writeAtomically(meta, values.entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray())
    }
}
