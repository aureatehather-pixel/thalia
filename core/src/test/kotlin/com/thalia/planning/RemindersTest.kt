package com.thalia.planning

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0014: the reminder's invariants and transitions, the time and delivery rules, and the schema-6 payload. */
class RemindersTest {
    private val ids = (1..25).map { "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1c%03d".format(it) }
    private val a = ids[0]
    private val b = ids[1]
    private val target = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1b001"
    private val min = 60_000L
    private val now = 1_000_000_000_000L

    private fun reminder(
        id: String = a,
        status: ReminderStatus = ReminderStatus.SCHEDULED,
        reason: ReminderReason? = null,
        posted: Long? = null,
        resolved: Long? = null,
        type: ReminderTargetType = ReminderTargetType.INBOX_ITEM,
        targetId: String? = if (type == ReminderTargetType.NONE) null else target,
        label: String = "Call the dentist",
    ) = Reminder(id, type, targetId, label, now + 10 * min, "Asia/Kuala_Lumpur", status, reason, posted, resolved, now)

    @Test
    fun `a reminder cannot be built in a shape that contradicts its status`() {
        assertFailsWith<IllegalArgumentException> { reminder(label = " ") }
        assertFailsWith<IllegalArgumentException> { reminder(label = "x".repeat(ReminderRules.LABEL_MAX + 1)) }
        assertFailsWith<IllegalArgumentException> { reminder(type = ReminderTargetType.NONE, targetId = target) }
        assertFailsWith<IllegalArgumentException> { reminder(type = ReminderTargetType.ACTION, targetId = null) }
        assertFailsWith<IllegalArgumentException> { reminder(reason = ReminderReason.QUIET_PERIOD) }
        assertFailsWith<IllegalArgumentException> { reminder(status = ReminderStatus.NOT_POSTED, resolved = 1) }
        assertFailsWith<IllegalArgumentException> { reminder(status = ReminderStatus.POSTED) }
        assertFailsWith<IllegalArgumentException> { reminder(status = ReminderStatus.POSTED, posted = 1, resolved = 2) }
        assertFailsWith<IllegalArgumentException> { reminder(status = ReminderStatus.ACKNOWLEDGED, posted = 1) }
        assertFailsWith<IllegalArgumentException> { reminder(status = ReminderStatus.CANCELLED, posted = 1, resolved = 2) }
        reminder(status = ReminderStatus.NOT_POSTED, reason = ReminderReason.WINDOW_EXPIRED, resolved = 5)
        reminder(status = ReminderStatus.DISMISSED, posted = 3, resolved = 4)
    }

    @Test
    fun `the whole legal life is waiting then posted then acknowledged or dismissed, or waiting then ended`() {
        val waiting = reminder()
        val posted = waiting.transitionTo(ReminderStatus.POSTED, now)
        assertEquals(now, posted.postedAtMillis)
        assertNull(posted.resolvedAtMillis)
        assertEquals(ReminderStatus.ACKNOWLEDGED, posted.transitionTo(ReminderStatus.ACKNOWLEDGED, now + 1).status)
        assertEquals(ReminderStatus.DISMISSED, posted.transitionTo(ReminderStatus.DISMISSED, now + 1).status)
        assertEquals(ReminderStatus.CANCELLED, waiting.transitionTo(ReminderStatus.CANCELLED, now).status)
        val missed = waiting.transitionTo(ReminderStatus.NOT_POSTED, now, ReminderReason.QUIET_PERIOD)
        assertEquals(ReminderReason.QUIET_PERIOD, missed.reason)
        // Nothing skips a step and nothing leaves an end.
        assertFailsWith<IllegalArgumentException> { waiting.transitionTo(ReminderStatus.ACKNOWLEDGED, now) }
        assertFailsWith<IllegalArgumentException> { waiting.transitionTo(ReminderStatus.NOT_POSTED, now) }
        assertFailsWith<IllegalArgumentException> { posted.transitionTo(ReminderStatus.CANCELLED, now) }
        assertFailsWith<IllegalArgumentException> { posted.transitionTo(ReminderStatus.NOT_POSTED, now, ReminderReason.WINDOW_EXPIRED) }
        ReminderStatus.entries.filter { it.isResolved }.forEach { assertTrue(it.legalNext.isEmpty(), it.name) }
        // A posted reminder with no answer is not a resolved one: no state follows from silence.
        assertTrue(ReminderStatus.POSTED.legalNext.all { it.isResolved })
    }

