package com.thalia.app.planning

import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Overdue words, the date picker's day arithmetic, the Inbox header, and where the Inbox now lives. */
class DueTextTest {
    private val today = LocalDate.of(2026, 10, 13)

    private fun action(
        due: LocalDate?,
        status: ActionStatus = ActionStatus.OPEN,
    ) = Action("a1", "Pay invoice", dueDate = due, status = status)

    @Test
    fun `an open action before today is overdue in words, by days`() {
        assertEquals("Overdue by 1 day", DueText.overdue(today.minusDays(1), ActionStatus.OPEN, today))
        assertEquals("Overdue by 12 days", DueText.overdue(today.minusDays(12), ActionStatus.OPEN, today))
        assertEquals("due 2026-10-12 · Overdue by 1 day", DueText.line(action(today.minusDays(1)), today))
    }

    @Test
    fun `today, later dates, no date, done and dropped actions carry no overdue cue, and today is due soon rather than overdue`() {
        assertNull(DueText.overdue(today, ActionStatus.OPEN, today))
        assertNull(DueText.overdue(today.plusDays(1), ActionStatus.OPEN, today))
        assertNull(DueText.cue(today.plusDays(9), ActionStatus.OPEN, today))
        assertNull(DueText.overdue(null, ActionStatus.OPEN, today))
        assertNull(DueText.overdue(today.minusDays(5), ActionStatus.DONE, today))
        assertNull(DueText.overdue(today.minusDays(5), ActionStatus.DROPPED, today))
        assertEquals("due 2026-10-13 · Due today", DueText.line(action(today), today))
        assertNull(DueText.line(action(null), today))
    }

    @Test
    fun `due soon runs from today through today plus 3 inclusive, for open actions only`() {
        fun soon(
            days: Long,
            status: ActionStatus = ActionStatus.OPEN,
        ) = DueText.dueSoon(today.plusDays(days), status, today)
        assertEquals("Due today", soon(0))
        assertEquals("Due tomorrow", soon(1))
        assertEquals("Due in 2 days", soon(2))
        assertEquals("Due in 3 days", soon(3))
        assertNull(soon(4))
        assertNull(soon(-1), "overdue is separate")
        assertNull(soon(1, ActionStatus.DONE))
        assertNull(soon(1, ActionStatus.DROPPED))
        assertNull(DueText.dueSoon(null, ActionStatus.OPEN, today))
        assertEquals("due 2026-10-16 · Due in 3 days", DueText.line(action(today.plusDays(3)), today))
        assertEquals("due 2026-10-17", DueText.line(action(today.plusDays(4)), today))
        assertEquals("due 2026-10-12 · Overdue by 1 day", DueText.line(action(today.minusDays(1)), today))
    }

    @Test
    fun `today is the calendar date in the given zone at the clock's instant`() {
        val instant = Instant.parse("2026-10-13T17:30:00Z")
        assertEquals(LocalDate.of(2026, 10, 13), DueText.today(instant, ZoneId.of("UTC")))
        assertEquals(LocalDate.of(2026, 10, 14), DueText.today(instant, ZoneId.of("Asia/Kuala_Lumpur")))
    }

    @Test
    fun `the picker's UTC milliseconds round trip for ordinary, leap and year-end days`() {
        listOf("2026-10-13", "2028-02-29", "2026-12-31", "2027-01-01").map(LocalDate::parse).forEach {
            assertEquals(it, DueText.fromPickerMillis(DueText.toPickerMillis(it)))
        }
    }

    @Test
    fun `the Inbox header reads the open count and never claims one while still reading`() {
        assertEquals("Inbox", inboxHeader(null))
        assertEquals("Inbox · 0 items", inboxHeader(0))
        assertEquals("Inbox · 1 item", inboxHeader(1))
        assertEquals("Inbox · 7 items", inboxHeader(7))
    }

    private fun source(name: String) = File("src/main/kotlin/com/thalia/app/planning/$name").readText()

    @Test
    fun `the Inbox is in Life once, not also on Plan, and the closing batch and triage entry points remain`() {
        val activity = source("PlanningActivity.kt")
        val plan = activity.substringAfter("KeepAlive(tab == ThaliaTab.PLAN)").substringBefore("KeepAlive(tab == ThaliaTab.LIFE)")
        val life = activity.substringAfter("KeepAlive(tab == ThaliaTab.LIFE)")
        assertFalse("InboxSection" in plan || "InboxCard" in plan, "no second Inbox on Plan")
        assertTrue("InboxCard(" in life && "scope.triage(" in life, "Life holds the Inbox with its Triage entry point")
        val others = File("src/main/kotlin/com/thalia/app").walkTopDown().filter { it.extension == "kt" && it.name != "InboxSection.kt" }
        assertEquals(emptyList(), others.filter { "InboxSection(" in it.readText() }.map { it.name }.toList())
        assertTrue("InboxOpenList(access, writes, changes, limit = INBOX_PAGE)" in source("WorkingDaySection.kt"), "closing batch stays")
    }

    @Test
    fun `the collapsed Inbox is the default and the due editor offers a picker and a clear`() {
        val inbox = source("InboxSection.kt")
        assertTrue("rememberSaveable { mutableStateOf(false) }" in inbox, "collapsed by default, kept across rotation")
        val life = source("LifeUi.kt")
        assertTrue("\"Pick a date\"" in life && "\"Clear date\"" in life && "DueDatePicker(" in life)
    }
}
