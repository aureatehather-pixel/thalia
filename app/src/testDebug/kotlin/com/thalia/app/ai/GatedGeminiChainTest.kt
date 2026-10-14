package com.thalia.app.ai

import com.sun.net.httpserver.HttpServer
import com.thalia.app.secret.ApiKeyStore
import com.thalia.app.secret.SealedSecret
import com.thalia.app.secret.SecretCipher
import com.thalia.conversation.ConversationPrompt
import com.thalia.conversation.ConversationTools
import com.thalia.conversation.GeminiPrices
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.RequestCost
import com.thalia.conversation.Turn
import com.thalia.conversation.TurnRole
import com.thalia.voicecheckins.gemini.GeminiApiKey
import com.thalia.voicecheckins.gemini.GeminiTextConversationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The whole chain a real send would take, with the real Gemini transport and the real gates, against a socket on this
 * machine. No request leaves it: the transport's base URL is localhost. (debug test source set, where the transport exists.)
 */
class GatedGeminiChainTest {
    @TempDir
    lateinit var dir: File

    private class Plain : SecretCipher {
        override fun seal(plaintext: ByteArray) = SealedSecret(byteArrayOf(1), plaintext.reversedArray())

        override fun open(sealed: SealedSecret) = sealed.ciphertext.reversedArray()

        override fun destroyKey() = Unit
    }

    private val secret = "chain-test-key-ABC123"
    private val seen = mutableListOf<Pair<String?, String>>()
    private var status = 200
    private val okBody =
        """{"candidates":[{"content":{"parts":[{"text":"Start with a1."}]},"finishReason":"STOP"}],""" +
            """"usageMetadata":{"promptTokenCount":900,"candidatesTokenCount":20,"thoughtsTokenCount":300,"totalTokenCount":1220}}"""
    private var body = okBody
    private var delayMillis = 0L
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seen += ex.requestHeaders.getFirst("x-goog-api-key") to String(ex.requestBody.readAllBytes())
                if (delayMillis > 0) Thread.sleep(delayMillis)
                val bytes = body.toByteArray()
                try {
                    ex.sendResponseHeaders(status, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                } catch (e: java.io.IOException) {
                    // The client gave up first.
                }
            }
            start()
        }

    @AfterTest
    fun stop() = server.stop(0)

    private val now = Instant.parse("2026-10-08T10:00:00Z")
    private val request =
        ProviderRequest(
            ConversationPrompt.INSTRUCTION,
            "Active day: 2026-10-08.",
            listOf(Turn(TurnRole.USER, "what first?")),
            ConversationTools.DECLARATIONS_JSON,
        )
    private val ledger get() = BudgetLedger(File(dir, "ai/budget.log"))

    private fun provider(open: Boolean = true): GatedConversationProvider {
        val consent = AiConsentStore(File(dir, "ai/consent.txt"))
        val keys = ApiKeyStore(File(dir, "ai/key.sealed"), Plain())
        if (open) {
            consent.accept(1)
            keys.save(secret)
        }
        val transport =
            GeminiTextConversationProvider(
                apiKey = { keys.load()?.let(::GeminiApiKey) },
                client = OkHttpClient.Builder().callTimeout(400, TimeUnit.MILLISECONDS).build(),
                baseUrl = "http://127.0.0.1:${server.address.port}/v1beta",
            )
        return GatedConversationProvider(transport, AiGates(consent, keys, ledger, GeminiPrices.TEXT, { now }))
    }

    @Test
    fun `closed gates send nothing to the socket`() =
        runBlocking {
            assertEquals(ProviderFailure.CONSENT_NEEDED, assertIs<ProviderResponse.Failed>(provider(open = false).respond(request)).failure)
            assertEquals(0, seen.size)
        }

    @Test
    fun `an open send carries the key in a header, one request, and settles at the usage Google reports including thinking`() =
        runBlocking {
            val reply = assertIs<ProviderResponse.Reply>(provider().respond(request))
            assertEquals("Start with a1.", reply.text)
            assertEquals(1, seen.size)
            assertEquals(secret, seen.single().first)
            assertFalse(secret in seen.single().second, "the key is a header, never part of the body")
            val price = GeminiPrices.TEXT.worstCase(now)
            assertEquals(RequestCost.micros(900, 320, price), ledger.summary(now).spentMicros)
            // The key lives only in the sealed file: no other file the app wrote mentions it.
            dir.walkTopDown().filter { it.isFile && it.name != "key.sealed" }.forEach { assertFalse(secret in it.readText(), it.name) }
        }

    @Test
    fun `the release policy per HTTP status, through the real transport and gates`() =
        runBlocking {
            // 400 and 500 are the only statuses Google documents as uncharged: released. Every other status is held.
            val released = listOf(400, 500)
            val held = listOf(401, 403, 404, 408, 413, 429, 502, 503, 504)
            val expected = released.associateWith { 0 } + held.associateWith { 1 }
            expected.forEach { (code, held) ->
                File(dir, "ai").deleteRecursively()
                status = code
                body = """{"error":{"message":"x"}}"""
                provider().respond(request)
                assertEquals(held, ledger.summary(now).heldCount, "HTTP $code should leave $held held")
            }
        }

    @Test
    fun `404, 413 and 503 reach the caller with their status and stay held, and the ledger holds no status or body`() =
        runBlocking {
            mapOf(
                404 to ProviderFailure.NOT_FOUND,
                413 to ProviderFailure.REQUEST_TOO_LARGE,
                503 to ProviderFailure.SERVER,
            ).forEach { (code, failure) ->
                File(dir, "ai").deleteRecursively()
                status = code
                body = """{"error":{"message":"private detail $secret"}}"""
                val f = assertIs<ProviderResponse.Failed>(provider().respond(request))
                assertEquals(failure, f.failure)
                assertEquals(code, f.httpStatus)
                assertFalse(f.notBilled)
                assertEquals(1, ledger.summary(now).heldCount, "HTTP $code is uncertain, so its allowance stays counted")
                File(dir, "ai").walkTopDown().filter { it.isFile && it.name != "key.sealed" }.forEach {
                    assertFalse("private detail" in it.readText() || secret in it.readText(), it.name)
                }
            }
        }

    @Test
    fun `a 400 is not billed, a timeout is held, and the held amount survives a new provider`() =
        runBlocking {
            status = 400
            body = """{"error":{"message":"bad"}}"""
            assertEquals(ProviderFailure.REJECTED, assertIs<ProviderResponse.Failed>(provider().respond(request)).failure)
            assertEquals(0, ledger.summary(now).spentMicros)

            status = 200
            body = okBody
            delayMillis = 1_500
            assertEquals(ProviderFailure.NETWORK, assertIs<ProviderResponse.Failed>(provider().respond(request)).failure)
            val held = ledger.summary(now)
            assertEquals(1, held.heldCount)
            assertTrue(held.heldMicros > 0)
            delayMillis = 0
            // The fake serves one request at a time, so let the slow one finish before the next.
            Thread.sleep(1_500)
            assertIs<ProviderResponse.Reply>(provider().respond(request))
            assertEquals(1, ledger.summary(now).heldCount, "a later success does not clear an earlier uncertain request")
        }
}
