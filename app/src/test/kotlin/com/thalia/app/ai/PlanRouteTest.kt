package com.thalia.app.ai

import com.thalia.app.conversation.providerFailureText
import com.thalia.conversation.ContextLimits
import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanRouteTest {
    private fun request(text: String = "hello") = ProviderRequest("instruction", text, emptyList(), "[]")

    private class Recording(
        val answer: suspend () -> ProviderResponse = { ProviderResponse.Reply("ok", emptyList()) },
    ) : ConversationProvider {
        var calls = 0

        override suspend fun respond(request: ProviderRequest): ProviderResponse {
            calls++
            return answer()
        }
    }

    @Test
    fun `readiness names the first missing piece in the provider's own order`() {
        assertEquals(PlanRouteReadiness.CONSENT_NEEDED, planRouteReadiness(false, null, false))
        assertEquals(PlanRouteReadiness.MODEL_NEEDED, planRouteReadiness(true, null, false))
        assertEquals(PlanRouteReadiness.SIGN_IN_NEEDED, planRouteReadiness(true, "m", false))
        assertEquals(PlanRouteReadiness.READY, planRouteReadiness(true, "m", true))
    }

    @Test
    fun `a request over the size limit is refused before the provider is called`() =
        runTest {
            val inner = Recording()
            val route = PlanRouteProvider(inner)
            val big = request("x".repeat(ContextLimits.REQUEST_BYTES + 10))
            assertEquals(ProviderFailure.TOO_LARGE, (route.respond(big) as ProviderResponse.Failed).failure)
            assertEquals(0, inner.calls)
            assertTrue(route.respond(request()) is ProviderResponse.Reply)
            assertEquals(1, inner.calls)
        }

    @Test
    fun `only one request is in flight, the second is refused as busy, and the door opens again afterwards`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val inner =
                Recording {
                    gate.await()
                    ProviderResponse.Reply("ok", emptyList())
                }
            val route = PlanRouteProvider(inner)
            val first = launch { route.respond(request()) }
            testScheduler.runCurrent()
            assertEquals(ProviderFailure.BUSY, (route.respond(request()) as ProviderResponse.Failed).failure)
            assertEquals(1, inner.calls)
            gate.complete(Unit)
            first.join()
            assertTrue(route.respond(request()) is ProviderResponse.Reply)
        }

    @Test
    fun `an unexpected exception from the provider is a network failure, a cancellation is not swallowed, and the door reopens`() =
        runTest {
            val route = PlanRouteProvider(Recording { error("boom") })
            assertEquals(ProviderFailure.NETWORK, (route.respond(request()) as ProviderResponse.Failed).failure)
            val cancelling = PlanRouteProvider(Recording { throw CancellationException("stop") })
            assertFailsWith<CancellationException> { cancelling.respond(request()) }
            assertEquals(ProviderFailure.NETWORK, (route.respond(request()) as ProviderResponse.Failed).failure)
        }

    @Test
    fun `installing a route is explicit and clearing it restores the default, with nothing installed to begin with`() {
        ConversationRoutes.clear()
        assertNull(ConversationRoutes.installed)
        ConversationRoutes.installPlan(Recording()) { PlanRouteReadiness.SIGN_IN_NEEDED }
        assertEquals(planRouteNotice(PlanRouteReadiness.SIGN_IN_NEEDED), ConversationRoutes.installed?.notice?.invoke())
        ConversationRoutes.clear()
        assertNull(ConversationRoutes.installed)
    }

    @Test
    fun `the plan route's failure texts make no budget claim, and the Gemini texts keep theirs`() {
        listOf(ProviderFailure.NETWORK, ProviderFailure.UNEXPECTED_STATUS, ProviderFailure.REQUEST_TOO_LARGE).forEach {
            assertFalse("budget" in providerFailureText(it, budgetNotes = false).lowercase(), it.name)
            assertTrue("budget" in providerFailureText(it, budgetNotes = true).lowercase(), it.name)
        }
        listOf(
            ProviderFailure.SIGN_IN_NEEDED,
            ProviderFailure.USAGE_LIMIT,
            ProviderFailure.USAGE_UNAVAILABLE,
            ProviderFailure.MODEL_NEEDED,
        ).forEach {
            val text = providerFailureText(it, false).lowercase()
            assertTrue("budget" !in text && "key" !in text, it.name)
        }
    }
}
