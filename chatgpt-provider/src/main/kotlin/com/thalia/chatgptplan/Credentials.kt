package com.thalia.chatgptplan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID

/** What a sign-in leaves. Kept only in [CredentialStore]; never logged, never in a failure, never in a file outside it. */
data class PlanCredentials(
    val issuer: String,
    val subject: String,
    val clientId: String,
    val hostId: String,
    val idToken: String?,
    val accessToken: String,
    val refreshToken: String,
    val scopes: List<String>,
    val expiresAtMillis: Long,
    val savedAtMillis: Long,
) {
    val mayUsePlan: Boolean get() = PlanScopes.USE in scopes

    override fun toString(): String = "PlanCredentials(client=$clientId, scopes=$scopes, expiresAt=$expiresAtMillis, tokens=redacted)"

    fun toJson(): String =
        buildJsonObject {
            put("issuer", issuer)
            put("subject", subject)
            put("client_id", clientId)
            put("ext_agent_host_id", hostId)
            put("id_token", idToken)
            put("access_token", accessToken)
            put("refresh_token", refreshToken)
            putJsonArray("scopes") { scopes.forEach { add(JsonPrimitive(it)) } }
            put("expires_at_millis", expiresAtMillis)
            put("saved_at_millis", savedAtMillis)
        }.toString()

    companion object {
        fun fromJson(text: String): PlanCredentials? =
            runCatching {
                val o = Json.parseToJsonElement(text).jsonObject
                PlanCredentials(
                    issuer = o.str("issuer"),
                    subject = o.str("subject"),
                    clientId = o.str("client_id"),
                    hostId = o.str("ext_agent_host_id"),
                    idToken = o["id_token"]?.jsonPrimitive?.contentOrNull,
                    accessToken = o.str("access_token"),
                    refreshToken = o.str("refresh_token"),
                    scopes = (o["scopes"] as JsonArray).map { it.jsonPrimitive.content },
                    expiresAtMillis = o["expires_at_millis"]!!.jsonPrimitive.longOrNull!!,
                    savedAtMillis = o["saved_at_millis"]!!.jsonPrimitive.longOrNull!!,
                )
            }.getOrNull()

        private fun JsonObject.str(name: String) = this[name]!!.jsonPrimitive.content
    }
}

/**
 * Where credentials live: on the phone, sealed, under the user's control (the terms require local storage). [save] must be
 * atomic: a half-written file may not replace a good one, because a rotated refresh token cannot be asked for twice.
 */
interface CredentialStore {
    fun load(): PlanCredentials?

    fun save(credentials: PlanCredentials)

    /** Forgets the tokens. The host id stays, so a later sign-in is the same host. */
    fun clear()

    /** A random id for this install, `urn:uuid:...`, unrelated to the person. Created once. */
    fun hostId(): String

    /** The client id OpenAI issued at registration. It is kept across sign-outs, because a registration is retained and reused. */
    fun issuedClientId(): String?

    fun rememberClientId(clientId: String)
}

class InMemoryCredentialStore(
    initial: PlanCredentials? = null,
    private val failSaves: () -> Boolean = { false },
) : CredentialStore {
    private var credentials = initial
    private val host = "urn:uuid:${UUID.randomUUID()}"
    var saves = 0
        private set

    override fun load() = credentials

    override fun save(credentials: PlanCredentials) {
        check(!failSaves()) { "the store refuses to save" }
        saves++
        this.credentials = credentials
    }

    override fun clear() {
        credentials = null
    }

    override fun hostId() = host

    private var issued: String? = initial?.clientId

    override fun issuedClientId() = issued

    override fun rememberClientId(clientId: String) {
        issued = clientId
    }
}
