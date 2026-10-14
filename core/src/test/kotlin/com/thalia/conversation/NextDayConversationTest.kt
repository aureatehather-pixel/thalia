package com.thalia.conversation

import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.DayPlan
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.ItemKind
import com.thalia.planning.OutcomeResult
import com.thalia.planning.Placement
import com.thalia.planning.PlanItem
import com.thalia.planning.PlanOutcome
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Planning the next active day from the conversation: what the model may say, and what Kotlin resolves and refuses. */
class NextDayConversationTest {
    private val today = ActiveDay(LocalDate.of(2026, 10, 12))
    private val nextDay = ActiveDay(LocalDate.of(2026, 10, 13))
    private val window = PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))
    private val settings = PlanningSettings(LocalTime.of(4, 0), window)
    private val write = Action("id-write", "Write intro", null, Priority.MUST, 60)
    private val read = Action("id-read", "Read paper", null, Priority.NORMAL, 30)
    private val email = Action("id-email", "Email tutor", null, Priority.IMPORTANT, 15)

    private fun item(
        id: String,
        plan: String,
        action: Action,
        position: Int,
        kind: ItemKind = ItemKind.QUEUED,
    ) = PlanItem(
        id,
        plan,
        action.id,
        kind,
        if (kind == ItemKind.PINNED) LocalTime.of(14, 0) else null,
        action.estimateMinutes,
        position,
        if (kind == ItemKind.QUEUED) Placement.WITHIN_TARGET else null,
        null,
        action.title,
        action.priority,
        null,
        null,
        null,
    )

    private fun plan(
        id: String,
        day: ActiveDay,
        vararg items: PlanItem,
    ) = DayPlanWithItems(DayPlan(id, day, 1, ZoneId.of("UTC"), LocalTime.of(4, 0), window, 600, 480, 0, 1L), items.toList())

    private fun context(
        todayPlan: DayPlanWithItems? = plan("p-today", today, item("i-write", "p-today", write, 0)),
        nextPlan: DayPlanWithItems? = null,
        next: ActiveDay? = nextDay,
        outcomes: Map<String, PlanOutcome> = emptyMap(),
    ) = ConversationContext.build(
        today,
        null,
        emptyList(),
        emptyList(),
        listOf(write, read, email),
        todayPlan,
        outcomes,
        settings,
        nextDay = next,
        nextPlan = nextPlan,
    )

    private fun call(args: String) = RawCall("propose_day_selection", args)

    private fun decide(
        ctx: ConversationContext,
        args: String,
    ) = OperationValidator.validateAll(ctx, listOf(call(args))) { "id" }.single()

    private fun refs(ctx: ConversationContext) = ctx.actions.associate { it.action.id to it.ref }

    @Test
    fun `the day argument is today, next or absent, and anything else is refused whole`() {
        fun parsed(args: String) = OperationParser.parse(call(args))
        assertEquals(
            DayTarget.TODAY,
            ((parsed("""{"action_refs":["a1"]}""") as ParseResult.Parsed).operation as RequestedOperation.ProposeDaySelection).target,
        )
        assertEquals(
            DayTarget.TODAY,
            (
                (
                    parsed(
                        """{"action_refs":["a1"],"day":"today"}""",
                    ) as ParseResult.Parsed
                ).operation as RequestedOperation.ProposeDaySelection
            ).target,
        )
        assertEquals(
            DayTarget.NEXT,
            (
                (
                    parsed(
                        """{"action_refs":["a1"],"day":"Next"}""",
                    ) as ParseResult.Parsed
                ).operation as RequestedOperation.ProposeDaySelection
            ).target,
        )
        listOf("tomorrow", "2026-10-14", "monday", "next week", "").forEach {
            assertIs<ParseResult.Unsupported>(parsed("""{"action_refs":["a1"],"day":"$it"}"""), it)
        }
        assertIs<ParseResult.Unsupported>(parsed("""{"action_refs":["a1"],"date":"2026-10-14"}"""), "a date argument is not allowed")
        assertIs<ParseResult.Unsupported>(parsed("""{"action_refs":["a1"],"day":13}"""))
    }

    @Test
    fun `the tool declares the day as today or next only`() {
        assertTrue("\"enum\": [\"today\", \"next\"]" in ConversationTools.DECLARATIONS_JSON)
    }

    @Test
    fun `the snapshot names the next active day and what its plan is`() {
        val none = context().render().lines().first { it.startsWith("Next active day:") }
        assertTrue("2026-10-13 (Tue 13 Oct)" in none && "none approved yet" in none && "never name a date" in none, none)
        val withPlan = context(nextPlan = plan("p-next", nextDay, item("i-read", "p-next", read, 0))).render()
        assertTrue("revision 1 approved: \"Read paper\"" in withPlan)
        assertTrue(context(next = null).render().lines().none { it.startsWith("Next active day:") })
    }

    @Test
    fun `a next-day selection is proposed for the day Kotlin resolved, based on today`() {
        val ctx = context()
        val a = refs(ctx)
        val proposal =
            assertIs<Proposal.DaySelection>(
                assertIs<Decision.Propose>(decide(ctx, """{"action_refs":["${a["id-read"]}","${a["id-email"]}"],"day":"next"}""")).proposal,
            )
        assertEquals(DayTarget.NEXT, proposal.target)
        assertEquals(nextDay, proposal.day)
        assertEquals(today, proposal.baseDay)
        assertEquals(setOf("id-read", "id-email"), proposal.selected)
    }

    @Test
    fun `the next day's own pins are kept for actions that stay, and today's pins are not borrowed`() {
        val pinnedNext = plan("p-next", nextDay, item("i-read", "p-next", read, 0, ItemKind.PINNED), item("i-email", "p-next", email, 1))
        val pinnedToday = plan("p-today", today, item("i-write", "p-today", write, 0, ItemKind.PINNED))
        val ctx = context(todayPlan = pinnedToday, nextPlan = pinnedNext)
        val a = refs(ctx)
        val proposal =
            assertIs<Proposal.DaySelection>(
                assertIs<Decision.Propose>(decide(ctx, """{"action_refs":["${a["id-read"]}","${a["id-write"]}"],"day":"next"}""")).proposal,
            )
        assertEquals(mapOf("id-read" to LocalTime.of(14, 0)), proposal.pins)
    }

    @Test
    fun `the same selection as the next day's plan changes nothing and is refused`() {
        val nextPlan = plan("p-next", nextDay, item("i-read", "p-next", read, 0))
        val ctx = context(nextPlan = nextPlan)
        val a = refs(ctx)
        val d = assertIs<Decision.Refuse>(decide(ctx, """{"action_refs":["${a["id-read"]}"],"day":"next"}"""))
        assertEquals(RefusalCode.NOTHING_TO_CHANGE, d.code)
    }

    @Test
    fun `an empty next day is a rest day and is refused, and a next day that cannot be named is refused`() {
        assertEquals(RefusalCode.EMPTY_SELECTION, assertIs<Decision.Refuse>(decide(context(), """{"action_refs":[],"day":"next"}""")).code)
        val ctx = context(next = null)
        val a = refs(ctx)
        assertEquals(
            RefusalCode.NO_NEXT_DAY,
            assertIs<Decision.Refuse>(decide(ctx, """{"action_refs":["${a["id-read"]}"],"day":"next"}""")).code,
        )
    }

    @Test
    fun `recorded outcomes lock today's plan but never the next day's`() {
        val ctx = context(outcomes = mapOf("i-write" to PlanOutcome("i-write", OutcomeResult.PARTIAL, 5L)))
        val a = refs(ctx)
        val args = """{"action_refs":["${a["id-read"]}"],"day":"%s"}"""
        assertEquals(RefusalCode.PLAN_LOCKED, assertIs<Decision.Refuse>(decide(ctx, args.format("today"))).code)
        assertIs<Decision.Propose>(decide(ctx, args.format("next")))
    }

    @Test
    fun `without a day the proposal is for today as before`() {
        val ctx = context()
        val a = refs(ctx)
        val proposal =
            assertIs<Proposal.DaySelection>(assertIs<Decision.Propose>(decide(ctx, """{"action_refs":["${a["id-read"]}"]}""")).proposal)
        assertEquals(DayTarget.TODAY, proposal.target)
        assertEquals(today, proposal.day)
        assertEquals(today, proposal.baseDay)
    }
}
