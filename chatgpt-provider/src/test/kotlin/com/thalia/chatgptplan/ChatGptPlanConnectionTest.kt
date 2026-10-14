package com.thalia.chatgptplan

import com.thalia.conversation.ConversationPrompt
import com.thalia.conversation.ConversationTools
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.Turn
import com.thalia.conversation.TurnRole
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The production composition against a simulated service (no network, no sign-in, no real credential). Every renewal in this file is
 * **simulated**: the fake service issues and rotates tokens, so it shows this app's handling of the documented shapes and nothing
 * about the real service's behaviour.
 */
class ChatGptPlanConnectionTest {
    private val now = 1_000_000_000L
    private val request =
        ProviderRequest(
            ConversationPrompt.INSTRUCTION,
            "Active day: 2026-10-08.",
            listOf(Turn(TurnRole.USER, "add a room booking")),
            ConversationTools.DECLARATIONS_JSON,
        )

    private class Rig(
        val fake: FakePlanService,
        val store: InMemoryCredentialStore,
        val connection: ChatGptPlanConnection,
    )

    private fun rig(
        scenario: PlanScenario,
        expiresAt: Long = 1_000_000_000L + 3_600_000,
        signedIn: Boolean = true,
        consented: Boolean = true,
        model: String? = "gpt-test",
        tokenEndpointDown: Boolean = false,
        refreshRevoked: Boolean = false,
    ): Rig {
        val fake = FakePlanService(scenario, tokenEndpointDown, refreshRevoked)
        val client =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .addInterceptor(HostAllowlist(PlanEndpoints.PRODUCTION.allowedHosts))
                .addInterceptor(fake)
                .build()
        val store = InMemoryCredentialStore(if (signedIn) sampleCredentials(expiresAt) else null)
        val connection = ChatGptPlanConnection.create(store, { model }, { consented }, client = client, clock = { now })
        return Rig(fake, store, connection)
    }

    private fun Rig.ask() = runBlocking { connection.provider.respond(request) }

    private fun ProviderResponse.failure() = assertIs<ProviderResponse.Failed>(this).failure

    @Test
    fun `a successful conversation returns the reply and the tool call as text, sends only the plan token and touches no other host`() {
        val rig = rig(PlanScenario.TOOL_CALL)
        val reply = assertIs<ProviderResponse.Reply>(rig.ask())
        assertEquals("Here is a suggestion.", reply.text)
        assertEquals(listOf("propose_new_action"), reply.calls.map { it.name })
        assertEquals("""{"title":"Book a room"}""", reply.calls.single().argsJson)
        assertEquals(1, rig.fake.responsesCalls)
        assertEquals(0, rig.fake.refreshCalls)
        assertEquals(0, rig.fake.otherHostCalls)
        assertEquals(listOf("fake-access-1"), rig.fake.bearersSeen)
        assertEquals(1200, reply.usage?.promptTokens)
    }

    @Test
    fun `an expired token is renewed first, the rotated pair is saved, and the renewed token is sent (simulated renewal)`() {
        val rig = rig(PlanScenario.REPLY, expiresAt = now - 1)
        assertIs<ProviderResponse.Reply>(rig.ask())
        assertEquals(1, rig.fake.refreshCalls)
        assertEquals(listOf("fake-access-2"), rig.fake.bearersSeen)
        val saved = assertNotNull(rig.store.load())
        assertEquals("fake-access-2", saved.accessToken)
        assertEquals("fake-refresh-2", saved.refreshToken)
        assertEquals(1, rig.store.saves)
        // The next request needs no renewal.
        assertIs<ProviderResponse.Reply>(rig.ask())
        assertEquals(1, rig.fake.refreshCalls)
    }

    @Test
    fun `a 401 renews once and retries once, and the retry succeeds (simulated renewal)`() {
        val rig = rig(PlanScenario.REJECT_FIRST_TOKEN)
        assertIs<ProviderResponse.Reply>(rig.ask())
        assertEquals(2, rig.fake.responsesCalls)
        assertEquals(1, rig.fake.refreshCalls)
        assertEquals(listOf("fake-access-1", "fake-access-2"), rig.fake.bearersSeen)
    }

