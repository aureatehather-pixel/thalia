package com.thalia.conversation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** USD per one million tokens, in micro-dollars, from [effectiveFrom] (a date in Google's own, unstated, time zone). */
data class ModelPrice(
    val effectiveFrom: LocalDate,
    val inputMicrosPerMillionTokens: Long,
    val outputMicrosPerMillionTokens: Long,
) {
    init {
        require(inputMicrosPerMillionTokens > 0 && outputMicrosPerMillionTokens > 0) { "a price must be positive" }
    }
}

/**
 * The published prices of one model over time, so a price change that is already announced is part of the arithmetic
 * rather than a surprise. [readOn] is the day the table was copied from the provider's pricing page: a later reader
 * should check it again, and the budget text says so.
 *
 * The provider does not say which time zone its dates are in, so a request is always costed at the highest price in force
 * on the day before, the day of or the day after it ([worstCase]). That can overstate a request near a change by up to the
 * size of the change, and never understates it.
 */
class PriceSchedule(
    val model: String,
    val readOn: LocalDate,
    prices: List<ModelPrice>,
) {
    private val ordered = prices.sortedBy { it.effectiveFrom }

    init {
        require(ordered.isNotEmpty()) { "a price schedule needs a price" }
        require(ordered.map { it.effectiveFrom }.distinct().size == ordered.size) { "two prices start on one date" }
    }

    /** The price in force on [date]; before the first entry, the first. */
    fun at(date: LocalDate): ModelPrice = ordered.lastOrNull { it.effectiveFrom <= date } ?: ordered.first()

    fun worstCase(at: Instant): ModelPrice {
        val day = at.atZone(ZoneOffset.UTC).toLocalDate()
        val around = listOf(day.minusDays(1), day, day.plusDays(1)).map { at(it) }
        return ModelPrice(
            day,
            around.maxOf { it.inputMicrosPerMillionTokens },
            around.maxOf { it.outputMicrosPerMillionTokens },
        )
    }
}

/**
 * Prices read from https://ai.google.dev/gemini-api/docs/pricing on 2026-10-08 (page dated 2026-10-07): paid tier,
 * `gemini-3.8-flash`, USD 0.75 input and USD 3.75 output (thinking included) per million tokens through 2026-12-31, and
 * USD 1.50 and USD 7.50 from 2027-01-01. The text model id is listed as Stable on https://ai.google.dev/gemini-api/docs/models.
 * The page was read through a summarising fetch, not saved, so check both again before the first real request.
 */
object GeminiPrices {
    const val TEXT_MODEL = "gemini-3.8-flash"

    val TEXT: PriceSchedule =
        PriceSchedule(
            model = TEXT_MODEL,
            readOn = LocalDate.of(2026, 10, 8),
            prices =
                listOf(
                    ModelPrice(LocalDate.of(2026, 1, 1), 750_000, 3_750_000),
                    ModelPrice(LocalDate.of(2027, 1, 1), 1_500_000, 7_500_000),
                ),
        )
}

/**
 * Prices read from https://developers.openai.com/api/docs/models on 2026-10-12: `gpt-6-luna`, USD 0.10 input and USD 0.50
 * output per million tokens, output including reasoning tokens. The page lists no cached-input price, so none is assumed: every
 * input token is costed at the full rate, which can only overstate. Third-party trackers quote the same figures. Check the pricing
 * page again before the first real request.
 */
object OpenAiPrices {
    const val TEXT_MODEL = "gpt-6-luna"

    val TEXT: PriceSchedule =
        PriceSchedule(
            model = TEXT_MODEL,
            readOn = LocalDate.of(2026, 10, 12),
            prices = listOf(ModelPrice(LocalDate.of(2026, 9, 22), 100_000, 500_000)),
        )
}

/** What one request may cost, before it is sent and after the service has said what it used. All in micro-dollars, rounded up. */
object RequestCost {
    /** Slack for the framing the service adds around the text, in tokens. */
    private const val FRAMING_TOKENS = 64L

    /**
     * An upper bound on the input tokens: one token per UTF-8 byte of everything sent. A token is never shorter than a
     * byte, so this cannot be low; it is roughly three to four times what the service will count for English text.
     */
    fun inputTokenCeiling(request: ProviderRequest): Long = requestBytes(request) + FRAMING_TOKENS

    /** The UTF-8 bytes of everything a request carries: instruction, snapshot, tool declarations and every turn. */
    fun requestBytes(request: ProviderRequest): Long =
        listOf(request.instruction, request.contextText, request.toolsJson).sumOf { it.toByteArray().size.toLong() } +
            request.turns.sumOf {
                it.text
                    .toByteArray()
                    .size
                    .toLong()
            }

    fun micros(
        inputTokens: Long,
        outputTokens: Long,
        price: ModelPrice,
    ): Long = ceilDiv(inputTokens * price.inputMicrosPerMillionTokens + outputTokens * price.outputMicrosPerMillionTokens)

    /** The most a request can cost: the input ceiling plus every output token it is allowed, at the worst price near [at]. */
    fun worstCaseMicros(
        request: ProviderRequest,
        maxOutputTokens: Int,
        schedule: PriceSchedule,
        at: Instant,
    ): Long = micros(inputTokenCeiling(request), maxOutputTokens.toLong(), schedule.worstCase(at))

    /**
     * What the service says the request used, or null when it did not say enough to cost it (no prompt count). Output is the
     * larger of candidates plus thinking and the service's total less the prompt, so a field it forgot cannot lower the cost.
     */
    fun actualMicros(
        usage: ProviderUsage?,
        schedule: PriceSchedule,
        at: Instant,
    ): Long? {
        val prompt = usage?.promptTokens?.toLong() ?: return null
        val named = (usage.outputTokens ?: 0).toLong() + (usage.thoughtTokens ?: 0).toLong()
        val remainder = usage.totalTokens?.let { it.toLong() - prompt } ?: 0L
        return micros(prompt, maxOf(named, remainder, 0L), schedule.worstCase(at))
    }

    private fun ceilDiv(value: Long): Long = (value + 999_999) / 1_000_000
}
