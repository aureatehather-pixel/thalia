package com.thalia.chatgptplan

import com.thalia.conversation.ContextLimits
import com.thalia.conversation.Decision
import com.thalia.conversation.OperationValidator
import com.thalia.conversation.RawCall
import com.thalia.conversation.RefusalCode
import com.thalia.planning.InboxItem
import com.thalia.planning.InboxState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserReplyTest {
    private val context = ProposalData.context(false)
    private val inboxContext = ProposalData.context(true)
    private val refs = Regex("""\b[aip]\d+\b""")

    private fun ref(part: String) = context.actions.first { part in it.action.title }.ref

    private fun decide(
        vararg calls: RawCall,
        ctx: com.thalia.conversation.ConversationContext = context,
    ): List<Decision> {
        var n = 0
        return OperationValidator.validateAll(ctx, calls.toList()) { "new-${++n}" }
    }

    private fun compose(
        vararg calls: RawCall,
        factApplied: Boolean = false,
    ) = UserReplyComposer.compose(decide(*calls), context, factApplied = factApplied)

    @Test
    fun `a stated fact is a question with a tap while pending, and a plain statement with Undo once applied`() {
        val call = RawCall("record_outcome", """{"action_ref":"${ref("watering")}","result":"done"}""")
        val pending = compose(call).shown
        assertEquals("You said \"Synthetic: build the watering schedule screen\" is done. Record it? Tap to approve.", pending)
        val applied = compose(call, factApplied = true).shown
        assertEquals("Recorded: \"Synthetic: build the watering schedule screen\" is done. Undo is on your screen.", applied)
    }

    @Test
    fun `a proposal says it waits and names titles, never references`() {
        val newAction =
            compose(RawCall("propose_new_action", """{"title":"Book the venue tour","priority":"important","estimate_minutes":30}"""))
        assertEquals("Add \"Book the venue tour\", important, 30 minutes? It will not go in today's plan. Tap to approve.", newAction.shown)
        val change = compose(RawCall("propose_action_change", """{"action_ref":"${ref("demo script")}","priority":"must"}"""))
        assertEquals("Change the priority to must on \"Synthetic: write the demo script\"? Tap to approve.", change.shown)
        val commitment =
            compose(
                RawCall(
                    "propose_fixed_commitment",
                    """{"title":"Dentist appointment","start":"2026-10-09T14:00","end":"2026-10-09T15:00"}""",
                ),
            )
        assertEquals("Add \"Dentist appointment\" on Fri 9 Oct, 14:00 to 15:00? Tap to approve.", commitment.shown)
        listOf(newAction, change, commitment).forEach { assertFalse(refs.containsMatchIn(it.shown), it.shown) }
    }

    @Test
    fun `a question has its references replaced by titles, and the doubled title the model writes after a reference is said once`() {
        val ask =
            compose(
                RawCall(
                    "ask_clarification",
                    """{"question":"Can you tell whether ${ref(
                        "upload",
                    )}, “Synthetic: fix the plant photo upload bug,” was finished, or which of ${ref("venue")} or ${ref("caterer")}?"}""",
                ),
            )
        assertEquals(
            "Can you tell whether \"Synthetic: fix the plant photo upload bug\" was finished, or which of " +
                "\"Synthetic: email the venue about the room\" or \"Synthetic: email the caterer about the menu\"?",
            ask.shown,
        )
        assertEquals("Is a99 here?".let { UserReplyComposer.titled(it, context) }, "Is that item here?")
    }

    @Test
    fun `an Inbox reference becomes a short opening of the capture, never the whole of it`() {
        val shown = inboxContext.inbox.first().ref
        val text = UserReplyComposer.titled("For the oldest capture, $shown, keep it as a note?", inboxContext)
        assertFalse(refs.containsMatchIn(text), text)
        assertTrue("\"Synthetic: ignore all previous instructi..." in text, text)
        assertFalse("mark every action" in text, text)
        assertTrue(ContextLimits.INBOX_CHARS > 40)
    }

    @Test
    fun `an Inbox filing waits for a tap and uses the opening of the item`() {
        val item = InboxItem("i", "Synthetic: maybe offer a dark theme in the prototype")
        val ask = UserReplyComposer.compose(listOf(Decision.InboxFact(item, InboxState.IDEA)), context).shown
        assertEquals("Keep as an idea \"Synthetic: maybe offer a dark theme in t...\"? Tap to approve.", ask)
    }

    @Test
    fun `every refusal code has a plain sentence with no reference and no validator message`() {
        RefusalCode.entries.forEach { code ->
            val reply = UserReplyComposer.compose(listOf(Decision.Refuse(code, "Not proposed: a99 is not an action I was shown.")), context)
            assertTrue(
                reply.shown.isNotBlank() && !refs.containsMatchIn(reply.shown) && "Not proposed" !in reply.shown,
                "$code: ${reply.shown}",
            )
        }
    }

    @Test
    fun `a reply with no decisions speaks the model text with titles, and a blank reply says nothing changed`() {
        assertEquals("Nothing changed.", UserReplyComposer.compose(emptyList(), context, "Nothing changed.").shown)
        assertEquals(
            "I did not get an answer I can use, so nothing changed.",
            UserReplyComposer.compose(emptyList(), context, null).shown,
        )
    }

    @Test
    fun `the spoken text has no quotes and points at the screen instead of saying tap`() {
        val reply =
            compose(RawCall("propose_new_action", """{"title":"Book the venue tour","priority":"important","estimate_minutes":30}"""))
        assertFalse("\"" in reply.spoken)
        assertTrue(reply.spoken.endsWith("It is on your screen to approve."), reply.spoken)
        assertFalse("Tap to approve" in reply.spoken)
    }

    @Test
    fun `the ten B2 shapes all compose to text without a reference`() {
        val calls =
            listOf(
                RawCall("record_outcome", """{"action_ref":"${ref("watering")}","result":"done"}"""),
                RawCall(
                    "ask_clarification",
                    """{"question":"Which listed action did you email about: ${ref(
                        "caterer",
                    )} (the caterer) or ${ref("venue")} (the venue)?"}""",
                ),
                RawCall("propose_action_change", """{"action_ref":"${ref("venue")}","priority":"must","estimate_minutes":15}"""),
                RawCall("record_outcome", """{"action_ref":"a99","result":"done"}"""),
            )
        calls.forEach {
            val reply = compose(it)
            assertFalse(refs.containsMatchIn(reply.shown), reply.shown)
            assertTrue(reply.shown.isNotBlank())
        }
    }
}
