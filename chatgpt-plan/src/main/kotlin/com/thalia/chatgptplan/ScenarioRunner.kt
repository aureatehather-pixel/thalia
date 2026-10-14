package com.thalia.chatgptplan

import com.thalia.conversation.ConversationPrompt
import com.thalia.conversation.ConversationTools
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.Turn
import com.thalia.conversation.TurnRole
import okhttp3.OkHttpClient

data class ScenarioReport(
    val scenario: Scenario,
    val passed: Boolean,
    val detail: String,
)

/**
 * Runs a scenario end to end through the real provider, session and token client against [LocalFakeServer], with in-memory fake
 * credentials. The real credentials are never touched and nothing leaves the phone. What each scenario must prove is stated here,
 * once, and checked by the unit tests and by the on-device simulate buttons alike.
 */
object ScenarioRunner {
    private val request =
        ProviderRequest(
            ConversationPrompt.INSTRUCTION,
            "Active day: 2026-10-08.",
            listOf(Turn(TurnRole.USER, "I finished the watering schedule screen.")),
            ConversationTools.DECLARATIONS_JSON,
        )

    fun run(scenario: Scenario): ScenarioReport {
        val now = 1_000_000_000L
        val expiredAtStart = scenario in setOf(Scenario.EXPIRED_THEN_RENEWED, Scenario.REVOKED, Scenario.RENEWAL_UNAVAILABLE)
        val credentials =
            PlanCredentials(
                issuer = "https://auth.openai.com",
                subject = "sim",
                clientId = "oaiapp_sim",
                hostId = "urn:uuid:sim",
                idToken = null,
                accessToken = "fake-access-1",
                refreshToken = "fake-refresh-1",
                scopes = PlanScopes.ALL.split(' '),
                expiresAtMillis = if (expiredAtStart) now - 1 else now + 3_600_000,
                savedAtMillis = now - 10,
            )
        val store = InMemoryCredentialStore(credentials)
        val fake = LocalFakeServer(scenario)
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    HostAllowlist(PlanEndpoints.PRODUCTION.allowedHosts),
                ).addInterceptor(fake)
                .build()
        val session = PlanSession(store, TokenClient(client, PlanEndpoints.PRODUCTION), { now })
        val provider = ChatGptPlanProvider(session, PlanEndpoints.PRODUCTION, client, { "sim-model" })
        val response = kotlinx.coroutines.runBlocking { provider.respond(request) }
        val outcome = describe(response)
        val problem = check(scenario, response, provider.lastIssue, fake, store, session)
        return ScenarioReport(scenario, problem == null, if (problem == null) outcome else "$outcome; PROBLEM: $problem")
    }

    private fun describe(response: ProviderResponse) =
        when (response) {
            is ProviderResponse.Reply -> "reply, ${response.calls.size} call(s)"
            is ProviderResponse.Failed -> "failed ${response.failure}"
        }

    @Suppress("CyclomaticComplexMethod")
    private fun check(
        scenario: Scenario,
        response: ProviderResponse,
        issue: PlanIssue,
        fake: LocalFakeServer,
        store: InMemoryCredentialStore,
        session: PlanSession,
    ): String? {
        if (fake.otherHostCalls != 0) return "a call went to another host"
        val failed = response as? ProviderResponse.Failed
        val reply = response as? ProviderResponse.Reply

        fun want(
            failure: ProviderFailure,
            why: PlanIssue,
            responses: Int,
            refreshes: Int,
        ): String? =
            when {
                failed?.failure != failure -> "expected $failure"
                issue != why -> "expected issue $why, got $issue"
                fake.responsesCalls != responses -> "expected $responses request(s), saw ${fake.responsesCalls}"
                fake.refreshCalls != refreshes -> "expected $refreshes renewal(s), saw ${fake.refreshCalls}"
                else -> null
            }
        return when (scenario) {
            Scenario.REPLY ->
                if (reply?.text == null ||
                    reply.calls.isNotEmpty() ||
                    fake.responsesCalls != 1
                ) {
                    "expected one plain reply"
                } else {
                    null
                }
            Scenario.TOOL_CALL, Scenario.TOOL_CALL_ONLY_IN_COMPLETED ->
                if (reply == null ||
                    reply.calls.singleOrNull()?.name != "record_outcome"
                ) {
                    "expected exactly one record_outcome call"
                } else {
                    null
                }
            Scenario.EXPIRED_THEN_RENEWED, Scenario.UNAUTHORIZED_THEN_RENEWED -> {
                val saved = store.load()
                when {
                    reply == null -> "expected a reply after renewal"
                    fake.refreshCalls != 1 -> "expected one renewal"
                    saved?.refreshToken != fake.currentRefreshToken ||
                        saved.accessToken != fake.currentAccessToken -> "rotated tokens were not saved"
                    saved.accessToken == "fake-access-1" -> "old access token kept"
                    scenario == Scenario.UNAUTHORIZED_THEN_RENEWED && fake.responsesCalls != 2 -> "expected exactly one retry"
                    else -> null
                }
            }
            Scenario.ALWAYS_UNAUTHORIZED -> want(ProviderFailure.SIGN_IN_NEEDED, PlanIssue.SIGNED_OUT, 2, 1)
            Scenario.REVOKED ->
                want(ProviderFailure.SIGN_IN_NEEDED, PlanIssue.SIGNED_OUT, 0, 1)
                    ?: if (session.signedIn() != null) "tokens were kept after a revoked grant" else null
            Scenario.RENEWAL_UNAVAILABLE ->
                want(ProviderFailure.NETWORK, PlanIssue.NETWORK, 0, 1)
                    ?: if (store.load() == null) "a renewal outage signed the person out" else null
            Scenario.USAGE_LIMIT_HTTP, Scenario.USAGE_LIMIT_IN_STREAM -> want(ProviderFailure.USAGE_LIMIT, PlanIssue.USAGE_LIMIT, 1, 0)
            Scenario.USAGE_UNAVAILABLE_IN_STREAM -> want(ProviderFailure.USAGE_UNAVAILABLE, PlanIssue.USAGE_UNAVAILABLE, 1, 0)
            Scenario.TRUNCATED_STREAM -> want(ProviderFailure.UNREADABLE, PlanIssue.STREAM_INTERRUPTED, 1, 0)
            Scenario.INCOMPLETE -> want(ProviderFailure.BLOCKED, PlanIssue.INCOMPLETE, 1, 0)
            Scenario.FAILED_IN_STREAM, Scenario.SERVER_UNAVAILABLE -> want(ProviderFailure.SERVER, PlanIssue.SERVICE_ERROR, 1, 0)
        }
    }
}
