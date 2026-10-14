package com.thalia.app.ai

import com.thalia.app.secret.ApiKeyStore
import com.thalia.app.secret.SealedSecret
import com.thalia.app.secret.SecretCipher
import com.thalia.conversation.ContextLimits
import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.GeminiPrices
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.ProviderUsage
import com.thalia.conversation.RequestCost
import com.thalia.conversation.Turn
import com.thalia.conversation.TurnRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class PlainCipher : SecretCipher {
    override fun seal(plaintext: ByteArray) = SealedSecret(byteArrayOf(1), plaintext.reversedArray())

    override fun open(sealed: SealedSecret) = sealed.ciphertext.reversedArray()

    override fun destroyKey() = Unit
}

/** A transport that records each call and answers from a script. */
private class FakeTransport(
    var answer: suspend () -> ProviderResponse = { ProviderResponse.Reply("ok", emptyList(), ProviderUsage(1_000, 100)) },
) : ConversationProvider {
    val requests = mutableListOf<ProviderRequest>()

    override suspend fun respond(request: ProviderRequest): ProviderResponse {
        requests += request
        return answer()
    }
}

/** ADR 0013's gates and reconciliation, with a counting fake transport. Nothing here reaches a network. */
class GatedConversationProviderTest {
    @TempDir
    lateinit var dir: File

    private var now = Instant.parse("2026-10-08T10:00:00Z")
    private var ids = 0
    private val request =
        ProviderRequest(
            "instruction",
            "snapshot",
            listOf(Turn(TurnRole.USER, "what should I do first?")),
            "[]",
        )

    private val consent get() = AiConsentStore(File(dir, "ai/consent.txt"))
    private val keys get() = ApiKeyStore(File(dir, "ai/key.sealed"), PlainCipher())
    private val ledger get() = BudgetLedger(File(dir, "ai/budget.log"))

    private fun open(): Pair<FakeTransport, GatedConversationProvider> {
        consent.accept(1)
        keys.save("fake-key-for-tests")
        return withTransport(FakeTransport())
    }

    private fun withTransport(
        transport: FakeTransport?,
        max: Int = ContextLimits.OUTPUT_TOKENS,
    ): Pair<FakeTransport, GatedConversationProvider> {
        val gates = AiGates(consent, keys, ledger, GeminiPrices.TEXT, { now }, { "id-${ids++}" })
        return (transport ?: FakeTransport()) to GatedConversationProvider(transport, gates, max)
    }

    private fun failure(r: ProviderResponse) = assertIs<ProviderResponse.Failed>(r).failure

    @Test
    fun `with no transport, consent off, no key, too large, or no budget, nothing is sent and nothing is reserved`() =
        runTest {
            // No transport at all.
            consent.accept(1)
            keys.save("k")
            assertEquals(ProviderFailure.UNAVAILABLE, failure(withTransport(null).second.respond(request)))

            // Consent off.
            consent.withdraw()
            val (t1, p1) = withTransport(FakeTransport())
            assertEquals(ProviderFailure.CONSENT_NEEDED, failure(p1.respond(request)))
            assertEquals(0, t1.requests.size)

            // Consent given, no key.
            consent.accept(1)
            keys.clear()
            val (t2, p2) = withTransport(FakeTransport())
            assertEquals(ProviderFailure.KEY_MISSING, failure(p2.respond(request)))
            assertEquals(0, t2.requests.size)

            // Key, but the request is over the limit.
            keys.save("k")
            val (t3, p3) = withTransport(FakeTransport())
            val huge = request.copy(contextText = "x".repeat(ContextLimits.REQUEST_BYTES))
            assertEquals(ProviderFailure.TOO_LARGE, failure(p3.respond(huge)))
            assertEquals(0, t3.requests.size)

            // Budget spent.
            assertEquals(BudgetLedger.SetBudget.SET, ledger.setBudget(1, now))
            val (t4, p4) = withTransport(FakeTransport())
            assertEquals(ProviderFailure.BUDGET_EXHAUSTED, failure(p4.respond(request)))
            assertEquals(0, t4.requests.size)

            assertEquals(0, ledger.summary(now).heldCount, "a refused request holds no allowance")
            assertFalse(File(dir, "ai/budget.log").readText().contains("R\t"))
        }

