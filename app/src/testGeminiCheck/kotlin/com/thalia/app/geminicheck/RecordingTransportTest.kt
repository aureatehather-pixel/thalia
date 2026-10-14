package com.thalia.app.geminicheck

import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.ProviderUsage
import com.thalia.conversation.Turn
import com.thalia.conversation.TurnRole
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class Counting(
    var answer: ProviderResponse = ProviderResponse.Reply("ok", emptyList(), ProviderUsage(1_000, 100, 0, 1_100)),
) : ConversationProvider {
    var calls = 0

    override suspend fun respond(request: ProviderRequest): ProviderResponse {
        calls++
        return answer
    }
}

/** The live check's own guard: eight Sends, a USD 0.42 run total of reservations, and a persistent stop. No network. */
class RecordingTransportTest {
    @TempDir
    lateinit var dir: File

    private val request = ProviderRequest("instruction", "snapshot", listOf(Turn(TurnRole.USER, "hello")), "[]")
    private val log get() = File(dir, "log")
    private val stop get() = File(dir, "stop")
    private val count get() = File(dir, "count")

    private fun transport(
        inner: ConversationProvider,
        maxSends: Int = 8,
        maxMicros: Long = 420_000L,
    ) = RecordingTransport(inner, log, stop, count, maxSends, maxMicros)

    @Test
    fun `the ninth send is refused before the transport`() =
        runTest {
            val inner = Counting()
            repeat(8) { assertIs<ProviderResponse.Reply>(transport(inner).respond(request)) }
            val ninth = transport(inner).respond(request)
            assertEquals(ProviderResponse.Failed(ProviderFailure.UNAVAILABLE), ninth)
            assertEquals(8, inner.calls)
        }

    @Test
    fun `a request that would pass the run total is refused, counting settled requests too`() =
        runTest {
            val inner = Counting()
            transport(inner, maxMicros = Long.MAX_VALUE).respond(request)
            val fields = count.readText().trim().split(" ")
            val one = fields[1].toLong()
            count.delete()
            log.delete()
            inner.calls = 0
            // Room for exactly two reservations; a third would pass the total even though the first two completed and settled.
            val t = transport(inner, maxMicros = one * 2)
            assertIs<ProviderResponse.Reply>(t.respond(request))
            assertIs<ProviderResponse.Reply>(t.respond(request))
            assertEquals(ProviderResponse.Failed(ProviderFailure.UNAVAILABLE), t.respond(request))
            assertEquals(2, inner.calls)
            assertTrue(log.readText().contains("would pass the total"))
        }

    @Test
    fun `the default run total is USD 0_42`() = assertEquals(420_000L, RecordingTransport.MAX_RESERVED_MICROS)

    @Test
    fun `a failure stops later sends, and the stop survives a new instance`() =
        runTest {
            val inner = Counting(ProviderResponse.Failed(ProviderFailure.RATE_LIMITED))
            transport(inner).respond(request)
            assertTrue(stop.isFile)
            inner.answer = ProviderResponse.Reply("ok", emptyList(), ProviderUsage(1, 1, 0, 2))
            assertEquals(ProviderResponse.Failed(ProviderFailure.UNAVAILABLE), transport(inner).respond(request))
            assertEquals(1, inner.calls)
        }

    @Test
    fun `the stop reason and the log name the http status and nothing else of the failure`() =
        runBlocking {
            val inner = Counting(ProviderResponse.Failed(ProviderFailure.NOT_FOUND, httpStatus = 404))
            transport(inner).respond(request)
            assertTrue("NOT_FOUND (HTTP 404) on send 1" in stop.readText())
            assertTrue("httpStatus=404" in log.readText())
            stop.delete()
            count.delete()
            log.delete()
            inner.answer = ProviderResponse.Failed(ProviderFailure.NETWORK)
            transport(inner).respond(request)
            assertTrue("NETWORK (HTTP none)" in stop.readText())
        }

    @Test
    fun `a reply without usage stops later sends`() =
        runTest {
            val inner = Counting(ProviderResponse.Reply("ok", emptyList(), usage = null))
            transport(inner).respond(request)
            assertTrue(stop.readText().contains("without usage"))
            transport(inner).respond(request)
            assertEquals(1, inner.calls)
        }

    @Test
    fun `usage without a total also stops`() =
        runTest {
            val inner = Counting(ProviderResponse.Reply("ok", emptyList(), ProviderUsage(10, 5)))
            transport(inner).respond(request)
            assertTrue(stop.isFile)
        }

    @Test
    fun `an unreadable counter counts as used up and the counter is written before the transport call`() =
        runTest {
            count.writeText("garbage")
            val inner = Counting()
            assertEquals(ProviderResponse.Failed(ProviderFailure.UNAVAILABLE), transport(inner).respond(request))
            assertEquals(0, inner.calls)
            count.delete()
            var seenDuringCall = false
            val probe =
                object : ConversationProvider {
                    override suspend fun respond(request: ProviderRequest): ProviderResponse {
                        seenDuringCall = count.isFile && count.readText().startsWith("1 ")
                        return ProviderResponse.Reply("ok", emptyList(), ProviderUsage(1, 1, 0, 2))
                    }
                }
            transport(probe).respond(request)
            assertTrue(seenDuringCall)
            assertFalse(stop.exists())
        }

    @Test
    fun `the log records latency and never a header or key`() =
        runTest {
            transport(Counting()).respond(request)
            val text = log.readText()
            assertTrue(Regex("--- response after \\d+ ms").containsMatchIn(text))
            assertFalse(text.contains("x-goog-api-key", ignoreCase = true))
        }
}
