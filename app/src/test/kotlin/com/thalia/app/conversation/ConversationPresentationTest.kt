package com.thalia.app.conversation

import com.thalia.conversation.Proposal
import com.thalia.planning.Action
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the conversation shows, as data: the rows, the pending card, the saved card and the next steps. No device is involved. */
class ConversationPresentationTest {
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private val due = Instant.parse("2026-10-07T07:00:00Z").toEpochMilli()

    private fun reminder(title: String?) = Proposal.Reminder("r1", title?.let { "act" }, title, due, kl, "a reason")

    @Test
    fun `a plain reply and its no-changes status are one row, Kotlin's status above the model's words, labelled Thalia`() {
        val rows =
            Presentation.rows(
                listOf(
                    Entry.User(1, "What matters most?"),
                    Entry.Assistant(2, "${ReplyText.AI_RESPONSE}Start with the script. Then read."),
                    Entry.Note(3, ReplyText.NO_CHANGES),
                ),
            )
        assertEquals(listOf(RowStyle.YOU, RowStyle.REPLY), rows.map { it.style })
        val reply = rows[1]
        assertEquals("Thalia", reply.label)
        assertEquals(ReplyText.NO_CHANGES, reply.status)
        assertEquals(listOf("Start with the script.", "Then read."), reply.lines)
        assertEquals(listOf(2, 3), reply.entryIds)
    }

    @Test
    fun `an explanation beside a decision is labelled Thalia's explanation and is a different style from any status`() {
        val row = Presentation.row(Entry.Assistant(1, "${ReplyText.AI_EXPLANATION}You said after lunch."))
        assertEquals(RowStyle.EXPLANATION, row.style)
        assertEquals("Thalia's explanation", row.label)
        assertEquals(listOf("You said after lunch."), row.lines)
        assertNull(row.status)
        // A model reason inside the proposal note is split off and carried separately, after Kotlin's line.
        val proposed =
            Presentation.row(
                Entry.Note(
                    2,
                    "Proposed, not applied yet. Add \"Book a room\", normal, 30 minutes? ${ReplyText.AI_EXPLANATION}Needed for the talk",
                ),
            )
        assertEquals(RowStyle.PROPOSED, proposed.style)
        assertEquals("Proposed, not applied", proposed.label)
        assertEquals("Needed for the talk", proposed.explanation)
        assertFalse(proposed.lines.joinToString().contains("Needed for the talk"))
    }

    @Test
    fun `a saved reminder is a card with its target, date and time and no zone, with Undo that names what it undoes`() {
        val facts = listOf("About" to "Read paper", "Date" to "Wed 7 Oct", "Time" to "15:00")
        val row =
            Presentation.row(
                Entry.Saved(
                    5,
                    "Reminder set for Wed 7 Oct, 15:00. Thalia will post one quiet notification, then wait.",
                    Undo.Reminder("r1", "Wed 7 Oct, 15:00"),
                    facts,
                ),
            )
        assertEquals(RowStyle.SAVED, row.style)
        assertEquals("Reminder set", row.title)
        assertEquals(facts, row.facts)
        assertTrue(row.lines.isEmpty())
        assertEquals(5, row.undoEntryId)
        assertEquals("Undo: cancel this reminder", row.undoDescription)
        assertTrue(listOf(row.spoken.orEmpty(), row.facts.toString()).none { "UTC" in it || "Kuala" in it })
        // A saved line that was already undone has no Undo.
        assertNull(
            Presentation
                .row(
                    Entry.Saved(6, "Undone: the reminder for Wed 7 Oct, 15:00 was cancelled. Nothing will be posted."),
                ).undoEntryId,
        )
    }

