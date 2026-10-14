package com.thalia.chatgptplan

import com.thalia.conversation.ConversationPrompt
import com.thalia.conversation.ConversationTools
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.Turn
import com.thalia.conversation.TurnRole
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProviderTest {
    private val request =
        ProviderRequest(
            ConversationPrompt.INSTRUCTION,
            "Active day: 2026-10-08.",
            listOf(Turn(TurnRole.USER, "hi")),
            ConversationTools.DECLARATIONS_JSON,
        )
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun stop() = server.close()

    @Test
    fun `every scenario ends the way it must, with no call to another host`() {
        for (scenario in Scenario.entries) {
            val report = ScenarioRunner.run(scenario)
            assertTrue(report.passed, "$scenario: ${report.detail}")
        }
    }

    @Test
    fun `a usage limit is one request and the end of it, with no retry, no second host and no other credential`() {
        val fake = LocalFakeServer(Scenario.USAGE_LIMIT_HTTP)
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    HostAllowlist(PlanEndpoints.PRODUCTION.allowedHosts),
                ).addInterceptor(fake)
                .build()
        val store = InMemoryCredentialStore(credentials())
        val provider =
            ChatGptPlanProvider(
                PlanSession(store, TokenClient(client, PlanEndpoints.PRODUCTION)),
                PlanEndpoints.PRODUCTION,
                client,
                { "m" },
            )
        val response = runBlocking { provider.respond(request) }
        assertEquals(ProviderFailure.USAGE_LIMIT, assertIs<ProviderResponse.Failed>(response).failure)
        assertEquals(PlanIssue.USAGE_LIMIT, provider.lastIssue)
        assertEquals(1, fake.calls.size)
        assertTrue(fake.calls.all { it.bearer in fake.validAccess })
    }

    @Test
    fun `the provider has no API key route and every request carries only the plan token`() {
        // Nothing a caller could pass is a bare string or byte array, so there is no place to hand it an API key.
        val types = ChatGptPlanProvider::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() }
        assertTrue(
            types.none { it == String::class.java || it == ByteArray::class.java || it == CharSequence::class.java },
            types.toString(),
        )
        val fake = LocalFakeServer(Scenario.REPLY)
        val client = OkHttpClient.Builder().addInterceptor(fake).build()
        val provider =
            ChatGptPlanProvider(
                PlanSession(
                    InMemoryCredentialStore(credentials()),
                    TokenClient(client, PlanEndpoints.PRODUCTION),
                ),
                PlanEndpoints.PRODUCTION,
                client,
                {
                    "m"
                },
            )
        runBlocking { provider.respond(request) }
        assertEquals(listOf("fake-access-1"), fake.calls.map { it.bearer })
    }

    private fun credentials() =
        PlanCredentials(
            "https://auth.openai.com",
            "s",
            "oaiapp_1",
            "urn:uuid:h",
            null,
            "fake-access-1",
            "fake-refresh-1",
            PlanScopes.ALL.split(' '),
            Long.MAX_VALUE / 2,
            0,
        )

    @Test
    fun `without consent, a model, or sign-in nothing is sent`() {
        val fake = LocalFakeServer(Scenario.REPLY)
        val client = OkHttpClient.Builder().addInterceptor(fake).build()

        fun provider(
            gates: PlanGates = PlanGates.OPEN,
            model: String? = "m",
            store: CredentialStore = InMemoryCredentialStore(credentials()),
        ) = ChatGptPlanProvider(PlanSession(store, TokenClient(client, PlanEndpoints.PRODUCTION)), PlanEndpoints.PRODUCTION, client, {
            model
        }, gates)
        val noConsent =
            object : PlanGates {
                override fun consented() = false

                override fun tryReserve() = true
            }
        assertEquals(
            ProviderFailure.CONSENT_NEEDED,
            (runBlocking { provider(noConsent).respond(request) } as ProviderResponse.Failed).failure,
        )
        val noModel = provider(model = null)
        assertEquals(ProviderFailure.MODEL_NEEDED, (runBlocking { noModel.respond(request) } as ProviderResponse.Failed).failure)
        assertEquals(PlanIssue.NO_MODEL, noModel.lastIssue)
        val signedOut = provider(store = InMemoryCredentialStore())
        assertEquals(ProviderFailure.SIGN_IN_NEEDED, (runBlocking { signedOut.respond(request) } as ProviderResponse.Failed).failure)
        assertEquals(PlanIssue.SIGNED_OUT, signedOut.lastIssue)
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `the request cap is hard and past it nothing is sent, and a retry after a 401 is counted too`() {
        val fake = LocalFakeServer(Scenario.UNAUTHORIZED_THEN_RENEWED)
        val client = OkHttpClient.Builder().addInterceptor(fake).build()
        var reserved = 0
        val cap =
            object : PlanGates {
                override fun consented() = true

                override fun tryReserve() = (reserved < 2).also { if (it) reserved++ }
            }
        val provider =
            ChatGptPlanProvider(
                PlanSession(
                    InMemoryCredentialStore(credentials()),
                    TokenClient(client, PlanEndpoints.PRODUCTION),
                ),
                PlanEndpoints.PRODUCTION,
                client,
                {
                    "m"
                },
                cap,
            )
        assertIs<ProviderResponse.Reply>(runBlocking { provider.respond(request) })
        assertEquals(2, reserved)
        val before = fake.calls.size
        assertEquals(ProviderFailure.BUDGET_EXHAUSTED, (runBlocking { provider.respond(request) } as ProviderResponse.Failed).failure)
        assertEquals(before, fake.calls.size)
    }

    @Test
    fun `a host that is not OpenAI's is refused, and so is a redirect`() {
        val client = planHttpClient(PlanEndpoints.PRODUCTION)
        assertFailsWith<IOException> { client.newCall(Request.Builder().url("https://evil.example/v1/responses").build()).execute() }
        val local =
            PlanEndpoints(
                server.url("/a").toString(),
                server.url("/t").toString(),
                server.url("/v1/responses").toString(),
                server.url("/m").toString(),
                "https://auth.openai.com",
                "https://api.openai.com/v1",
            )
        server.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .addHeader("Location", "https://evil.example/steal")
                .build(),
        )
        val provider =
            ChatGptPlanProvider(
                PlanSession(
                    InMemoryCredentialStore(credentials()),
                    TokenClient(planHttpClient(local), local),
                ),
                local,
                planHttpClient(local),
                {
                    "m"
                },
            )
        val response = runBlocking { provider.respond(request) }
        assertEquals(ProviderFailure.NETWORK, assertIs<ProviderResponse.Failed>(response).failure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `over a real socket the reply streams in, the headers and body are right, and the token is only in the header`() {
        val local =
            PlanEndpoints(
                server.url("/a").toString(),
                server.url("/t").toString(),
                server.url("/v1/responses").toString(),
                server.url("/m").toString(),
                "https://auth.openai.com",
                "https://api.openai.com/v1",
            )
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .setHeader("Content-Type", "text/event-stream")
                .body(
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hi \"}\n\n" +
                        "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\"," +
                        "\"name\":\"ask_clarification\"," +
                        "\"arguments\":\"{\\\"question\\\":\\\"Which one?\\\"}\"}}\n\n" +
                        "data: {\"type\":\"response.completed\",\"response\":{}}\n\n",
                ).build(),
        )
        val provider =
            ChatGptPlanProvider(
                PlanSession(
                    InMemoryCredentialStore(credentials()),
                    TokenClient(planHttpClient(local), local),
                ),
                local,
                planHttpClient(local),
                {
                    "gpt-test"
                },
            )
        val reply = assertIs<ProviderResponse.Reply>(runBlocking { provider.respond(request) })
        assertEquals("Hi ", reply.text)
        assertEquals("ask_clarification", reply.calls.single().name)
        val recorded = server.takeRequest()
        assertEquals("Bearer fake-access-1", recorded.headers["Authorization"])
        assertEquals("/v1/responses", recorded.url.encodedPath)
        val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals(ResponsesRequest.KEYS, body.keys)
        assertEquals("gpt-test", body["model"]!!.jsonPrimitive.content)
        assertFalse("fake-access-1" in recorded.body!!.utf8())
        assertFalse("fake-access-1" in recorded.url.toString())
    }
}
