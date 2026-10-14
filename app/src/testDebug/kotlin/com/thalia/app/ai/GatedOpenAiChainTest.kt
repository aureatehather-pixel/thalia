package com.thalia.app.ai

import com.sun.net.httpserver.HttpServer
import com.thalia.app.secret.ApiKeyStore
import com.thalia.app.secret.SealedSecret
import com.thalia.app.secret.SecretCipher
import com.thalia.conversation.ConversationPrompt
import com.thalia.conversation.ConversationTools
import com.thalia.conversation.OpenAiPrices
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.RequestCost
import com.thalia.conversation.Turn
import com.thalia.conversation.TurnRole
import com.thalia.openai.OpenAiResponsesProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The whole chain a real send takes on the everyday route: the real OpenAI provider and the real gates, against a socket on this
 * machine. No request leaves it. It pins what the first paid request will cost and what a refusal leaves in the budget.
 */
class GatedOpenAiChainTest {
    @TempDir
    lateinit var dir: File

    private class Plain : SecretCipher {
        override fun seal(plaintext: ByteArray) = SealedSecret(byteArrayOf(1), plaintext.reversedArray())

        override fun open(sealed: SealedSecret) = sealed.ciphertext.reversedArray()

        override fun destroyKey() = Unit
    }

    private val secret = "sk-chain-test-ABC123"
    private val seen = mutableListOf<Pair<String?, String>>()
    private var status = 200
    private val okBody =
        """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Start with a1."}]}],""" +
            """"usage":{"input_tokens":1200,"output_tokens":90,"total_tokens":1290}}"""
    private var body = okBody
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seen += ex.requestHeaders.getFirst("Authorization") to String(ex.requestBody.readAllBytes())
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

    @AfterTest
    fun stop() = server.stop(0)

    private val now = Instant.parse("2026-10-12T10:00:00Z")
    private val request =
        ProviderRequest(
            ConversationPrompt.INSTRUCTION,
            "Active day: 2026-10-12.",
            listOf(Turn(TurnRole.USER, "what first?")),
            ConversationTools.DECLARATIONS_JSON,
        )
    private val ledger get() = BudgetLedger(File(dir, "ai/budget.log"), OpenAiConsentWording.INITIAL_BUDGET_MICROS)

    private fun provider(): GatedConversationProvider {
        val consent = AiConsentStore(File(dir, "ai/consent.txt"), OpenAiConsentWording.VERSION).also { it.accept(1) }
        val keys = ApiKeyStore(File(dir, "ai/openai-key.sealed"), Plain()).also { it.save(secret) }
        val transport = OpenAiResponsesProvider({ keys.load() }, baseUrl = "http://127.0.0.1:${server.address.port}/v1")
        return GatedConversationProvider(transport, AiGates(consent, keys, ledger, OpenAiPrices.TEXT, { now }))
    }

    @Test
    fun `a send uses the bearer key once and settles at the reported usage, a fraction of a cent`() =
        runBlocking {
            val reply = assertIs<ProviderResponse.Reply>(provider().respond(request))
            assertEquals("Start with a1.", reply.text)
            assertEquals(1, seen.size)
            assertEquals("Bearer $secret", seen.single().first)
            assertFalse(secret in seen.single().second)
            val spent = ledger.summary(now).spentMicros
            assertEquals(RequestCost.micros(1200, 90, OpenAiPrices.TEXT.worstCase(now)), spent)
            assertEquals(165L, spent)
            assertTrue(spent < 10_000, "one ordinary request is well under one cent")
            dir.walkTopDown().filter { it.isFile && it.name != "openai-key.sealed" }.forEach {
                assertFalse(
                    secret in it.readText(),
                    it.name,
                )
            }
        }

    @Test
    fun `the budget starts at one dollar and the worst case of one request is a small part of it`() {
        assertEquals(1_000_000L, ledger.summary(now).budgetMicros)
        val worst = RequestCost.worstCaseMicros(request, 4_096, OpenAiPrices.TEXT, now)
        assertTrue(worst < 5_000, "worst case $worst micro-USD")
    }

    @Test
    fun `a billing refusal is reported as billing and returns its allowance, a server error stays held`() =
        runBlocking {
            status = 429
            body = """{"error":{"code":"insufficient_quota","message":"private $secret"}}"""
            val billing = assertIs<ProviderResponse.Failed>(provider().respond(request))
            assertEquals(ProviderFailure.BILLING_NEEDED, billing.failure)
            assertEquals(0, ledger.summary(now).heldCount)
            File(dir, "ai").walkTopDown().filter { it.isFile && it.name != "openai-key.sealed" }.forEach {
                assertFalse("private" in it.readText(), it.name)
            }
            File(dir, "ai/budget.log").delete()
            status = 500
            body = "{}"
            assertEquals(ProviderFailure.SERVER, assertIs<ProviderResponse.Failed>(provider().respond(request)).failure)
            assertEquals(1, ledger.summary(now).heldCount, "OpenAI documents no unbilled 500, so it stays counted")
        }

    @Test
    fun `a rejected request is returned and a cut-off reply is charged at its reported usage`() =
        runBlocking {
            status = 400
            body = "{}"
            assertEquals(ProviderFailure.REJECTED, assertIs<ProviderResponse.Failed>(provider().respond(request)).failure)
            assertEquals(0L, ledger.summary(now).spentMicros)
            status = 200
            body = """{"status":"incomplete","output":[],"usage":{"input_tokens":1000,"output_tokens":4096,"total_tokens":5096}}"""
            assertEquals(ProviderFailure.BLOCKED, assertIs<ProviderResponse.Failed>(provider().respond(request)).failure)
            assertEquals(RequestCost.micros(1000, 4096, OpenAiPrices.TEXT.worstCase(now)), ledger.summary(now).spentMicros)
        }
}
