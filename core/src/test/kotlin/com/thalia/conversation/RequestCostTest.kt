package com.thalia.conversation

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestCostTest {
    private val schedule = GeminiPrices.TEXT
    private val before = Instant.parse("2026-10-08T10:00:00Z")
    private val request = ProviderRequest("instruction", "context", listOf(Turn(TurnRole.USER, "hello")), "[]")

    @Test
    fun `the table carries the announced change on 2027-01-01 and a request is costed at the higher price near it`() {
        assertEquals(750_000, schedule.at(LocalDate.of(2026, 12, 31)).inputMicrosPerMillionTokens)
        assertEquals(1_500_000, schedule.at(LocalDate.of(2027, 1, 1)).inputMicrosPerMillionTokens)
        assertEquals(750_000, schedule.worstCase(Instant.parse("2026-12-30T12:00:00Z")).inputMicrosPerMillionTokens)
        // The provider does not say whose midnight its dates use, so the day before the change already costs the new price.
        assertEquals(1_500_000, schedule.worstCase(Instant.parse("2026-12-31T12:00:00Z")).inputMicrosPerMillionTokens)
        assertEquals(7_500_000, schedule.worstCase(Instant.parse("2027-01-15T12:00:00Z")).outputMicrosPerMillionTokens)
    }

    @Test
    fun `cost rounds up in micro-dollars and a worst case counts every allowed output token`() {
        val price = schedule.at(LocalDate.of(2026, 10, 8))
        assertEquals(1, RequestCost.micros(1, 0, price))
        assertEquals(750_000 + 3_750_000, RequestCost.micros(1_000_000, 1_000_000, price))
        val ceiling = RequestCost.inputTokenCeiling(request)
        assertEquals("instruction".length + "context".length + "[]".length + "hello".length + 64L, ceiling)
        val worst = RequestCost.worstCaseMicros(request, 4_096, schedule, before)
        assertEquals(RequestCost.micros(ceiling, 4_096, price), worst)
        assertTrue(worst >= 4_096 * 3_750_000L / 1_000_000)
    }

    @Test
    fun `the ceiling counts utf-8 bytes, so text that tokenizes badly is not undercounted`() {
        val wide = request.copy(contextText = "日本語".repeat(100))
        assertEquals(
            request.copy(contextText = "x".repeat(900)).let(RequestCost::inputTokenCeiling),
            RequestCost.inputTokenCeiling(wide) - 0,
        )
    }

    @Test
    fun `reported usage is costed from thinking and the service total, and missing usage costs nothing it can state`() {
        val price = schedule.worstCase(before)
        // Thinking is billed as output though it is not in the candidates count.
        assertEquals(RequestCost.micros(100, 80, price), RequestCost.actualMicros(ProviderUsage(100, 30, 50), schedule, before))
        // A total larger than the named fields wins, so a field the service forgot cannot lower the cost.
        assertEquals(RequestCost.micros(100, 400, price), RequestCost.actualMicros(ProviderUsage(100, 30, 50, 500), schedule, before))
        assertNull(RequestCost.actualMicros(null, schedule, before))
        assertNull(RequestCost.actualMicros(ProviderUsage(null, 30), schedule, before))
    }

    @Test
    fun `a schedule needs a price and no two prices may share a date`() {
        assertFailsWith<IllegalArgumentException> { PriceSchedule("m", LocalDate.of(2026, 1, 1), emptyList()) }
        val p = ModelPrice(LocalDate.of(2026, 1, 1), 1, 1)
        assertFailsWith<IllegalArgumentException> { PriceSchedule("m", LocalDate.of(2026, 1, 1), listOf(p, p)) }
    }
}