    @Test
    fun `the size limit is on true UTF-8 bytes, so exactly at it is sent and one byte more is not`() =
        runTest {
            val (transport, provider) = open()
            val padding = (ContextLimits.REQUEST_BYTES - RequestCost.requestBytes(request)).toInt()
            val exact = request.copy(contextText = request.contextText + "x".repeat(padding))
            assertEquals(ContextLimits.REQUEST_BYTES.toLong(), RequestCost.requestBytes(exact))
            assertIs<ProviderResponse.Reply>(provider.respond(exact))
            assertEquals(ProviderFailure.TOO_LARGE, failure(provider.respond(exact.copy(contextText = exact.contextText + "x"))))
            assertEquals(1, transport.requests.size, "the oversized request was not sent")
            assertEquals(1, File(dir, "ai/budget.log").readLines().count { it.startsWith("R\t") }, "only the allowed one reserved")
        }

    @Test
    fun `an allowed request reserves before it sends, sends once, and settles at the reported cost`() =
        runTest {
            val (transport, provider) = open()
            var heldWhileSending = -1L
            transport.answer = {
                heldWhileSending = ledger.summary(now).heldMicros
                ProviderResponse.Reply("ok", emptyList(), ProviderUsage(1_000, 100, 200, 1_300))
            }

            assertIs<ProviderResponse.Reply>(provider.respond(request))

            val worst = RequestCost.worstCaseMicros(request, ContextLimits.OUTPUT_TOKENS, GeminiPrices.TEXT, now)
            assertEquals(worst, heldWhileSending, "the allowance was on the disk while the request was in flight")
            assertEquals(1, transport.requests.size, "one request, no tool rounds")
            val price = GeminiPrices.TEXT.worstCase(now)
            val s = ledger.summary(now)
            assertEquals(RequestCost.micros(1_000, 300, price), s.spentMicros)
            assertEquals(0, s.heldCount)
        }

    @Test
    fun `a reply with no usage is charged at its worst case, not at zero`() =
        runTest {
            val (transport, provider) = open()
            transport.answer = { ProviderResponse.Reply("ok", emptyList(), null) }
            provider.respond(request)
            assertEquals(
                RequestCost.worstCaseMicros(request, ContextLimits.OUTPUT_TOKENS, GeminiPrices.TEXT, now),
                ledger.summary(now).spentMicros,
            )
            assertEquals(0, ledger.summary(now).heldCount)
        }

    @Test
    fun `a status the service documents as unbilled returns the allowance, and a refused reply with usage is charged`() =
        runTest {
            val (transport, provider) = open()
            transport.answer = { ProviderResponse.Failed(ProviderFailure.REJECTED, notBilled = true) }
            provider.respond(request)
            assertEquals(0, ledger.summary(now).spentMicros)

            transport.answer = { ProviderResponse.Failed(ProviderFailure.BLOCKED, ProviderUsage(500, null, 4_000, 4_500)) }
            provider.respond(request)
            assertEquals(RequestCost.micros(500, 4_000, GeminiPrices.TEXT.worstCase(now)), ledger.summary(now).spentMicros)
        }

    @Test
    fun `a timeout, a throw and a request with no verdict keep their allowance, and so does a restart`() =
        runTest {
            val (transport, provider) = open()
            val worst = RequestCost.worstCaseMicros(request, ContextLimits.OUTPUT_TOKENS, GeminiPrices.TEXT, now)

            transport.answer = { ProviderResponse.Failed(ProviderFailure.NETWORK) }
            provider.respond(request)
            transport.answer = { error("the transport blew up") }
            assertEquals(ProviderFailure.NETWORK, failure(provider.respond(request)))
            transport.answer = { ProviderResponse.Failed(ProviderFailure.SERVER) }
            provider.respond(request)

            val s = ledger.summary(now)
            assertEquals(3, s.heldCount)
            assertEquals(3 * worst, s.heldMicros)
            // A new process (a new ledger and provider over the same files) sees the same uncertain allowances.
            assertEquals(3 * worst, BudgetLedger(File(dir, "ai/budget.log")).summary(now).spentMicros)
        }

    @Test
    fun `leaving the screen mid-request keeps the allowance`() =
        runTest {
            val (transport, provider) = open()
            val started = CompletableDeferred<Unit>()
            transport.answer = {
                started.complete(Unit)
                CompletableDeferred<ProviderResponse>().await()
            }
            val job = launch(start = CoroutineStart.UNDISPATCHED) { provider.respond(request) }
            started.await()
            job.cancelAndJoin()
            assertEquals(1, ledger.summary(now).heldCount)
        }

