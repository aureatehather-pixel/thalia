package com.thalia.app.planning.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.Direction
import com.thalia.planning.Goal
import com.thalia.planning.InboxItem
import com.thalia.planning.InboxState
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Project
import com.thalia.planning.Reminder
import com.thalia.planning.ReminderTargetType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The privacy deletions: clear the direction, delete a goal nobody uses, delete a filed item, delete or edit a closing note.
 * Each refuses what is referenced and changes nothing then; nothing cascades. Robolectric's plain SQLite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanningPrivacyStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val opened = mutableListOf<PlanningDatabase>()
    private var now = 1_000L
    private val utc = ZoneId.of("UTC")

    private fun newStore(): PlanningStore {
        val name = "planning-privacy-test-${names.size}.db"
        names += name
        return PlanningStore(plainPlanningDatabase(context, context.getDatabasePath(name)).also { opened += it }) { now++ }
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    private suspend fun PlanningStore.fileItem(
        id: String,
        to: InboxState,
    ) {
        captureItem(InboxItem(id, "text of $id"))
        resolveItem(id, to)
    }

    @Test
    fun `clearing the direction removes only the direction and can be repeated`() =
        runTest {
            val s = newStore()
            s.saveGoal(Goal("g", "Goal", "Done"))
            s.saveDirection(Direction("Be well"))
            assertTrue(s.clearDirection())
            assertNull(s.direction())
            assertFalse(s.clearDirection())
            assertEquals(1, s.goals().size)
            s.saveDirection(Direction("Again"))
            assertEquals("Again", s.direction()?.text)
        }

    @Test
    fun `a goal with a linked project is blocked and nothing changes`() =
        runTest {
            val s = newStore()
            s.saveGoal(Goal("g", "Learn", "Fluent"))
            s.saveProject(Project("p1", "Course", goalId = "g"))
            s.saveProject(Project("p2", "Practice", goalId = "g"))
            s.saveProject(Project("p3", "Other"))
            val blocked = assertIs<GoalDeletion.Blocked>(s.deleteGoal("g"))
            assertEquals(listOf("Course", "Practice"), blocked.projectTitles)
            assertEquals(1, s.goals().size)
            assertEquals(listOf("g", "g", null), s.projects().map { it.goalId })
        }

    @Test
    fun `an unlinked goal is deleted and its neighbours stay`() =
        runTest {
            val s = newStore()
            s.saveGoal(Goal("g1", "One", "x"))
            s.saveGoal(Goal("g2", "Two", "y"))
            s.saveProject(Project("p", "Proj", goalId = "g2"))
            assertEquals(GoalDeletion.Deleted, s.deleteGoal("g1"))
            assertEquals(listOf("g2"), s.goals().map { it.id })
            assertEquals(GoalDeletion.NotFound, s.deleteGoal("g1"))
            assertEquals(1, s.projects().size)
        }

    @Test
    fun `a goal freed by changing its project can then be deleted`() =
        runTest {
            val s = newStore()
            s.saveGoal(Goal("g", "Goal", "x"))
            val project = Project("p", "Proj", goalId = "g")
            s.saveProject(project)
            assertIs<GoalDeletion.Blocked>(s.deleteGoal("g"))
            s.saveProject(project.copy(goalId = null), project)
            assertEquals(GoalDeletion.Deleted, s.deleteGoal("g"))
        }

    @Test
    fun `filed notes ideas archives and discards can be deleted, open and action items cannot`() =
        runTest {
            val s = newStore()
            listOf("n" to InboxState.NOTE, "i" to InboxState.IDEA, "a" to InboxState.ARCHIVED, "d" to InboxState.DISCARDED)
                .forEach { (id, state) -> s.fileItem(id, state) }
            s.captureItem(InboxItem("open", "still open"))
            s.captureItem(InboxItem("act", "to action"))
            assertNotNull(s.inboxItem("act"))
            s.convertToAction("act", Action("act-action", "Do it"))
            listOf("n", "i", "a", "d").forEach { assertEquals(InboxDeletion.Deleted(0), s.deleteFiledItem(it)) }
            assertEquals(InboxDeletion.NotFound, s.deleteFiledItem("n"))
            assertEquals(InboxDeletion.StillOpen, s.deleteFiledItem("open"))
            assertEquals(InboxDeletion.BecameAction, s.deleteFiledItem("act"))
            assertEquals(setOf("open", "act"), s.inbox().map { it.value.id }.toSet())
            assertEquals(2, s.inbox().size)
            assertEquals(1, s.actions().size)
        }

    @Test
    fun `deleting a filed item leaves its reminder and says so`() =
        runTest {
            val s = newStore()
            s.fileItem("n", InboxState.NOTE)
            s.createReminder(Reminder("r", ReminderTargetType.INBOX_ITEM, "n", "label", 500_000L, "UTC", createdAtMillis = 10))
            assertEquals(InboxDeletion.Deleted(1), s.deleteFiledItem("n"))
            assertEquals(listOf("r"), s.reminders().map { it.id })
            assertNull(s.inboxItem("n"))
        }

    private suspend fun PlanningStore.closedDay(
        date: LocalDate,
        note: String?,
        focus: Int? = null,
    ) {
        saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
        val at = date.atTime(15, 0).atZone(utc).toInstant()
        openWorkingDay(at, utc)
        focus?.let { setFocus(ActiveDay(date), it) }
        closeWorkingDay(ActiveDay(date), Instant.ofEpochMilli(at.toEpochMilli() + 3_600_000), note)
    }

    @Test
    fun `closing notes list newest first and an edit keeps the day`() =
        runTest {
            val s = newStore()
            s.closedDay(LocalDate.of(2026, 10, 3), "old note", focus = 90)
            s.closedDay(LocalDate.of(2026, 10, 5), "newer note")
            s.closedDay(LocalDate.of(2026, 10, 6), null)
            val notes = s.closingNotes()
            assertEquals(listOf("2026-10-05" to "newer note", "2026-10-03" to "old note"), notes.map { it.first.toString() to it.second })
            val day = ActiveDay(LocalDate.of(2026, 10, 3))
            assertEquals(NoteChange.STALE, s.editClosingNote(day, "wrong", "x"))
            assertEquals(NoteChange.DONE, s.editClosingNote(day, "old note", "edited"))
            val row = assertNotNull(s.workingDay(day))
            assertEquals("edited", row.closingNote)
            assertEquals(90, row.focusMinutes)
            assertNotNull(row.closedAt)
        }

    @Test
    fun `deleting a closing note erases the text only and a repeat finds nothing`() =
        runTest {
            val s = newStore()
            s.closedDay(LocalDate.of(2026, 10, 3), "private thought", focus = 60)
            val day = ActiveDay(LocalDate.of(2026, 10, 3))
            assertEquals(NoteChange.STALE, s.deleteClosingNote(day, "other"))
            assertEquals(NoteChange.DONE, s.deleteClosingNote(day, "private thought"))
            val row = assertNotNull(s.workingDay(day))
            assertNull(row.closingNote)
            assertEquals(60, row.focusMinutes)
            assertNotNull(row.closedAt)
            assertEquals(emptyList(), s.closingNotes())
            assertEquals(NoteChange.NOT_FOUND, s.deleteClosingNote(day, "private thought"))
            assertEquals(NoteChange.NOT_FOUND, s.editClosingNote(ActiveDay(LocalDate.of(2026, 1, 1)), "a", "b"))
        }

    @Test
    fun `an over-long edited note is refused and the stored note is kept`() =
        runTest {
            val s = newStore()
            s.closedDay(LocalDate.of(2026, 10, 3), "keep me")
            val day = ActiveDay(LocalDate.of(2026, 10, 3))
            runCatching { s.editClosingNote(day, "keep me", "x".repeat(1_001)) }
            assertEquals("keep me", s.workingDay(day)?.closingNote)
        }
}
