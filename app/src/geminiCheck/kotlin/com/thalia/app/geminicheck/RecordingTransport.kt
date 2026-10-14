package com.thalia.app.geminicheck

import com.thalia.conversation.ContextLimits
import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.GeminiPrices
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.RequestCost
import java.io.File
import java.time.Instant

/**
 * Wraps the real transport for the live check: before each request it writes what is about to leave the phone (the snapshot
 * and the conversation turns, which are synthetic here) and its size and worst-case cost, and afterwards what came back and how
 * long it took. The key is added inside the transport, as a header, and is never in anything written here; neither are headers.
 *
 * The check's own limits, on top of the production gates and independent of the in-app budget:
 * - at most [maxSends] requests;
 * - the worst-case reservations of every request sent, settled or not, add up to at most [maxReservedMicros]. A request whose
 *   reservation would pass that total is refused before the transport. Reservations are counted, not actual cost, so the
 *   total only ever errs high;
 * - the first fault (any failure, or a reply without usage) writes [stopFile], after which no further request is sent.
 * There is no retry here or below. The counter is written before the transport is called, so a crash or a cancelled screen
 * still counts the request. All three files survive a restart; uninstalling the app removes them.
 */
internal class RecordingTransport(
    private val inner: ConversationProvider,
    private val log: File,
    private val stopFile: File,
    private val counterFile: File,
    private val maxSends: Int = MAX_SENDS,
    private val maxReservedMicros: Long = MAX_RESERVED_MICROS,
    private val now: () -> Instant = Instant::now,
) : ConversationProvider {
    override suspend fun respond(request: ProviderRequest): ProviderResponse {
        if (stopFile.exists()) return refuse("stopped after an earlier fault: ${stopFile.readText().trim()}")
        val (sent, reserved) = counts()
        if (sent >= maxSends) return refuse("the check's limit of $maxSends sends is reached")
        val worst = RequestCost.worstCaseMicros(request, ContextLimits.OUTPUT_TOKENS, GeminiPrices.TEXT, now())
        if (reserved + worst > maxReservedMicros) {
            return refuse("reservations so far $reserved plus this request's $worst micro-USD would pass the total of $maxReservedMicros")
        }
        counterFile.writeText("${sent + 1} ${reserved + worst}\n")
        log.appendText(
            "--- request ${now()} number=${sent + 1}\nbytes=${RequestCost.requestBytes(request)} worstCaseMicros=$worst " +
                "reservedTotalMicros=${reserved + worst}\n" +
                "turns:\n${request.turns.joinToString("\n") { "  ${it.role}: ${it.text}" }}\n" +
                "snapshot:\n${request.contextText}\n",
        )
        val started = System.nanoTime()
        val response = inner.respond(request)
        val millis = (System.nanoTime() - started) / 1_000_000
        log.appendText("--- response after $millis ms\n$response\n")
        val fault =
            when {
                response is ProviderResponse.Failed ->
                    "failure ${response.failure} (HTTP ${response.httpStatus ?: "none"}) on send ${sent + 1}"
                response is ProviderResponse.Reply && response.usage?.totalTokens == null -> "reply without usage on send ${sent + 1}"
                else -> null
            }
        if (fault != null) {
            stopFile.writeText("$fault\n")
            log.appendText("--- STOP: $fault. No further request will be sent.\n")
        }
        return response
    }

    /** Sends so far and micro-USD reserved so far. An unreadable counter counts as used up, never as empty. */
    private fun counts(): Pair<Int, Long> {
        if (!counterFile.isFile) return 0 to 0L
        val parts = counterFile.readText().trim().split(' ')
        val sent = parts.getOrNull(0)?.toIntOrNull()
        val reserved = parts.getOrNull(1)?.toLongOrNull()
        return if (parts.size == 2 && sent != null && reserved != null) sent to reserved else maxSends to maxReservedMicros
    }

    private fun refuse(why: String): ProviderResponse {
        log.appendText("--- refused locally, nothing sent: $why\n")
        return ProviderResponse.Failed(ProviderFailure.UNAVAILABLE)
    }

    companion object {
        const val MAX_SENDS = 8

        /** USD 0.42, the owner's cap on cumulative reservations for the whole run. */
        const val MAX_RESERVED_MICROS = 420_000L
    }
}
