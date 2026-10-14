package com.thalia.app.ai

import com.thalia.app.secret.ApiKeyStore
import com.thalia.conversation.ContextLimits
import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.PriceSchedule
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.RequestCost
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Everything a request is checked against before it may leave the phone. */
internal class AiGates(
    val consent: AiConsentStore,
    val keys: ApiKeyStore,
    val ledger: BudgetLedger,
    val prices: PriceSchedule,
    val clock: () -> Instant = Instant::now,
    val newId: () -> String = { UUID.randomUUID().toString() },
)

/**
 * The one door to the network, ADR 0013. [transport] is the real service (null where the build cannot reach one). Every
 * request passes these gates in order, and a refusal at any of them means nothing was sent:
 *
 * 1. a transport exists, consent to the current wording is on and a key is saved;
 * 2. the request is within [ContextLimits.REQUEST_BYTES];
 * 3. no other request is in flight;
 * 4. its worst-case cost, at the highest price near today, fits in the month's budget, and that allowance is on the disk.
 *
 * Only then is the request sent, once: this provider makes exactly one transport call per request and never sends a
 * function result back, so there is one round per message. Afterwards the allowance is reconciled. A reply or a refused
 * reply with usage settles at the reported cost. A status the service documents as unbilled releases it. Anything else,
 * a timeout, a dropped connection, a reply without usage, a cancelled screen or a crash, leaves the full allowance counted:
 * the request may have been billed, and an unknown cost is charged at its worst case rather than at zero.
 */
internal class GatedConversationProvider(
    private val transport: ConversationProvider?,
    private val gates: AiGates,
    private val maxOutputTokens: Int = ContextLimits.OUTPUT_TOKENS,
) : ConversationProvider {
    private val inFlight = AtomicBoolean(false)

    override suspend fun respond(request: ProviderRequest): ProviderResponse {
        if (transport == null) return ProviderResponse.Failed(ProviderFailure.UNAVAILABLE)
        if (!gates.consent.isAccepted()) return ProviderResponse.Failed(ProviderFailure.CONSENT_NEEDED)
        if (!gates.keys.hasKey()) return ProviderResponse.Failed(ProviderFailure.KEY_MISSING)
        if (requestBytes(request) > ContextLimits.REQUEST_BYTES) return ProviderResponse.Failed(ProviderFailure.TOO_LARGE)
        if (!inFlight.compareAndSet(false, true)) return ProviderResponse.Failed(ProviderFailure.BUSY)
        try {
            val start = gates.clock()
            val worstCase = RequestCost.worstCaseMicros(request, maxOutputTokens, gates.prices, start)
            val held =
                when (val r = gates.ledger.reserve(gates.newId(), worstCase, start)) {
                    is Reservation.Reserved -> r
                    is Reservation.BudgetExhausted -> return ProviderResponse.Failed(ProviderFailure.BUDGET_EXHAUSTED)
                    Reservation.Unwritable -> return ProviderResponse.Failed(ProviderFailure.LEDGER_UNWRITABLE)
                }
            // From here the request may reach the network. A cancellation or a throw leaves the allowance counted.
            val response =
                try {
                    transport.respond(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return ProviderResponse.Failed(ProviderFailure.NETWORK)
                }
            reconcile(held, response, worstCase)
            return response
        } finally {
            inFlight.set(false)
        }
    }

    private fun reconcile(
        held: Reservation.Reserved,
        response: ProviderResponse,
        worstCase: Long,
    ) {
        val at = gates.clock()
        val usage =
            when (response) {
                is ProviderResponse.Reply -> response.usage
                is ProviderResponse.Failed -> response.usage
            }
        val actual = RequestCost.actualMicros(usage, gates.prices, at)
        when {
            // The transport made no request at all (for example its key could not be opened).
            response is ProviderResponse.Failed && response.failure == ProviderFailure.UNAVAILABLE -> gates.ledger.release(held, at)
            actual != null -> gates.ledger.settle(held, actual, at)
            response is ProviderResponse.Failed && response.notBilled -> gates.ledger.release(held, at)
            // A reply that did not say what it used was processed: charge the allowance in full, as a settled cost.
            response is ProviderResponse.Reply -> gates.ledger.settle(held, worstCase, at)
            // Otherwise it stays held and uncertain.
        }
    }

    private fun requestBytes(request: ProviderRequest): Long = RequestCost.requestBytes(request)
}
