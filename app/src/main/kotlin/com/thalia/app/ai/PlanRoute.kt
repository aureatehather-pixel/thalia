package com.thalia.app.ai

import com.thalia.conversation.ContextLimits
import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.RequestCost
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The ChatGPT-plan route's readiness, with each missing piece named. The order is the order the provider checks them in:
 * consent, then a model, then a sign-in (stored, then shown to work). Nothing here knows how a sign-in is made or where a credential is kept.
 */
internal enum class PlanRouteReadiness { UNAVAILABLE, CONSENT_NEEDED, MODEL_NEEDED, SIGN_IN_NEEDED, SIGN_IN_UNVERIFIED, READY }

internal fun planRouteReadiness(
    consented: Boolean,
    model: String?,
    signedIn: Boolean,
): PlanRouteReadiness =
    when {
        !consented -> PlanRouteReadiness.CONSENT_NEEDED
        model == null -> PlanRouteReadiness.MODEL_NEEDED
        !signedIn -> PlanRouteReadiness.SIGN_IN_NEEDED
        else -> PlanRouteReadiness.READY
    }

/** The one line at the top of the conversation on this route. It claims nothing about eligibility, metering or cost. */
internal fun planRouteNotice(readiness: PlanRouteReadiness): String =
    when (readiness) {
        PlanRouteReadiness.UNAVAILABLE -> "ChatGPT plan route: not available in this build, so nothing is sent."
        PlanRouteReadiness.CONSENT_NEEDED -> "ChatGPT plan route: not agreed to yet, so nothing is sent."
        PlanRouteReadiness.MODEL_NEEDED -> "ChatGPT plan route: no model is chosen, so nothing is sent."
        PlanRouteReadiness.SIGN_IN_NEEDED -> "ChatGPT plan route: not signed in, so nothing is sent."
        PlanRouteReadiness.SIGN_IN_UNVERIFIED ->
            "ChatGPT plan route: a sign-in is stored but has not been shown to work, so nothing is sent."
        PlanRouteReadiness.READY ->
            "ChatGPT plan route: ready. Pressing Send shares a snapshot of your planning data with ChatGPT using your sign-in. " +
                "Nothing is applied without your tap."
    }

/**
 * This route's own door, in front of the provider and instead of the Gemini gates: the request must fit the size limit, and only
 * one may be in flight. There is no key, no ledger and no dollar budget here, and no cap that stands in for metering, which is not
 * established. Consent, a model and a sign-in are checked by the provider and reported as their own failures. An unexpected
 * exception from the provider is a network failure; a cancellation is not swallowed.
 */
internal class PlanRouteProvider(
    private val inner: ConversationProvider,
) : ConversationProvider {
    private val inFlight = AtomicBoolean(false)

    override suspend fun respond(request: ProviderRequest): ProviderResponse {
        if (RequestCost.requestBytes(request) > ContextLimits.REQUEST_BYTES) return ProviderResponse.Failed(ProviderFailure.TOO_LARGE)
        if (!inFlight.compareAndSet(false, true)) return ProviderResponse.Failed(ProviderFailure.BUSY)
        try {
            return try {
                inner.respond(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProviderResponse.Failed(ProviderFailure.NETWORK)
            }
        } finally {
            inFlight.set(false)
        }
    }
}

/** A provider with the notice that goes with it. */
internal class ConversationRoute(
    val provider: ConversationProvider,
    val notice: () -> String,
)

/**
 * Which route the conversation uses when it is not the default. **Nothing in a shipped build installs one**: [installed] stays
 * null, so the conversation uses the default provider exactly as before. An isolated check build or a test installs the ChatGPT-plan
 * route here with a provider it composed itself, so this code never names OkHttp or the provider module, and a release build needs
 * neither.
 */
internal object ConversationRoutes {
    @Volatile
    var installed: ConversationRoute? = null
        private set

    /** Installs the ChatGPT-plan route over [inner], behind this route's own door. */
    fun installPlan(
        inner: ConversationProvider,
        readiness: () -> PlanRouteReadiness,
    ) {
        installed = ConversationRoute(PlanRouteProvider(inner)) { planRouteNotice(readiness()) }
    }

    fun clear() {
        installed = null
    }
}

/**
 * Whether Send in the conversation could reach a model right now: an installed route, or a connection with consent and a key.
 * When it cannot, answering a call opens the structured check-in instead of a conversation that would answer "AI is off".
 */
internal fun aiUsable(ai: AiServices): Boolean =
    ConversationRoutes.installed != null || (ai.connection != AiConnection.NONE && ai.consent.isAccepted() && ai.keys.hasKey())
