package com.thalia.chatgptplan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class Tokens(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSeconds: Long,
    val scopes: List<String>?,
) {
    override fun toString(): String = "Tokens(redacted, expiresIn=$expiresInSeconds)"
}

sealed interface TokenResult {
    data class Ok(
        val tokens: Tokens,
    ) : TokenResult

    /** The authorization server said no: the code or refresh token is not valid. [error] is its error code, nothing else. */
    data class Rejected(
        val status: Int,
        val error: String?,
    ) : TokenResult

    /** Nothing was decided: a dropped connection, a 5xx or a 429. Tokens are kept and the person is not signed out. */
    data class Transient(
        val status: Int?,
    ) : TokenResult
}

/**
 * The two token-endpoint calls. The code exchange follows the documented fields (`client_id`, `code`, `code_verifier`,
 * `redirect_uri`, `resource`). The refresh request is documented only as `grant_type=refresh_token` with the saved `client_id` and
 * `refresh_token` (profiles-and-sessions page), to the same token endpoint (the discovery document lists `refresh_token` and
 * auth method `none`). Nothing documents a `resource` or `scope` on refresh, so none is sent. Form encoding is assumed from the
 * code exchange. Whether the server accepts exactly this is still established only by the proof.
 */
class TokenClient(
    private val client: OkHttpClient,
    private val endpoints: PlanEndpoints,
) {
    suspend fun exchange(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ): TokenResult =
        post(
            FormBody
                .Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", clientId)
                .add("code", code)
                .add("code_verifier", verifier)
                .add("redirect_uri", redirectUri)
                .add("resource", endpoints.resource)
                .build(),
        )

    suspend fun refresh(
        clientId: String,
        refreshToken: String,
    ): TokenResult =
        post(
            FormBody
                .Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", clientId)
                .add("refresh_token", refreshToken)
                .build(),
        )

    private suspend fun post(body: FormBody): TokenResult =
        withContext(Dispatchers.IO) {
            try {
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(endpoints.tokenUrl)
                            .post(body)
                            .build(),
                    ).execute()
                    .use { response ->
                        val text = response.body.string().take(MAX_BODY)
                        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                        when {
                            response.isSuccessful -> parse(json) ?: TokenResult.Transient(response.code)
                            response.code == 400 || response.code == 401 || response.code == 403 ->
                                TokenResult.Rejected(response.code, json?.get("error")?.jsonPrimitive?.contentOrNull)
                            else -> TokenResult.Transient(response.code)
                        }
                    }
            } catch (e: IOException) {
                TokenResult.Transient(null)
            }
        }

    private fun parse(json: kotlinx.serialization.json.JsonObject?): TokenResult.Ok? {
        val access = json?.get("access_token")?.jsonPrimitive?.contentOrNull ?: return null
        val expires = json["expires_in"]?.jsonPrimitive?.let { it.longOrNull ?: it.intOrNull?.toLong() } ?: DEFAULT_EXPIRES_SECONDS
        return TokenResult.Ok(
            Tokens(
                accessToken = access,
                refreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull,
                idToken = json["id_token"]?.jsonPrimitive?.contentOrNull,
                expiresInSeconds = expires,
                scopes =
                    json["scope"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.split(' ')
                        ?.filter { it.isNotBlank() },
            ),
        )
    }

    private companion object {
        const val MAX_BODY = 64 * 1024
        const val DEFAULT_EXPIRES_SECONDS = 3600L
    }
}
