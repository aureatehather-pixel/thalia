package com.thalia.conversation

import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.Direction
import com.thalia.planning.Goal
import com.thalia.planning.InboxItem
import com.thalia.planning.PlanningSettings
import com.thalia.planning.Priority
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import com.thalia.planning.Stamped
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Readiness for the Slice 4A synthetic live check (ADR 0013), computed locally with no network: what one Send would reserve,
 * for a dataset shaped like the check build's seed, and the most any one request can ever reserve.
 */
class LiveCheckCostTest {
    private val today = LocalDate.of(2026, 10, 8)
    private val now = Instant.parse("2026-10-08T10:00:00Z")

    private fun context(withInbox: Boolean): ConversationContext {
        val goal = Goal("g", "Synthetic: demo the prototype", "Synthetic: five testers try it", null)
        val project = Project("p", "Synthetic Garden app", "g", ProjectState.ACTIVE)
        val actions =
            listOf(
                Action("a1", "Synthetic: build the watering schedule screen", "p", Priority.MUST, 90, today.plusDays(1)),
                Action("a2", "Synthetic: fix the plant photo upload bug", "p", Priority.IMPORTANT, 60, today.plusDays(4)),
                Action("a3", "Synthetic: write the demo script", "p", Priority.IMPORTANT, 45, today.plusDays(7)),
                Action("a4", "Synthetic: reply to the beta testers", null, Priority.NORMAL, 20),
                Action("a5", "Synthetic: tidy the old notes folder", null, Priority.OPTIONAL, 30),
            )
        val inbox =
            listOf(
                "Synthetic: the thing with the vendor",
                "Synthetic: maybe offer a dark theme in the prototype",
                "Synthetic long capture: " + "this note rambles about the garden app and ".repeat(14),
            ).mapIndexed { i, text -> Stamped(InboxItem("i$i", text), i.toLong(), i.toLong()) }
        return ConversationContext.build(
            ActiveDay(today),
            Direction("Synthetic: ship a small prototype of the Synthetic Garden app"),
            listOf(goal),
            listOf(project),
            actions,
            null,
            emptyMap(),
            PlanningSettings(window = PlanningSettings.SUGGESTED_WINDOW),
            inboxItems = if (withInbox) inbox else null,
        )
    }

    private fun request(
        withInbox: Boolean,
        message: String = "What should I put in today's plan, and why?",
    ) = ProviderRequest(
        ConversationPrompt.INSTRUCTION,
        context(withInbox).render(),
        listOf(Turn(TurnRole.USER, message)),
        ConversationTools.DECLARATIONS_JSON,
    )

    @Test
    fun `a typical send in the check reserves a few cents, and a triage a little more`() {
        val ordinary = RequestCost.worstCaseMicros(request(false), ContextLimits.OUTPUT_TOKENS, GeminiPrices.TEXT, now)
        val triage = RequestCost.worstCaseMicros(request(true), ContextLimits.OUTPUT_TOKENS, GeminiPrices.TEXT, now)
        println(
            "live check: ordinary ${RequestCost.requestBytes(request(false))} bytes reserves $ordinary micro-USD; " +
                "triage ${RequestCost.requestBytes(request(true))} bytes reserves $triage micro-USD",
        )
        // Output dominates: 4,096 tokens at USD 3.75 per million is 15,360 micro-USD of the reservation.
        assertTrue(ordinary in 15_360..40_000, "ordinary send reserves $ordinary")
        assertTrue(triage >= ordinary && triage <= 40_000, "triage send reserves $triage")
        assertTrue(RequestCost.requestBytes(request(true)) < 20_000)
    }

    @Test
    fun `the most any one request can reserve is bounded by the request limit and the output limit, before and after the price change`() {
        val big = request(false).copy(contextText = "x".repeat(ContextLimits.REQUEST_BYTES))
        val capped =
            big.copy(
                contextText =
                    big.contextText.take(
                        ContextLimits.REQUEST_BYTES - (RequestCost.requestBytes(big) - ContextLimits.REQUEST_BYTES).toInt(),
                    ),
            )
        assertEquals(ContextLimits.REQUEST_BYTES.toLong(), RequestCost.requestBytes(capped))
        val now2026 = RequestCost.worstCaseMicros(capped, ContextLimits.OUTPUT_TOKENS, GeminiPrices.TEXT, now)
        val in2027 =
            RequestCost.worstCaseMicros(
                capped,
                ContextLimits.OUTPUT_TOKENS,
                GeminiPrices.TEXT,
                Instant.parse("2027-02-01T00:00:00Z"),
            )
        assertTrue(now2026 <= 52_000, "USD 0.052 at the 2026 price, got $now2026")
        assertTrue(in2027 <= 104_000, "USD 0.104 at the 2027 price, got $in2027")
    }
}
