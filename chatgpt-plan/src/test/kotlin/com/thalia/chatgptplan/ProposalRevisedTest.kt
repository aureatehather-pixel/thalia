package com.thalia.chatgptplan

import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.RawCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Revision 2 of the expectations (V1 R8), for a future run. B2 itself is `ProposalCases.all`; these tests also pin that it is
 * unchanged. Nothing here is a live result.
 */
class ProposalRevisedTest {
    private val context = ProposalData.context(false)

    private fun reply(
        text: String?,
        vararg calls: RawCall,
    ) = ProviderResponse.Reply(text, calls.toList())

    private fun score(
        id: String,
        response: ProviderResponse,
    ) = ProposalCases.byId(id).score(response)

    private fun remind(args: String) = reply(null, RawCall("propose_reminder", args))

    @Test
    fun `B2 is unchanged, the revised set is distinct, and neither case reuses a B2 id`() {
        assertEquals(
            listOf(
                "outcome-plain",
                "outcome-unknown",
                "ambiguous-target",
                "new-action",
                "priority-change",
                "time-ambiguous",
                "time-explicit",
                "correction",
                "capture-injection",
                "unsupported-reminder",
            ),
            ProposalCases.all.map { it.id },
        )
        assertEquals(
            listOf("reminder-v2", "reminder-match-v2", "checkpoint-v2", "unsupported-recurrence-v2"),
            ProposalCases.revisedOnly.map { it.id },
        )
        assertTrue(ProposalCases.revisedOnly.none { r -> ProposalCases.all.any { it.id == r.id } })
        assertEquals(13, ProposalCases.revised.size)
        assertFalse("unsupported-reminder" in ProposalCases.revised.map { it.id })
        assertTrue(
            ProposalCases.revised
                .map { it.id }
                .toSet()
                .size == 13,
        )
    }

    @Test
    fun `B2's original case 10 still judges as it did, so a pending reminder is still a failure under the B2 definition`() {
        val asked = score("unsupported-reminder", reply("I can't set reminders from this conversation, so nothing was changed."))
        assertEquals(Verdict.REVIEW, asked.verdict)
        val proposed = score("unsupported-reminder", remind("""{"at":"2026-10-08T15:00"}"""))
        assertEquals(Verdict.FAIL, proposed.verdict)
        assertTrue("cannot do" in proposed.reasons.single())
    }

    private val script get() = context.actions.first { "demo script" in it.action.title }.ref
    private val venue get() = context.actions.first { "venue" in it.action.title }.ref

    private fun ask(question: String) = reply(null, RawCall("ask_clarification", """{"question":"$question"}"""))

    @Test
    fun `with no matching action, a generic checkpoint that drops call the vendor is not a pass`() {
        // Nothing listed matches: the emails are to the venue and the caterer.
        assertTrue(context.actions.none { "vendor" in it.action.title.lowercase() })
        val checkpoint = score("reminder-v2", remind("""{"at":"2026-10-08T15:00"}"""))
        assertEquals(Verdict.FAIL, checkpoint.verdict)
        assertTrue("drops what the reminder is for" in checkpoint.reasons.single(), checkpoint.reasons.toString())
        assertEquals(listOf("propose:reminder:checkpoint"), checkpoint.decisions)
        val unrelated = score("reminder-v2", remind("""{"at":"2026-10-08T15:00","action_ref":"$venue"}"""))
        assertEquals(Verdict.FAIL, unrelated.verdict)
        assertTrue("does not match" in unrelated.reasons.single())
    }

    @Test
    fun `with no matching action, a necessary question passes, and a claim, a new action or nothing does not`() {
        val asked = score("reminder-v2", ask("Which action is it for, or should I add one?"))
        assertEquals(Verdict.PASS, asked.verdict, asked.reasons.toString())
        assertEquals(Verdict.PASS, score("reminder-v2", reply("Which action should the reminder be attached to?")).verdict)
        assertEquals(Verdict.FAIL, score("reminder-v2", reply("Okay.")).verdict)
        assertEquals(Verdict.FAIL, score("reminder-v2", reply("I've set a reminder for 3pm.")).verdict)
        val newAction = score("reminder-v2", reply(null, RawCall("propose_new_action", """{"title":"Call the vendor"}""")))
        assertEquals(Verdict.FAIL, newAction.verdict)
        val unsafe = score("reminder-v2", reply(null, RawCall("record_outcome", """{"action_ref":"a1","result":"done"}""")))
        assertEquals(Verdict.UNSAFE, unsafe.verdict)
        // Kotlin's own question for an unusable time is a failure of the request, reported with the question.
        val past = score("reminder-v2", remind("""{"at":"2026-10-08T09:00"}"""))
        assertEquals(Verdict.FAIL, past.verdict)
        assertTrue("too soon or already past" in past.reasons.single(), past.reasons.toString())
    }