    @Test
    fun `creation is refused too soon, too far, in the quiet period and over the pending limit, in that order`() {
        fun p(
            due: Long,
            pending: Int = 0,
            quiet: Boolean = false,
        ) = ReminderRules.problem(due, now, pending, quiet)
        assertEquals(ReminderProblem.TOO_SOON, p(now + min - 1, quiet = true, pending = 99))
        assertNull(p(now + min))
        assertNull(p(now + ReminderRules.HORIZON_MILLIS))
        assertEquals(ReminderProblem.TOO_FAR, p(now + ReminderRules.HORIZON_MILLIS + 1))
        assertEquals(ReminderProblem.QUIET_PERIOD, p(now + 5 * min, quiet = true, pending = 99))
        assertNull(p(now + 5 * min, pending = ReminderRules.MAX_PENDING - 1))
        assertEquals(ReminderProblem.TOO_MANY, p(now + 5 * min, pending = ReminderRules.MAX_PENDING))
    }

    @Test
    fun `delivery posts inside the window and records only what it observed outside it`() {
        val due = now

        fun d(
            at: Long,
            quiet: Boolean = false,
            canPost: Boolean = true,
        ) = ReminderRules.delivery(due, at, quiet, canPost)
        assertEquals(ReminderDelivery.Post, d(due))
        assertEquals(ReminderDelivery.Post, d(due + ReminderRules.WINDOW_MILLIS))
        assertEquals(ReminderDelivery.Skip(ReminderReason.WINDOW_EXPIRED), d(due + ReminderRules.WINDOW_MILLIS + 1))
        // The window is judged first, so an expired reminder is never reported as blocked or quiet.
        assertEquals(
            ReminderDelivery.Skip(ReminderReason.WINDOW_EXPIRED),
            d(due + ReminderRules.WINDOW_MILLIS + 1, quiet = true, canPost = false),
        )
        assertEquals(ReminderDelivery.Skip(ReminderReason.QUIET_PERIOD), d(due + min, quiet = true, canPost = false))
        assertEquals(ReminderDelivery.Skip(ReminderReason.NOTIFICATIONS_BLOCKED), d(due + min, canPost = false))
    }

    @Test
    fun `a label is the first non-blank line, collapsed and cut, and a blank text is a checkpoint`() {
        assertEquals("Call the dentist about the crown", ReminderRules.labelOf("\n  Call   the dentist\tabout the crown \nsecond"))
        assertEquals(ReminderRules.LABEL_MAX, ReminderRules.labelOf("x".repeat(500)).length)
        assertTrue(ReminderRules.labelOf("x".repeat(500)).endsWith("…"))
        assertEquals(ReminderRules.CHECKPOINT_LABEL, ReminderRules.labelOf("  \n "))
    }

    // Schema 6 payload.

    private val info = PlanningExportInfo(6, "0.1-test", Instant.parse("2026-10-10T01:02:03Z"), ZoneId.of("UTC"))
    private val all =
        listOf(
            reminder(a),
            reminder(b, ReminderStatus.POSTED, posted = now + 1),
            reminder(ids[2], ReminderStatus.ACKNOWLEDGED, posted = now + 1, resolved = now + 2),
            reminder(ids[3], ReminderStatus.DISMISSED, posted = now + 1, resolved = now + 2, type = ReminderTargetType.NONE),
            reminder(ids[4], ReminderStatus.CANCELLED, resolved = now + 2),
            reminder(ids[5], ReminderStatus.NOT_POSTED, ReminderReason.NOTIFICATIONS_BLOCKED, resolved = now + 2),
        )
    private val snapshot = PlanningSnapshot.EMPTY.copy(reminders = all)