    @Test
    fun `the pending reminder card shows the target, date and time, and never the zone, though it is placed in that zone`() {
        val card = Presentation.pendingCard(Pending(reminder("Read paper"), "unused", "Set reminder"))
        assertEquals("One-time reminder", card.headline)
        assertEquals("Read paper", card.about?.value)
        assertEquals(listOf("Date" to "Wed 7 Oct", "Time" to "15:00"), card.when_.map { it.label to it.value })
        // 07:00 UTC is 15:00 in Kuala Lumpur: the time is shown where the reminder is placed, with no zone named.
        assertTrue(
            listOf(card.spoken, card.headline, card.about.toString(), card.when_.toString(), card.lines.toString()).none {
                "UTC" in
                    it ||
                    "Kuala" in it ||
                    "zone" in it.lowercase()
            },
        )
        assertEquals("Set reminder", card.approveLabel)
        assertTrue("Waiting for your approval" in card.spoken && "15:00" in card.spoken)
        // The proposal still holds the zone it is scheduled in.
        assertEquals(kl, (reminder("Read paper")).zone)
    }

    @Test
    fun `a generic checkpoint says plainly that it carries no description`() {
        val card = Presentation.pendingCard(Pending(reminder(null), "unused", "Set reminder"))
        assertEquals("Generic check-in", card.about?.value)
        assertEquals("It carries no description of what it is for.", card.about?.detail)
    }

    @Test
    fun `any other proposal is a readable card from its summary, one sentence per line, without the proof prefix`() {
        val proposal = Proposal.NewAction(Action("a", "Synthetic: Book a room"), null, null)
        val card =
            Presentation.pendingCard(
                Pending(
                    proposal,
                    "Add the action \"Synthetic: Book a room\", normal, about 30 min. It will not be added to today's plan.",
                    "Add action",
                ),
            )
        assertEquals("Add the action \"Book a room\", normal, about 30 min.", card.headline)
        assertEquals(listOf("It will not be added to today's plan."), card.lines)
        assertEquals("Add action", card.approveLabel)
    }

    @Test
    fun `a refusal says why and gives one useful next step, from fixed phrases`() {
        fun step(text: String) = Presentation.row(Entry.NotApplied(1, text))
        assertEquals(
            NextStep.AI_SETTINGS,
            step("AI is off. Nothing was sent. Turn it on in AI settings, or carry on by hand: planning works as usual.").nextStep,
        )
        assertEquals(
            "AI settings",
            step("AI has no key saved. Nothing was sent. Add one in AI settings, or carry on by hand.").nextStep?.label,
        )
        assertEquals(NextStep.APPROVE_AGAIN, step("Not added: storage failed and nothing changed. You can approve again.").nextStep)
        assertEquals(
            NextStep.PLANNING,
            step("Could not undo: that reminder was no longer waiting, so nothing was cancelled. Check Reminders.").nextStep,
        )
        val generic = step("Not applied: that is not an action I was shown.")
        assertEquals(NextStep.REPHRASE, generic.nextStep)
        assertTrue("Nothing was applied" in generic.nextStepHint.orEmpty())
        assertNull(generic.nextStep?.label)
        assertTrue(
            "still waiting" in
                step("Not cancelled: storage failed and the reminder is still waiting. You can press Undo again.").lines.joinToString(),
        )
    }

    @Test
    fun `a dismissal, a day-screen redirect and a status note keep their own styles`() {
        assertEquals(RowStyle.NOTE, Presentation.row(Entry.Note(1, "Dismissed. Nothing was applied.")).style)
        assertEquals("Dismissed", Presentation.row(Entry.Note(1, "Dismissed. Nothing was applied.")).label)
        assertEquals(RowStyle.DAY_SCREEN, Presentation.row(Entry.Redirect(2, "Open and close the day on the day screen.")).style)
        assertEquals(RowStyle.STATUS, Presentation.row(Entry.Note(3, ReplyText.NO_CHANGES)).style)
    }

    @Test
    fun `the spoken-reply controls are not part of any row, so they exist only as the visibly unbuilt placeholder`() {
        val rows = Presentation.rows(listOf(Entry.Assistant(1, "${ReplyText.AI_RESPONSE}Hi."), Entry.Note(2, ReplyText.NO_CHANGES)))
        assertTrue(rows.none { it.nextStep != null || it.undoEntryId != null })
        assertEquals("Check the draft, edit it if needed, then press Send.", VOICE_DRAFT_READY_NOTE)
    }
}