    @Test
    fun `a 401 that persists after one renewal is final, with no third request, and asks for sign-in`() {
        val rig = rig(PlanScenario.ALWAYS_UNAUTHORIZED)
        assertEquals(ProviderFailure.SIGN_IN_NEEDED, rig.ask().failure())
        assertEquals(2, rig.fake.responsesCalls)
        assertEquals(1, rig.fake.refreshCalls)
        assertEquals(PlanIssue.SIGNED_OUT, rig.connection.provider.lastIssue)
    }

    @Test
    fun `a revoked refresh token signs the person out here and asks for sign-in, sending no inference request`() {
        val rig = rig(PlanScenario.REPLY, expiresAt = now - 1, refreshRevoked = true)
        assertEquals(ProviderFailure.SIGN_IN_NEEDED, rig.ask().failure())
        assertEquals(0, rig.fake.responsesCalls)
        assertNull(rig.store.load())
    }

    @Test
    fun `a renewal outage is a network failure that keeps the sign-in, and no inference request follows`() {
        val rig = rig(PlanScenario.REPLY, expiresAt = now - 1, tokenEndpointDown = true)
        assertEquals(ProviderFailure.NETWORK, rig.ask().failure())
        assertEquals(0, rig.fake.responsesCalls)
        assertNotNull(rig.store.load())
    }

    @Test
    fun `the plan's usage limit, from the status or from the stream, is one request and the end of it`() {
        listOf(PlanScenario.USAGE_LIMIT_HTTP, PlanScenario.USAGE_LIMIT_IN_STREAM).forEach { scenario ->
            val rig = rig(scenario)
            assertEquals(ProviderFailure.USAGE_LIMIT, rig.ask().failure(), scenario.name)
            assertEquals(1, rig.fake.responsesCalls, scenario.name)
            assertEquals(0, rig.fake.refreshCalls, scenario.name)
            assertEquals(PlanIssue.USAGE_LIMIT, rig.connection.provider.lastIssue)
        }
        val unavailable = rig(PlanScenario.USAGE_UNAVAILABLE_IN_STREAM)
        assertEquals(ProviderFailure.USAGE_UNAVAILABLE, unavailable.ask().failure())
        assertEquals(1, unavailable.fake.responsesCalls)
    }

    @Test
    fun `a network failure and a server error are reported as such, once, with nothing retried`() {
        val down = rig(PlanScenario.NETWORK_DOWN)
        assertEquals(ProviderFailure.NETWORK, down.ask().failure())
        assertEquals(1, down.fake.responsesCalls)
        val server = rig(PlanScenario.SERVER_ERROR)
        assertEquals(ProviderFailure.SERVER, server.ask().failure())
        assertEquals(1, server.fake.responsesCalls)
    }

    @Test
    fun `without consent, a model or a sign-in nothing is sent, and a sign-in without the plan scope asks for sign-in`() {
        val noConsent = rig(PlanScenario.REPLY, consented = false)
        assertEquals(ProviderFailure.CONSENT_NEEDED, noConsent.ask().failure())
        val noModel = rig(PlanScenario.REPLY, model = null)
        assertEquals(ProviderFailure.MODEL_NEEDED, noModel.ask().failure())
        val signedOut = rig(PlanScenario.REPLY, signedIn = false)
        assertEquals(ProviderFailure.SIGN_IN_NEEDED, signedOut.ask().failure())
        listOf(noConsent, noModel, signedOut).forEach { assertTrue(it.fake.calls.isEmpty()) }
        // A grant without the plan scope makes no request either.
        val scoped = FakePlanService()
        val client = OkHttpClient.Builder().addInterceptor(scoped).build()
        val noScope =
            ChatGptPlanConnection.create(
                InMemoryCredentialStore(sampleCredentials(now + 3_600_000, listOf("openid"))),
                { "gpt-test" },
                { true },
                client = client,
                clock = { now },
            )
        assertEquals(ProviderFailure.SIGN_IN_NEEDED, (runBlocking { noScope.provider.respond(request) } as ProviderResponse.Failed).failure)
        assertEquals(PlanIssue.NO_PLAN_SCOPE, noScope.provider.lastIssue)
        assertTrue(scoped.calls.isEmpty())
    }

    @Test
    fun `the connection has no API key parameter, so no other credential and no paid fallback can be passed`() {
        val types = ChatGptPlanConnection.Companion::class.java.declaredMethods.flatMap { it.parameterTypes.toList() }
        assertTrue(types.none { it == String::class.java || it == ByteArray::class.java }, types.toString())
    }
}