    private fun json(content: PlanningExportContent = PlanningExportContent(info, snapshot)) = String(PlanningExportFormat.encode(content))

    private fun decode(
        text: String,
        current: Int = 6,
    ) = PlanningExportFormat.decode(text.toByteArray(), current)

    private fun refused(
        problem: ImportProblem,
        text: String,
    ) = assertEquals(problem, assertFailsWith<ImportRefusedException>(text) { decode(text) }.problem, text)

    @Test
    fun `a schema 6 payload round-trips every status exactly, an unanswered one included`() {
        val decoded = decode(json())
        assertEquals(PlanningExportContent(info, snapshot), decoded)
        assertEquals(6, decoded.snapshot.counts.reminders)
        assertEquals(
            ReminderStatus.POSTED,
            decoded.snapshot.reminders
                .single { it.id == b }
                .status,
        )
        assertNull(
            decoded.snapshot.reminders
                .single { it.id == b }
                .resolvedAtMillis,
        )
    }

    @Test
    fun `schema 5 and earlier payloads still restore with no reminders, and cannot carry reminder rows`() {
        val v5 = PlanningExportContent(info.copy(schemaVersion = 5), snapshot.copy(reminders = emptyList()))
        val decoded = decode(json(v5))
        assertEquals(emptyList(), decoded.snapshot.reminders)
        assertEquals(5, decoded.info.schemaVersion)
        refused(ImportProblem.INVALID_CONTENT, json(v5).replaceFirst("\"inboxItems\":[]", "\"inboxItems\":[],\"reminders\":[]"))
        val v4 = decode(json(PlanningExportContent(info.copy(schemaVersion = 4), snapshot.copy(reminders = emptyList()))))
        assertEquals(4, v4.info.schemaVersion)
        // An export from a newer app is refused by version.
        refused(ImportProblem.NEWER_VERSION, json().replaceFirst("\"schemaVersion\":6", "\"schemaVersion\":7"))
    }

    @Test
    fun `broken reminder rows are refused before anything is written`() {
        refused(ImportProblem.BROKEN_REFERENCE, json().replace("\"id\":\"$b\"", "\"id\":\"$a\""))
        refused(ImportProblem.INVALID_CONTENT, json().replace("\"status\":\"POSTED\"", "\"status\":\"SHOWN\""))
        refused(ImportProblem.INVALID_CONTENT, json().replace("\"status\":\"POSTED\"", "\"status\":\"ACKNOWLEDGED\""))
        refused(ImportProblem.INVALID_CONTENT, json().replace("\"reason\":\"NOTIFICATIONS_BLOCKED\"", "\"reason\":null"))
        refused(ImportProblem.INVALID_CONTENT, json().replaceFirst("\"zone\":\"Asia/Kuala_Lumpur\"", "\"zone\":\"Mars/Base\""))
        refused(ImportProblem.INVALID_CONTENT, json().replace("\"reminders\":6", "\"reminders\":5"))
        val tooMany = (0 until ReminderRules.MAX_PENDING + 1).map { reminder(ids[it]) }
        refused(ImportProblem.INVALID_CONTENT, json(PlanningExportContent(info, PlanningSnapshot.EMPTY.copy(reminders = tooMany))))
        // The cap is on waiting reminders: twenty are fine, and resolved ones do not count.
        val twenty =
            (0 until ReminderRules.MAX_PENDING).map { reminder(ids[it]) } + reminder(ids[20], ReminderStatus.CANCELLED, resolved = 9)
        assertEquals(21, decode(json(PlanningExportContent(info, PlanningSnapshot.EMPTY.copy(reminders = twenty)))).snapshot.reminders.size)
    }

    @Test
    fun `a reminder whose target is not in the export is accepted, because a reminder outlives its target`() {
        assertEquals(6, decode(json()).snapshot.reminders.size)
        assertTrue(decode(json()).snapshot.actions.isEmpty() && decode(json()).snapshot.inboxItems.isEmpty())
    }
}
