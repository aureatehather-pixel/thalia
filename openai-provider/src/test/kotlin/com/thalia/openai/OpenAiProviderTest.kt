package com.thalia.openai

import com.thalia.conversation.ConversationTools
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.Turn
import com.thalia.conversation.TurnRole
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiProviderTest {
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun stop() = server.close()

    private fun provider(key: String? = "sk-test-key") =
        OpenAiResponsesProvider({ key }, baseUrl = server.url("/v1").toString().trimEnd('/'))

    private val request =
        ProviderRequest("instruction", "snapshot", listOf(Turn(TurnRole.USER, "hello")), ConversationTools.DECLARATIONS_JSON)

    private fun reply(
        body: String,
        code: Int = 200,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .build(),
    )

    @Test
    fun `a function call and text become a reply with usage, and the request has the documented shape`() {
        reply(
            """{"status":"completed","output":[
              {"type":"reasoning","summary":[]},
              {"type":"message","content":[{"type":"output_text","text":"Marking it."}]},
              {"type":"function_call","name":"record_outcome","arguments":"{\"action_ref\":\"a1\",\"result\":\"done\"}","call_id":"c1"}],
              "usage":{"input_tokens":1200,"output_tokens":90,"total_tokens":1290,"output_tokens_details":{"reasoning_tokens":40}}}""",
        )
        val result = runBlocking { provider().respond(request) }
        val ok = assertIs<ProviderResponse.Reply>(result)
        assertEquals("Marking it.", ok.text)
        assertEquals("record_outcome", ok.calls.single().name)
        assertEquals(1200, ok.usage?.promptTokens)
        // output_tokens already includes reasoning; it is not added again as thought tokens.
        assertEquals(90, ok.usage?.outputTokens)
        assertNull(ok.usage?.thoughtTokens)

        val sent = server.takeRequest()
        assertEquals("/v1/responses", sent.url.encodedPath)
        assertEquals("Bearer sk-test-key", sent.headers["Authorization"])
        val body = Json.parseToJsonElement(sent.body!!.utf8()).jsonObject
        assertEquals("gpt-6-luna", body["model"]?.jsonPrimitive?.content)
        assertEquals("false", body["store"]?.jsonPrimitive?.content)
        assertEquals("auto", body["tool_choice"]?.jsonPrimitive?.content)
        assertEquals(
            "low",
            body["reasoning"]
                ?.jsonObject
                ?.get("effort")
                ?.jsonPrimitive
                ?.content,
        )
        assertTrue(
            body
                .getValue("max_output_tokens")
                .jsonPrimitive.content
                .toInt() > 0,
        )
        assertEquals(
            "function",
            body
                .getValue("tools")
                .jsonArray
                .first()
                .jsonObject["type"]
                ?.jsonPrimitive
                ?.content,
        )
        assertFalse("temperature" in body)
    }

    @Test
    fun `no key means no request`() {
        val result = runBlocking { provider(key = null).respond(request) }
        assertEquals(ProviderFailure.UNAVAILABLE, assertIs<ProviderResponse.Failed>(result).failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `statuses map to failures and an insufficient-quota 429 is a billing problem`() {
        fun status(
            code: Int,
            body: String = "{}",
        ): ProviderResponse.Failed {
            reply(body, code)
            return assertIs(runBlocking { provider().respond(request) })
        }
        assertEquals(ProviderFailure.AUTH, status(401).failure)
        assertEquals(ProviderFailure.RATE_LIMITED, status(429, """{"error":{"code":"rate_limit_exceeded"}}""").failure)
        val billing = status(429, """{"error":{"code":"insufficient_quota"}}""")
        assertEquals(ProviderFailure.BILLING_NEEDED, billing.failure)
        assertTrue(billing.notBilled)
        val rejected = status(400)
        assertEquals(ProviderFailure.REJECTED, rejected.failure)
        assertTrue(rejected.notBilled)
        assertEquals(ProviderFailure.NOT_FOUND, status(404).failure)
        val server500 = status(500)
        assertEquals(ProviderFailure.SERVER, server500.failure)
        assertFalse(server500.notBilled)
    }

    @Test
    fun `an incomplete or refused reply is not used but its usage is kept`() {
        reply("""{"status":"incomplete","output":[],"usage":{"input_tokens":10,"output_tokens":4096,"total_tokens":4106}}""")
        val cut = assertIs<ProviderResponse.Failed>(runBlocking { provider().respond(request) })
        assertEquals(ProviderFailure.BLOCKED, cut.failure)
        assertEquals(4096, cut.usage?.outputTokens)
        reply("""{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"no"}]}]}""")
        assertEquals(ProviderFailure.BLOCKED, assertIs<ProviderResponse.Failed>(runBlocking { provider().respond(request) }).failure)
        reply("not json")
        assertEquals(ProviderFailure.UNREADABLE, assertIs<ProviderResponse.Failed>(runBlocking { provider().respond(request) }).failure)
    }

    @Test
    fun `a request OkHttp would resend by itself is sent once, and an unreachable service is a network failure`() {
        reply("{}", 408)
        reply("""{"status":"completed","output":[]}""")
        val timeout = assertIs<ProviderResponse.Failed>(runBlocking { provider().respond(request) })
        assertEquals(ProviderFailure.UNEXPECTED_STATUS, timeout.failure)
        assertEquals(1, server.requestCount)
        val unreachable = OpenAiResponsesProvider({ "k" }, baseUrl = "http://127.0.0.1:1/v1")
        assertEquals(ProviderFailure.NETWORK, assertIs<ProviderResponse.Failed>(runBlocking { unreachable.respond(request) }).failure)
    }

    @Test
    fun `a failure never carries the key or the body`() {
        reply("""{"error":{"message":"bad key sk-test-key"}}""", 401)
        val result = runBlocking { provider().respond(request) }
        assertFalse(result.toString().contains("sk-test-key"))
    }
}