    @Test
    fun `a second request while one is in flight is refused as busy and sends nothing`() =
        runTest {
            val (transport, provider) = open()
            val gate = CompletableDeferred<ProviderResponse>()
            val started = CompletableDeferred<Unit>()
            transport.answer = {
                started.complete(Unit)
                gate.await()
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) { provider.respond(request) }
            started.await()
            assertEquals(ProviderFailure.BUSY, failure(provider.respond(request)))
            gate.complete(ProviderResponse.Reply("ok", emptyList(), ProviderUsage(10, 10)))
            assertIs<ProviderResponse.Reply>(first.await())
            assertEquals(1, transport.requests.size)
        }

    @Test
    fun `a transport that made no request releases the allowance`() =
        runTest {
            val (transport, provider) = open()
            transport.answer = { ProviderResponse.Failed(ProviderFailure.UNAVAILABLE) }
            provider.respond(request)
            assertEquals(0, ledger.summary(now).spentMicros)
        }

    @Test
    fun `the allowance reserved is the worst case, so requests stop before the budget is passed even if usage would have been small`() =
        runTest {
            val (transport, provider) = open()
            val worst = RequestCost.worstCaseMicros(request, ContextLimits.OUTPUT_TOKENS, GeminiPrices.TEXT, now)
            ledger.setBudget(worst * 2, now)
            transport.answer = { ProviderResponse.Failed(ProviderFailure.NETWORK) }
            provider.respond(request)
            provider.respond(request)
            assertEquals(ProviderFailure.BUDGET_EXHAUSTED, failure(provider.respond(request)))
            assertEquals(2, transport.requests.size)
        }

    @Test
    fun `a request near the announced price change is reserved at the higher price`() =
        runTest {
            val (transport, provider) = open()
            transport.answer = { ProviderResponse.Reply("ok", emptyList(), null) }
            now = Instant.parse("2026-12-31T12:00:00Z")
            provider.respond(request)
            assertTrue(transport.requests.size == 1)
            val after =
                RequestCost.micros(
                    RequestCost.inputTokenCeiling(request),
                    ContextLimits.OUTPUT_TOKENS.toLong(),
                    GeminiPrices.TEXT.at(java.time.LocalDate.of(2027, 1, 1)),
                )
            val before =
                RequestCost.micros(
                    RequestCost.inputTokenCeiling(request),
                    ContextLimits.OUTPUT_TOKENS.toLong(),
                    GeminiPrices.TEXT.at(java.time.LocalDate.of(2026, 12, 30)),
                )
            assertTrue(after > before)
            // With a reply that carries no usage the full allowance is charged, which shows which price was reserved.
            assertEquals(after, ledger.summary(now).spentMicros)
        }

    @Test
    fun `the wording says every proposed change needs Approve and the old exception is gone`() {
        val text = AiConsentWording.TEXT
        assertTrue("changes nothing until you tap Approve" in text && "Typing or saying yes never approves" in text)
        assertFalse("saved at once" in text || "except an outcome" in text, "no exception to Approve remains")
        assertEquals("ai-text-paid-2026-10-13", AiConsentWording.VERSION)
    }

    @Test
    fun `a consent given to the earlier wording blocks paid use until accepted again, and keeps the key`() =
        runTest {
            // The state of an installed app: consent to the 2026-10-08 wording, a saved key, and a spent allowance.
            AiConsentStore(File(dir, "ai/consent.txt"), "ai-text-paid-2026-10-08").accept(1)
            keys.save("fake-key-for-tests")
            val before = ledger.summary(now)

            val (transport, provider) = withTransport(FakeTransport())
            assertFalse(consent.isAccepted(), "the earlier consent no longer counts")
            assertEquals(ProviderFailure.CONSENT_NEEDED, failure(provider.respond(request)))
            assertEquals(0, transport.requests.size, "nothing is sent")
            assertEquals(before, ledger.summary(now), "a refused request reserves nothing")
            assertTrue(keys.hasKey(), "the key is untouched")

            // Accepting the new wording is a fresh act, and only then does a request go out.
            consent.accept(2)
            assertTrue(consent.isAccepted())
            assertTrue(keys.hasKey())
            assertIs<ProviderResponse.Reply>(withTransport(FakeTransport()).second.respond(request))
        }
}
