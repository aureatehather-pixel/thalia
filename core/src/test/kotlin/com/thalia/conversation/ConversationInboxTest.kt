package com.thalia.conversation

import com.thalia.planning.ActiveDay
import com.thalia.planning.InboxConversion
import com.thalia.planning.InboxItem
import com.thalia.planning.InboxState
import com.thalia.planning.InboxText
import com.thalia.planning.PlanningSettings
import com.thalia.planning.Stamped
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** ADR 0012 at the operation boundary: what a conversation may do with the Inbox, and what it may not. */
class ConversationInboxTest {
    private val day = ActiveDay(LocalDate.of(2026, 10, 7))
    private val first = InboxItem("item-1", "Ask about the café menu")
    private val second = InboxItem("item-2", "Remind me to call the dentist at 3pm")
    private val filed = InboxItem("item-3", "already a note", InboxState.NOTE, "already a note", null, 9)

    private fun context(vararg items: Stamped<InboxItem>) =
        ConversationContext.build(
            day,
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            null,
            emptyMap(),
            PlanningSettings(),
            inboxItems = items.toList(),
        )

    private val ctx =
        context(
            Stamped(second, 20, 20),
            Stamped(first, 10, 10),
            Stamped(filed, 5, 9),
        )

    private fun decide(vararg calls: RawCall) = OperationValidator.validateAll(ctx, calls.toList()) { "new-id" }

    private fun call(
        name: String,
        args: String,
    ) = RawCall(name, args)

    @Test
    fun `the snapshot lists only open captures, oldest first, as not actions and in no plan`() {
        assertEquals(listOf("i1", "i2"), ctx.inbox.map { it.ref })
        assertEquals(listOf(first, second), ctx.inbox.map { it.item })
        val text = ctx.render()
        assertTrue("i1 \"Ask about the café menu\"" in text)
        assertTrue("captures, not actions or commitments, and none is in any plan" in text)
        assertTrue("already a note" !in text)
        assertTrue("item-1" !in text)
    }

    @Test
    fun `a long capture is cut only in what is shown, never in what is stored`() {
        val long = InboxItem("item-9", "line one\nline two " + "y".repeat(ContextLimits.INBOX_CHARS))
        val shown = context(Stamped(long, 1, 1)).inbox.single()
        assertTrue(shown.cut)
        assertTrue('\n' !in shown.shown)
        assertEquals(long.text, shown.item.text)
        val text = context(Stamped(long, 1, 1)).render()
        assertTrue("CUT: you see only the first ${ContextLimits.INBOX_CHARS} characters" in text)
        assertTrue("1 of these captures are cut" in text)
    }

    @Test
    fun `captures are private unless the person chose them, and the snapshot says so`() {
        val ordinary =
            ConversationContext.build(day, null, emptyList(), emptyList(), emptyList(), null, emptyMap(), PlanningSettings())
        assertEquals(false, ordinary.inboxShared)
        assertEquals(emptyList(), ordinary.inbox)
        val text = ordinary.render()
        assertTrue("captures are private and are not shared" in text)
        assertTrue("café" !in text)
        // Chosen but gone: shared, and honest that nothing is left.
        val gone = context()
        assertTrue(gone.inboxShared)
        assertTrue("none of them is still open" in gone.render())
    }

    @Test
    fun `the Inbox in a request is limited and says how many were left out`() {
        val many = (1..ContextLimits.INBOX + 3).map { Stamped(InboxItem("m$it", "capture $it"), it.toLong(), it.toLong()) }
        val limited = context(*many.toTypedArray())
        assertEquals(ContextLimits.INBOX, limited.inbox.size)
        assertEquals(3, limited.inboxNotShown)
        assertEquals(
            "capture 1",
            limited.inbox
                .first()
                .item.text,
        )
    }

    @Test
    fun `a plainly stated filing is a fact for exactly that item, whatever the word it was said in`() {
        val decision = decide(call(OperationParser.RESOLVE_INBOX_ITEM, """{"item_ref":"i1","outcome":"Note"}""")).single()
        assertIs<Decision.InboxFact>(decision)
        assertEquals(first, decision.item)
        assertEquals(InboxState.NOTE, decision.to)
        listOf("idea" to InboxState.IDEA, "archive" to InboxState.ARCHIVED, "discard" to InboxState.DISCARDED).forEach { (word, state) ->
            val d = decide(call(OperationParser.RESOLVE_INBOX_ITEM, """{"item_ref":"i2","outcome":"$word"}""")).single()
            assertEquals(state, (d as Decision.InboxFact).to)
        }
    }