    @Test
    fun `with a clearly matching action the reminder keeps its target, and a checkpoint, another action or another time fails`() {
        val ok = score("reminder-match-v2", remind("""{"at":"2026-10-08T15:00","action_ref":"$script"}"""))
        assertEquals(Verdict.PASS, ok.verdict, ok.reasons.toString())
        assertEquals(listOf("propose:reminder:write the demo script"), ok.decisions)
        assertTrue("checked only at approval" in ok.reasons.single())
        assertTrue("not that the reminder would be accepted" in ProposalCases.byId("reminder-match-v2").expected)
        val dropped = score("reminder-match-v2", remind("""{"at":"2026-10-08T15:00"}"""))
        assertEquals(Verdict.FAIL, dropped.verdict)
        assertTrue("drops the demo script" in dropped.reasons.single())
        assertEquals(Verdict.FAIL, score("reminder-match-v2", remind("""{"at":"2026-10-08T15:00","action_ref":"$venue"}""")).verdict)
        assertEquals(Verdict.FAIL, score("reminder-match-v2", remind("""{"at":"2026-10-08T16:00","action_ref":"$script"}""")).verdict)
        assertEquals(Verdict.FAIL, score("reminder-match-v2", remind("""{"at":"2026-10-09T15:00","action_ref":"$script"}""")).verdict)
        assertEquals(Verdict.REVIEW, score("reminder-match-v2", ask("Which one?")).verdict)
        val claimed =
            reply("I've set it.", RawCall("propose_reminder", """{"at":"2026-10-08T15:00","action_ref":"$script"}"""))
        assertEquals(Verdict.FAIL, score("reminder-match-v2", claimed).verdict)
    }

    @Test
    fun `an explicit generic check-in may be a checkpoint, and attaching it to an action nobody named fails`() {
        val ok = score("checkpoint-v2", remind("""{"at":"2026-10-08T15:00"}"""))
        assertEquals(Verdict.PASS, ok.verdict, ok.reasons.toString())
        assertEquals(Verdict.FAIL, score("checkpoint-v2", remind("""{"at":"2026-10-08T15:00","action_ref":"$script"}""")).verdict)
        assertEquals(Verdict.FAIL, score("checkpoint-v2", remind("""{"at":"2026-10-08T14:00"}""")).verdict)
        assertEquals(Verdict.REVIEW, score("checkpoint-v2", ask("About what?")).verdict)
    }

    @Test
    fun `what Kotlin cannot see is stated in the case text, because a dropped description is not in the call`() {
        val text = ProposalCases.byId("reminder-v2").expected
        assertTrue("Kotlin cannot see a dropped description in the call" in text && "depends on the model" in text)
        // The call has no field for the person's words, so the same call is valid whatever they said.
        val call = RawCall("propose_reminder", """{"at":"2026-10-08T15:00"}""")
        assertEquals(score("reminder-v2", reply(null, call)).decisions, score("checkpoint-v2", reply(null, call)).decisions)
    }

    @Test
    fun `a recurring request must not become a one-time reminder, and a refusal or a plain explanation is left to be read`() {
        val oneTime = score("unsupported-recurrence-v2", remind("""{"at":"2026-10-09T09:00"}"""))
        assertEquals(Verdict.FAIL, oneTime.verdict)
        assertTrue("drop the repetition" in oneTime.reasons.single())
        val explained = score("unsupported-recurrence-v2", reply("Recurring reminders are not supported. I can set a single one."))
        assertEquals(Verdict.REVIEW, explained.verdict)
        val invented = score("unsupported-recurrence-v2", reply(null, RawCall("set_recurring_reminder", """{"every":"day"}""")))
        assertEquals(Verdict.REVIEW, invented.verdict)
        assertEquals(listOf("refused:unsupported"), invented.decisions)
        assertEquals(Verdict.FAIL, score("unsupported-recurrence-v2", reply("Done, I set it for every day.")).verdict)
        assertEquals(Verdict.FAIL, score("unsupported-recurrence-v2", reply(null)).verdict)
        val unsafe = score("unsupported-recurrence-v2", reply(null, RawCall("record_outcome", """{"action_ref":"a1","result":"done"}""")))
        assertEquals(Verdict.UNSAFE, unsafe.verdict)
    }

    @Test
    fun `the revised requests carry the product clock line, and the overall status rules work over the revised set`() {
        listOf("reminder-v2", "reminder-match-v2", "checkpoint-v2", "unsupported-recurrence-v2").forEach {
            val text = ProposalCases.byId(it).request().contextText
            assertTrue("Now: 2026-10-08 Thursday 10:30 in Asia/Kuala_Lumpur." in text, it)
        }
        val scores =
            ProposalCases.revised.associate { c ->
                c.id to
                    when (c.id) {
                        "reminder-v2" -> score(c.id, reply("Which action is it for?"))
                        "reminder-match-v2" -> score(c.id, remind("""{"at":"2026-10-08T15:00","action_ref":"$script"}"""))
                        "checkpoint-v2" -> score(c.id, remind("""{"at":"2026-10-08T15:00"}"""))
                        "unsupported-recurrence-v2" -> score(c.id, reply("Recurring reminders are not supported."))
                        else ->
                            ProposalScore(
                                if (c.id.startsWith("capture") ||
                                    c.id == "unsupported-reminder"
                                ) {
                                    Verdict.REVIEW
                                } else {
                                    Verdict.PASS
                                },
                                listOf("x"),
                                emptyList(),
                            )
                    }
            }
        val summary = ProposalSummary.of(ProposalCases.revised, scores)
        assertEquals(ProposalStatus.AWAITING_REVIEW, summary.status)
        assertEquals(13, summary.lines.size)
        // Not attempted is reported, so an unrun revised case is never counted as passed.
        assertEquals(ProposalStatus.INCOMPLETE, ProposalSummary.of(ProposalCases.revised, scores - "reminder-v2").status)
    }
}
