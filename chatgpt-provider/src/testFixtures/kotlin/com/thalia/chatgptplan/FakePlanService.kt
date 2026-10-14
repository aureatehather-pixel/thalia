package com.thalia.chatgptplan

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/** What the simulated service does on a request to the Responses endpoint. */
enum class PlanScenario {
    REPLY,
    TOOL_CALL,
    REJECT_FIRST_TOKEN,
    ALWAYS_UNAUTHORIZED,
    USAGE_LIMIT_HTTP,
    USAGE_LIMIT_IN_STREAM,
    USAGE_UNAVAILABLE_IN_STREAM,
    SERVER_ERROR,
    NETWORK_DOWN,
}

/**
 * A simulated auth.openai.com and api.openai.com as an OkHttp interceptor that never reaches the network. It issues rotating
 * tokens, rejects a refresh token it already rotated, rejects an access token it did not issue, and records every call. Everything
 * it shows is **simulated behaviour built from the documentation's error names**: it demonstrates this app's handling, not the
 * real service's. A renewal seen here is a simulated renewal.
 */
class FakePlanService(
    var scenario: PlanScenario = PlanScenario.REPLY,
    private val tokenEndpointDown: Boolean = false,
    private val refreshRevoked: Boolean = false,
    private val toolName: String = "propose_new_action",
    private val toolArgs: String = """{"title":"Book a room"}""",
) : Interceptor {
    data class Call(
        val host: String,
        val path: String,
        val bearer: String?,
    )

    val calls = mutableListOf<Call>()
    val responsesCalls get() = calls.count { it.path.endsWith("/responses") }
    val refreshCalls get() = calls.count { it.path.endsWith("/oauth/token") }
    val otherHostCalls get() = calls.count { it.host != "auth.openai.com" && it.host != "api.openai.com" }
    val bearersSeen get() = calls.filter { it.path.endsWith("/responses") }.map { it.bearer }

    private var generation = 1
    private var rejectedOnce = false
    val validAccess: MutableSet<String> = mutableSetOf("fake-access-1")
    private var currentRefresh = "fake-refresh-1"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val bearer = request.header("Authorization")?.removePrefix("Bearer ")
        calls += Call(request.url.host, request.url.encodedPath, bearer)
        return when {
            request.url.encodedPath.endsWith("/oauth/token") -> token(chain)
            request.url.encodedPath.endsWith("/responses") -> responses(chain, bearer)
            else -> reply(chain, 404, "{}")
        }
    }

    private fun token(chain: Interceptor.Chain): Response {
        if (tokenEndpointDown) return reply(chain, 503, "{}")
        val form = okio.Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
        val params = form.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        return when {
            params["grant_type"] != "refresh_token" -> reply(chain, 400, """{"error":"unsupported_grant_type"}""")
            refreshRevoked || params["refresh_token"] != currentRefresh -> reply(chain, 400, """{"error":"invalid_grant"}""")
            else -> {
                generation++
                currentRefresh = "fake-refresh-$generation"
                validAccess += "fake-access-$generation"
                reply(
                    chain,
                    200,
                    """{"access_token":"fake-access-$generation","refresh_token":"$currentRefresh","expires_in":3600,"scope":"${PlanScopes.ALL}"}""",
                )
            }
        }
    }

    private fun responses(
        chain: Interceptor.Chain,
        bearer: String?,
    ): Response {
        if (scenario == PlanScenario.NETWORK_DOWN) throw IOException("simulated network failure")
        if (scenario == PlanScenario.ALWAYS_UNAUTHORIZED) return reply(chain, 401, "{}")
        if (scenario == PlanScenario.REJECT_FIRST_TOKEN && !rejectedOnce) {
            rejectedOnce = true
            validAccess.clear()
        }
        if (bearer !in validAccess) return reply(chain, 401, """{"error":{"code":"invalid_token"}}""")
        return when (scenario) {
            PlanScenario.USAGE_LIMIT_HTTP -> reply(chain, 429, """{"error":{"code":"${ChatGptPlanProvider.LIMIT_CODE}"}}""")
            PlanScenario.SERVER_ERROR -> reply(chain, 503, "{}")
            PlanScenario.USAGE_LIMIT_IN_STREAM -> stream(chain, failed(ChatGptPlanProvider.LIMIT_CODE))
            PlanScenario.USAGE_UNAVAILABLE_IN_STREAM -> stream(chain, failed(ChatGptPlanProvider.UNAVAILABLE_CODE))
            PlanScenario.TOOL_CALL -> stream(chain, delta("Here is a suggestion.") + toolItem() + completed())
            else -> stream(chain, delta("Start with the watering screen, ") + delta("because it is a must.") + completed())
        }
    }

    private fun event(json: String) = "event: x\ndata: $json\n\n"

    private fun delta(text: String) = event("""{"type":"response.output_text.delta","delta":"$text"}""")

    private fun failed(code: String) = event("""{"type":"response.failed","response":{"error":{"code":"$code"}}}""")

    private fun toolItem() =
        event(
            """{"type":"response.output_item.done","item":{"type":"function_call","name":"$toolName","arguments":${jsonString(
                toolArgs,
            )}}}""",
        )

    private fun completed() = event("""{"type":"response.completed","response":{"usage":{"input_tokens":1200,"output_tokens":80}}}""")

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun stream(
        chain: Interceptor.Chain,
        body: String,
    ) = reply(chain, 200, body, "text/event-stream")

    private fun reply(
        chain: Interceptor.Chain,
        code: Int,
        body: String,
        type: String = "application/json",
    ): Response =
        Response
            .Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("simulated")
            .body(body.toResponseBody(type.toMediaType()))
            .build()
}

/** Sample credentials for tests: obviously fake tokens, the plan scope included, expiring at [expiresAtMillis]. */
fun sampleCredentials(
    expiresAtMillis: Long,
    scopes: List<String> = PlanScopes.ALL.split(' '),
) = PlanCredentials(
    issuer = "https://auth.openai.com",
    subject = "synthetic-subject",
    clientId = "synthetic-client",
    hostId = "urn:uuid:00000000-0000-4000-8000-000000000000",
    idToken = null,
    accessToken = "fake-access-1",
    refreshToken = "fake-refresh-1",
    scopes = scopes,
    expiresAtMillis = expiresAtMillis,
    savedAtMillis = 0L,
)
