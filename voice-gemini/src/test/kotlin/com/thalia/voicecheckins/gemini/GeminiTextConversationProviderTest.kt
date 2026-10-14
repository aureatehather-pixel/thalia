package com.thalia.voicecheckins.gemini

import com.thalia.conversation.ContextLimits
import com.thalia.conversation.ConversationPrompt
import com.thalia.conversation.ConversationTools
import com.thalia.conversation.OperationParser
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
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Gemini text adapter against a local fake server only. No request here leaves this machine. */
class GeminiTextConversationProviderTest {
    private val server = MockWebServer().apply { start() }
    private val secret = "test-secret-key-123"

    private val request =
        ProviderRequest(
            ConversationPrompt.INSTRUCTION,
            "Active day: 2026-10-06.",
            listOf(
                Turn(TurnRole.USER, "I finished the intro"),
                Turn(TurnRole.ASSISTANT, "Nice."),
                Turn(TurnRole.USER, "and read the paper"),
            ),
            ConversationTools.DECLARATIONS_JSON,
        )

    @AfterTest
    fun stop() = server.close()

    private fun provider(key: String? = secret) =
        GeminiTextConversationProvider(
            apiKey = { key?.let(::GeminiApiKey) },
            client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
            model = "test-model",
            baseUrl = server.url("/v1beta").toString().trimEnd('/'),
        )

    private fun reply(
        code: Int,
        body: String,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .build(),
    )

    private fun respond(p: GeminiTextConversationProvider = provider()) = runBlocking { p.respond(request) }

