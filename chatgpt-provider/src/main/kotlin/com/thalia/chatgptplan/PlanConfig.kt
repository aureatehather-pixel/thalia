package com.thalia.chatgptplan

import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Where Sign in with ChatGPT and the Responses API live. The authorization and token endpoints and the resource value are
 * quoted from developers.openai.com/siwc/token-sharing-open-source/sign-in, read 2026-10-10. The Responses and models paths
 * are the public API's, which that documentation names but does not spell out; the proof is what confirms them.
 */
data class PlanEndpoints(
    val authorizeUrl: String,
    val tokenUrl: String,
    val responsesUrl: String,
    val modelsUrl: String,
    val issuer: String,
    val resource: String,
) {
    /** Only these hosts are ever contacted. Anything else, including a redirect, is refused. */
    val allowedHosts: Set<String> = listOf(authorizeUrl, tokenUrl, responsesUrl, modelsUrl).map { it.toHttpUrl().host }.toSet()

    companion object {
        val PRODUCTION =
            PlanEndpoints(
                authorizeUrl = "https://auth.openai.com/api/accounts/authorize",
                tokenUrl = "https://auth.openai.com/api/accounts/oauth/token",
                responsesUrl = "https://api.openai.com/v1/responses",
                modelsUrl = "https://api.openai.com/v1/models",
                issuer = "https://auth.openai.com",
                resource = "https://api.openai.com/v1",
            )
    }
}

object PlanScopes {
    const val IDENTITY = "openid profile email"
    const val PLAN = "offline_access resource.invoke chatgpt.tokens.use.direct"

    /** The scope that authorizes plan usage. A valid ID token alone does not. */
    const val USE = "chatgpt.tokens.use.direct"
    const val ALL = "$IDENTITY $PLAN"
}

object PlanClient {
    /** The documented value for a first registration. The issued client id replaces it. */
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
    const val AGENT_NAME = "Thalia"
    const val CALLBACK_PATH = "/auth/callback"
}
