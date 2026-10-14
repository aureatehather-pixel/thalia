package com.thalia.chatgptplan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Checks an ID token's signature. Claims are checked separately by [IdTokenClaims]. */
fun interface IdTokenVerifier {
    suspend fun verify(jwt: String): Boolean
}

/**
 * Verifies an ID token against the issuer's published JWKS, as developers.openai.com/siwc/website says to: the key set is found
 * through the discovery document (`jwks_uri`, which must be on the discovery document's own host and scheme (https in production), never hardcoded), only
 * `RS256` is accepted (the discovery document lists no other `id_token_signing_alg_values_supported`), the key is chosen by `kid`,
 * and an unfamiliar `kid` refreshes the key set once. Uses the JDK's RSA only, no new dependency. Any failure means "not verified".
 */
class JwksIdTokenVerifier(
    private val client: OkHttpClient,
    private val discoveryUrl: String,
) : IdTokenVerifier {
    private val lock = Mutex()
    private var keys: Map<String, JsonObject> = emptyMap()

    override suspend fun verify(jwt: String): Boolean =
        runCatching {
            val parts = jwt.split('.')
            if (parts.size != 3) return false
            val header = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[0]))).jsonObject
            if (header["alg"]?.jsonPrimitive?.contentOrNull != ALG) return false
            val kid = header["kid"]?.jsonPrimitive?.contentOrNull ?: return false
            val jwk = key(kid) ?: return false
            if (jwk["kty"]?.jsonPrimitive?.contentOrNull != "RSA") return false
            val public =
                KeyFactory.getInstance("RSA").generatePublic(
                    RSAPublicKeySpec(bigint(jwk, "n"), bigint(jwk, "e")),
                )
            Signature.getInstance("SHA256withRSA").run {
                initVerify(public)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(Base64.getUrlDecoder().decode(parts[2]))
            }
        }.getOrDefault(false)

    private suspend fun key(kid: String): JsonObject? =
        lock.withLock {
            keys[kid] ?: fetchKeys().also { keys = it }[kid]
        }

    private fun bigint(
        jwk: JsonObject,
        name: String,
    ) = BigInteger(1, Base64.getUrlDecoder().decode(jwk.getValue(name).jsonPrimitive.content))

    private suspend fun fetchKeys(): Map<String, JsonObject> =
        withContext(Dispatchers.IO) {
            try {
                val discovery = Json.parseToJsonElement(get(discoveryUrl)).jsonObject
                val jwksUri = discovery["jwks_uri"]?.jsonPrimitive?.contentOrNull ?: return@withContext emptyMap()
                val uri = jwksUri.toHttpUrl()
                val discoveryHttp = discoveryUrl.toHttpUrl()
                if (uri.host != discoveryHttp.host || uri.isHttps != discoveryHttp.isHttps) return@withContext emptyMap()
                val set = Json.parseToJsonElement(get(jwksUri)).jsonObject["keys"] as? JsonArray ?: return@withContext emptyMap()
                set
                    .mapNotNull { it as? JsonObject }
                    .mapNotNull { jwk -> jwk["kid"]?.jsonPrimitive?.contentOrNull?.let { it to jwk } }
                    .toMap()
            } catch (e: IOException) {
                emptyMap()
            } catch (e: IllegalArgumentException) {
                emptyMap()
            }
        }

    private fun get(url: String): String =
        client
            .newCall(
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build(),
            ).execute()
            .use { response ->
                if (!response.isSuccessful) throw IOException("status ${response.code}")
                response.body.string().take(MAX_BODY)
            }

    private companion object {
        const val ALG = "RS256"
        const val MAX_BODY = 256 * 1024
    }
}