    @Test
    fun `the request carries the key in a header only, the model in the path, declared tools, AUTO mode and the turns`() {
        reply(200, """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}""")
        respond()
        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/test-model:generateContent", recorded.url.encodedPath)
        assertEquals(secret, recorded.headers["x-goog-api-key"])
        assertFalse(secret in recorded.url.toString())
        val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertTrue(
            "Active day: 2026-10-06." in
                body["systemInstruction"]!!
                    .jsonObject["parts"]!!
                    .jsonArray[0]
                    .jsonObject["text"]!!
                    .jsonPrimitive.content,
        )
        assertEquals(listOf("user", "model", "user"), body["contents"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(
            "AUTO",
            body["toolConfig"]!!
                .jsonObject["functionCallingConfig"]!!
                .jsonObject["mode"]!!
                .jsonPrimitive.content,
        )
        val declarations = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(OperationParser.NAMES.size, declarations.size)
        assertEquals(
            ContextLimits.OUTPUT_TOKENS,
            body["generationConfig"]!!
                .jsonObject["maxOutputTokens"]!!
                .jsonPrimitive.content
                .toInt(),
        )
        // Google advises against lowering a Gemini 3 model's temperature, so none is sent.
        assertFalse("temperature" in body["generationConfig"]!!.jsonObject)
    }

    @Test
    fun `text and function calls come back as text and raw calls, with usage, and nothing is executed`() {
        reply(
            200,
            """{"candidates":[{"content":{"parts":[
                {"thought":true,"text":"private reasoning"},
                {"text":"Recorded, and here is a thought."},
                {"functionCall":{"name":"record_outcome","args":{"action_ref":"a1","result":"done"}}}
              ]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":30,"thoughtsTokenCount":50,"totalTokenCount":200}}""",
        )
        val r = assertIs<ProviderResponse.Reply>(respond())
        assertEquals("Recorded, and here is a thought.", r.text)
        assertEquals("record_outcome", r.calls.single().name)
        assertEquals(
            "a1",
            Json
                .parseToJsonElement(r.calls.single().argsJson)
                .jsonObject["action_ref"]!!
                .jsonPrimitive.content,
        )
        assertEquals(120, r.usage?.promptTokens)
        assertEquals(30, r.usage?.outputTokens)
        assertEquals(50, r.usage?.thoughtTokens)
        assertEquals(200, r.usage?.totalTokens)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a missing key is unavailable and sends nothing`() {
        assertEquals(ProviderResponse.Failed(ProviderFailure.UNAVAILABLE), respond(provider(key = null)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `failures map to honest categories and never carry the reply or the key`() {
        fun failure(
            code: Int,
            body: String = "{}",
        ): ProviderFailure {
            reply(code, body)
            return assertIs<ProviderResponse.Failed>(respond()).failure
        }
        assertEquals(ProviderFailure.AUTH, failure(401))
        assertEquals(ProviderFailure.AUTH, failure(403))
        assertEquals(ProviderFailure.RATE_LIMITED, failure(429))
        assertEquals(ProviderFailure.REJECTED, failure(400, """{"error":{"message":"bad schema $secret"}}"""))
        assertEquals(ProviderFailure.SERVER, failure(503))
        assertEquals(ProviderFailure.NOT_FOUND, failure(404))
        assertEquals(ProviderFailure.REQUEST_TOO_LARGE, failure(413))
        assertEquals(ProviderFailure.UNEXPECTED_STATUS, failure(408))
        assertEquals(ProviderFailure.UNEXPECTED_STATUS, failure(418))
        assertEquals(ProviderFailure.UNREADABLE, failure(200, "not json"))
        assertEquals(ProviderFailure.BLOCKED, failure(200, """{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertEquals(ProviderFailure.BLOCKED, failure(200, """{"candidates":[]}"""))
        // A reply cut off may hold half a call, so it is not used even when it parses.
        assertEquals(
            ProviderFailure.BLOCKED,
            failure(
                200,
                """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"record_outcome","args":{}}}]},"finishReason":"MAX_TOKENS"}]}""",
            ),
        )
        assertEquals(ProviderFailure.UNREADABLE, failure(200, """{"candidates":[{"finishReason":"MALFORMED_FUNCTION_CALL"}]}"""))
    }

    @Test
    fun `only the statuses Google documents as uncharged are marked not billed, and a refused reply keeps its usage`() {
        fun failed(
            code: Int,
            body: String = "{}",
        ): ProviderResponse.Failed {
            reply(code, body)
            return assertIs<ProviderResponse.Failed>(respond())
        }
        listOf(400, 500).forEach { assertTrue(failed(it).notBilled, "$it is documented as not billed") }
        listOf(401, 403, 429, 502, 503, 504).forEach { assertFalse(failed(it).notBilled, "$it is undocumented, so uncertain") }
        assertFalse(failed(200, "not json").notBilled)
        val cut =
            failed(
                200,
                """{"candidates":[{"finishReason":"MAX_TOKENS"}],"usageMetadata":{"promptTokenCount":10,"totalTokenCount":4106}}""",
            )
        assertEquals(ProviderFailure.BLOCKED, cut.failure)
        assertFalse(cut.notBilled)
        assertEquals(4106, cut.usage?.totalTokens)
    }

    @Test
    fun `the http status is kept for every failed status, uncertain unless documented, and the body and key are never carried`() {
        fun failed(code: Int): ProviderResponse.Failed {
            reply(code, """{"error":{"message":"detail with $secret and a secret body"}}""")
            return assertIs<ProviderResponse.Failed>(respond())
        }
        mapOf(
            400 to ProviderFailure.REJECTED,
            401 to ProviderFailure.AUTH,
            403 to ProviderFailure.AUTH,
            404 to ProviderFailure.NOT_FOUND,
            408 to ProviderFailure.UNEXPECTED_STATUS,
            413 to ProviderFailure.REQUEST_TOO_LARGE,
            429 to ProviderFailure.RATE_LIMITED,
            500 to ProviderFailure.SERVER,
            502 to ProviderFailure.SERVER,
            503 to ProviderFailure.SERVER,
            504 to ProviderFailure.SERVER,
        ).forEach { (code, failure) ->
            val f = failed(code)
            assertEquals(failure, f.failure, "HTTP $code")
            assertEquals(code, f.httpStatus, "HTTP $code is kept")
            assertEquals(code == 400 || code == 500, f.notBilled, "HTTP $code billing certainty is unchanged")
            val text = f.toString()
            assertFalse(secret in text || "secret body" in text || "detail" in text, "HTTP $code carries only the status")
        }
        // Statuses that never reached the service, or whose reply could not be read, have no status of their own to report.
        reply(200, "not json")
        assertNull(assertIs<ProviderResponse.Failed>(respond()).httpStatus)
    }

    @Test
    fun `one request makes one attempt, even for statuses OkHttp would resend or follow by itself`() {
        fun attempts(
            code: Int,
            vararg headers: Pair<String, String>,
        ): Pair<ProviderResponse.Failed, Int> {
            val before = server.requestCount
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        MockResponse
                            .Builder()
                            .code(code)
                            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                            .body("{}")
                            .build()
                }
            val failed = assertIs<ProviderResponse.Failed>(respond())
            return failed to server.requestCount - before
        }
        // 408 and a 503 with Retry-After: 0 are resent by OkHttp unless told not to; a redirect is followed.
        val (timeout, n408) = attempts(408)
        assertEquals(1, n408)
        assertEquals(ProviderFailure.UNEXPECTED_STATUS, timeout.failure)
        assertEquals(408, timeout.httpStatus)
        val (busy, n503) = attempts(503, "Retry-After" to "0")
        assertEquals(1, n503)
        assertEquals(503, busy.httpStatus)
        val (moved, n302) = attempts(302, "Location" to server.url("/elsewhere").toString())
        assertEquals(1, n302)
        assertEquals(302, moved.httpStatus)
        assertEquals(ProviderFailure.UNEXPECTED_STATUS, moved.failure)
    }

    @Test
    fun `a connection that fails is a network failure`() {
        val p = provider()
        server.close()
        assertEquals(ProviderResponse.Failed(ProviderFailure.NETWORK), respond(p))
    }

    @Test
    fun `a plain text reply has no calls`() {
        reply(200, """{"candidates":[{"content":{"parts":[{"text":"Doing no more today is fine."}]},"finishReason":"STOP"}]}""")
        val r = assertIs<ProviderResponse.Reply>(respond())
        assertTrue(r.calls.isEmpty())
        assertNull(r.usage)
    }
}
