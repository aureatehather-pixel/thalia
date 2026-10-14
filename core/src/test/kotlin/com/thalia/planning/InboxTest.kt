package com.thalia.planning

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR 0012: the item's own rules, the reminder hint, and the schema-5 payload. */
class InboxTest {
    private val a = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1b001"
    private val b = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1b002"
    private val c = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1b003"
    private val actionId = InboxConversion.actionIdFor(c)

    private val info = PlanningExportInfo(5, "0.1-test", Instant.parse("2026-10-07T01:02:03Z"), ZoneId.of("UTC"))

    private val snapshot =
        PlanningSnapshot(
            direction = null,
            settings = null,
            goals = emptyList(),
            projects = emptyList(),
            actions = listOf(Stamped(Action(actionId, "Made from capture"), 5, 6)),
            inboxItems =
                listOf(
                    Stamped(InboxItem(a, "  open, with spaces\nand a line  "), 10, 10),
                    Stamped(InboxItem(b, "a note", InboxState.NOTE, "edited note", null, 30), 11, 40),
                    Stamped(InboxItem(c, "became one", InboxState.ACTION, null, actionId, 50), 12, 50),
                ),
        )

    private fun json(content: PlanningExportContent = PlanningExportContent(info, snapshot)) = String(PlanningExportFormat.encode(content))

    private fun decode(
        text: String,
        current: Int = 5,
    ) = PlanningExportFormat.decode(text.toByteArray(), current)

    private fun refused(
        problem: ImportProblem,
        text: String,
    ) = assertEquals(problem, assertFailsWith<ImportRefusedException>(text) { decode(text) }.problem, text)

    @Test
    fun `an item has the invariants its state implies`() {
        assertFailsWith<IllegalArgumentException> { InboxItem(a, "  ") }
        assertFailsWith<IllegalArgumentException> { InboxItem(a, "x", InboxState.ACTION) }
        assertFailsWith<IllegalArgumentException> { InboxItem(a, "x", InboxState.OPEN, actionId = "act") }
        assertFailsWith<IllegalArgumentException> { InboxItem(a, "x", InboxState.NOTE, null, null, 5) }
        assertFailsWith<IllegalArgumentException> { InboxItem(a, "x", InboxState.ARCHIVED, null, null, null) }
        assertFailsWith<IllegalArgumentException> { InboxItem(a, "x", InboxState.OPEN, null, null, 5) }
        assertTrue(InboxState.DISCARDED.canReopen)
        assertFalse(InboxState.ACTION.canReopen)
        assertFalse(InboxState.OPEN.canReopen)
    }

    @Test
    fun `converting the same item always means the same action id`() {
        assertEquals(InboxConversion.actionIdFor(a), InboxConversion.actionIdFor(a))
        assertTrue(InboxConversion.actionIdFor(a) != InboxConversion.actionIdFor(b))
        assertEquals(
            InboxConversion.actionIdFor(a),
            java.util.UUID
                .fromString(InboxConversion.actionIdFor(a))
                .toString(),
        )
    }

    @Test
    fun `the reminder hint only says may, on obvious wording, and misses are accepted`() {
        listOf("Remind me to call mum", "don't forget the form", "call the dentist at 3pm", "check the oven in 20 minutes", "Set an alarm")
            .forEach { assertTrue(ReminderHint.mayBeReminder(it), it) }
        listOf("Ask about the café menu", "learn to juggle", "The reminder app idea is nice?").let {
            assertFalse(ReminderHint.mayBeReminder(it[0]))
            assertFalse(ReminderHint.mayBeReminder(it[1]))
        }
        assertTrue(ReminderHint.TEXT.startsWith("This may be a reminder"))
        assertTrue("nothing is scheduled" in ReminderHint.TEXT)
    }

    @Test
    fun `a schema 5 payload round-trips every state, exact text and times`() {
        assertEquals(PlanningExportContent(info, snapshot), decode(json()))
        assertEquals(3, snapshot.counts.inboxItems)
    }

    @Test
    fun `a schema 4 payload still restores with an empty Inbox, and cannot carry inbox rows`() {
        val v4 = PlanningExportContent(info.copy(schemaVersion = 4), snapshot.copy(inboxItems = emptyList()))
        val decoded = decode(json(v4))
        assertEquals(emptyList(), decoded.snapshot.inboxItems)
        assertEquals(4, decoded.info.schemaVersion)
        // A schema 4 payload with an inbox key is not a schema 4 payload.
        val smuggled = json(v4).replaceFirst("\"workingDays\":[]", "\"workingDays\":[],\"inboxItems\":[]")
        refused(ImportProblem.INVALID_CONTENT, smuggled)
        // And a schema 5 payload from a newer app is refused by version, as before.
        refused(ImportProblem.NEWER_VERSION, json().replaceFirst("\"schemaVersion\":5", "\"schemaVersion\":6"))
    }

    @Test
    fun `broken inbox rows are refused before anything is written`() {
        refused(ImportProblem.BROKEN_REFERENCE, json().replace("\"actionId\":\"$actionId\"", "\"actionId\":\"$a\""))
        refused(ImportProblem.BROKEN_REFERENCE, json().replace("\"id\":\"$b\"", "\"id\":\"$a\""))
        val twoOneAction =
            snapshot.copy(
                inboxItems = snapshot.inboxItems + Stamped(InboxItem(a, "also", InboxState.ACTION, null, actionId, 60), 13, 60),
            )
        refused(ImportProblem.BROKEN_REFERENCE, json(PlanningExportContent(info, twoOneAction)))
        refused(ImportProblem.INVALID_CONTENT, json().replace("\"state\":\"NOTE\"", "\"state\":\"LATER\""))
        refused(ImportProblem.INVALID_CONTENT, json().replace("\"body\":\"edited note\"", "\"body\":null"))
        refused(ImportProblem.INVALID_CONTENT, json().replace("\"inboxItems\":3", "\"inboxItems\":2"))
    }

    @Test
    fun `an over-long capture is refused on import, never cut`() {
        val long = "x".repeat(InboxText.MAX + 1)
        refused(ImportProblem.INVALID_CONTENT, json().replace("a note", long))
    }
}
