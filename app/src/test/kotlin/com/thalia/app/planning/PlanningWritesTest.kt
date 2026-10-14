package com.thalia.app.planning

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.FakeSealer
import com.thalia.app.planning.data.OutcomeRecording
import com.thalia.app.planning.data.OutcomeRefusal
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningFiles
import com.thalia.app.planning.data.PlanningRefusal
import com.thalia.app.planning.data.plainOpener
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.ContextField
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D-1 and D-2 of the Stage D phone run: an outcome changed an action's status while the Actions list kept the old one, and a
 * manual status change left the outcome rows stale. Every write now goes through [PlanningWrites], which tells the screen
 * after it commits, and the editor's save is refused when the status it was opened with is no longer the stored one.
 * The tests drive the same writes the screen does, then load what each section would load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanningWritesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val files = PlanningFiles.of(context, "writes")
    private val opened = mutableListOf<PlanningDatabase>()
    private val utc = ZoneId.of("UTC")
    private val day = ActiveDay(LocalDate.of(2026, 10, 4))
    private val access: PlanningAccess
    private var changes = 0
    private val writes: PlanningWrites

    init {
        val (opener, replacement) = plainOpener(context, files, FakeSealer(), opened)
        access = PlanningAccess(opener, replacement, Dispatchers.Unconfined)
        writes = PlanningWrites(access) { changes++ }
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        (files.live + files.staged + listOf(files.journal)).forEach(File::delete)
        files.setAsideRoot.deleteRecursively()
    }

    private suspend fun plan(): DayPlanWithItems =
        access.withStore { store ->
            store.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
            store.saveAction(Action("x", "Write chapter"))
            val proposal = assertNotNull(store.propose(day, utc, null, setOf("x"), emptyMap()))
            assertIs<ApprovalResult.Approved>(store.approve(proposal, utc)).plan
        }

    /** What the Actions list shows, freshly loaded as the editor section does on a change. */
    private suspend fun listedStatus() = access.withStore { it.actions() }.single { it.id == "x" }.status

    /** What the outcome rows show, freshly loaded as the day section does on a change. */
    private suspend fun rowText(): String =
        access.withStore { store ->
            val latest = checkNotNull(store.planFor(day))
            outcomeRowText(outcomeRows(latest, store.outcomes().associateBy { it.planItemId }, store.actions(), day).single())
        }

    @Test
    fun `an outcome that closes an action is announced after it commits, and the Actions list then shows it`() =
        runTest {
            val item = plan().items.single().id
            assertEquals(ActionStatus.OPEN, listedStatus())
            assertEquals(0, changes)

            assertIs<OutcomeRecording.Recorded>(writes.recordOutcome(item, OutcomeResult.DONE, day))

            assertEquals(1, changes)
            assertEquals(ActionStatus.DONE, listedStatus())
            assertEquals("Write chapter: done (action done)", rowText())

            assertIs<OutcomeRecording.Recorded>(writes.recordOutcome(item, OutcomeResult.PARTIAL, day))
            assertEquals(2, changes)
            assertEquals(ActionStatus.OPEN, listedStatus())
        }

    @Test
    fun `a manual status change is announced, and the outcome rows then show the action's new status`() =
        runTest {
            val item = plan().items.single().id
            writes.recordOutcome(item, OutcomeResult.PARTIAL, day)
            assertEquals("Write chapter: partial", rowText())
            val before = changes

            val editor = access.withStore { it.actions() }.single()
            assertEquals(null, writes.saveAction(editor.copy(status = ActionStatus.DONE), expectedStatus = editor.status))

            assertEquals(before + 1, changes)
            assertEquals("Write chapter: partial (action done)", rowText())
            assertEquals(ActionStatus.DONE, listedStatus())
        }

    @Test
    fun `an editor opened before an outcome closed the action cannot silently reopen it`() =
        runTest {
            val item = plan().items.single().id
            val staleEditor = access.withStore { it.actions() }.single() // opened while the action is open
            writes.recordOutcome(item, OutcomeResult.DONE, day) // the action is closed, with the marker
            val outcomesBefore = access.withStore { it.outcomes() }
            assertTrue(outcomesBefore.single().closedAction)
            val announced = changes

            // Saving the old form would write status open back, reopen the action and clear the marker.
            val refused = writes.saveAction(staleEditor.copy(title = "Renamed by a stale form"), expectedStatus = staleEditor.status)

            assertEquals(PlanningRefusal.ACTION_CHANGED, refused)
            assertEquals(announced, changes, "a refusal changes nothing and tells nobody")
            val stored = access.withStore { it.actions() }.single()
            assertEquals(ActionStatus.DONE, stored.status)
            assertEquals("Write chapter", stored.title)
            assertEquals(outcomesBefore, access.withStore { it.outcomes() }, "the outcome and its marker are untouched")
        }

    @Test
    fun `the same editor opened again after the change saves, and a hand-made status change then clears the marker`() =
        runTest {
            val item = plan().items.single().id
            writes.recordOutcome(item, OutcomeResult.DONE, day)

            val fresh = access.withStore { it.actions() }.single() // the list reloaded: done
            assertEquals(ActionStatus.DONE, fresh.status)
            assertEquals(null, writes.saveAction(fresh.copy(status = ActionStatus.OPEN), expectedStatus = fresh.status))

            assertEquals(ActionStatus.OPEN, listedStatus())
            assertEquals(false, access.withStore { it.outcomes() }.single().closedAction)
        }

    @Test
    fun `a stale save of another field is refused too, and a new action needs no expectation`() =
        runTest {
            val item = plan().items.single().id
            val staleEditor = access.withStore { it.actions() }.single()
            writes.recordOutcome(item, OutcomeResult.DONE, day)

            assertEquals(PlanningRefusal.ACTION_CHANGED, writes.saveAction(staleEditor.copy(estimateMinutes = 45), staleEditor.status))
            assertEquals(30, access.withStore { it.actions() }.single().estimateMinutes)

            assertEquals(null, writes.saveAction(Action("new", "A new action"), expectedStatus = null))
            assertEquals(2, access.withStore { it.actions() }.size)
        }

    @Test
    fun `a project editor opened before a conversation change cannot overwrite it, and the same editor reopened saves`() =
        runTest {
            assertEquals(null, writes.run({ it.saveProject(Project("p", "Thesis", state = ProjectState.ACTIVE)) }) { it == null })
            val staleEditor = access.withStore { it.projects() }.single()
            access.withStore { it.changeProjectContext("p", ContextField.BLOCKER, expected = null, text = "Waiting on data") }

            val draft = staleEditor.copy(purpose = "Graduate")
            val before = changes
            assertEquals(PlanningRefusal.PROJECT_CHANGED, writes.run({ it.saveProject(draft, staleEditor) }) { it == null })
            assertEquals(before, changes)
            val stored = access.withStore { it.projects() }.single()
            assertEquals("Waiting on data", stored.blocker)
            assertEquals(null, stored.purpose)

            assertEquals(null, writes.run({ it.saveProject(stored.copy(purpose = "Graduate"), stored) }) { it == null })
            val saved = access.withStore { it.projects() }.single()
            assertEquals("Graduate", saved.purpose)
            assertEquals("Waiting on data", saved.blocker)
        }

    @Test
    fun `refusals and failures tell the screen nothing`() =
        runTest {
            val item = plan().items.single().id
            assertEquals(0, changes)

            val future = writes.recordOutcome(item, OutcomeResult.DONE, day.plusDays(-1)) // before the plan's day
            assertEquals(OutcomeRefusal.NOT_ELIGIBLE, assertIs<OutcomeRecording.Refused>(future).reason)
            assertEquals(PlanningRefusal.PROJECT_NOT_FOUND, writes.saveAction(Action("p", "Orphan", projectId = "missing"), null))
            assertEquals(0, changes)

            val failure = runCatching { writes.run<Unit>({ error("the write died") }) { true } }
            assertTrue(failure.isFailure)
            assertEquals(0, changes)
        }
}