    @Test
    fun `a filing cannot carry rewritten content, name an unseen item, or use another outcome`() {
        val withBody =
            decide(
                call(OperationParser.RESOLVE_INBOX_ITEM, """{"item_ref":"i1","outcome":"note","body":"my rewrite"}"""),
            ).single()
        assertIs<Decision.Refuse>(withBody)
        assertIs<Decision.Refuse>(decide(call(OperationParser.RESOLVE_INBOX_ITEM, """{"item_ref":"i9","outcome":"note"}""")).single())
        assertIs<Decision.Refuse>(decide(call(OperationParser.RESOLVE_INBOX_ITEM, """{"item_ref":"i1","outcome":"action"}""")).single())
        assertIs<Decision.Refuse>(decide(call(OperationParser.RESOLVE_INBOX_ITEM, """{"item_ref":"item-3","outcome":"note"}""")).single())
        assertIs<Decision.Refuse>(decide(call(OperationParser.RESOLVE_INBOX_ITEM, """{"item_ref":"i1"}""")).single())
    }

    @Test
    fun `filing one item twice in a reply applies once`() {
        val decisions =
            decide(
                call(OperationParser.RESOLVE_INBOX_ITEM, """{"item_ref":"i1","outcome":"note"}"""),
                call(OperationParser.RESOLVE_INBOX_ITEM, """{"item_ref":"i1","outcome":"archive"}"""),
            )
        assertIs<Decision.InboxFact>(decisions[0])
        assertEquals(RefusalCode.DUPLICATE_IN_REPLY, (decisions[1] as Decision.Refuse).code)
    }

    @Test
    fun `a model's own choice or a rewrite always waits for approval`() {
        val archive =
            decide(
                call(OperationParser.PROPOSE_INBOX_RESOLUTION, """{"item_ref":"i1","outcome":"archive","reason":"stale"}"""),
            ).single()
        val proposal = (archive as Decision.Propose).proposal
        assertIs<Proposal.InboxResolution>(proposal)
        assertEquals(InboxState.ARCHIVED, proposal.to)
        assertEquals(null, proposal.body)

        val rewritten =
            decide(
                call(OperationParser.PROPOSE_INBOX_RESOLUTION, """{"item_ref":"i1","outcome":"idea","body":"A tidier idea"}"""),
            ).single()
        assertEquals("A tidier idea", ((rewritten as Decision.Propose).proposal as Proposal.InboxResolution).body)
    }

    @Test
    fun `a proposal's body is for a note or an idea only, and within the capture limit`() {
        assertIs<Decision.Refuse>(
            decide(call(OperationParser.PROPOSE_INBOX_RESOLUTION, """{"item_ref":"i1","outcome":"archive","body":"x"}""")).single(),
        )
        val tooLong = "x".repeat(InboxText.MAX + 1)
        assertIs<Decision.Refuse>(
            decide(call(OperationParser.PROPOSE_INBOX_RESOLUTION, """{"item_ref":"i1","outcome":"note","body":"$tooLong"}""")).single(),
        )
        assertIs<Decision.Refuse>(
            decide(call(OperationParser.PROPOSE_INBOX_RESOLUTION, """{"item_ref":"i7","outcome":"note"}""")).single(),
        )
    }

    @Test
    fun `an action from a capture is a proposal with the item's fixed action id, and is never planned`() {
        val decision = decide(call(OperationParser.PROPOSE_NEW_ACTION, """{"title":"Ask about the menu","inbox_ref":"i1"}""")).single()
        val proposal = (decision as Decision.Propose).proposal
        assertIs<Proposal.NewAction>(proposal)
        assertEquals("item-1", proposal.inboxItemId)
        assertEquals(InboxConversion.actionIdFor("item-1"), proposal.action.id)
        assertEquals(emptySet(), ctx.todaySelection)
        assertIs<Decision.Refuse>(decide(call(OperationParser.PROPOSE_NEW_ACTION, """{"title":"x","inbox_ref":"i9"}""")).single())
    }

    @Test
    fun `an ambiguous capture is left alone, and a question changes nothing`() {
        val decision =
            decide(
                call(OperationParser.ASK_CLARIFICATION, """{"question":"Is the dentist item a reminder or an action?"}"""),
            ).single()
        assertIs<Decision.Ask>(decision)
    }

    @Test
    fun `the declarations offer exactly the supported names, and only propose_reminder (Slice 5, behind approval) can schedule`() {
        val declared =
            Json
                .parseToJsonElement(
                    ConversationTools.DECLARATIONS_JSON,
                ).jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }
                .toSet()
        assertEquals(OperationParser.NAMES, declared)
        assertTrue(OperationParser.RESOLVE_INBOX_ITEM in declared && OperationParser.PROPOSE_INBOX_RESOLUTION in declared)
        // Since R8 one declared tool proposes a one-time reminder. It only proposes: nothing here arms an alarm by itself.
        assertEquals(
            setOf(OperationParser.PROPOSE_REMINDER),
            declared.filter { "remind" in it || "alarm" in it || "schedule" in it }.toSet(),
        )
        val tools = ConversationTools.DECLARATIONS_JSON
        assertTrue("\"inbox_ref\"" in tools)
    }
}
