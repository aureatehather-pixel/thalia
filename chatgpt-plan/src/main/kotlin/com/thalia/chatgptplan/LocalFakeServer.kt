package com.thalia.chatgptplan

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** What the fake service does. Each is a failure or a success the real service can produce, simulated with no network at all. */
enum class Scenario {
    REPLY,
    TOOL_CALL,
    TOOL_CALL_ONLY_IN_COMPLETED,
    EXPIRED_THEN_RENEWED,
    UNAUTHORIZED_THEN_RENEWED,
    ALWAYS_UNAUTHORIZED,
    REVOKED,
    RENEWAL_UNAVAILABLE,
    USAGE_LIMIT_HTTP,
    USAGE_LIMIT_IN_STREAM,
    USAGE_UNAVAILABLE_IN_STREAM,
    TRUNCATED_STREAM,
    INCOMPLETE,
    FAILED_IN_STREAM,
    SERVER_UNAVAILABLE,
}

/**
 * A stand-in for auth.openai.com and api.openai.com, as an OkHttp interceptor that never reaches the network. It issues
 * rotating tokens, rejects a refresh token it has already rotated, rejects an access token it did not issue, and counts every
 * call by host, so a test can prove that a limit produced one request and no second attempt anywhere. Used by the unit tests
 * and by the on-device "simulate" buttons; neither signs in or sends anything.
 */
class LocalFakeServer(
    var scenario: Scenario,
    private val toolCallJson: String = """{"action_ref":"a1","result":"done"}""",
    private val toolName: String = "record_outcome",
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

    private var generation = 1
    private var rejectedOnce = false
    val validAccess: MutableSet<String> = mutableSetOf("fake-access-1")
    private var currentRefresh = "fake-refresh-1"

    val currentRefreshToken get() = currentRefresh
    val currentAccessToken get() = "fake-access-$generation"

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
        val form = chain.request().body.let { okio.Buffer().also { buffer -> it?.writeTo(buffer) }.readUtf8() }
        val params = form.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        return when {
            params["grant_type"] == "authorization_code" -> issue(chain)
            params["grant_type"] != "refresh_token" -> reply(chain, 400, """{"error":"unsupported_grant_type"}""")
            scenario == Scenario.REVOKED -> reply(chain, 400, """{"error":"invalid_grant"}""")
            scenario == Scenario.RENEWAL_UNAVAILABLE -> reply(chain, 503, "{}")
            params["refresh_token"] != currentRefresh -> reply(chain, 400, """{"error":"invalid_grant"}""")
            else -> issue(chain)
        }
    }

    private fun issue(chain: Interceptor.Chain): Response {
        generation++
        currentRefresh = "fake-refresh-$generation"
        validAccess += "fake-access-$generation"
        return reply(
            chain,
            200,
            """{"access_token":"fake-access-$generation","refresh_token":"$currentRefresh","expires_in":3600,"scope":"${PlanScopes.ALL}"}""",
        )
    }

    private fun responses(
        chain: Interceptor.Chain,
        bearer: String?,
    ): Response {
        if (scenario == Scenario.ALWAYS_UNAUTHORIZED) return reply(chain, 401, "{}")
        if (scenario == Scenario.UNAUTHORIZED_THEN_RENEWED && !rejectedOnce) {
            rejectedOnce = true
            validAccess.clear()
        }
        if (bearer !in validAccess) return reply(chain, 401, """{"error":{"code":"invalid_token"}}""")
        return when (scenario) {
            Scenario.USAGE_LIMIT_HTTP -> reply(chain, 429, """{"error":{"code":"${ChatGptPlanProvider.LIMIT_CODE}"}}""")
            Scenario.SERVER_UNAVAILABLE -> reply(chain, 503, "{}")
            Scenario.USAGE_LIMIT_IN_STREAM -> stream(chain, failed(ChatGptPlanProvider.LIMIT_CODE))
            Scenario.USAGE_UNAVAILABLE_IN_STREAM -> stream(chain, failed(ChatGptPlanProvider.UNAVAILABLE_CODE))
            Scenario.FAILED_IN_STREAM -> stream(chain, failed("server_error"))
            Scenario.INCOMPLETE ->
                stream(
                    chain,
                    delta("Part of an ans") +
                        event("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}"""),
                )
            Scenario.TRUNCATED_STREAM -> stream(chain, delta("I have put the demo script in your plan and it is done."))
            Scenario.TOOL_CALL -> stream(chain, delta("Here is a suggestion.") + toolItem() + completed(null))
            Scenario.TOOL_CALL_ONLY_IN_COMPLETED -> stream(chain, completed(toolOutput()))
            else ->
                stream(
                    chain,
                    delta("Today I would start with the watering schedule screen, ") + delta("because it is a must.") + completed(null),
                )
        }
    }

    private fun event(json: String) = "event: x\ndata: $json\n\n"

    private fun delta(text: String) = event("""{"type":"response.output_text.delta","delta":"$text"}""")

    private fun failed(code: String) = event("""{"type":"response.failed","response":{"error":{"code":"$code"}}}""")

    private fun toolItem() =
        event(
            """{"type":"response.output_item.done","item":{"type":"function_call","name":"$toolName","arguments":${jsonString(
                toolCallJson,
            )}}}""",
        )

    private fun toolOutput() = """{"type":"function_call","name":"$toolName","arguments":${jsonString(toolCallJson)}}"""

    private fun completed(output: String?) =
        event(
            """{"type":"response.completed","response":{"usage":{"input_tokens":1200,"output_tokens":80}${output?.let {
                ",\"output\":[$it]"
            } ?: ""}}}""",
        )

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
