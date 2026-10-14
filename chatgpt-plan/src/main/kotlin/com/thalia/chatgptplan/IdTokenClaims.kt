package com.thalia.chatgptplan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * The claims of an ID token, checked for issuer, audience, expiry, issued-at, subject and nonce. The signature is checked first by
 * [IdTokenVerifier] (this class only reads the payload). Nothing here authorizes plan usage; the scope does.
 */
data class IdTokenClaims(
    val issuer: String?,
    val subject: String?,
    val audience: List<String>,
    val expiresAtSeconds: Long?,
    val issuedAtSeconds: Long?,
    val nonce: String?,
) {
    fun problem(
        expectedIssuer: String,
        clientId: String,
        expectedNonce: String?,
        nowSeconds: Long,
    ): String? =
        when {
            issuer != expectedIssuer -> "issuer"
            clientId !in audience -> "audience"
            expiresAtSeconds == null || expiresAtSeconds <= nowSeconds -> "expired"
            expectedNonce != null && nonce != expectedNonce -> "nonce"
            issuedAtSeconds == null -> "iat"
            subject.isNullOrBlank() -> "subject"
            else -> null
        }

    companion object {
        fun parse(jwt: String): IdTokenClaims? =
            runCatching {
                val parts = jwt.split('.')
                require(parts.size == 3)
                val o = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).jsonObject
                IdTokenClaims(
                    issuer = o["iss"]?.jsonPrimitive?.contentOrNull,
                    subject = o["sub"]?.jsonPrimitive?.contentOrNull,
                    audience = audience(o),
                    expiresAtSeconds = o["exp"]?.jsonPrimitive?.longOrNull,
                    issuedAtSeconds = o["iat"]?.jsonPrimitive?.longOrNull,
                    nonce = o["nonce"]?.jsonPrimitive?.contentOrNull,
                )
            }.getOrNull()

        private fun audience(o: JsonObject): List<String> =
            when (val aud = o["aud"]) {
                is JsonArray -> aud.map { it.jsonPrimitive.content }
                null -> emptyList()
                else -> listOf(aud.jsonPrimitive.content)
            }
    }
}
